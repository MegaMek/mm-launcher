# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later
"""Release-only Apple keychain setup, notarization and final PKG verification."""

import argparse
import base64
from dataclasses import dataclass
import json
import os
from pathlib import Path
import plistlib
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import tempfile

import launcher_release as release


@dataclass(frozen=True)
class AppleConfiguration:
    mode: str
    team_id: str = ""
    identity: str = ""
    key_id: str = ""
    issuer_id: str = ""


def decoded_secret(environment, key):
    value = environment.get(key)
    if not isinstance(value, str) or not value or len(value) > 1024 * 1024:
        raise release.ReleaseError(f"{key} must be a bounded base64 credential")
    try:
        content = base64.b64decode(value, validate=True)
    except ValueError as error:
        raise release.ReleaseError(f"{key} is not valid base64") from error
    if not content:
        raise release.ReleaseError(f"{key} is empty")
    return content


def configuration(environment, require_credentials=True):
    flag = environment.get("APPLE_SIGNING_ENABLED", "")
    if flag in ("", "false"):
        return AppleConfiguration("unsigned")
    if flag != "true":
        raise release.ReleaseError("APPLE_SIGNING_ENABLED must be exactly true, false, or unset")
    team = environment.get("APPLE_TEAM_ID", "")
    identity = environment.get("APPLE_SIGNING_IDENTITY", "")
    key_id = environment.get("APPLE_NOTARY_KEY_ID", "")
    issuer = environment.get("APPLE_NOTARY_ISSUER_ID", "")
    release.verify_apple_identity(team, identity)
    if not re.fullmatch(r"[A-Z0-9]{10}", key_id):
        raise release.ReleaseError("APPLE_NOTARY_KEY_ID must be the 10-character API key ID")
    if not re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", issuer):
        raise release.ReleaseError("APPLE_NOTARY_ISSUER_ID must be the team API issuer UUID")
    if require_credentials:
        for key in ("APPLE_APPLICATION_P12", "APPLE_INSTALLER_P12", "APPLE_NOTARY_PRIVATE_KEY"):
            decoded_secret(environment, key)
        for key in ("APPLE_APPLICATION_P12_PASSWORD", "APPLE_INSTALLER_P12_PASSWORD"):
            value = environment.get(key)
            if not isinstance(value, str) or not value:
                raise release.ReleaseError(f"{key} is required for enabled Apple signing")
    return AppleConfiguration("apple", team, identity, key_id, issuer)


def require_release(environment):
    if (environment.get("GITHUB_REPOSITORY") != release.REPOSITORY
            or environment.get("GITHUB_REF") != "refs/heads/main"
            or environment.get("GITHUB_EVENT_NAME") != "workflow_dispatch"):
        raise release.ReleaseError("Apple signing is restricted to official manual releases from main")


