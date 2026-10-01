# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later
"""Official launcher releases and read-only CI reuse checks; no write retries."""

import argparse
import base64
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
VERSION_DECLARATION = re.compile(r'^version[ \t]*=[ \t]*"([^"\r\n]+)"[ \t]*\r?$', re.MULTILINE)
MSI_LIMIT = 300 * 1024 * 1024
PAGE_SIZE = 100
MAX_PAGES = 10
RELEASE_RUN = re.compile(r"^Launcher-Release-Run: ([1-9][0-9]{0,19})$", re.MULTILINE)
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


def source_version(root):
    content = (root / "build.gradle.kts").read_bytes().decode("utf-8")
    declarations = list(VERSION_DECLARATION.finditer(content))
    if len(declarations) != 1:
        raise ReleaseError("Expected exactly one version declaration in build.gradle.kts")
    return content, declarations[0]


def require_sha(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ReleaseError("Expected an exact source commit or tree SHA")
    return value


def validate_source(version, root, environment, expected_commit=None):
    numeric_version(version)
    if environment.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise ReleaseError(f"Publication is restricted to {REPOSITORY}")
    if environment.get("GITHUB_REF") != "refs/heads/main":
        raise ReleaseError("Publication must be dispatched from main")
    dispatched = require_sha(environment.get("GITHUB_SHA"))
    commit = dispatched if expected_commit is None else require_sha(expected_commit)
    _, declaration = source_version(root)
    if declaration.group(1) != version:
        raise ReleaseError("Requested version must exactly match build.gradle.kts")
    return commit


def verify_checkout(root, commit):
    require_sha(commit)
    result = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"],
                            capture_output=True, text=True, check=False)
    if result.returncode or result.stdout.strip() != commit:
        raise ReleaseError(f"Checkout must match the exact prepared commit: {result.stderr.strip()}")


def next_patch_version(value):
    major, minor, patch = numeric_version(value)
    candidate = f"{major}.{minor}.{patch + 1}"
    numeric_version(candidate)
    return candidate


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


def verify_main(reference, commit):
    target = reference.get("object") if isinstance(reference, dict) else None
    if (not isinstance(reference, dict) or reference.get("ref") != "refs/heads/main"
            or not isinstance(target, dict) or target.get("type") != "commit"
            or target.get("sha") != commit):
        raise ReleaseError("main changed since dispatch; inspect it and start a new release run")


def prepare_patch(github, root, environment):
    content, declaration = source_version(root)
    current = declaration.group(1)
    commit = validate_source(current, root, environment)
    candidate = next_patch_version(current)
    require_unused_version(github, candidate)
    verify_main(github.request("GET", f"{API}/git/ref/heads/main"), commit)
    updated = content[:declaration.start(1)] + candidate + content[declaration.end(1):]
    return candidate, commit, content, updated


def tree_entries(tree):
    if (not isinstance(tree, dict) or tree.get("truncated") is not False
            or not isinstance(tree.get("tree"), list)):
        raise ReleaseError("Expected a complete Git tree")
    require_sha(tree.get("sha"))
    entries = {}
    for entry in tree["tree"]:
        if (not isinstance(entry, dict) or not isinstance(entry.get("path"), str)
                or not entry["path"] or entry["path"] in entries
                or not isinstance(entry.get("mode"), str)
                or entry.get("type") not in ("blob", "tree", "commit")):
            raise ReleaseError("Malformed or duplicated Git tree entry")
        entries[entry["path"]] = (entry["mode"], entry["type"], require_sha(entry.get("sha")))
    return entries


def blob_sha(content):
    data = content.encode("utf-8")
    return hashlib.sha1(f"blob {len(data)}\0".encode("ascii") + data).hexdigest()


