# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later

import base64
import contextlib
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

import launcher_apple as apple
import launcher_release as release
from test_launcher_release import COMMIT, ENVIRONMENT


INTEL = "a6bdee64-dece-4e6b-a38d-6367c4255f68"
SILICON = "a4ba432e-66e7-485b-9ee9-504dc2707545"
PRIVATE_KEY = b"fake status-only API private key"


class AppleStatusTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.runner_temp = self.root / "runner-temp"
        self.runner_temp.mkdir()
        self.summary = self.root / "summary"
        self.output = self.root / "reports"
        self.environment = {
            **ENVIRONMENT, "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_SHA": COMMIT,
            "APPLE_NOTARY_KEY_ID": "Z1Y2X3W4V5", "APPLE_NOTARY_ISSUER_ID": INTEL,
            "APPLE_NOTARY_PRIVATE_KEY": base64.b64encode(PRIVATE_KEY).decode("ascii"),
            "RUNNER_TEMP": str(self.runner_temp), "GITHUB_STEP_SUMMARY": str(self.summary),
        }
        self.calls = []
        self.keys = []
        self.statuses = {INTEL: "Accepted", SILICON: "In Progress"}
        self.info_override = None
        self.info_padding = 0
        self.log_override = None
        self.fail_command = None
        self.stdout = io.StringIO()
        for mock in (patch.object(apple.sys, "platform", "darwin"),
                     patch.object(apple, "run", side_effect=self.fake_run),
                     contextlib.redirect_stdout(self.stdout)):
            mock.__enter__()
            self.addCleanup(mock.__exit__, None, None, None)
        checkout_patch = patch.object(release, "verify_checkout")
        self.checkout = checkout_patch.start()
        self.addCleanup(checkout_patch.stop)

    def fake_run(self, command, timeout=120):
        self.calls.append(command)
        self.assertEqual(["/usr/bin/xcrun", "notarytool"], command[:2])
        self.assertIn(command[2], ("info", "log"))
        self.assertEqual(120, timeout)
        key = Path(command[command.index("--key") + 1])
        self.keys.append(key)
        self.assertEqual(PRIVATE_KEY, key.read_bytes())
        self.assertEqual(self.runner_temp, key.parent.parent)
        if os.name != "nt":
            self.assertEqual(0o600, stat.S_IMODE(key.stat().st_mode))
            self.assertEqual(0o700, stat.S_IMODE(key.parent.stat().st_mode))
        if command[2] == self.fail_command:
            if command[2] == "log":
                Path(command[-1]).write_text('{"jobId":', encoding="utf-8")
            raise release.ReleaseError("notarytool authentication/service failure")
        identifier = command[3]
        if command[2] == "info":
            self.assertEqual(["--output-format", "json"], command[-2:])
            return (self.info_override if self.info_override is not None
                    else json.dumps({"id": identifier.upper(), "status": self.statuses[identifier]}).ljust(
                        self.info_padding))
        self.assertIn(self.statuses[identifier], ("Invalid", "Rejected"))
        log_path = Path(command[-1])
        self.assertEqual(key.parent, log_path.parent)
        log_path.write_text(self.log_override if self.log_override is not None else json.dumps(
            {"jobId": identifier, "status": self.statuses[identifier],
             "issues": [{"message": "fixture signing rejection"}]}), encoding="utf-8")
        return "Successfully downloaded submission log."

    def check(self, intel=INTEL, silicon=SILICON, environment=None):
        apple.check_notarization_status(
            self.environment if environment is None else environment, intel, silicon, self.output)

    def assert_clean_private_key(self):
        self.assertEqual([], list(self.runner_temp.iterdir()))
        self.assertTrue(all(not key.exists() for key in self.keys))
        public = self.stdout.getvalue() + (self.summary.read_text(encoding="utf-8") if self.summary.exists() else "")
        if self.output.exists():
            public += "".join(file.read_text(encoding="utf-8") for file in self.output.iterdir())
        self.assertNotIn(PRIVATE_KEY.decode("ascii"), public)
        self.assertNotIn(self.environment["APPLE_NOTARY_PRIVATE_KEY"], public)
        self.assertNotIn(".p8", public)

    def test_accepted_and_pending_are_read_only_reported_and_cleaned_without_p12_or_enablement(self):
        self.environment["APPLE_SIGNING_ENABLED"] = "not an activation"
        self.check(intel=INTEL.upper())
        self.checkout.assert_called_once_with(Path.cwd(), COMMIT)
        self.assertEqual(["info", "info"], [command[2] for command in self.calls])
        self.assertEqual([INTEL, SILICON], [command[3] for command in self.calls])
        self.assertEqual({f"{platform}-info.json" for platform in release.APPLE_PLATFORMS},
                         {file.name for file in self.output.iterdir()})
        summary = self.summary.read_text(encoding="utf-8")
        self.assertIn("| macos-intel |", summary)
        self.assertIn("Accepted", summary)
        self.assertIn("In Progress", summary)
        self.assertIn("pending, not acceptance", summary)
        self.assertIn("not release proofs", summary)
        self.assert_clean_private_key()

    def test_invalid_and_rejected_retain_both_logs_and_fail_after_cleanup(self):
        self.statuses = {INTEL: "Invalid", SILICON: "Rejected"}
        with self.assertRaisesRegex(release.ReleaseError, "Apple rejected existing submissions"):
            self.check()
        self.assertEqual(["info", "log", "info", "log"], [command[2] for command in self.calls])
        self.assertEqual(4, len(list(self.output.iterdir())))
        for platform, identifier in zip(release.APPLE_PLATFORMS, (INTEL, SILICON)):
            self.assertEqual(identifier, json.loads(
                (self.output / f"{platform}-log.json").read_text(encoding="utf-8"))["jobId"])
        self.assertIn("Rejected", self.summary.read_text(encoding="utf-8"))
        self.assert_clean_private_key()

    def test_malformed_unbound_unknown_duplicate_or_oversized_info_fails_closed(self):
        invalid = (
            "", "{", "null", "[]",
            json.dumps({"id": INTEL, "status": "Finished"}),
            json.dumps({"id": SILICON, "status": "Accepted"}),
            json.dumps({"id": INTEL}),
            json.dumps({"id": None, "status": "Accepted"}),
            f'{{"id":"{INTEL}","status":"Invalid","status":"Accepted"}}',
            " " * (release.SIGNING_RECORD_LIMIT + 1),
        )
        for index, text in enumerate(invalid):
            with self.subTest(text=text[:150]):
                self.output = self.root / f"invalid-info-{index}"
                self.info_override = text
                before = len(self.calls)
                with self.assertRaises((release.ReleaseError, ValueError)):
                    self.check()
                self.assertEqual(before + 1, len(self.calls), "failed queries must not retry")
                self.assertEqual([], list(self.output.iterdir()))
                self.assertFalse(self.summary.exists())
                self.assert_clean_private_key()

    def test_exact_info_size_limit_is_accepted(self):
        self.info_padding = release.SIGNING_RECORD_LIMIT
        self.check()
        self.assertEqual(2, len(list(self.output.iterdir())))
        self.assertEqual(2, len(self.calls))
        self.assert_clean_private_key()

    def test_exact_log_size_limit_is_retrieved_before_reporting_rejection(self):
        self.statuses[INTEL] = "Invalid"
        self.log_override = json.dumps({"jobId": INTEL, "status": "Invalid", "issues": []}).ljust(
            2 * 1024 * 1024)
        with self.assertRaisesRegex(release.ReleaseError, "Apple rejected existing submissions"):
            self.check()
        self.assertEqual(2 * 1024 * 1024, (self.output / "macos-intel-log.json").stat().st_size)
        self.assertEqual(["info", "log", "info"], [command[2] for command in self.calls])
        self.assert_clean_private_key()

    def test_origin_source_uuid_and_credential_guards_precede_private_file_and_queries(self):
        changes = (
            ("GITHUB_REPOSITORY", "other/mm-launcher"), ("GITHUB_REF", "refs/heads/feature"),
            ("GITHUB_EVENT_NAME", "pull_request"), ("GITHUB_SHA", "bad-sha"),
            ("APPLE_NOTARY_KEY_ID", ""), ("APPLE_NOTARY_KEY_ID", None),
            ("APPLE_NOTARY_ISSUER_ID", "not-an-issuer"),
            ("APPLE_NOTARY_PRIVATE_KEY", "not base64"),
        )
        for key, value in changes:
            with self.subTest(key=key, value=value), self.assertRaises(release.ReleaseError):
                self.check(environment={**self.environment, key: value})
        for intel, silicon in (("", SILICON), ("../key", SILICON), (INTEL, None), (INTEL, INTEL.upper())):
            with self.subTest(intel=intel, silicon=silicon), self.assertRaises(release.ReleaseError):
                self.check(intel, silicon)
        with patch.object(apple.sys, "platform", "win32"), self.assertRaises(release.ReleaseError):
            self.check()
        with patch.object(release, "verify_checkout", side_effect=release.ReleaseError("wrong checkout")):
            with self.assertRaisesRegex(release.ReleaseError, "wrong checkout"):
                self.check()
        self.assertEqual([], self.calls)
        self.assertFalse(self.output.exists())
        self.assert_clean_private_key()

    def test_native_info_and_log_failures_are_visible_and_clean_up_without_retry(self):
        for operation in ("info", "log"):
            with self.subTest(operation=operation):
                self.output = self.root / f"native-failure-{operation}"
                self.statuses[INTEL] = "Invalid"
                self.fail_command = operation
                before = len(self.calls)
                with self.assertRaisesRegex(release.ReleaseError, "authentication/service failure"):
                    self.check()
                self.assertEqual(before + (1 if operation == "info" else 2), len(self.calls))
                self.assertEqual({"macos-intel-info.json"} if operation == "log" else set(),
                                 {file.name for file in self.output.glob("*.json")})
                self.assertFalse(self.summary.exists())
                self.assert_clean_private_key()

    def test_unbound_duplicate_malformed_and_oversized_logs_fail_and_remove_private_key(self):
        self.statuses[INTEL] = "Invalid"
        for index, text in enumerate((
                "{", "[]", json.dumps({"jobId": SILICON}), json.dumps({"issues": []}),
                f'{{"jobId":"{INTEL}","jobId":"{SILICON}"}}', " " * (2 * 1024 * 1024 + 1))):
            with self.subTest(log=text[:150]):
                self.output = self.root / f"invalid-log-{index}"
                self.log_override = text
                with self.assertRaises((release.ReleaseError, ValueError)):
                    self.check()
                self.assertTrue((self.output / "macos-intel-info.json").exists())
                self.assertEqual({"macos-intel-info.json"},
                                 {file.name for file in self.output.iterdir()},
                                 "invalid logs must never enter the public artifact directory")
                self.assertFalse(self.summary.exists())
                self.assert_clean_private_key()

    def test_failed_report_copy_cannot_publish_partial_json_and_cleans_staging(self):
        self.statuses[INTEL] = "Invalid"

        def partial_copy(source, destination):
            self.assertEqual(self.runner_temp, source.parent.parent)
            Path(destination).write_text('{"jobId":', encoding="utf-8")
            raise OSError("fixture report copy failed")

        with patch.object(apple.shutil, "copyfile", side_effect=partial_copy):
            with self.assertRaisesRegex(OSError, "fixture report copy failed"):
                self.check()
        self.assertEqual({"macos-intel-info.json"}, {file.name for file in self.output.iterdir()})
        self.assertFalse(self.summary.exists())
        self.assert_clean_private_key()

    def test_cli_status_uses_exact_dispatch_and_requested_ids_without_signing(self):
        with patch.dict(os.environ, self.environment, clear=True), patch.object(apple.sys, "argv", [
                "launcher_apple.py", "status", "--intel-submission-id", INTEL,
                "--silicon-submission-id", SILICON, "--assets", str(self.output)]):
            apple.main()
        self.checkout.assert_called_once_with(Path.cwd(), COMMIT)
        self.assertEqual(["info", "info"], [command[2] for command in self.calls])
        self.assert_clean_private_key()


if __name__ == "__main__":
    unittest.main()
