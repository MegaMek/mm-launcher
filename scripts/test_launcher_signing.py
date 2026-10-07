# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later

import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import launcher_release as release
import launcher_signing as signing
from test_launcher_release import COMMIT, ENVIRONMENT, VERSION, FakeCiGitHub, FakeGitHub


CERTIFICATE = hashlib.sha256(b"unit-test-certificate").hexdigest()
SIGNING_ENVIRONMENT = {
    **ENVIRONMENT,
    "GITHUB_EVENT_NAME": "workflow_dispatch",
    "LAUNCHER_SIGNING_ENABLED": "true",
    "SIGNPATH_ORGANIZATION_ID": "12345678-1234-1234-1234-123456789abc",
    "SIGNPATH_PROJECT_SLUG": "launcher",
    "SIGNPATH_SIGNING_POLICY_SLUG": "release",
    "SIGNPATH_ARTIFACT_CONFIGURATION_SLUG": "windows-msi",
    "SIGNPATH_CERTIFICATE_SHA256": CERTIFICATE,
    "SIGNPATH_API_TOKEN": "unit-test-submitter-token",
}
PWSH = shutil.which("pwsh")
ROOT = Path(__file__).resolve().parent.parent


class LauncherSigningTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "build.gradle.kts").write_text(f'version = "{VERSION}"\n',
                                                  encoding="utf-8", newline="\n")
        self.source = self.root / "source"
        self.destination = self.root / "publication"
        self.input = self.root / "input"
        self.signed = self.root / "signed"
        self.verification = self.root / "verification.json"
        self.write_assets(VERSION)

    def write_assets(self, version):
        self.source.mkdir(exist_ok=True)
        for file in self.source.iterdir():
            file.unlink()
        for suffix in release.INSTALLERS:
            name = f"MegaMek-Launcher-{version}-{suffix}"
            file = self.source / name
            file.write_bytes(name.encode("ascii"))
            file.with_name(name + ".sha256").write_text(
                f"{release.digest(file)}  {name}\n", encoding="ascii")

    def prepare(self, mode="signpath", version=VERSION):
        signing.prepare_assets(self.source, self.destination, self.input, version, mode)

    def signed_record(self, version=VERSION, commit=COMMIT):
        self.signed.mkdir()
        name = f"MegaMek-Launcher-{version}-windows-x64.msi"
        original = self.input / name
        file = self.signed / name
        file.write_bytes(original.read_bytes() + b"\nunit-test-signed-bytes")
        record = {
            "schema_version": 1, "name": name, "version": version, "commit": commit,
            "unsigned_sha256": release.digest(original), "sha256": release.digest(file),
            "size": file.stat().st_size, "certificate_sha256": CERTIFICATE,
            "signature_status": "Valid", "timestamped": True,
        }
        self.verification.write_text(json.dumps(record), encoding="utf-8")
        return record

    def complete(self, version=VERSION, commit=COMMIT):
        signing.accept_signed_msi(self.destination, self.signed, self.verification,
                                  version, commit, CERTIFICATE)
        return release.validate_files(self.destination, version)

    def test_disabled_signing_needs_no_account_and_ignores_unused_configuration(self):
        for value in ("", "false"):
            with self.subTest(value=value):
                configuration = release.signing_configuration({
                    "LAUNCHER_SIGNING_ENABLED": value, "SIGNPATH_ORGANIZATION_ID": "not-configured"})
                self.assertEqual(release.SigningConfiguration("unsigned"), configuration)
        self.assertEqual("unsigned", release.signing_configuration({}).mode)

    def test_enabled_signing_captures_public_configuration_but_never_outputs_token(self):
        output = self.root / "output"
        environment = {**SIGNING_ENVIRONMENT, "GITHUB_OUTPUT": str(output),
                       "SIGNPATH_CERTIFICATE_SHA256": CERTIFICATE.upper()}
        with patch.dict(os.environ, environment, clear=True), \
                patch("launcher_release.Path.cwd", return_value=self.root), \
                patch("launcher_release.sys.argv", ["launcher_release.py", "signing-plan"]), \
                patch("launcher_release.GitHub") as github, patch("builtins.print"):
            release.main()
        values = dict(line.split("=", 1) for line in output.read_text().splitlines())
        self.assertEqual("signpath", values["mode"])
        self.assertEqual(CERTIFICATE, values["certificate_sha256"])
        self.assertEqual("windows-msi", values["artifact_configuration_slug"])
        self.assertNotIn(SIGNING_ENVIRONMENT["SIGNPATH_API_TOKEN"], output.read_text())
        github.return_value.request.assert_not_called()

    def test_invalid_enable_flags_and_incomplete_enabled_configuration_fail_closed(self):
        for value in ("True", "FALSE", "1", " true", "tru", None):
            with self.subTest(flag=value), self.assertRaises(release.ReleaseError):
                release.signing_configuration({**SIGNING_ENVIRONMENT, "LAUNCHER_SIGNING_ENABLED": value})
        for key in SIGNING_ENVIRONMENT:
            if key.startswith("SIGNPATH_"):
                environment = dict(SIGNING_ENVIRONMENT)
                del environment[key]
                with self.subTest(missing=key), self.assertRaises(release.ReleaseError):
                    release.signing_configuration(environment)
        for key, value in (
                ("SIGNPATH_ORGANIZATION_ID", "not-a-uuid"),
                ("SIGNPATH_PROJECT_SLUG", "launcher\nmode=unsigned"),
                ("SIGNPATH_SIGNING_POLICY_SLUG", "invalid policy"),
                ("SIGNPATH_ARTIFACT_CONFIGURATION_SLUG", ""),
                ("SIGNPATH_CERTIFICATE_SHA256", "f" * 63),
                ("SIGNPATH_API_TOKEN", " \n")):
            with self.subTest(key=key), self.assertRaises(release.ReleaseError):
                release.signing_configuration({**SIGNING_ENVIRONMENT, key: value})

    def test_signing_plan_rejects_forks_branches_and_nonmanual_events_without_outputs(self):
        output = self.root / "output"
        for changes in (
                {"GITHUB_REPOSITORY": "fork/mm-launcher"}, {"GITHUB_REF": "refs/heads/feature"},
                {"GITHUB_EVENT_NAME": "pull_request"}, {"GITHUB_EVENT_NAME": "push"},
                {"SIGNPATH_API_TOKEN": ""}):
            with self.subTest(changes=changes), \
                    patch.dict(os.environ, {**SIGNING_ENVIRONMENT, "GITHUB_OUTPUT": str(output),
                                            **changes}, clear=True), \
                    patch("launcher_release.Path.cwd", return_value=self.root), \
                    patch("launcher_release.sys.argv", ["launcher_release.py", "signing-plan"]), \
                    patch("launcher_release.GitHub") as github, self.assertRaises(release.ReleaseError):
                release.main()
            self.assertFalse(output.exists())
            github.return_value.request.assert_not_called()

    def test_unsigned_staging_preserves_all_ten_bytes_and_has_no_signing_input(self):
        self.prepare("unsigned")
        self.assertFalse(self.input.exists())
        expected = {file.name: file.read_bytes() for file in self.source.iterdir()}
        self.assertEqual(expected, {file.name: file.read_bytes() for file in self.destination.iterdir()})
        with self.assertRaises(FileExistsError):
            self.prepare("unsigned")

    def test_unknown_staging_modes_cannot_silently_preserve_unsigned_assets(self):
        with self.assertRaises(release.ReleaseError):
            self.prepare("unexpected-mode")
        self.assertFalse(self.destination.exists())

    def test_source_checksum_errors_stop_before_staging_or_signing(self):
        file = self.source / f"MegaMek-Launcher-{VERSION}-windows-x64.msi"
        file.write_bytes(b"unexpected change")
        with self.assertRaisesRegex(release.ReleaseError, "Checksum mismatch"):
            self.prepare()
        self.assertFalse(self.destination.exists())
        self.assertFalse(self.input.exists())

    def test_only_msi_is_submitted_and_only_its_bytes_and_checksum_change(self):
        self.prepare()
        name = f"MegaMek-Launcher-{VERSION}-windows-x64.msi"
        self.assertEqual([name], [file.name for file in self.input.iterdir()])
        before = {file.name: file.read_bytes() for file in self.destination.iterdir()}
        record = self.signed_record()
        assets = self.complete()
        self.assertEqual(10, len(assets))
        for file in self.destination.iterdir():
            if file.name not in (name, name + ".sha256"):
                self.assertEqual(before[file.name], file.read_bytes())
        self.assertNotEqual(before[name], (self.destination / name).read_bytes())
        self.assertEqual(f'{record["sha256"]}  {name}\n',
                         (self.destination / (name + ".sha256")).read_text(encoding="ascii"))

    def test_invalid_verification_never_replaces_the_original_installer(self):
        self.prepare()
        original = self.signed_record()
        name = original["name"]
        before = (self.destination / name).read_bytes()
        for key, value in (
                ("schema_version", True), ("schema_version", 2), ("name", "other.msi"),
                ("version", "0.14.99"), ("commit", "b" * 40),
                ("certificate_sha256", "f" * 64), ("signature_status", "NotTrusted"),
                ("timestamped", False), ("timestamped", "true"), ("sha256", "f" * 64),
                ("unsigned_sha256", "f" * 64), ("size", original["size"] + 1),
                ("size", True)):
            record = {**original, key: value}
            self.verification.write_text(json.dumps(record), encoding="utf-8")
            with self.subTest(field=key, value=value), self.assertRaises(release.ReleaseError):
                self.complete()
            self.assertEqual(before, (self.destination / name).read_bytes())
            release.validate_files(self.destination, VERSION)

    def test_signed_output_rejects_extras_wrong_names_unchanged_bytes_and_size_overflow(self):
        self.prepare()
        record = self.signed_record()
        file = self.signed / record["name"]
        extra = self.signed / "unexpected.txt"
        extra.write_text("not an MSI")
        with self.assertRaisesRegex(release.ReleaseError, "exactly the expected"):
            self.complete()
        extra.unlink()
        wrong = file.with_name("other.msi")
        file.rename(wrong)
        with self.assertRaises(release.ReleaseError):
            self.complete()
        wrong.rename(file)
        file.write_bytes((self.input / record["name"]).read_bytes())
        record["sha256"] = record["unsigned_sha256"]
        record["size"] = file.stat().st_size
        self.verification.write_text(json.dumps(record))
        with self.assertRaisesRegex(release.ReleaseError, "final MSI bytes"):
            self.complete()
        with file.open("wb") as stream:
            stream.truncate(release.INSTALLER_LIMIT + 1)
        with self.assertRaisesRegex(release.ReleaseError, "bounded Windows MSI"):
            self.complete()

    def test_verification_json_is_bounded_unambiguous_and_versioned(self):
        for content in ('{"schema_version":1,"schema_version":2}', "{bad json",
                        " " * (release.SIGNING_RECORD_LIMIT + 1), ""):
            self.verification.write_text(content, encoding="utf-8")
            with self.subTest(content=content[:40]), self.assertRaises(release.ReleaseError):
                release.load_signing_record(self.verification)

    @patch("builtins.print")
    def test_signed_publication_rechecks_evidence_before_any_write_and_reports_exact_scope(self, output):
        self.prepare()
        record = self.signed_record()
        assets = self.complete()
        github = FakeGitHub()
        with self.assertRaises(release.ReleaseError):
            release.publish(github, VERSION, COMMIT, assets, {**record, "timestamped": False}, CERTIFICATE)
        self.assertEqual([], github.calls)
        release.publish(github, VERSION, COMMIT, assets, record, CERTIFICATE)
        self.assertIn("Windows MSI is Authenticode-signed and timestamped", github.release["body"])
        self.assertIn("certificate by SignPath Foundation", github.release["body"])
        self.assertIn("Linux/macOS installers remain unsigned", github.release["body"])
        self.assertEqual(record["sha256"], next(
            asset["digest"][7:] for asset in github.release["assets"] if asset["name"] == record["name"]))

    def test_enabled_publish_record_and_finalize_require_evidence_before_remote_work(self):
        self.prepare()
        for command in ("publish", "record", "finalize"):
            with self.subTest(command=command), \
                    patch.dict(os.environ, ENVIRONMENT, clear=True), \
                    patch("launcher_release.Path.cwd", return_value=self.root), \
                    patch("launcher_release.verify_checkout"), \
                    patch("launcher_release.GitHub") as github, \
                    patch("launcher_release.sys.argv", ["launcher_release.py", command,
                          "--version", VERSION, "--commit", COMMIT, "--assets", str(self.destination),
                          "--windows-signing", "signpath"]), self.assertRaises(release.ReleaseError):
                release.main()
            github.return_value.request.assert_not_called()

    @patch("builtins.print")
    def test_signed_finalize_cli_uses_verified_final_bytes_and_never_republishes(self, output):
        github = FakeCiGitHub((self.root / "build.gradle.kts").read_text())
        version, commit, base = release.prepare_candidate(github, self.root, ENVIRONMENT)
        self.write_assets(version)
        self.prepare(version=version)
        record = self.signed_record(version, commit)
        assets = self.complete(version, commit)
        release.publish(github, version, commit, assets, record, CERTIFICATE)
        (self.root / "build.gradle.kts").write_text(f'version = "{version}"\n', newline="\n")
        github.calls.clear()
        with patch.dict(os.environ, ENVIRONMENT, clear=True), \
                patch("launcher_release.Path.cwd", return_value=self.root), \
                patch("launcher_release.verify_checkout"), \
                patch("launcher_release.GitHub", return_value=github), \
                patch("launcher_release.sys.argv", ["launcher_release.py", "finalize",
                      "--version", version, "--commit", commit, "--base", base,
                      "--assets", str(self.destination), "--windows-signing", "signpath",
                      "--signing-verification", str(self.verification),
                      "--signer-certificate-sha256", CERTIFICATE]):
            release.main()
        self.assertEqual(commit, github.main["object"]["sha"])
        self.assertEqual([("PATCH", f"{release.API}/git/refs/heads/main")],
                         [(method, endpoint) for method, endpoint, _ in github.calls if method != "GET"])

    @patch("builtins.print")
    def test_signed_provenance_supports_ci_reuse_and_detects_signing_record_drift(self, output):
        github = FakeCiGitHub((self.root / "build.gradle.kts").read_text())
        version, commit, _ = release.prepare_candidate(github, self.root, ENVIRONMENT)
        self.write_assets(version)
        self.prepare(version=version)
        record = self.signed_record(version, commit)
        assets = self.complete(version, commit)
        release.publish(github, version, commit, assets, record, CERTIFICATE)
        provenance = self.root / release.PROVENANCE_FILE
        release.record_publication(github, version, commit, assets, ENVIRONMENT, provenance,
                                   record, CERTIFICATE)
        retained = json.loads(provenance.read_text(encoding="utf-8"))
        self.assertEqual(record, retained["windows_signing"])
        (self.root / "build.gradle.kts").write_text(f'version = "{version}"\n', newline="\n")
        environment = {**ENVIRONMENT, "GITHUB_EVENT_NAME": "push", "GITHUB_SHA": commit}
        for changed in (False, True):
            current = copy.deepcopy(retained)
            if changed:
                current["windows_signing"]["sha256"] = "f" * 64
            github.retain_provenance(current)
            github.calls.clear()
            with self.subTest(changed=changed), patch("launcher_release.verify_checkout"):
                if changed:
                    with self.assertRaises(release.ReleaseError):
                        release.verified_release_push(github, self.root, environment)
                else:
                    self.assertTrue(release.verified_release_push(github, self.root, environment)[0])
            self.assertTrue(all(method == "GET" for method, _, _ in github.calls))

    @unittest.skipUnless(PWSH, "PowerShell is required for Authenticode guard unit tests")
    def test_powershell_signature_timestamp_certificate_and_msi_identity_guards(self):
        script = r"""
. "$env:SIGNATURE_SCRIPT"
$ErrorActionPreference = 'Stop'
$certificate = [pscustomobject]@{ RawData = [byte[]](1, 2, 3) }
$pin = [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData($certificate.RawData)).ToLowerInvariant()
$valid = [pscustomobject]@{
    Status = 'Valid'; SignerCertificate = $certificate; TimeStamperCertificate = $certificate
}
function Expect-Rejection([scriptblock]$Action) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (!$rejected) { throw 'Expected signature/identity guard rejection.' }
}
Assert-LauncherSignature $valid $pin
foreach ($status in @('NotSigned', 'HashMismatch', 'NotTrusted', 'UnknownError')) {
    $invalid = [pscustomobject]@{
        Status = $status; SignerCertificate = $certificate; TimeStamperCertificate = $certificate
    }
    Expect-Rejection { Assert-LauncherSignature $invalid $pin }
}
Expect-Rejection { Assert-LauncherSignature $null $pin }
Expect-Rejection { Assert-LauncherSignature $valid ('f' * 64) }
Expect-Rejection { Assert-LauncherSignature $valid 'not-a-certificate' }
$missingTimestamp = [pscustomobject]@{
    Status = 'Valid'; SignerCertificate = $certificate; TimeStamperCertificate = $null
}
Expect-Rejection { Assert-LauncherSignature $missingTimestamp $pin }
$identity = @{
    ProductName = 'MegaMek Launcher'; ProductVersion = '0.14.5'
    UpgradeCode = '{616FFD64-0D7F-4FBE-9BB9-63FD6D9D32FA}'
}
Assert-LauncherMsiIdentity $identity '0.14.5'
foreach ($field in @('ProductName', 'ProductVersion', 'UpgradeCode')) {
    $wrong = $identity.Clone()
    $wrong[$field] = 'incorrect'
    Expect-Rejection { Assert-LauncherMsiIdentity $wrong '0.14.5' }
}
"""
        result = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-Command", script],
                                env={**os.environ, "SIGNATURE_SCRIPT":
                                     str(ROOT / "scripts" / "verify_launcher_signature.ps1")},
                                capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    @unittest.skipUnless(PWSH, "PowerShell is required for MSI verification orchestration unit tests")
    def test_powershell_verification_binds_files_and_rejects_identity_drift_without_receipt(self):
        self.prepare()
        expected = self.signed_record()
        receipt = self.root / "powershell-verification.json"
        rejected = self.root / "rejected-verification.json"
        script = r"""
. "$env:SIGNATURE_SCRIPT"
$ErrorActionPreference = 'Stop'
$certificate = [pscustomobject]@{ RawData = [byte[]](1, 2, 3) }
$pin = [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData($certificate.RawData)).ToLowerInvariant()
$script:wrongIdentity = $false
function Get-AuthenticodeSignature {
    param([string]$LiteralPath)
    return [pscustomobject]@{
        Status = 'Valid'; SignerCertificate = $certificate; TimeStamperCertificate = $certificate
    }
}
function Get-LauncherMsiIdentity([string]$Path) {
    $version = if ($script:wrongIdentity -and $Path -eq $env:SIGNED_MSI) { '0.14.99' } else { '0.14.5' }
    return @{
        ProductName = 'MegaMek Launcher'; ProductVersion = $version
        UpgradeCode = '{616FFD64-0D7F-4FBE-9BB9-63FD6D9D32FA}'
    }
}
Confirm-LauncherMsi -UnsignedMsi $env:UNSIGNED_MSI -SignedMsi $env:SIGNED_MSI `
    -Version '0.14.5' -Commit ('a' * 40) -CertificateSha256 $pin -Verification $env:RECEIPT
$script:wrongIdentity = $true
$failed = $false
try {
    Confirm-LauncherMsi -UnsignedMsi $env:UNSIGNED_MSI -SignedMsi $env:SIGNED_MSI `
        -Version '0.14.5' -Commit ('a' * 40) -CertificateSha256 $pin -Verification $env:REJECTED_RECEIPT
} catch { $failed = $true }
if (!$failed -or (Test-Path -LiteralPath $env:REJECTED_RECEIPT)) {
    throw 'Changed MSI identity must fail without producing verification.'
}
"""
        result = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-Command", script],
                                env={**os.environ, "SIGNATURE_SCRIPT":
                                     str(ROOT / "scripts" / "verify_launcher_signature.ps1"),
                                     "UNSIGNED_MSI": str(self.input / expected["name"]),
                                     "SIGNED_MSI": str(self.signed / expected["name"]),
                                     "RECEIPT": str(receipt), "REJECTED_RECEIPT": str(rejected)},
                                capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        actual = json.loads(receipt.read_text(encoding="utf-8"))
        expected["certificate_sha256"] = hashlib.sha256(bytes((1, 2, 3))).hexdigest()
        self.assertEqual(expected, actual)
        self.assertFalse(rejected.exists())

    def test_workflow_keeps_signing_release_only_and_consumers_bound_to_immutable_outputs(self):
        workflow = (ROOT / ".github" / "workflows" / "launcher-release.yml").read_text()
        prepare = workflow.split("\n  prepare:", 1)[1].split("\n  installers:", 1)[0]
        assets = workflow.split("\n  release-assets:", 1)[1].split("\n  publish:", 1)[0]
        publish = workflow.split("\n  publish:", 1)[1].split("\n  finalize:", 1)[0]
        finalize = workflow.split("\n  finalize:", 1)[1]
        self.assertLess(prepare.index("signing-plan"), prepare.index("launcher_release.py candidate"))
        self.assertIn("if: needs.prepare.outputs.signing_mode == 'signpath'", assets)
        self.assertIn("github-artifact-id: ${{ steps.unsigned.outputs.artifact-id }}", assets)
        self.assertIn("wait-for-completion: true", assets)
        self.assertIn("Confirm-LauncherMsi", assets)
        self.assertNotIn("continue-on-error", assets)
        self.assertNotIn("overwrite: true", assets)
        for consumer in (publish, finalize):
            self.assertIn("artifact-ids: ${{ needs.release-assets.outputs.artifact_id }}", consumer)
            self.assertIn("artifact-ids: ${{ needs.release-assets.outputs.verification_artifact_id }}", consumer)
            self.assertNotIn("pattern: MegaMek-Launcher-*-installers", consumer)
            self.assertIn('--windows-signing "$WINDOWS_SIGNING"', consumer)
            self.assertIn("--signer-certificate-sha256", consumer)
        ordinary = (ROOT / ".github" / "workflows" / "launcher-archives.yml").read_text()
        self.assertNotIn("secrets.SIGNPATH", ordinary)
        self.assertNotIn("github-action-submit-signing-request", ordinary)
        self.assertIn("-p 'test_launcher_*.py'", ordinary)


if __name__ == "__main__":
    unittest.main()