def prepare_candidate(github, root, environment):
    run = environment.get("GITHUB_RUN_ID")
    if not isinstance(run, str) or not re.fullmatch("[1-9][0-9]{0,19}", run):
        raise ReleaseError("Candidate preparation requires the exact positive workflow run ID")
    version, base, original, updated = prepare_patch(github, root, environment)
    source = github.request("GET", f"{API}/git/commits/{base}")
    if not isinstance(source, dict) or source.get("sha") != base or not isinstance(source.get("tree"), dict):
        raise ReleaseError("Dispatched source commit identity changed")
    base_tree = require_sha(source["tree"].get("sha"))
    tree = github.request("GET", f"{API}/git/trees/{base_tree}")
    entries = tree_entries(tree)
    if tree["sha"] != base_tree or entries.get("build.gradle.kts") != ("100644", "blob", blob_sha(original)):
        raise ReleaseError("Version source does not match the dispatched Git tree")

    changed = github.request("POST", f"{API}/git/trees", {
        "base_tree": base_tree,
        "tree": [{"path": "build.gradle.kts", "mode": "100644", "type": "blob", "content": updated}],
    })
    if not isinstance(changed, dict):
        raise ReleaseError("GitHub did not return the version-bump tree")
    changed_sha = require_sha(changed.get("sha"))
    verified = github.request("GET", f"{API}/git/trees/{changed_sha}")
    expected = {**entries, "build.gradle.kts": ("100644", "blob", blob_sha(updated))}
    if tree_entries(verified) != expected or verified["sha"] != changed_sha or changed_sha == base_tree:
        raise ReleaseError("Version bump must change only the version file in the source tree")
    commit = github.request("POST", f"{API}/git/commits", {
        "message": (f"Prepare launcher release candidate {version}\n\n"
                    f"Launcher-Release-Run: {run}\n"
                    "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"),
        "tree": changed_sha, "parents": [base],
    })
    if (not isinstance(commit, dict) or not isinstance(commit.get("tree"), dict)
            or commit["tree"].get("sha") != changed_sha
            or not isinstance(commit.get("parents"), list)
            or len(commit["parents"]) != 1 or not isinstance(commit["parents"][0], dict)
            or commit["parents"][0].get("sha") != base):
        raise ReleaseError("Version bump commit must have the exact source tree and dispatched parent")
    bumped = require_sha(commit.get("sha"))
    if bumped == base:
        raise ReleaseError("Version bump did not create a new commit")
    candidate_ref = f"refs/heads/release-candidates/{version}/{run}"
    reference = github.request("POST", f"{API}/git/refs", {"ref": candidate_ref, "sha": bumped})
    if (not isinstance(reference, dict) or reference.get("ref") != candidate_ref
            or not isinstance(reference.get("object"), dict)
            or reference["object"].get("type") != "commit" or reference["object"].get("sha") != bumped):
        raise ReleaseError("Candidate branch does not bind the exact version-only commit")
    return version, bumped, base


def verify_candidate(github, version, commit, base):
    numeric_version(version)
    require_sha(commit)
    require_sha(base)
    candidate = github.request("GET", f"{API}/git/commits/{commit}")
    original = github.request("GET", f"{API}/git/commits/{base}")
    if (not isinstance(candidate, dict) or candidate.get("sha") != commit
            or not isinstance(candidate.get("parents"), list) or len(candidate["parents"]) != 1
            or not isinstance(candidate["parents"][0], dict) or candidate["parents"][0].get("sha") != base
            or not isinstance(original, dict) or original.get("sha") != base):
        raise ReleaseError("Candidate must have exactly the captured main commit as its parent")
    trees = []
    for item in (original, candidate):
        if not isinstance(item.get("tree"), dict):
            raise ReleaseError("Candidate commit has no Git tree")
        tree_sha = require_sha(item["tree"].get("sha"))
        tree = github.request("GET", f"{API}/git/trees/{tree_sha}")
        if not isinstance(tree, dict) or tree.get("sha") != tree_sha:
            raise ReleaseError("Candidate Git tree identity changed")
        trees.append(tree_entries(tree))
    entry = trees[0].get("build.gradle.kts")
    if entry is None or entry[:2] != ("100644", "blob"):
        raise ReleaseError("Candidate base has no regular version source")
    blob = github.request("GET", f"{API}/git/blobs/{entry[2]}")
    if (not isinstance(blob, dict) or blob.get("sha") != entry[2]
            or blob.get("encoding") != "base64" or not isinstance(blob.get("content"), str)):
        raise ReleaseError("Cannot verify candidate base version bytes")
    try:
        content = base64.b64decode(blob["content"].replace("\n", ""), validate=True).decode("utf-8")
    except (ValueError, UnicodeError) as error:
        raise ReleaseError("Malformed candidate base version blob") from error
    declarations = list(VERSION_DECLARATION.finditer(content))
    if blob_sha(content) != entry[2] or len(declarations) != 1:
        raise ReleaseError("Candidate base version blob does not match its Git tree")
    declaration = declarations[0]
    if next_patch_version(declaration.group(1)) != version:
        raise ReleaseError("Candidate must increment exactly one patch from its base")
    updated = content[:declaration.start(1)] + version + content[declaration.end(1):]
    expected = {**trees[0], "build.gradle.kts": ("100644", "blob", blob_sha(updated))}
    if trees[1] != expected:
        raise ReleaseError("Candidate must change only the exact version literal")