def run(command, timeout=120):
    try:
        result = subprocess.run(command, capture_output=True, text=True, check=False, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        raise release.ReleaseError(f"{Path(command[0]).name} timed out after {timeout} seconds") from error
    if result.returncode:
        raise release.ReleaseError(f"{Path(command[0]).name} failed: {result.stdout.strip()} {result.stderr.strip()}")
    return result.stdout + result.stderr


def setup(environment):
    require_release(environment)
    config = configuration(environment)
    if config.mode != "apple" or sys.platform != "darwin":
        raise release.ReleaseError("Apple keychain setup requires enabled signing on a macOS runner")
    runner_temp = Path(environment["RUNNER_TEMP"]).resolve()
    original = shlex.split(run(["/usr/bin/security", "list-keychains", "-d", "user"]))
    directory = Path(tempfile.mkdtemp(prefix="mm-launcher-apple-", dir=runner_temp))
    directory.chmod(0o700)
    keychain = directory / "signing.keychain-db"
    state = {"keychain": str(keychain), "original_keychains": original}
    (directory / "state.json").write_text(json.dumps(state), encoding="utf-8")
    # Register the owned directory before importing keys so the always-cleanup step can handle failures.
    with Path(environment["GITHUB_ENV"]).open("a", encoding="utf-8") as output:
        output.write(f"APPLE_SIGNING_DIRECTORY={directory}\nAPPLE_SIGNING_KEYCHAIN={keychain}\n")
    password = secrets.token_urlsafe(32)
    run(["/usr/bin/security", "create-keychain", "-p", password, str(keychain)])
    run(["/usr/bin/security", "set-keychain-settings", "-lut", "7200", str(keychain)])
    run(["/usr/bin/security", "unlock-keychain", "-p", password, str(keychain)])
    for kind in ("APPLICATION", "INSTALLER"):
        file = directory / f"{kind.lower()}.p12"
        file.write_bytes(decoded_secret(environment, f"APPLE_{kind}_P12"))
        file.chmod(0o600)
        run(["/usr/bin/security", "import", str(file), "-k", str(keychain),
             "-P", environment[f"APPLE_{kind}_P12_PASSWORD"], "-T", "/usr/bin/codesign",
             "-T", "/usr/bin/productbuild", "-T", "/usr/bin/productsign"])
        file.unlink()
    run(["/usr/bin/security", "set-key-partition-list", "-S", "apple-tool:,apple:",
         "-s", "-k", password, str(keychain)])
    run(["/usr/bin/security", "list-keychains", "-d", "user", "-s", str(keychain), *original])
    identities = run(["/usr/bin/security", "find-identity", "-v", "-p", "codesigning", str(keychain)])
    if f'"Developer ID Application: {config.identity}"' not in identities:
        raise release.ReleaseError("Temporary keychain does not contain the approved Developer ID Application")
    key = directory / f"AuthKey_{config.key_id}.p8"
    key.write_bytes(decoded_secret(environment, "APPLE_NOTARY_PRIVATE_KEY"))
    key.chmod(0o600)


def cleanup(environment):
    value = environment.get("APPLE_SIGNING_DIRECTORY")
    if not value:
        return
    runner_temp = Path(environment["RUNNER_TEMP"]).resolve()
    directory = Path(value)
    if (directory.is_symlink() or directory.resolve().parent != runner_temp
            or not directory.name.startswith("mm-launcher-apple-")):
        raise release.ReleaseError("Refusing cleanup outside the owned Apple signing directory")
    state = json.loads((directory / "state.json").read_text(encoding="utf-8"))
    keychain = directory / "signing.keychain-db"
    if (state.get("keychain") != str(keychain)
            or not isinstance(state.get("original_keychains"), list)
            or any(not isinstance(item, str) for item in state["original_keychains"])):
        raise release.ReleaseError("Invalid Apple keychain cleanup state")
    try:
        run(["/usr/bin/security", "list-keychains", "-d", "user", "-s", *state["original_keychains"]])
        if keychain.exists():
            run(["/usr/bin/security", "delete-keychain", str(keychain)])
    finally:
        shutil.rmtree(directory)


def verify_code_details(text, team, identity, identifier=None):
    if (f"TeamIdentifier={team}" not in text.splitlines()
            or f"Authority=Developer ID Application: {identity}" not in text.splitlines()
            or not re.search(r"^Timestamp=.+$", text, re.MULTILINE)
            or not re.search(r"^CodeDirectory .*flags=.*\bruntime\b", text, re.MULTILINE)
            or identifier is not None and f"Identifier={identifier}" not in text.splitlines()):
        raise release.ReleaseError("Mac code must retain the approved Developer ID, timestamp and hardened runtime")


def verify_package_signature(text, identity):
    if ("Status: signed by a certificate trusted by macOS" not in text
            or not re.search(r"(?m)^\s*1\.\s+" +
                             re.escape(f"Developer ID Installer: {identity}") + r"\s*$", text)):
        raise release.ReleaseError("PKG must be trusted and signed by the approved Developer ID Installer")


def complete(package, version, commit, platform, team, identity, key_id, issuer, directory, verification):
    release.verify_apple_identity(team, identity)
    name = f"MegaMek-Launcher-{version}-{platform}.pkg"
    if (platform not in release.APPLE_PLATFORMS or package.name != name
            or package.is_symlink() or not package.is_file()
            or not 0 < package.stat().st_size <= release.INSTALLER_LIMIT):
        raise release.ReleaseError("Apple signing requires the exact bounded native PKG")
    verify_package_signature(run(["/usr/sbin/pkgutil", "--check-signature", str(package)]), identity)
    with tempfile.TemporaryDirectory(prefix="mm-launcher-apple-inspection-") as inspection:
        expanded = Path(inspection) / "expanded"
        run(["/usr/sbin/pkgutil", "--expand-full", str(package), str(expanded)])
        apps = list(expanded.rglob("MegaMek Launcher.app"))
        if len(apps) != 1 or apps[0].is_symlink():
            raise release.ReleaseError("Signed PKG must contain exactly one launcher application")
        app = apps[0]
        with (app / "Contents" / "Info.plist").open("rb") as source:
            info = plistlib.load(source)
        expected_version = release.apple_app_version(version)
        if (info.get("CFBundleIdentifier") != "org.megamek.launcher"
                or info.get("CFBundleShortVersionString") != expected_version):
            raise release.ReleaseError("Signed app identifier/version does not match the release")
        run(["/usr/bin/codesign", "--verify", "--deep", "--strict", "--verbose=2", str(app)])
        verify_code_details(run(["/usr/bin/codesign", "--display", "--verbose=4", str(app)]),
                            team, identity, "org.megamek.launcher")
        java = app / "Contents" / "runtime" / "Contents" / "Home" / "bin" / "java"
        launcher = app / "Contents" / "MacOS" / "MegaMek Launcher"
        architecture = "x86_64" if platform == "macos-intel" else "arm64"
        for executable in (launcher, java):
            if executable.is_symlink() or not executable.is_file():
                raise release.ReleaseError("Signed app is missing its regular native launcher or bundled Java")
            if run(["/usr/bin/lipo", "-archs", str(executable)]).strip() != architecture:
                raise release.ReleaseError("Signed native code architecture does not match its release platform")
            run(["/usr/bin/codesign", "--verify", "--strict", str(executable)])
            verify_code_details(run(["/usr/bin/codesign", "--display", "--verbose=4", str(executable)]),
                                team, identity)
    key = directory / f"AuthKey_{key_id}.p8"
    authentication = ["--key", str(key), "--key-id", key_id, "--issuer", issuer]
    submission = json.loads(run(["/usr/bin/xcrun", "notarytool", "submit", str(package),
                                 *authentication, "--wait", "--timeout", "30m", "--output-format", "json"],
                                timeout=2100))
    request_id = submission.get("id")
    if (submission.get("status") != "Accepted" or not isinstance(request_id, str)
            or not re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", request_id)):
        raise release.ReleaseError(f"Apple notarization was not accepted: {submission}")
    run(["/usr/bin/xcrun", "stapler", "staple", str(package)], timeout=300)
    run(["/usr/bin/xcrun", "stapler", "validate", str(package)])
    assessment = run(["/usr/sbin/spctl", "--assess", "--type", "install", "--verbose=2", str(package)])
    if not re.search(r"(?m)^.+: accepted$", assessment):
        raise release.ReleaseError("Gatekeeper did not accept the final installer")
    if "source=Notarized Developer ID" not in assessment:
        raise release.ReleaseError("Gatekeeper did not identify notarized Developer ID software")
    verify_package_signature(run(["/usr/sbin/pkgutil", "--check-signature", str(package)]), identity)
    if package.stat().st_size > release.INSTALLER_LIMIT:
        raise release.ReleaseError("Stapled installer exceeds the updater size limit")
    sha = release.digest(package)
    record = {"schema_version": 1, "name": name, "version": version, "commit": commit,
              "platform": platform, "size": package.stat().st_size, "sha256": sha,
              "team_id": team, "identity": identity, "notarization_id": request_id,
              "notarization_status": "Accepted", "timestamped": True, "hardened_runtime": True,
              "stapled": True, "gatekeeper_status": "accepted"}
    release.verify_apple_record(record, version, commit,
                                [release.Asset(package, package.stat().st_size, sha)], team, identity)
    verification.parent.mkdir(parents=True, exist_ok=True)
    with verification.open("x", encoding="utf-8") as output:
        json.dump(record, output, sort_keys=True)
    package.with_name(name + ".sha256").write_text(f"{sha}  {name}\n", encoding="ascii", newline="\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("plan", "setup", "complete", "cleanup"))
    parser.add_argument("--version")
    parser.add_argument("--commit")
    parser.add_argument("--platform", choices=release.APPLE_PLATFORMS)
    parser.add_argument("--package", type=Path)
    parser.add_argument("--verification", type=Path)
    args = parser.parse_args()
    if args.command == "cleanup":
        cleanup(os.environ)
        return
    require_release(os.environ)
    if args.command == "plan":
        config = configuration(os.environ)
        with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
            for key, value in vars(config).items():
                output.write(f"{key}={value}\n")
        print(f"::notice::Mac release signing mode: {config.mode}")
    elif args.command == "setup":
        if args.commit is None:
            raise release.ReleaseError("Apple setup requires the exact version-only release candidate")
        _, declaration = release.source_version(Path.cwd())
        release.validate_source(declaration.group(1), Path.cwd(), os.environ, args.commit)
        release.verify_checkout(Path.cwd(), args.commit)
        release.verify_candidate(release.GitHub(), declaration.group(1), args.commit,
                                 release.require_sha(os.environ.get("GITHUB_SHA")))
        setup(os.environ)
    else:
        if sys.platform != "darwin" or args.commit is None or args.platform is None:
            raise release.ReleaseError("Apple completion requires macOS, exact source and package/verification paths")
        _, declaration = release.source_version(Path.cwd())
        version = args.version or declaration.group(1)
        package = args.package or Path("build") / "distributions" / f"MegaMek-Launcher-{version}-{args.platform}.pkg"
        verification = args.verification or Path("build") / "apple-verification" / (args.platform + ".json")
        release.validate_source(version, Path.cwd(), os.environ, args.commit)
        release.verify_checkout(Path.cwd(), args.commit)
        config = configuration(os.environ, require_credentials=False)
        if config.mode != "apple":
            raise release.ReleaseError("Notarization requires enabled Apple signing")
        complete(package, version, args.commit, args.platform, config.team_id, config.identity,
                 config.key_id, config.issuer_id, Path(os.environ["APPLE_SIGNING_DIRECTORY"]),
                 verification)


if __name__ == "__main__":
    try:
        main()
    except (release.ReleaseError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"Apple release signing failed: {error}. Publication must not continue.", file=sys.stderr)
        sys.exit(1)
