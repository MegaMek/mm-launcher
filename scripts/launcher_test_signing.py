# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later
"""Plan and verify a non-publishing SignPath test-signing rehearsal."""

import argparse
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import tempfile

import launcher_release as release


def test_source(root, environment, version=None, commit=None):
    if environment.get("GITHUB_EVENT_NAME") != "workflow_dispatch":
        raise release.ReleaseError("Test signing requires official manual dispatch from main")
    _, declaration = release.source_version(root)
    current = declaration.group(1)
    source = release.validate_source(current, root, environment)
    if (version is not None and version != current) or (commit is not None and commit != source):
        raise release.ReleaseError("Test signing must use the unchanged version and exact dispatched main commit")
    release.verify_checkout(root, source)
    return current, source


def configuration(environment):
    pin = environment.get("SIGNPATH_TEST_CERTIFICATE_SHA256")
    if not isinstance(pin, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", pin):
        raise release.ReleaseError("SIGNPATH_TEST_CERTIFICATE_SHA256 must pin the self-signed test certificate")
    return release.signing_configuration({
        **environment,
        "LAUNCHER_SIGNING_ENABLED": "true",
        "SIGNPATH_SIGNING_POLICY_SLUG": "test-signing",
        "SIGNPATH_CERTIFICATE_SHA256": pin,
    })


def run(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, check=False, timeout=120)
    except subprocess.TimeoutExpired as error:
        raise release.ReleaseError(f"{command[0]} test verification timed out") from error
    if result.returncode:
        raise release.ReleaseError(f"{command[0]} test verification failed: {result.stdout} {result.stderr}")
    return result.stdout


def verify(directory, version, commit, certificate_sha256, tsa_certificates):
    pin = release.certificate_digest(certificate_sha256)
    release.numeric_version(version)
    release.require_sha(commit)
    name = f"MegaMek-Launcher-{version}-windows-x64.msi"
    expected = {name, name + ".sha256", "test-certificate.cer", "test-signing-inspection.json"}
    if directory.is_symlink() or not directory.is_dir() or {file.name for file in directory.iterdir()} != expected:
        raise release.ReleaseError("Test signing must retain exactly the inspected MSI, checksum, certificate and record")
    package = directory / name
    checksum = directory / (name + ".sha256")
    certificate = directory / "test-certificate.cer"
    for file, limit in ((package, release.INSTALLER_LIMIT), (checksum, 256), (certificate, 65536)):
        if file.is_symlink() or not file.is_file() or not 0 < file.stat().st_size <= limit:
            raise release.ReleaseError("Test signing requires bounded, nonempty regular files")
    record = release.load_signing_record(directory / "test-signing-inspection.json")
    if (not isinstance(record, dict)
            or set(record) != {"schema_version", "purpose", "name", "version", "commit", "unsigned_sha256",
                        "sha256", "size", "certificate_sha256", "timestamped"}
            or type(record["schema_version"]) is not int or record["schema_version"] != 1
            or record["purpose"] != "signpath-test-only" or record["name"] != name
            or record["version"] != version or record["commit"] != commit
            or type(record["size"]) is not int or record["size"] != package.stat().st_size
            or record["timestamped"] is not True or record["certificate_sha256"] != pin
            or record["sha256"] != release.digest(package) or release.digest(certificate) != pin
            or not isinstance(record["unsigned_sha256"], str)
            or not re.fullmatch(r"[0-9a-f]{64}", record["unsigned_sha256"])
            or record["unsigned_sha256"] == record["sha256"]
            or checksum.read_bytes() != f'{record["sha256"]}  {name}\n'.encode("ascii")):
        raise release.ReleaseError("Test-signing inspection does not match the pinned certificate and final MSI bytes")
    if tsa_certificates.is_symlink() or not tsa_certificates.is_file():
        raise release.ReleaseError("Timestamp verification requires the system CA bundle")
    with tempfile.TemporaryDirectory(prefix="launcher-test-certificate-") as temporary:
        pem = Path(temporary) / "test-certificate.pem"
        pem.write_text(ssl.DER_cert_to_PEM_cert(certificate.read_bytes()), encoding="ascii", newline="\n")
        run(["openssl", "verify", "-CAfile", str(pem), "-check_ss_sig", str(pem)])
        result = run(["osslsigncode", "verify", "-CAfile", str(pem), "-TSA-CAfile",
                      str(tsa_certificates), "-require-leaf-hash", f"sha256:{pin}", "-in", str(package)])
        if ("Timestamp Server Signature verification: ok" not in result
                or "Timestamp Server Signature verification: failed" in result
                or "Timestamp is not available" in result
                or "Signature verification: failed" in result):
            raise release.ReleaseError("Test signing requires an explicitly verified signature and trusted timestamp")
    evidence = {**record, "cryptographically_verified": True, "publicly_trusted": False}
    with (directory / "test-signing-verification.json").open("x", encoding="utf-8", newline="\n") as output:
        json.dump(evidence, output, sort_keys=True)
    (directory / "TEST-ONLY.txt").write_text(
        "SignPath onboarding test only. This MSI uses a self-signed test certificate.\n"
        "It is not a public release or launcher update. Do not distribute or install it.\n",
        encoding="ascii", newline="\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("plan", "verify"))
    parser.add_argument("--version")
    parser.add_argument("--commit")
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--certificate-sha256")
    parser.add_argument("--tsa-certificates", type=Path)
    args = parser.parse_args()
    version, commit = test_source(Path.cwd(), os.environ, args.version, args.commit)
    if args.command == "plan":
        config = configuration(os.environ)
        values = {"version": version, "commit": commit, "organization_id": config.organization_id,
                  "project_slug": config.project_slug, "signing_policy_slug": config.signing_policy_slug,
                  "artifact_configuration_slug": config.artifact_configuration_slug,
                  "certificate_sha256": config.certificate_sha256}
        with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
            for key, value in values.items():
                output.write(f"{key}={value}\n")
        print("Planned test-signing only; no release or version change is authorized.")
    else:
        if any(value is None for value in (args.version, args.commit, args.assets,
                                          args.certificate_sha256, args.tsa_certificates)):
            raise release.ReleaseError("Test verification requires source, assets, certificate pin and timestamp CAs")
        verify(args.assets, version, commit, args.certificate_sha256, args.tsa_certificates)
        print("Verified test-only signature and MSI digest; no public trust or publication is authorized.")


if __name__ == "__main__":
    try:
        main()
    except (release.ReleaseError, OSError) as error:
        print(f"Launcher test signing failed: {error}. No release may be published.", file=sys.stderr)
        sys.exit(1)
