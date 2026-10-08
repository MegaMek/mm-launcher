# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later
"""Stage same-run release assets and accept only a verified signed Windows MSI."""

import argparse
import os
from pathlib import Path
import shutil
import sys

import launcher_release as release


def prepare_assets(source, destination, signing_input, version, mode):
    if mode not in ("unsigned", "signpath"):
        raise release.ReleaseError("Windows signing mode must be unsigned or signpath")
    assets = release.validate_files(source, version)
    destination.mkdir()
    for asset in assets:
        shutil.copyfile(asset.path, destination / asset.path.name)
    release.validate_files(destination, version)
    if mode == "signpath":
        signing_input.mkdir()
        name = f"MegaMek-Launcher-{version}-windows-x64.msi"
        shutil.copyfile(destination / name, signing_input / name)


def accept_signed_msi(destination, signed_directory, verification, version, commit, certificate_sha256):
    assets = release.validate_files(destination, version)
    name = f"MegaMek-Launcher-{version}-windows-x64.msi"
    if signed_directory.is_symlink() or not signed_directory.is_dir():
        raise release.ReleaseError("Signed output must be a real directory")
    files = list(signed_directory.iterdir())
    if (len(files) != 1 or files[0].name != name or files[0].is_symlink()
            or not files[0].is_file() or not 0 < files[0].stat().st_size <= release.INSTALLER_LIMIT):
        raise release.ReleaseError("SignPath must return exactly the expected bounded Windows MSI")
    signed = release.Asset(files[0], files[0].stat().st_size, release.digest(files[0]))
    record = release.load_signing_record(verification)
    final_assets = [signed if asset.path.name == name else asset for asset in assets]
    release.verify_signing_record(record, version, commit, final_assets, certificate_sha256)
    original = next(asset for asset in assets if asset.path.name == name)
    if record["unsigned_sha256"] != original.sha256:
        raise release.ReleaseError("Signing verification does not match the original tested MSI")
    shutil.copyfile(signed.path, destination / name)
    (destination / (name + ".sha256")).write_text(f"{signed.sha256}  {name}\n",
                                               encoding="ascii", newline="\n")
    release.verify_signing_record(record, version, commit,
                                 release.validate_files(destination, version), certificate_sha256)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "complete", "verify-apple"))
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--signing-input", type=Path)
    parser.add_argument("--signed", type=Path)
    parser.add_argument("--verification", type=Path)
    parser.add_argument("--certificate-sha256")
    parser.add_argument("--windows-signing", choices=("unsigned", "signpath"), required=True)
    parser.add_argument("--apple-verification", type=Path)
    parser.add_argument("--apple-team-id")
    parser.add_argument("--apple-signing-identity")
    args = parser.parse_args()
    commit = release.validate_source(args.version, Path.cwd(), os.environ, args.commit)
    if os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch":
        raise release.ReleaseError("Release asset preparation requires official manual dispatch")
    release.verify_checkout(Path.cwd(), commit)
    if args.command == "prepare":
        if args.source is None or args.signing_input is None:
            raise release.ReleaseError("Asset preparation requires source and signing input directories")
        prepare_assets(args.source, args.assets, args.signing_input, args.version, args.windows_signing)
    elif args.command == "complete":
        if (args.windows_signing != "signpath" or args.signed is None or args.verification is None
                or args.certificate_sha256 is None):
            raise release.ReleaseError("Completing signing requires signed output, verification and certificate")
        accept_signed_msi(args.assets, args.signed, args.verification, args.version,
                          commit, args.certificate_sha256)
    else:
        if any(value is None for value in
               (args.apple_verification, args.apple_team_id, args.apple_signing_identity)):
            raise release.ReleaseError("Apple staging verification requires both records and approved team/identity")
        release.verify_apple_records(release.load_apple_records(args.apple_verification),
                                     args.version, commit, release.validate_files(args.assets, args.version),
                                     args.apple_team_id, args.apple_signing_identity)
    print(f"Validated final {args.windows_signing} release assets for {args.version} at {commit}")


if __name__ == "__main__":
    try:
        main()
    except (release.ReleaseError, OSError) as error:
        print(f"Launcher signing failed: {error}. Publication must not continue.", file=sys.stderr)
        sys.exit(1)
