# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later
"""Manual official launcher publication; no package installation or write retries."""

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote


REPOSITORY = "MegaMek/mm-launcher"
API = f"repos/{REPOSITORY}"
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
MSI_LIMIT = 300 * 1024 * 1024
PAGE_SIZE = 100
MAX_PAGES = 10
INSTALLERS = (
    "windows-x64.msi", "linux-x64.deb", "linux-x64.rpm",
    "macos-intel.pkg", "macos-apple-silicon.pkg",
)


class ReleaseError(Exception):
    pass


def numeric_version(value):
    if not isinstance(value, str) or len(value) > 32 or not VERSION.fullmatch(value):
        raise ReleaseError("Version must be canonical major.minor.patch without a v prefix")
    parts = tuple(int(part) for part in value.split("."))
    if any(part > limit for part, limit in zip(parts, (255, 255, 65535))):
        raise ReleaseError("Version exceeds Windows Installer's 255.255.65535 limits")
    return parts


def validate_source(version, root, environment):
    numeric_version(version)
    if environment.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise ReleaseError(f"Publication is restricted to {REPOSITORY}")
    if environment.get("GITHUB_REF") != "refs/heads/main":
        raise ReleaseError("Publication must be dispatched from main")
    commit = environment.get("GITHUB_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ReleaseError("Expected an exact source commit SHA")
    declarations = re.findall(r'^version\s*=\s*"([^"\r\n]+)"\s*$',
                              (root / "build.gradle.kts").read_text(encoding="utf-8"),
                              re.MULTILINE)
    if declarations != [version]:
        raise ReleaseError("Requested version must exactly match build.gradle.kts")
    return commit


class GitHub:
    def request(self, method, endpoint, data=None, file=None, missing_ok=False):
        command = ["gh", "api", "--include", "--method", method, endpoint]
        if data is not None:
            command += ["--input", "-"]
        if file is not None:
            command += ["--header", "Content-Type: application/octet-stream", "--input", str(file)]
        result = subprocess.run(command, input=json.dumps(data) if data is not None else None,
                                capture_output=True, text=True, check=False)
        headers, separator, body = result.stdout.partition("\n\n")
        status = re.match(r"HTTP/\S+\s+([0-9]{3})\b", headers)
        if not separator or status is None:
            raise ReleaseError(f"Invalid gh API response for {method} {endpoint}: {result.stderr.strip()}")
        code = int(status.group(1))
        if code == 404 and missing_ok:
            return None
        if result.returncode or not 200 <= code < 300:
            raise ReleaseError(f"GitHub {method} {endpoint} failed (HTTP {code}): {result.stderr.strip()}")
        try:
            return json.loads(body)
        except json.JSONDecodeError as error:
            raise ReleaseError(f"Invalid GitHub JSON for {method} {endpoint}") from error


def require_unused_version(github, version):
    candidate = numeric_version(version)
    for tag in (version, "v" + version):
        if github.request("GET", f"{API}/git/ref/tags/{tag}", missing_ok=True) is not None:
            raise ReleaseError(f"Tag {tag} already exists; never replace or reuse a release tag")
    for page in range(1, MAX_PAGES + 1):
        releases = github.request("GET", f"{API}/releases?per_page={PAGE_SIZE}&page={page}")
        if not isinstance(releases, list) or len(releases) > PAGE_SIZE:
            raise ReleaseError("Invalid GitHub release inventory")
        for release in releases:
            if not isinstance(release, dict):
                raise ReleaseError("Invalid GitHub release inventory entry")
            tag = release.get("tag_name")
            if tag in (version, "v" + version):
                raise ReleaseError(f"Release {tag} already exists, including drafts; inspect it manually")
            if release.get("draft") is False and release.get("prerelease") is False:
                if not isinstance(tag, str) or numeric_version(tag.removeprefix("v")) >= candidate:
                    raise ReleaseError("Candidate must be newer than every published stable launcher release")
            elif not isinstance(release.get("draft"), bool) or not isinstance(release.get("prerelease"), bool):
                raise ReleaseError("Release inventory is missing draft/prerelease state")
        if len(releases) < PAGE_SIZE:
            return
    raise ReleaseError("Release inventory pagination bound reached; refusing partial discovery")


@dataclass(frozen=True)
class Asset:
    path: Path
    size: int
    sha256: str


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def validate_files(directory, version):
    numeric_version(version)
    names = [f"MegaMek-Launcher-{version}-{suffix}" for suffix in INSTALLERS]
    expected = set(names + [name + ".sha256" for name in names])
    if directory.is_symlink() or not directory.is_dir():
        raise ReleaseError("Installer artifacts must be in a real directory")
    files = list(directory.iterdir())
    if {file.name for file in files} != expected or len(files) != len(expected):
        raise ReleaseError("Expected exactly five native installers and their five checksum files")
    assets = []
    for name in names:
        file = directory / name
        checksum = directory / (name + ".sha256")
        for path in (file, checksum):
            if path.is_symlink() or not path.is_file() or path.stat().st_size <= 0:
                raise ReleaseError(f"Expected a nonempty regular file: {path.name}")
        if name.endswith(".msi") and file.stat().st_size > MSI_LIMIT:
            raise ReleaseError("MSI exceeds the launcher's 300 MiB self-update limit")
        if checksum.stat().st_size > 512:
            raise ReleaseError(f"Checksum file exceeds limit: {checksum.name}")
        sha = digest(file)
        if checksum.read_text(encoding="ascii").strip() != f"{sha}  {name}":
            raise ReleaseError(f"Checksum mismatch: {name}")
        assets.append(Asset(file, file.stat().st_size, sha))
        assets.append(Asset(checksum, checksum.stat().st_size, digest(checksum)))
    return assets


def positive_id(value):
    return type(value) is int and value > 0


def verify_asset(metadata, asset, version):
    if not isinstance(metadata, dict) or not positive_id(metadata.get("id")):
        raise ReleaseError("Uploaded asset has no immutable positive ID")
    url = f"https://github.com/{REPOSITORY}/releases/download/v{version}/{asset.path.name}"
    if (metadata.get("name") != asset.path.name or type(metadata.get("size")) is not int
            or metadata["size"] != asset.size or metadata.get("state") != "uploaded"
            or metadata.get("digest") != "sha256:" + asset.sha256
            or metadata.get("browser_download_url") != url):
        raise ReleaseError(f"Uploaded asset identity/size/state/SHA-256 mismatch: {asset.path.name}")
    return metadata["id"]


def verify_release(release, assets, version, draft, identities=None):
    if (not isinstance(release, dict) or not positive_id(release.get("id"))
            or release.get("tag_name") != "v" + version
            or release.get("draft") is not draft or release.get("prerelease") is not False):
        raise ReleaseError("Release identity or publication state does not match the request")
    metadata = release.get("assets")
    if not isinstance(metadata, list) or len(metadata) != len(assets):
        raise ReleaseError("Release does not contain the complete ten-file asset set")
    by_name = {}
    ids = set()
    for item in metadata:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str) or item["name"] in by_name:
            raise ReleaseError("Duplicate or malformed release assets")
        by_name[item["name"]] = item
    for asset in assets:
        identifier = verify_asset(by_name.get(asset.path.name), asset, version)
        if identifier in ids or identities is not None and identities.get(asset.path.name) != identifier:
            raise ReleaseError("Release asset IDs were duplicated or changed")
        ids.add(identifier)
    return release["id"]