def finalize_main(github, version, commit, base, assets):
    try:
        verify_candidate(github, version, commit, base)
        published = github.request("GET", f"{API}/releases/tags/v{version}")
        identifier = verify_release(published, assets, version, False)
        identities = {item["name"]: item["id"] for item in published["assets"]}
        verify_tag(github.request("GET", f"{API}/git/ref/tags/v{version}"), version, commit)
        if verify_release(github.request("GET", f"{API}/releases/latest"),
                          assets, version, False, identities) != identifier:
            raise ReleaseError("Candidate is not the confirmed latest stable release")
        reference = github.request("GET", f"{API}/git/ref/heads/main")
        if (isinstance(reference, dict) and isinstance(reference.get("object"), dict)
                and reference["object"].get("sha") == commit):
            verify_main(reference, commit)
            print(f"Published launcher {version}; main is already synchronized at {commit}")
            return
        verify_main(reference, base)
        reference = github.request("PATCH", f"{API}/git/refs/heads/main",
                                   {"sha": commit, "force": False})
        verify_main(reference, commit)
    except ReleaseError as error:
        raise ReleaseError(f"Version synchronization pending for {version}: {error}. "
                           "Inspect the public release and main; resume finalization only, "
                           "never republish or force-update main") from error
    print(f"Published launcher {version}; synchronized main to {commit}")


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


def verify_asset(metadata, asset, version, draft_release_url=None):
    if not isinstance(metadata, dict) or not positive_id(metadata.get("id")):
        raise ReleaseError("Uploaded asset has no immutable positive ID")
    url = f"https://github.com/{REPOSITORY}/releases/download/v{version}/{asset.path.name}"
    urls = [url]
    if isinstance(draft_release_url, str):
        match = re.fullmatch(
            re.escape(f"https://github.com/{REPOSITORY}/releases/tag/") +
            r"(untagged-[0-9a-f]+)", draft_release_url)
        if match:
            urls.append(f"https://github.com/{REPOSITORY}/releases/download/"
                        f"{match.group(1)}/{asset.path.name}")
    if (metadata.get("name") != asset.path.name or type(metadata.get("size")) is not int
            or metadata["size"] != asset.size or metadata.get("state") != "uploaded"
            or metadata.get("digest") != "sha256:" + asset.sha256
            or metadata.get("browser_download_url") not in urls):
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
        identifier = verify_asset(by_name.get(asset.path.name), asset, version,
                                  release.get("html_url") if draft else None)
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


