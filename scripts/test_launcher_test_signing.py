# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import launcher_release as release
import launcher_test_signing as rehearsal
from test_launcher_signing import PWSH, ROOT, SIGNING_ENVIRONMENT
from test_launcher_release import COMMIT, VERSION


CERTIFICATE = b"offline-test-certificate"
PIN = hashlib.sha256(CERTIFICATE).hexdigest()
ENVIRONMENT = {**SIGNING_ENVIRONMENT, "SIGNPATH_TEST_CERTIFICATE_SHA256": PIN}


class LauncherTestSigningTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "build.gradle.kts").write_text(f'version = "{VERSION}"\n', newline="\n")
        self.directory = self.root / "result"
        self.directory.mkdir()
        self.name = f"MegaMek-Launcher-{VERSION}-windows-x64.msi"
        self.package = self.directory / self.name
        self.package.write_bytes(b"offline-signed-test-msi")
        (self.directory / "test-certificate.cer").write_bytes(CERTIFICATE)
        self.record = {
            "schema_version": 1, "purpose": "signpath-test-only", "name": self.name,
            "version": VERSION, "commit": COMMIT, "unsigned_sha256": "f" * 64,
            "sha256": release.digest(self.package), "size": self.package.stat().st_size,
            "certificate_sha256": PIN, "timestamped": True,
        }
        self.inspection = self.directory / "test-signing-inspection.json"
        self.write_record()
        self.checksum = self.directory / (self.name + ".sha256")
        self.checksum.write_bytes(f'{self.record["sha256"]}  {self.name}\n'.encode("ascii"))
        self.cas = self.root / "timestamp-cas.pem"
        self.cas.write_text("offline timestamp roots")
        self.evidence = self.directory / "test-signing-verification.json"

    def write_record(self):
        self.inspection.write_text(json.dumps(self.record), encoding="utf-8")

    def verify(self):
        rehearsal.verify(self.directory, VERSION, COMMIT, PIN, self.cas)

    def test_configuration_never_uses_production_policy_pin_or_enable_flag(self):
        config = rehearsal.configuration({
            **ENVIRONMENT, "SIGNPATH_SIGNING_POLICY_SLUG": "release-signing",
            "SIGNPATH_CERTIFICATE_SHA256": "a" * 64, "LAUNCHER_SIGNING_ENABLED": "false",
        })
        self.assertEqual("test-signing", config.signing_policy_slug)
        self.assertEqual(PIN, config.certificate_sha256)
        self.assertEqual(PIN, rehearsal.configuration({
            **ENVIRONMENT, "SIGNPATH_TEST_CERTIFICATE_SHA256": PIN.upper()}).certificate_sha256)
        for key in ("SIGNPATH_TEST_CERTIFICATE_SHA256", "SIGNPATH_ORGANIZATION_ID",
                    "SIGNPATH_PROJECT_SLUG", "SIGNPATH_ARTIFACT_CONFIGURATION_SLUG", "SIGNPATH_API_TOKEN"):
            environment = {**ENVIRONMENT, key: ""}
            with self.subTest(key=key), self.assertRaises(release.ReleaseError):
                rehearsal.configuration(environment)
        with self.assertRaises(release.ReleaseError):
            rehearsal.configuration({**ENVIRONMENT, "SIGNPATH_TEST_CERTIFICATE_SHA256": "not-a-pin"})

    def test_plan_captures_unchanged_main_version_and_public_settings_without_network_or_token_output(self):
        output = self.root / "github-output"
        with patch.dict(os.environ, {**ENVIRONMENT, "GITHUB_OUTPUT": str(output)}, clear=True), \
                patch("launcher_test_signing.Path.cwd", return_value=self.root), \
                patch("launcher_test_signing.sys.argv", ["launcher_test_signing.py", "plan"]), \
                patch("launcher_release.verify_checkout") as checkout, \
                patch("launcher_release.GitHub") as github, patch("builtins.print"):
            rehearsal.main()
        values = dict(line.split("=", 1) for line in output.read_text().splitlines())
        self.assertEqual(VERSION, values["version"])
        self.assertEqual(COMMIT, values["commit"])
        self.assertEqual("test-signing", values["signing_policy_slug"])
        self.assertNotIn(ENVIRONMENT["SIGNPATH_API_TOKEN"], output.read_text())
        checkout.assert_called_once_with(self.root, COMMIT)
        github.assert_not_called()
        self.assertEqual(f'version = "{VERSION}"\n', (self.root / "build.gradle.kts").read_text())

    def test_plan_rejects_forks_nonmain_nonmanual_invalid_settings_and_checkout_drift_without_outputs(self):
        output = self.root / "github-output"
        for changes in (
                {"GITHUB_REPOSITORY": "fork/mm-launcher"}, {"GITHUB_REF": "refs/heads/feature"},
                {"GITHUB_EVENT_NAME": "push"}, {"GITHUB_EVENT_NAME": "pull_request"},
                {"GITHUB_SHA": "main"}, {"SIGNPATH_TEST_CERTIFICATE_SHA256": ""},
                {"SIGNPATH_PROJECT_SLUG": "launcher\ninjected=true"}):
            with self.subTest(changes=changes), \
                    patch.dict(os.environ, {**ENVIRONMENT, "GITHUB_OUTPUT": str(output), **changes}, clear=True), \
                    patch("launcher_test_signing.Path.cwd", return_value=self.root), \
                    patch("launcher_test_signing.sys.argv", ["launcher_test_signing.py", "plan"]), \
                    patch("launcher_release.verify_checkout"), self.assertRaises(release.ReleaseError):
                rehearsal.main()
            self.assertFalse(output.exists())
        with patch("launcher_release.verify_checkout", side_effect=release.ReleaseError("checkout mismatch")), \
                self.assertRaises(release.ReleaseError):
            rehearsal.test_source(self.root, ENVIRONMENT)

    def test_verification_source_cannot_be_a_release_candidate_or_different_version(self):
        for version, commit in ((VERSION, "b" * 40), ("0.99.9", COMMIT)):
            with self.subTest(version=version, commit=commit), self.assertRaises(release.ReleaseError):
                rehearsal.test_source(self.root, ENVIRONMENT, version, commit)

    def test_verification_uses_isolated_pinned_certificate_and_separate_timestamp_roots(self):
        commands = []

        def tools(command):
            commands.append(command)
            pem = Path(command[command.index("-CAfile") + 1])
            self.assertIn("BEGIN CERTIFICATE", pem.read_text())
            self.assertNotEqual(self.directory, pem.parent)
            return "Timestamp Server Signature verification: ok\n"

        with patch("launcher_test_signing.run", side_effect=tools):
            self.verify()
        self.assertEqual(["openssl", "osslsigncode"], [command[0] for command in commands])
        self.assertIn("-check_ss_sig", commands[0])
        self.assertEqual(str(self.cas), commands[1][commands[1].index("-TSA-CAfile") + 1])
        self.assertEqual(str(self.package), commands[1][-1])
        self.assertEqual(f"sha256:{PIN}", commands[1][commands[1].index("-require-leaf-hash") + 1])
        self.assertFalse(Path(commands[0][3]).exists())
        evidence = json.loads(self.evidence.read_text())
        self.assertTrue(evidence["cryptographically_verified"])
        self.assertFalse(evidence["publicly_trusted"])
        self.assertEqual("signpath-test-only", evidence["purpose"])
        self.assertIn("Do not distribute or install", (self.directory / "TEST-ONLY.txt").read_text())
        with self.assertRaises(release.ReleaseError):
            release.verify_signing_record(evidence, VERSION, COMMIT,
                                         [release.Asset(self.package, self.package.stat().st_size,
                                                        release.digest(self.package))], PIN)

    def test_crypto_or_timestamp_failures_leave_no_success_evidence_and_remove_temporary_certificate(self):
        for failing_tool in ("openssl", "osslsigncode"):
            commands = []

            def tools(command):
                commands.append(command)
                if command[0] == failing_tool:
                    raise release.ReleaseError("cryptographic verification rejected")
                return "Timestamp Server Signature verification: ok\n"

            with self.subTest(tool=failing_tool), patch("launcher_test_signing.run", side_effect=tools), \
                    self.assertRaises(release.ReleaseError):
                self.verify()
            self.assertFalse(self.evidence.exists())
            self.assertFalse((self.directory / "TEST-ONLY.txt").exists())
            self.assertFalse(Path(commands[0][3]).exists())

    def test_zero_exit_with_unverified_timestamp_or_rejected_additional_signature_is_not_success(self):
        for result in ("Signature verification: ok\n",
                       "Timestamp Server Signature verification: failed\n",
                       "Timestamp Server Signature verification: ok\nTimestamp is not available\n",
                       "Timestamp Server Signature verification: ok\nSignature verification: failed\n"):
            with self.subTest(result=result), patch("launcher_test_signing.run", return_value=result), \
                    self.assertRaisesRegex(release.ReleaseError, "explicitly verified"):
                self.verify()
            self.assertFalse(self.evidence.exists())

    def test_record_tampering_fails_before_native_verification(self):
        original = dict(self.record)
        for key, value in (
                ("schema_version", True), ("purpose", "release"), ("name", "other.msi"),
                ("version", "0.99.9"), ("commit", "b" * 40), ("unsigned_sha256", ["invalid"]),
                ("unsigned_sha256", original["sha256"]), ("size", True),
                ("size", 1), ("sha256", "e" * 64), ("certificate_sha256", "e" * 64),
                ("timestamped", False), ("extra", "unexpected")):
            self.record = {**original, key: value}
            self.write_record()
            with self.subTest(key=key, value=value), patch("launcher_test_signing.run") as tools, \
                    self.assertRaises(release.ReleaseError):
                self.verify()
            tools.assert_not_called()
            self.assertFalse(self.evidence.exists())

    def test_extra_missing_changed_bytes_or_duplicate_fields_fail_before_native_verification(self):
        variants = (
            (self.directory / "extra.txt", b"unexpected"),
            (self.package, b"changed signed MSI"),
            (self.directory / "test-certificate.cer", b"another certificate"),
            (self.checksum, b"incorrect checksum\n"),
            (self.inspection, self.inspection.read_bytes().replace(
                b'"schema_version": 1', b'"schema_version": 1, "schema_version": 1')),
        )
        for file, content in variants:
            before = file.read_bytes() if file.exists() else None
            try:
                file.write_bytes(content)
                with self.subTest(file=file.name), patch("launcher_test_signing.run") as tools, \
                        self.assertRaises(release.ReleaseError):
                    self.verify()
                tools.assert_not_called()
            finally:
                if before is None:
                    file.unlink()
                else:
                    file.write_bytes(before)
        for invalid in (None, [], [self.record], "not a record"):
            self.inspection.write_text(json.dumps(invalid))
            with self.subTest(record=invalid), self.assertRaises(release.ReleaseError):
                self.verify()
        self.write_record()
        self.cas.unlink()
        with self.assertRaises(release.ReleaseError):
            self.verify()
        self.assertFalse(self.evidence.exists())

    def test_native_tool_failures_and_timeouts_are_explicit(self):
        with patch("launcher_test_signing.subprocess.run",
                   return_value=subprocess.CompletedProcess(["osslsigncode"], 1, "", "digest mismatch")), \
                self.assertRaisesRegex(release.ReleaseError, "digest mismatch"):
            rehearsal.run(["osslsigncode", "verify"])
        with patch("launcher_test_signing.subprocess.run",
                   side_effect=subprocess.TimeoutExpired(["osslsigncode"], 120)), \
                self.assertRaisesRegex(release.ReleaseError, "timed out"):
            rehearsal.run(["osslsigncode", "verify"])

    @unittest.skipUnless(PWSH, "PowerShell is required for test-certificate and MSI inspection checks")
    def test_windows_inspection_keeps_production_trust_strict_and_does_not_accept_identity_or_pin_drift(self):
        unsigned = self.root / self.name
        unsigned.write_bytes(b"offline-unsigned-msi")
        signed = self.root / "signed"
        signed.mkdir()
        (signed / self.name).write_bytes(self.package.read_bytes())
        output = self.root / "powershell-result"
        script = r"""
. "$env:TEST_SIGNATURE_SCRIPT"
$ErrorActionPreference = 'Stop'
$certificate = [pscustomobject]@{ RawData = [byte[]](1, 2, 3); Subject = 'CN=Test'; Issuer = 'CN=Test' }
$pin = [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData($certificate.RawData)).ToLowerInvariant()
$signature = [pscustomobject]@{
    Status = 'NotTrusted'; SignerCertificate = $certificate; TimeStamperCertificate = $certificate
}
function Expect-Rejection([scriptblock]$Action) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (!$rejected) { throw 'Expected test/production guard rejection.' }
}
Assert-LauncherTestCertificate $signature $pin
Expect-Rejection { Assert-LauncherSignature $signature $pin }
Expect-Rejection { Assert-LauncherTestCertificate $signature ('f' * 64) }
$certificate.Issuer = 'CN=Production CA'
Expect-Rejection { Assert-LauncherTestCertificate $signature $pin }
$certificate.Issuer = $certificate.Subject
$signature.TimeStamperCertificate = $null
Expect-Rejection { Assert-LauncherTestCertificate $signature $pin }
$signature.TimeStamperCertificate = $certificate
$script:wrongIdentity = $false
function Get-AuthenticodeSignature { return $signature }
function Get-LauncherMsiIdentity([string]$Path) {
    return @{
        ProductName = 'MegaMek Launcher'
        ProductVersion = $(if ($script:wrongIdentity) { '9.9.9' } else { $env:TEST_VERSION })
        UpgradeCode = '{616FFD64-0D7F-4FBE-9BB9-63FD6D9D32FA}'
    }
}
$arguments = @{
    UnsignedMsi = $env:UNSIGNED_MSI; SignedDirectory = $env:SIGNED_DIRECTORY
    Version = $env:TEST_VERSION; Commit = $env:TEST_COMMIT; CertificateSha256 = $pin
}
$script:wrongIdentity = $true
Expect-Rejection { Confirm-LauncherTestMsi @arguments -OutputDirectory $env:TEST_RESULT }
if (Test-Path -LiteralPath $env:TEST_RESULT) { throw 'Identity failure must not retain test inspection.' }
$script:wrongIdentity = $false
Confirm-LauncherTestMsi @arguments -OutputDirectory $env:TEST_RESULT
Expect-Rejection { Confirm-LauncherTestMsi @arguments -OutputDirectory $env:TEST_RESULT }
[IO.File]::WriteAllText((Join-Path $env:SIGNED_DIRECTORY 'unexpected.txt'), 'unexpected')
Expect-Rejection { Confirm-LauncherTestMsi @arguments -OutputDirectory "$env:TEST_RESULT-extra" }
"""
        result = subprocess.run(
            [PWSH, "-NoProfile", "-NonInteractive", "-Command", script], capture_output=True, text=True,
            check=False, timeout=60, env={
                **os.environ, "TEST_SIGNATURE_SCRIPT": str(ROOT / "scripts" / "verify_launcher_test_signature.ps1"),
                "UNSIGNED_MSI": str(unsigned), "SIGNED_DIRECTORY": str(signed), "TEST_VERSION": VERSION,
                "TEST_COMMIT": COMMIT, "TEST_RESULT": str(output),
            })
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        record = json.loads((output / "test-signing-inspection.json").read_text())
        self.assertEqual("signpath-test-only", record["purpose"])
        self.assertEqual(release.digest(unsigned), record["unsigned_sha256"])
        self.assertEqual(self.package.read_bytes(), (output / self.name).read_bytes())
        self.assertEqual(bytes((1, 2, 3)), (output / "test-certificate.cer").read_bytes())


if __name__ == "__main__":
    unittest.main()
