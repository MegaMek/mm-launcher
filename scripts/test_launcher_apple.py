# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later

import base64
import copy
import json
import os
from pathlib import Path
import plistlib
import shlex
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import launcher_apple as apple
import launcher_release as release
import launcher_signing as staging
from test_launcher_release import COMMIT, ENVIRONMENT, VERSION, FakeCiGitHub, FakeGitHub
from test_launcher_signing import CERTIFICATE


TEAM = "A1B2C3D4E5"
IDENTITY = f"MegaMek Test Team ({TEAM})"
REQUEST = "12345678-1234-1234-1234-123456789abc"
ENV = {
    **ENVIRONMENT, "GITHUB_EVENT_NAME": "workflow_dispatch",
    "APPLE_SIGNING_ENABLED": "true", "APPLE_TEAM_ID": TEAM, "APPLE_SIGNING_IDENTITY": IDENTITY,
    "APPLE_NOTARY_KEY_ID": "Z1Y2X3W4V5", "APPLE_NOTARY_ISSUER_ID": REQUEST,
    "APPLE_APPLICATION_P12": base64.b64encode(b"fake app p12").decode("ascii"),
    "APPLE_INSTALLER_P12": base64.b64encode(b"fake installer p12").decode("ascii"),
    "APPLE_NOTARY_PRIVATE_KEY": base64.b64encode(b"fake API private key").decode("ascii"),
    "APPLE_APPLICATION_P12_PASSWORD": "app-fixture-password",
    "APPLE_INSTALLER_P12_PASSWORD": "installer-fixture-password",
}


class AppleReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.verification = self.root / "verification"
        self.verification.mkdir()
        self.environment_output = self.root / "environment"
        self.calls = []
        self.notary_status = "Accepted"
        self.team = TEAM
        self.timestamp = True
        self.hardened_runtime = True
        self.app_version = release.apple_app_version(VERSION)
        self.architecture = None
        self.package_signature = True
        self.fail_stapling = False
        self.write_assets(VERSION)
        (self.root / "build.gradle.kts").write_text(f'version = "{VERSION}"\n', newline="\n")

    def write_assets(self, version):
        for file in self.assets.iterdir():
            file.unlink()
        for suffix in release.INSTALLERS:
            file = self.assets / f"MegaMek-Launcher-{version}-{suffix}"
            file.write_bytes(file.name.encode("ascii"))
            file.with_name(file.name + ".sha256").write_text(
                f"{release.digest(file)}  {file.name}\n", encoding="ascii", newline="\n")

    def fake_run(self, command, timeout=120):
        self.calls.append(command)
        if "--check-signature" in command:
            if self.package_signature:
                return ("Status: signed by a developer certificate issued by Apple for distribution\n"
                        f"    1. Developer ID Installer: {IDENTITY}\n")
            return "Status: no signature\n"
        if "--expand-full" in command:
            app = Path(command[-1]) / "Payload" / "MegaMek Launcher.app"
            contents = app / "Contents"
            contents.mkdir(parents=True)
            with (contents / "Info.plist").open("wb") as file:
                plistlib.dump({"CFBundleIdentifier": "org.megamek.launcher",
                              "CFBundleShortVersionString": self.app_version}, file)
            for file in (contents / "MacOS" / "MegaMek Launcher",
                         contents / "runtime" / "Contents" / "Home" / "bin" / "java"):
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(b"mock native executable")
            return ""
        if command[0].endswith("codesign"):
            if "--display" not in command:
                return ""
            text = (f"Identifier=org.megamek.launcher\nTeamIdentifier={self.team}\n"
                    f"Authority=Developer ID Application: {IDENTITY}\n")
            if self.timestamp:
                text += "Timestamp=Oct 7, 2026\n"
            if self.hardened_runtime:
                text += "CodeDirectory v=20500 size=100 flags=0x10000(runtime) hashes=4\n"
            return text
        if command[0].endswith("lipo"):
            platform = self.current_platform
            return (self.architecture or ("x86_64" if platform == "macos-intel" else "arm64")) + "\n"
        if "notarytool" in command:
            return json.dumps({"id": REQUEST, "status": self.notary_status})
        if "stapler" in command:
            if self.fail_stapling:
                raise release.ReleaseError("mock stapler failure")
            if "staple" in command:
                with Path(command[-1]).open("ab") as file:
                    file.write(b"\nstapled-ticket")
            return ""
        if command[0].endswith("spctl"):
            return f"{command[-1]}: accepted\nsource=Notarized Developer ID\n"
        raise AssertionError(f"Unexpected Apple tool: {command}")

    def complete(self, platform="macos-intel", version=VERSION, commit=COMMIT, rehearsal=False):
        self.current_platform = platform
        package = self.assets / f"MegaMek-Launcher-{version}-{platform}.pkg"
        proof = self.verification / (platform + ".json")
        with patch("launcher_apple.run", side_effect=self.fake_run):
            apple.complete(package, version, commit, platform, TEAM, IDENTITY,
                           ENV["APPLE_NOTARY_KEY_ID"], REQUEST, self.root, proof, rehearsal=rehearsal)
        return json.loads(proof.read_text())

    def rehearsal_assets(self):
        for platform in release.APPLE_PLATFORMS:
            self.complete(platform, rehearsal=True)
            shutil.copyfile(self.verification / (platform + ".json"), self.assets / (platform + ".json"))
        for file in self.assets.iterdir():
            if "macos-" not in file.name:
                file.unlink()

    def test_rehearsal_plan_captures_unchanged_main_without_enabling_production_or_network_calls(self):
        for flag in ("", "false"):
            output = self.root / ("rehearsal-output-" + flag)
            environment = {**ENV, "APPLE_SIGNING_ENABLED": flag, "GITHUB_OUTPUT": str(output)}
            with self.subTest(flag=flag), patch.dict(os.environ, environment, clear=True), \
                    patch("launcher_apple.Path.cwd", return_value=self.root), \
                    patch("launcher_apple.release.verify_checkout") as checkout, \
                    patch("launcher_apple.release.GitHub") as github, \
                    patch("launcher_apple.release.verify_candidate") as candidate, \
                    patch("launcher_apple.sys.argv", ["launcher_apple.py", "rehearsal-plan"]), \
                    patch("builtins.print"):
                apple.main()
                self.assertEqual(flag, os.environ["APPLE_SIGNING_ENABLED"])
            checkout.assert_called_once_with(self.root, COMMIT)
            github.assert_not_called()
            candidate.assert_not_called()
            values = dict(line.split("=", 1) for line in output.read_text().splitlines())
            self.assertEqual({"version": VERSION, "commit": COMMIT, "team_id": TEAM, "identity": IDENTITY,
                              "key_id": ENV["APPLE_NOTARY_KEY_ID"], "issuer_id": REQUEST}, values)
            for key, value in ENV.items():
                if key.startswith("APPLE_") and ("P12" in key or "PRIVATE_KEY" in key):
                    self.assertNotIn(value, output.read_text())

    def test_rehearsal_rejects_wrong_origin_checkout_and_source_overrides_before_key_import(self):
        environment = {**ENV, "APPLE_SIGNING_ENABLED": "false"}
        arguments = ["launcher_apple.py", "rehearsal-setup", "--version", VERSION, "--commit", COMMIT]
        cases = [
            ({"GITHUB_REPOSITORY": "fork/mm-launcher"}, arguments, None),
            ({"GITHUB_REF": "refs/heads/feature"}, arguments, None),
            ({"GITHUB_EVENT_NAME": "push"}, arguments, None),
            ({}, [*arguments[:-1], "b" * 40], None),
            ({}, [*arguments, "--version", "0.14.99"], None),
            ({}, ["launcher_apple.py", "rehearsal-setup"], None),
            ({}, [*arguments, "--package", str(self.assets / "override.pkg")], None),
            ({}, [*arguments, "--verification", str(self.verification / "override.json")], None),
            ({}, arguments, release.ReleaseError("checkout does not match")),
        ]
        for changes, argv, checkout_error in cases:
            with self.subTest(changes=changes, argv=argv, checkout_error=checkout_error), \
                    patch.dict(os.environ, {**environment, **changes}, clear=True), \
                    patch("launcher_apple.Path.cwd", return_value=self.root), \
                    patch("launcher_apple.release.verify_checkout", side_effect=checkout_error), \
                    patch("launcher_apple.sys.argv", argv), patch("launcher_apple.setup") as setup, \
                    patch("launcher_apple.configuration") as config, self.assertRaises(release.ReleaseError):
                apple.main()
            setup.assert_not_called()
            config.assert_not_called()

    def test_rehearsal_requires_all_credentials_even_when_production_signing_is_disabled(self):
        for key in ("APPLE_TEAM_ID", "APPLE_SIGNING_IDENTITY", "APPLE_NOTARY_KEY_ID", "APPLE_NOTARY_ISSUER_ID",
                    "APPLE_APPLICATION_P12", "APPLE_APPLICATION_P12_PASSWORD",
                    "APPLE_INSTALLER_P12", "APPLE_INSTALLER_P12_PASSWORD", "APPLE_NOTARY_PRIVATE_KEY"):
            environment = {**ENV, "APPLE_SIGNING_ENABLED": "false", "GITHUB_OUTPUT": str(self.root / "output")}
            del environment[key]
            with self.subTest(missing=key), patch.dict(os.environ, environment, clear=True), \
                    patch("launcher_apple.Path.cwd", return_value=self.root), \
                    patch("launcher_apple.release.verify_checkout"), \
                    patch("launcher_apple.sys.argv", ["launcher_apple.py", "rehearsal-plan"]), \
                    self.assertRaises(release.ReleaseError):
                apple.main()
            self.assertFalse((self.root / "output").exists())

    def test_rehearsal_setup_reuses_owned_keychain_without_release_candidate_or_flag_mutation(self):
        environment = {**ENV, "APPLE_SIGNING_ENABLED": "false"}
        with patch.dict(os.environ, environment, clear=True), \
                patch("launcher_apple.Path.cwd", return_value=self.root), \
                patch("launcher_apple.release.verify_checkout"), \
                patch("launcher_apple.release.verify_candidate") as candidate, \
                patch("launcher_apple.sys.platform", "darwin"), \
                patch("launcher_apple.sys.argv", ["launcher_apple.py", "rehearsal-setup",
                      "--version", VERSION, "--commit", COMMIT]), patch("launcher_apple.setup") as setup:
            apple.main()
            self.assertEqual("false", os.environ["APPLE_SIGNING_ENABLED"])
        setup.assert_called_once_with({**environment, "APPLE_SIGNING_ENABLED": "true"})
        candidate.assert_not_called()
        with patch.dict(os.environ, environment, clear=True), \
                patch("launcher_apple.Path.cwd", return_value=self.root), \
                patch("launcher_apple.release.verify_checkout"), patch("launcher_apple.sys.platform", "win32"), \
                patch("launcher_apple.sys.argv", ["launcher_apple.py", "rehearsal-setup",
                      "--version", VERSION, "--commit", COMMIT]), patch("launcher_apple.setup") as setup, \
                self.assertRaises(release.ReleaseError):
            apple.main()
        setup.assert_not_called()

    def test_rehearsal_completion_reuses_native_checks_and_cannot_authorize_production(self):
        for platform in release.APPLE_PLATFORMS:
            environment = {key: value for key, value in ENV.items()
                           if "P12" not in key and key != "APPLE_NOTARY_PRIVATE_KEY"}
            environment.update(APPLE_SIGNING_ENABLED="false", APPLE_SIGNING_DIRECTORY=str(self.root))
            with self.subTest(platform=platform), patch.dict(os.environ, environment, clear=True), \
                    patch("launcher_apple.Path.cwd", return_value=self.root), \
                    patch("launcher_apple.release.verify_checkout"), patch("launcher_apple.sys.platform", "darwin"), \
                    patch("launcher_apple.complete") as complete, \
                    patch("launcher_apple.sys.argv", ["launcher_apple.py", "rehearsal-complete",
                          "--version", VERSION, "--commit", COMMIT, "--platform", platform]):
                apple.main()
            complete.assert_called_once_with(
                Path("build") / "distributions" / f"MegaMek-Launcher-{VERSION}-{platform}.pkg",
                VERSION, COMMIT, platform, TEAM, IDENTITY, ENV["APPLE_NOTARY_KEY_ID"], REQUEST,
                self.root, Path("build") / "apple-rehearsal" / (platform + ".json"), rehearsal=True)
            record = self.complete(platform, rehearsal=True)
            package = self.assets / record["name"]
            self.assertEqual("apple-rehearsal-only", record["purpose"])
            self.assertEqual(release.digest(package), record["sha256"])
            self.assertTrue(record["stapled"])
            with self.assertRaises(release.ReleaseError):
                release.verify_apple_record(record, VERSION, COMMIT,
                                            [release.Asset(package, package.stat().st_size, record["sha256"])],
                                            TEAM, IDENTITY)

    def test_rehearsal_aggregates_both_bound_final_packages_without_private_credentials(self):
        self.rehearsal_assets()
        environment = {key: value for key, value in ENV.items()
                       if "P12" not in key and key != "APPLE_NOTARY_PRIVATE_KEY"}
        environment["APPLE_SIGNING_ENABLED"] = "false"
        with patch.dict(os.environ, environment, clear=True), \
                patch("launcher_apple.Path.cwd", return_value=self.root), \
                patch("launcher_apple.release.verify_checkout"), \
                patch("launcher_apple.sys.argv", ["launcher_apple.py", "rehearsal-verify",
                      "--version", VERSION, "--commit", COMMIT, "--assets", str(self.assets)]), \
                patch("builtins.print"):
            apple.main()
        result = json.loads((self.assets / "apple-rehearsal-verification.json").read_text())
        self.assertEqual("apple-rehearsal-only", result["purpose"])
        self.assertEqual(COMMIT, result["commit"])
        self.assertEqual(set(release.APPLE_PLATFORMS), {item["platform"] for item in result["records"]})
        self.assertIn("disposable test system", (self.assets / "TEST-ONLY.txt").read_text())
        self.assertIn("not public release", (self.assets / "TEST-ONLY.txt").read_text())

    def test_rehearsal_rejects_missing_extra_changed_files_and_corrupt_test_records(self):
        self.rehearsal_assets()
        name = f"MegaMek-Launcher-{VERSION}-macos-intel.pkg"
        for file in list(self.assets.iterdir()):
            original = file.read_bytes()
            with self.subTest(missing=file.name):
                file.unlink()
                with self.assertRaises(release.ReleaseError):
                    apple.verify_rehearsal(self.assets, VERSION, COMMIT, TEAM, IDENTITY)
                file.write_bytes(original)
            with self.subTest(changed=file.name):
                file.write_bytes(original + b"changed")
                with self.assertRaises(release.ReleaseError):
                    apple.verify_rehearsal(self.assets, VERSION, COMMIT, TEAM, IDENTITY)
                file.write_bytes(original)
        extra = self.assets / "extra"
        extra.write_bytes(b"extra")
        with self.assertRaises(release.ReleaseError):
            apple.verify_rehearsal(self.assets, VERSION, COMMIT, TEAM, IDENTITY)
        extra.unlink()
        file = self.assets / "macos-intel.json"
        original = file.read_bytes()
        valid = json.loads(original)
        for field, value in (("purpose", "release"), ("platform", "macos-apple-silicon"),
                             ("commit", "b" * 40), ("identity", f"Other ({TEAM})"),
                             ("sha256", "f" * 64), ("size", True), ("timestamped", False),
                             ("stapled", False), ("gatekeeper_status", "rejected"),
                             ("notarization_status", "Invalid"), ("extra", "unexpected")):
            with self.subTest(field=field, value=value):
                file.write_text(json.dumps({**valid, field: value}))
                with self.assertRaises(release.ReleaseError):
                    apple.verify_rehearsal(self.assets, VERSION, COMMIT, TEAM, IDENTITY)
        for text in ('null', '[]', '"text"', '{"purpose":"apple-rehearsal-only","purpose":"release"}'):
            file.write_text(text)
            with self.subTest(record=text), self.assertRaises(release.ReleaseError):
                apple.verify_rehearsal(self.assets, VERSION, COMMIT, TEAM, IDENTITY)
        file.write_bytes(original)
        (self.assets / (name + ".sha256")).write_text("f" * 64 + "  " + name + "\n")
        with self.assertRaises(release.ReleaseError):
            apple.verify_rehearsal(self.assets, VERSION, COMMIT, TEAM, IDENTITY)
        self.assertFalse((self.assets / "apple-rehearsal-verification.json").exists())
        self.assertFalse((self.assets / "TEST-ONLY.txt").exists())

    def test_disabled_mode_is_independent_of_windows_and_requires_no_apple_credentials(self):
        for flag in ("", "false"):
            self.assertEqual(apple.AppleConfiguration("unsigned"),
                             apple.configuration({"APPLE_SIGNING_ENABLED": flag,
                                                  "LAUNCHER_SIGNING_ENABLED": "true"}))
        self.assertEqual("apple", apple.configuration({**ENV, "LAUNCHER_SIGNING_ENABLED": "false"}).mode)

    def test_invalid_flag_missing_credentials_and_injected_public_settings_fail_closed(self):
        for flag in ("True", "FALSE", "1", " true", None):
            with self.subTest(flag=flag), self.assertRaises(release.ReleaseError):
                apple.configuration({**ENV, "APPLE_SIGNING_ENABLED": flag})
        for key in ENV:
            if key.startswith("APPLE_") and key != "APPLE_SIGNING_ENABLED":
                environment = dict(ENV)
                del environment[key]
                with self.subTest(missing=key), self.assertRaises(release.ReleaseError):
                    apple.configuration(environment)
        for key, value in (
                ("APPLE_SIGNING_IDENTITY", "Other (WRONGTEAM1)"),
                ("APPLE_SIGNING_IDENTITY", IDENTITY + "\nmode=unsigned"),
                ("APPLE_TEAM_ID", "short"), ("APPLE_NOTARY_KEY_ID", "bad"),
                ("APPLE_NOTARY_ISSUER_ID", "not-an-issuer"),
                ("APPLE_APPLICATION_P12", "invalid base64"),
                ("APPLE_NOTARY_PRIVATE_KEY", "a" * (1024 * 1024 + 1))):
            with self.subTest(key=key), self.assertRaises(release.ReleaseError):
                apple.configuration({**ENV, key: value})

    def test_plan_outputs_only_public_captured_settings_and_requires_manual_official_main(self):
        output = self.root / "output"
        with patch.dict(os.environ, {**ENV, "GITHUB_OUTPUT": str(output)}, clear=True), \
                patch("launcher_apple.sys.argv", ["launcher_apple.py", "plan"]), patch("builtins.print"):
            apple.main()
        self.assertEqual({"mode": "apple", "team_id": TEAM, "identity": IDENTITY,
                          "key_id": ENV["APPLE_NOTARY_KEY_ID"], "issuer_id": REQUEST},
                         dict(line.split("=", 1) for line in output.read_text().splitlines()))
        for key in ("APPLE_APPLICATION_P12", "APPLE_NOTARY_PRIVATE_KEY", "APPLE_APPLICATION_P12_PASSWORD"):
            self.assertNotIn(ENV[key], output.read_text())
        for changes in ({"GITHUB_EVENT_NAME": "pull_request"}, {"GITHUB_EVENT_NAME": "push"},
                        {"GITHUB_REPOSITORY": "fork/mm-launcher"}, {"GITHUB_REF": "refs/heads/feature"}):
            with self.subTest(changes=changes), self.assertRaises(release.ReleaseError):
                apple.require_release({**ENV, **changes})

    def test_temporary_keychain_is_restored_and_credentials_removed_after_partial_setup_failure(self):
        original = str(self.root / "original login.keychain-db")
        environment = {**ENV, "RUNNER_TEMP": str(self.root), "GITHUB_ENV": str(self.environment_output)}

        def tools(command, timeout=120):
            self.calls.append(command)
            if command[1:4] == ["list-keychains", "-d", "user"] and "-s" not in command:
                return shlex.quote(original)
            if "create-keychain" in command:
                Path(command[-1]).write_bytes(b"mock keychain")
            if "import" in command:
                raise release.ReleaseError("simulated certificate password failure")
            return ""

        with patch("launcher_apple.sys.platform", "darwin"), patch("launcher_apple.run", side_effect=tools):
            with self.assertRaisesRegex(release.ReleaseError, "password"):
                apple.setup(environment)
            captured = dict(line.split("=", 1) for line in self.environment_output.read_text().splitlines())
            directory = Path(captured["APPLE_SIGNING_DIRECTORY"])
            self.assertTrue(directory.exists())
            apple.cleanup({**environment, **captured})
            self.assertFalse(directory.exists())
        self.assertIn(["/usr/bin/security", "list-keychains", "-d", "user", "-s", original], self.calls)
        self.assertTrue(any("delete-keychain" in call for call in self.calls))
        self.assertEqual([], list(self.root.glob("mm-launcher-apple-*")))

    def test_cleanup_rejects_unowned_paths(self):
        for directory in (self.root, self.root.parent, self.assets):
            with self.subTest(directory=directory), self.assertRaises(release.ReleaseError):
                apple.cleanup({"RUNNER_TEMP": str(self.root), "APPLE_SIGNING_DIRECTORY": str(directory)})
        self.assertTrue(self.assets.exists())

    def test_successful_key_import_uses_owned_keychain_and_removes_both_export_files(self):
        environment = {**ENV, "RUNNER_TEMP": str(self.root), "GITHUB_ENV": str(self.environment_output)}

        def tools(command, timeout=120):
            self.calls.append(command)
            if "create-keychain" in command:
                Path(command[-1]).write_bytes(b"mock keychain")
            if "find-identity" in command:
                return f'1) abcdef "Developer ID Application: {IDENTITY}"\n'
            return ""

        with patch("launcher_apple.sys.platform", "darwin"), patch("launcher_apple.run", side_effect=tools):
            apple.setup(environment)
            captured = dict(line.split("=", 1) for line in self.environment_output.read_text().splitlines())
            directory = Path(captured["APPLE_SIGNING_DIRECTORY"])
            self.assertEqual([], list(directory.glob("*.p12")))
            self.assertEqual(b"fake API private key",
                             (directory / f'AuthKey_{ENV["APPLE_NOTARY_KEY_ID"]}.p8').read_bytes())
            self.assertEqual(2, sum("import" in command for command in self.calls))
            self.assertTrue(any("set-key-partition-list" in command for command in self.calls))
            apple.cleanup({**environment, **captured})
            self.assertFalse(directory.exists())

    def test_setup_proves_the_candidate_before_importing_any_private_key(self):
        argv = ["launcher_apple.py", "setup", "--commit", "b" * 40]
        with patch.dict(os.environ, ENV, clear=True), \
                patch("launcher_apple.Path.cwd", return_value=self.root), \
                patch("launcher_apple.sys.argv", argv), \
                patch("launcher_apple.release.verify_checkout") as checkout, \
                patch("launcher_apple.release.GitHub") as github, \
                patch("launcher_apple.release.verify_candidate",
                      side_effect=release.ReleaseError("not a version-only candidate")) as candidate, \
                patch("launcher_apple.setup") as setup, self.assertRaises(release.ReleaseError):
            apple.main()
        checkout.assert_called_once_with(self.root, "b" * 40)
        candidate.assert_called_once_with(github.return_value, VERSION, "b" * 40, COMMIT)
        setup.assert_not_called()

    def test_jpackage_version_offset_and_final_stapled_bytes_are_verified_on_both_architectures(self):
        before = {file.name: file.read_bytes() for file in self.assets.iterdir()}
        records = [self.complete(platform) for platform in release.APPLE_PLATFORMS]
        assets = release.validate_files(self.assets, VERSION)
        release.verify_apple_records(records, VERSION, COMMIT, assets, TEAM, IDENTITY)
        self.assertEqual("1.14.5", release.apple_app_version(VERSION))
        for file in self.assets.iterdir():
            if "macos-" not in file.name:
                self.assertEqual(before[file.name], file.read_bytes())
        for record in records:
            self.assertNotEqual(before[record["name"]], (self.assets / record["name"]).read_bytes())
            self.assertEqual(release.digest(self.assets / record["name"]), record["sha256"])
            self.assertTrue(record["stapled"])
        self.assertTrue(any("AuthKey_" in argument for call in self.calls for argument in call))

    def test_wrong_signer_version_runtime_architecture_and_notary_status_produce_no_evidence(self):
        for attribute, value in (
                ("package_signature", False), ("team", "WRONGTEAM1"), ("timestamp", False),
                ("hardened_runtime", False), ("app_version", "1.14.99"),
                ("architecture", "arm64"), ("notary_status", "Invalid"),
                ("notary_status", "In Progress"), ("fail_stapling", True)):
            with self.subTest(attribute=attribute, value=value):
                previous = getattr(self, attribute)
                setattr(self, attribute, value)
                before = (self.assets / f"MegaMek-Launcher-{VERSION}-macos-intel.pkg").read_bytes()
                with self.assertRaises(release.ReleaseError):
                    self.complete()
                self.assertFalse((self.verification / "macos-intel.json").exists())
                setattr(self, attribute, previous)
                if attribute != "fail_stapling":
                    self.assertEqual(before, (self.assets / f"MegaMek-Launcher-{VERSION}-macos-intel.pkg").read_bytes())

    def test_pkg_signature_requires_the_exact_trusted_leaf_not_a_similar_chain_name(self):
        valid = f"Status: signed by a certificate trusted by macOS\n  1. Developer ID Installer: {IDENTITY}\n"
        apple.verify_package_signature(valid, IDENTITY)
        for text in (valid.replace("1.", "2."), valid.replace(IDENTITY, IDENTITY + " altered"),
                     valid.replace("trusted by macOS", "untrusted"), "Status: no signature"):
            with self.subTest(text=text), self.assertRaises(release.ReleaseError):
                apple.verify_package_signature(text, IDENTITY)

    def test_pkg_signature_accepts_native_developer_id_and_existing_trusted_status(self):
        for status in ("signed by a developer certificate issued by Apple for distribution",
                       "signed by a certificate trusted by macOS"):
            text = (f'Package "MegaMek Launcher.pkg":\n'
                    f"   Status: {status}\n"
                    "   Signed with a trusted timestamp on: 2026-10-13 03:10:44 +0000\n"
                    "   Certificate Chain:\n"
                    f"    1. Developer ID Installer: {IDENTITY}\n"
                    "       SHA256 Fingerprint:\n"
                    "           AA BB CC DD\n"
                    "    2. Developer ID Certification Authority\n"
                    "    3. Apple Root CA\n")
            with self.subTest(status=status):
                apple.verify_package_signature(text, IDENTITY)

    def test_pkg_signature_rejects_unknown_or_ambiguous_status_and_reports_native_output(self):
        good = "signed by a developer certificate issued by Apple for distribution"
        statuses = ("no signature", "signed by an untrusted certificate", "invalid signature",
                    "signed by a certificate trusted by macOS but revoked", good + " but expired",
                    good + "\n   Status: invalid signature", good + "\n   Status: " + good,
                    good + "\n   Status:", good + "\n   Status: \t", "\n   Status: " + good, "")
        for status in statuses:
            text = f"   Status: {status}\n    1. Developer ID Installer: {IDENTITY}\n"
            with self.subTest(status=status), self.assertRaises(release.ReleaseError) as raised:
                apple.verify_package_signature(text, IDENTITY)
            self.assertIn("pkgutil --check-signature output:", str(raised.exception))
            self.assertIn(text.strip(), str(raised.exception))
        for leaf in (f"Other Team ({TEAM})", IDENTITY + f"\n    1. Developer ID Installer: {IDENTITY}"):
            text = f"   Status: {good}\n    1. Developer ID Installer: {leaf}\n"
            with self.subTest(leaf=leaf), self.assertRaises(release.ReleaseError) as raised:
                apple.verify_package_signature(text, IDENTITY)
            self.assertIn(text.strip(), str(raised.exception))

    def test_changed_or_incomplete_records_cannot_authorize_publication(self):
        records = [self.complete(platform) for platform in release.APPLE_PLATFORMS]
        assets = release.validate_files(self.assets, VERSION)
        for field, value in (("sha256", "f" * 64), ("size", True), ("commit", "b" * 40),
                             ("team_id", "WRONGTEAM1"), ("timestamped", False),
                             ("hardened_runtime", False), ("stapled", False),
                             ("notarization_status", "Invalid"), ("gatekeeper_status", "rejected"),
                             ("notarization_id", ""), ("schema_version", True)):
            changed = copy.deepcopy(records)
            changed[0][field] = value
            github = FakeGitHub()
            with self.subTest(field=field), self.assertRaises(release.ReleaseError):
                release.publish(github, VERSION, COMMIT, assets, apple_records=changed,
                                apple_team_id=TEAM, apple_identity=IDENTITY)
            self.assertEqual([], github.calls)
        for incomplete in ([], records[:1], [records[0], records[0]]):
            with self.subTest(incomplete=incomplete), self.assertRaises(release.ReleaseError):
                release.verify_apple_records(incomplete, VERSION, COMMIT, assets, TEAM, IDENTITY)
        malformed = copy.deepcopy(records)
        malformed[0]["platform"] = []
        with self.assertRaises(release.ReleaseError):
            release.verify_apple_records(malformed, VERSION, COMMIT, assets, TEAM, IDENTITY)

    @patch("builtins.print")
    def test_windows_and_apple_signing_can_be_enabled_independently_or_together(self, output):
        records = [self.complete(platform) for platform in release.APPLE_PLATFORMS]
        msi = self.assets / f"MegaMek-Launcher-{VERSION}-windows-x64.msi"
        original = release.digest(msi)
        msi.write_bytes(msi.read_bytes() + b"\nsigned MSI")
        msi.with_name(msi.name + ".sha256").write_text(f"{release.digest(msi)}  {msi.name}\n")
        windows = {"schema_version": 1, "name": msi.name, "version": VERSION, "commit": COMMIT,
                   "unsigned_sha256": original, "sha256": release.digest(msi), "size": msi.stat().st_size,
                   "certificate_sha256": CERTIFICATE, "signature_status": "Valid", "timestamped": True}
        for use_windows in (False, True):
            for use_apple in (False, True):
                github = FakeGitHub()
                release.publish(github, VERSION, COMMIT, release.validate_files(self.assets, VERSION),
                                windows if use_windows else None, CERTIFICATE if use_windows else None,
                                records if use_apple else None, TEAM if use_apple else None,
                                IDENTITY if use_apple else None)
                body = github.release["body"]
                self.assertEqual(use_windows, "SignPath" in body)
                self.assertEqual(use_apple, "notarized by Apple and stapled" in body)
                self.assertNotIn("Linux installers are signed", body)

    def test_publish_record_and_finalize_cli_require_apple_evidence_before_remote_work(self):
        for command in ("publish", "record", "finalize"):
            with self.subTest(command=command), patch.dict(os.environ, ENVIRONMENT, clear=True), \
                    patch("launcher_release.Path.cwd", return_value=self.root), \
                    patch("launcher_release.verify_checkout"), patch("launcher_release.GitHub") as github, \
                    patch("launcher_release.sys.argv", ["launcher_release.py", command,
                          "--version", VERSION, "--commit", COMMIT, "--assets", str(self.assets),
                          "--macos-signing", "apple"]), self.assertRaises(release.ReleaseError):
                release.main()
            github.return_value.request.assert_not_called()

    @patch("builtins.print")
    def test_apple_provenance_reuses_final_bytes_and_finalization_only_advances_main(self, output):
        github = FakeCiGitHub((self.root / "build.gradle.kts").read_text())
        version, commit, base = release.prepare_candidate(github, self.root, ENVIRONMENT)
        self.write_assets(version)
        self.app_version = release.apple_app_version(version)
        records = [self.complete(platform, version, commit) for platform in release.APPLE_PLATFORMS]
        assets = release.validate_files(self.assets, version)
        release.publish(github, version, commit, assets, apple_records=records,
                        apple_team_id=TEAM, apple_identity=IDENTITY)
        retained = self.root / release.PROVENANCE_FILE
        release.record_publication(github, version, commit, assets, ENVIRONMENT, retained,
                                   apple_records=records, apple_team_id=TEAM, apple_identity=IDENTITY)
        provenance = json.loads(retained.read_text())
        github.retain_provenance(provenance)
        (self.root / "build.gradle.kts").write_text(f'version = "{version}"\n', newline="\n")
        environment = {**ENVIRONMENT, "GITHUB_EVENT_NAME": "push", "GITHUB_SHA": commit}
        with patch("launcher_release.verify_checkout"):
            self.assertTrue(release.verified_release_push(github, self.root, environment)[0])
        with patch.dict(os.environ, ENVIRONMENT, clear=True), \
                patch("launcher_release.Path.cwd", return_value=self.root), \
                patch("launcher_release.verify_checkout"), patch("launcher_release.GitHub", return_value=github), \
                patch("launcher_release.sys.argv", ["launcher_release.py", "finalize",
                      "--version", version, "--commit", commit, "--base", base, "--assets", str(self.assets),
                      "--macos-signing", "apple", "--apple-verification", str(self.verification),
                      "--apple-team-id", TEAM, "--apple-signing-identity", IDENTITY]):
            release.main()
        self.assertEqual(commit, github.main["object"]["sha"])
        provenance["apple_signing"][1]["sha256"] = "f" * 64
        github.retain_provenance(provenance)
        with patch("launcher_release.verify_checkout"), self.assertRaises(release.ReleaseError):
            release.verified_release_push(github, self.root, environment)

    def test_proof_directory_and_release_staging_reject_extras_and_mismatched_pkg_bytes(self):
        records = [self.complete(platform) for platform in release.APPLE_PLATFORMS]
        self.assertEqual(records, release.load_apple_records(self.verification))
        extra = self.verification / "unexpected.txt"
        extra.write_text("unexpected")
        with self.assertRaises(release.ReleaseError):
            release.load_apple_records(self.verification)
        extra.unlink()
        package = self.assets / records[0]["name"]
        package.write_bytes(package.read_bytes() + b"tampered")
        package.with_name(package.name + ".sha256").write_text(f"{release.digest(package)}  {package.name}\n")
        with patch.dict(os.environ, ENV, clear=True), patch("launcher_signing.Path.cwd", return_value=self.root), \
                patch("launcher_signing.release.verify_checkout"), \
                patch("launcher_signing.sys.argv", ["launcher_signing.py", "verify-apple",
                      "--version", VERSION, "--commit", COMMIT, "--assets", str(self.assets),
                      "--windows-signing", "unsigned", "--apple-verification", str(self.verification),
                      "--apple-team-id", TEAM, "--apple-signing-identity", IDENTITY]), \
                self.assertRaises(release.ReleaseError):
            staging.main()

    def test_process_failures_are_visible_without_printing_secret_command_arguments(self):
        with patch("launcher_apple.subprocess.run", return_value=subprocess.CompletedProcess(
                [], 1, "", "wrong password")):
            with self.assertRaisesRegex(release.ReleaseError, "wrong password") as caught:
                apple.run(["/usr/bin/security", "import", "-P", "DO_NOT_PRINT_SECRET"])
            self.assertNotIn("DO_NOT_PRINT_SECRET", str(caught.exception))
        command = ["/usr/bin/security", "import", "-P", "DO_NOT_PRINT_SECRET"]
        with patch("launcher_apple.subprocess.run", side_effect=subprocess.TimeoutExpired(command, 120)):
            with self.assertRaisesRegex(release.ReleaseError, "timed out") as caught:
                apple.run(command)
            self.assertNotIn("DO_NOT_PRINT_SECRET", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