def verified_release_push(github, root, environment):
    if (environment.get("GITHUB_EVENT_NAME") != "push"
            or environment.get("GITHUB_REPOSITORY") != REPOSITORY
            or environment.get("GITHUB_REF") != "refs/heads/main"
            or environment.get("CI_SOURCE_COMMIT")):
        return False, "PRs, manual runs, forks and reusable release builds always build installers."
    commit = require_sha(environment.get("GITHUB_SHA"))
    verify_checkout(root, commit)
    candidate = github.request("GET", f"{API}/git/commits/{commit}")
    if (not isinstance(candidate, dict) or candidate.get("sha") != commit
            or not isinstance(candidate.get("message"), str)):
        raise ReleaseError("Cannot inspect the exact pushed commit")
    runs = RELEASE_RUN.findall(candidate["message"])
    if len(runs) != 1:
        return False, "Ordinary main change; no unique release-run provenance."
    _, declaration = source_version(root)
    version = declaration.group(1)
    numeric_version(version)
    published = github.request("GET", f"{API}/releases/tags/v{version}", missing_ok=True)
    if published is None:
        return False, "Candidate has not been published; installers must be built."
    if (not isinstance(published, dict) or not isinstance(published.get("draft"), bool)
            or not isinstance(published.get("prerelease"), bool)):
        raise ReleaseError("Cannot determine the candidate release's publication state")
    if published["draft"] or published["prerelease"]:
        return False, "A draft or prerelease is not a verified stable release."
    reference = github.request("GET", f"{API}/git/ref/tags/v{version}")
    target = reference.get("object") if isinstance(reference, dict) else None
    if not isinstance(target, dict):
        raise ReleaseError("Cannot inspect the published release tag")
    if target.get("sha") != commit or target.get("type") != "commit":
        return False, "This push is not the exact published candidate."
    verify_tag(reference, version, commit)
    parents = candidate.get("parents")
    if not isinstance(parents, list) or len(parents) != 1 or not isinstance(parents[0], dict):
        raise ReleaseError("Release candidate must have exactly one captured base")
    base = require_sha(parents[0].get("sha"))
    verify_candidate(github, version, commit, base)
    run_id = int(runs[0])
    branch = f"refs/heads/release-candidates/{version}/{run_id}"
    reference = github.request("GET", f"{API}/git/ref/heads/release-candidates/{version}/{run_id}")
    if (not isinstance(reference, dict) or reference.get("ref") != branch
            or not isinstance(reference.get("object"), dict)
            or reference["object"].get("type") != "commit" or reference["object"].get("sha") != commit):
        raise ReleaseError("Release-run branch does not bind this exact candidate")
    run = github.request("GET", f"{API}/actions/runs/{run_id}")
    if (not isinstance(run, dict) or not positive_id(run.get("id")) or run.get("id") != run_id
            or run.get("event") != "workflow_dispatch" or run.get("head_branch") != "main"
            or run.get("head_sha") != base or run.get("path") != ".github/workflows/launcher-release.yml"
            or not isinstance(run.get("repository"), dict)
            or run["repository"].get("full_name") != REPOSITORY):
        raise ReleaseError("Candidate provenance is not the official release workflow at its captured base")
    names = {"prepare", "publish", "installers / Release tooling contracts"}
    names.update(f"installers / Native installers - {platform}"
                 for platform in ("windows-x64", "linux-x64", "macos-intel", "macos-apple-silicon"))
    jobs = {}
    for page in range(1, MAX_PAGES + 1):
        result = github.request("GET", f"{API}/actions/runs/{run_id}/jobs"
                                f"?filter=latest&per_page={PAGE_SIZE}&page={page}")
        if (not isinstance(result, dict) or not isinstance(result.get("jobs"), list)
                or len(result["jobs"]) > PAGE_SIZE or type(result.get("total_count")) is not int):
            raise ReleaseError("Invalid release-job inventory")
        for job in result["jobs"]:
            if (not isinstance(job, dict) or not isinstance(job.get("name"), str)
                    or job["name"] in jobs or not positive_id(job.get("run_id"))
                    or job.get("run_id") != run_id or job.get("head_sha") != base):
                raise ReleaseError("Release jobs have duplicated or mismatched provenance")
            jobs[job["name"]] = job
        if len(jobs) == result["total_count"]:
            break
        if len(result["jobs"]) < PAGE_SIZE:
            raise ReleaseError("Incomplete release-job inventory")
    else:
        raise ReleaseError("Release-job pagination bound reached")
    if not names.issubset(jobs) or any(jobs[name].get("status") != "completed"
                                     or jobs[name].get("conclusion") != "success" for name in names):
        return False, "Release build/test/publication success is not confirmed; running installers."
    metadata = published.get("assets")
    if not isinstance(metadata, list) or len(metadata) != 10:
        raise ReleaseError("Published release is missing the complete installer/checksum set")
    expected_names = {f"MegaMek-Launcher-{version}-{suffix}{checksum}"
                      for suffix in INSTALLERS for checksum in ("", ".sha256")}
    assets = []
    for item in metadata:
        if (not isinstance(item, dict) or not isinstance(item.get("name"), str)
                or item["name"] not in expected_names
                or type(item.get("size")) is not int or item["size"] <= 0
                or not isinstance(item.get("digest"), str)
                or not re.fullmatch(r"sha256:[0-9a-f]{64}", item["digest"])):
            raise ReleaseError("Published installer metadata is incomplete or malformed")
        if item["name"].endswith(".msi") and item["size"] > MSI_LIMIT:
            raise ReleaseError("Published MSI exceeds the updater size limit")
        assets.append(Asset(Path(item["name"]), item["size"], item["digest"][7:]))
    # Publication already verified the bytes; this gate checks their retained API identities.
    verify_release(published, assets, version, False)
    if {asset.path.name for asset in assets} != expected_names:
        raise ReleaseError("Published release assets were duplicated or substituted")
    return True, (f"Exact version-only candidate {commit} was built, tested and published by "
                  f"https://github.com/{REPOSITORY}/actions/runs/{run_id}; no duplicate installer build.")


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
        identities[asset.path.name] = verify_asset(metadata, asset, version, release.get("html_url"))
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
    parser.add_argument("command", choices=("prepare", "candidate", "publish", "finalize", "ci-plan"))
    parser.add_argument("--version")
    parser.add_argument("--commit")
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--base")
    args = parser.parse_args()
    root = Path.cwd()
    github = GitHub()
    if args.command == "ci-plan":
        if any(value is not None for value in (args.version, args.commit, args.assets, args.base)):
            raise ReleaseError("CI planning derives its identity from the triggering checkout")
        output = os.environ.get("GITHUB_OUTPUT")
        if not output:
            raise ReleaseError("GITHUB_OUTPUT is required for CI planning")
        reused, reason = verified_release_push(github, root, os.environ)
        with Path(output).open("a", encoding="utf-8") as file:
            file.write(f"build_installers={'false' if reused else 'true'}\n")
        print(f"::notice::{reason}")
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with Path(summary).open("a", encoding="utf-8") as file:
                file.write(f"### Installer CI decision\n\n{reason}\n")
        return
    if args.command in ("prepare", "candidate"):
        if args.version is not None or args.commit is not None or args.assets is not None or args.base is not None:
            raise ReleaseError("Candidate preparation derives its version from committed source")
        output = os.environ.get("GITHUB_OUTPUT")
        if not output:
            raise ReleaseError("GITHUB_OUTPUT is required for preparation")
        dispatched = require_sha(os.environ.get("GITHUB_SHA"))
        verify_checkout(root, dispatched)
        if args.command == "prepare":
            version, commit, _, _ = prepare_patch(github, root, os.environ)
            print(f"Validated next patch {version} from {commit}; no remote writes performed")
        else:
            version, commit, base = prepare_candidate(github, root, os.environ)
            print(f"Prepared launcher {version} at {commit}; main remains at {base}")
        with Path(output).open("a", encoding="utf-8") as file:
            file.write(f"version={version}\ncommit={commit}\n")
            if args.command == "candidate":
                file.write(f"base={base}\n")
    else:
        if args.version is None or args.commit is None or args.assets is None:
            raise ReleaseError("Publication requires the exact prepared commit and artifact directory")
        commit = validate_source(args.version, root, os.environ, args.commit)
        verify_checkout(root, commit)
        assets = validate_files(args.assets, args.version)
        if args.command == "publish":
            if args.base is not None:
                raise ReleaseError("Publication does not update main; use finalize afterward")
            publish(github, args.version, commit, assets)
        else:
            finalize_main(github, args.version, commit, require_sha(args.base), assets)


if __name__ == "__main__":
    try:
        main()
    except ReleaseError as error:
        print(f"Launcher release/CI check failed: {error}. No automatic write retry or rollback.", file=sys.stderr)
        sys.exit(1)