def verify_tag(reference, version, commit):
    target = reference.get("object") if isinstance(reference, dict) else None
    if (not isinstance(reference, dict) or reference.get("ref") != "refs/tags/v" + version
            or not isinstance(target, dict) or target.get("type") != "commit"
            or target.get("sha") != commit):
        raise ReleaseError("Release tag does not bind the exact tested source commit")


def publish(github, version, commit, assets):
    require_unused_version(github, version)
    tag = "v" + version
    reference = github.request("POST", f"{API}/git/refs",
                               {"ref": "refs/tags/" + tag, "sha": commit})
    verify_tag(reference, version, commit)
    body = (f"Native installers built and tested from [{commit}](https://github.com/{REPOSITORY}/commit/{commit}).\n\n"
            "Windows x64 MSI supports user-authorized launcher self-update. Linux and macOS installers "
            "must be updated manually. Installers are unsigned; macOS packages are not notarized. "
            "SHA-256 files provide integrity checks, not independent publisher signing.\n")
    release = github.request("POST", f"{API}/releases", {
        "tag_name": tag, "target_commitish": commit, "name": f"MegaMek Launcher {version}",
        "body": body, "generate_release_notes": True, "draft": True, "prerelease": False,
    })
    identifier = verify_release(release, [], version, True)
    identities = {}
    for asset in assets:
        metadata = github.request("POST", f"https://uploads.github.com/{API}/releases/{identifier}"
                                  f"/assets?name={quote(asset.path.name, safe='')}", file=asset.path)
        identities[asset.path.name] = verify_asset(metadata, asset, version)
    staged = github.request("GET", f"{API}/releases/{identifier}")
    if verify_release(staged, assets, version, True, identities) != identifier:
        raise ReleaseError("Staged release ID changed")
    verify_tag(github.request("GET", f"{API}/git/ref/tags/{tag}"), version, commit)
    published = github.request("PATCH", f"{API}/releases/{identifier}",
                               {"draft": False, "prerelease": False, "make_latest": "true"})
    if verify_release(published, assets, version, False, identities) != identifier:
        raise ReleaseError("Published release ID changed; inspect GitHub before further action")
    latest = github.request("GET", f"{API}/releases/latest")
    if verify_release(latest, assets, version, False, identities) != identifier:
        raise ReleaseError("Published release is not the latest stable release; inspect GitHub")
    print(f"Published https://github.com/{REPOSITORY}/releases/tag/{tag}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "publish"))
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit")
    parser.add_argument("--assets", type=Path)
    args = parser.parse_args()
    commit = validate_source(args.version, Path.cwd(), os.environ)
    github = GitHub()
    if args.command == "prepare":
        require_unused_version(github, args.version)
        output = os.environ.get("GITHUB_OUTPUT")
        if not output:
            raise ReleaseError("GITHUB_OUTPUT is required for preparation")
        with Path(output).open("a", encoding="utf-8") as file:
            file.write(f"version={args.version}\ncommit={commit}\n")
        print(f"Validated launcher {args.version} at {commit}; no remote writes performed")
    else:
        if args.commit != commit or args.assets is None:
            raise ReleaseError("Publication requires the exact prepared commit and artifact directory")
        assets = validate_files(args.assets, args.version)
        publish(github, args.version, commit, assets)


if __name__ == "__main__":
    try:
        main()
    except ReleaseError as error:
        print(f"Launcher release failed: {error}. No automatic write retry or rollback.", file=sys.stderr)
        sys.exit(1)
