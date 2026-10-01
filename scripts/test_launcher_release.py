# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later

import copy
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import launcher_release as release


VERSION = "0.14.5"
COMMIT = "a" * 40
ENVIRONMENT = {
    "GITHUB_REPOSITORY": release.REPOSITORY,
    "GITHUB_REF": "refs/heads/main",
    "GITHUB_SHA": COMMIT,
}


class FakeGitHub:
    def __init__(self):
        self.calls = []
        self.reference = None
        self.release = None
        self.inventory = []
        self.existing_tag = None
        self.fail_upload = False
        self.mutate_staged = None
        self.mutate_latest = None

    def request(self, method, endpoint, data=None, file=None, missing_ok=False):
        self.calls.append((method, endpoint, copy.deepcopy(data)))
        if method == "GET" and "/git/ref/tags/" in endpoint:
            return copy.deepcopy(self.reference or self.existing_tag)
        if method == "GET" and "/releases?" in endpoint:
            return copy.deepcopy(self.inventory)
        if method == "POST" and endpoint.endswith("/git/refs"):
            self.reference = {"ref": data["ref"], "object": {"type": "commit", "sha": data["sha"]}}
            return copy.deepcopy(self.reference)
        if method == "POST" and endpoint.endswith("/releases"):
            self.release = {"id": 37, **data, "assets": []}
            return copy.deepcopy(self.release)
        if method == "POST" and "/assets?name=" in endpoint:
            if self.fail_upload:
                raise release.ReleaseError("Simulated failed upload")
            metadata = {
                "id": 100 + len(self.release["assets"]), "name": file.name,
                "size": file.stat().st_size, "state": "uploaded",
                "digest": "sha256:" + hashlib.sha256(file.read_bytes()).hexdigest(),
                "browser_download_url": f"https://github.com/{release.REPOSITORY}"
                                        f"/releases/download/v{VERSION}/{file.name}",
            }
            self.release["assets"].append(metadata)
            return copy.deepcopy(metadata)
        if method == "GET" and endpoint.endswith("/releases/37"):
            result = copy.deepcopy(self.release)
            if self.mutate_staged:
                self.mutate_staged(result)
            return result
        if method == "PATCH" and endpoint.endswith("/releases/37"):
            self.release.update(data)
            return copy.deepcopy(self.release)
        if method == "GET" and endpoint.endswith("/releases/latest"):
            result = copy.deepcopy(self.release)
            if self.mutate_latest:
                self.mutate_latest(result)
            return result
        raise AssertionError(f"Unexpected request: {method} {endpoint}")


class LauncherReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "build.gradle.kts").write_text(f'version = "{VERSION}"\n', encoding="utf-8")
        self.directory = self.root / "assets"
        self.directory.mkdir()
        for suffix in release.INSTALLERS:
            name = f"MegaMek-Launcher-{VERSION}-{suffix}"
            content = name.encode("ascii")
            (self.directory / name).write_bytes(content)
            sha = hashlib.sha256(content).hexdigest()
            (self.directory / (name + ".sha256")).write_text(f"{sha}  {name}\n", encoding="ascii")

    def assets(self):
        return release.validate_files(self.directory, VERSION)

    def assert_no_publication(self, github):
        self.assertFalse(any(method == "PATCH" for method, _, _ in github.calls))

    def test_source_is_exact_official_main_and_committed_version(self):
        self.assertEqual(COMMIT, release.validate_source(VERSION, self.root, ENVIRONMENT))
        for field, value in (("GITHUB_REPOSITORY", "Someone/mm-launcher"),
                             ("GITHUB_REF", "refs/heads/feature"),
                             ("GITHUB_SHA", "main")):
            with self.subTest(field=field):
                with self.assertRaises(release.ReleaseError):
                    release.validate_source(VERSION, self.root, {**ENVIRONMENT, field: value})
        with self.assertRaisesRegex(release.ReleaseError, "exactly match"):
            release.validate_source("0.14.6", self.root, ENVIRONMENT)
        (self.root / "build.gradle.kts").write_text('version = "0.14.5"\nversion = "0.14.5"\n')
        with self.assertRaises(release.ReleaseError):
            release.validate_source(VERSION, self.root, ENVIRONMENT)

    def test_numeric_msi_version_contract_and_exact_limits(self):
        self.assertEqual((255, 255, 65535), release.numeric_version("255.255.65535"))
        for value in ("v0.14.5", "0.14.5-SNAPSHOT", "0.14.5;echo injected", "00.14.5",
                      "0.14.5\n", "256.0.0", "0.256.0", "0.0.65536"):
            with self.subTest(value=value), self.assertRaises(release.ReleaseError):
                release.numeric_version(value)

    def test_existing_tags_and_draft_releases_are_never_reused(self):
        github = FakeGitHub()
        github.existing_tag = {"ref": "refs/tags/" + VERSION}
        with self.assertRaisesRegex(release.ReleaseError, "already exists"):
            release.require_unused_version(github, VERSION)
        github.existing_tag = None
        github.inventory = [{"tag_name": "v" + VERSION, "draft": True, "prerelease": False}]
        with self.assertRaisesRegex(release.ReleaseError, "including drafts"):
            release.require_unused_version(github, VERSION)
        self.assertTrue(all(method == "GET" for method, _, _ in github.calls))

    def test_candidate_must_be_newer_than_every_stable_release(self):
        github = FakeGitHub()
        github.inventory = [{"tag_name": "v0.14.4", "draft": False, "prerelease": False}]
        release.require_unused_version(github, VERSION)
        for tag in ("v0.14.6", "v0.15.0", "unknown"):
            with self.subTest(tag=tag):
                github.inventory[0]["tag_name"] = tag
                with self.assertRaises(release.ReleaseError):
                    release.require_unused_version(github, VERSION)

    def test_inventory_pagination_is_bounded_and_incomplete_discovery_fails(self):
        github = FakeGitHub()
        github.inventory = [{"tag_name": "v0.14.4", "draft": False,
                             "prerelease": False}] * release.PAGE_SIZE
        with self.assertRaisesRegex(release.ReleaseError, "pagination bound"):
            release.require_unused_version(github, VERSION)
        pages = [endpoint for _, endpoint, _ in github.calls if "/releases?" in endpoint]
        self.assertEqual(release.MAX_PAGES, len(pages))
        self.assertTrue(pages[-1].endswith(f"page={release.MAX_PAGES}"))

    def test_exact_five_installer_and_checksum_pairs_are_verified(self):
        assets = self.assets()
        self.assertEqual(10, len(assets))
        for asset in assets:
            self.assertEqual(asset.path.stat().st_size, asset.size)
            self.assertEqual(hashlib.sha256(asset.path.read_bytes()).hexdigest(), asset.sha256)
        extra = self.directory / "unexpected.zip"
        extra.write_bytes(b"wrong artifact")
        with self.assertRaisesRegex(release.ReleaseError, "exactly five"):
            self.assets()
        extra.unlink()
        assets[0].path.unlink()
        with self.assertRaisesRegex(release.ReleaseError, "exactly five"):
            self.assets()

    def test_wrong_checksum_and_empty_installer_fail_before_publication(self):
        file = self.directory / f"MegaMek-Launcher-{VERSION}-windows-x64.msi"
        file.write_bytes(b"changed")
        with self.assertRaisesRegex(release.ReleaseError, "Checksum mismatch"):
            self.assets()
        file.write_bytes(b"")
        with self.assertRaisesRegex(release.ReleaseError, "nonempty regular"):
            self.assets()

    def test_msi_limit_matches_the_updater_and_rejects_one_extra_byte(self):
        self.assertEqual(300 * 1024 * 1024, release.MSI_LIMIT)
        file = self.directory / f"MegaMek-Launcher-{VERSION}-windows-x64.msi"
        with file.open("wb") as output:
            output.truncate(release.MSI_LIMIT + 1)
        with self.assertRaisesRegex(release.ReleaseError, "300 MiB"):
            self.assets()
        with file.open("wb") as output:
            output.truncate(release.MSI_LIMIT)
        checksum = file.with_name(file.name + ".sha256")
        checksum.write_text(f"{release.digest(file)}  {file.name}\n", encoding="ascii")
        self.assertEqual(release.MSI_LIMIT, self.assets()[0].size)

    def test_links_are_not_installer_inputs(self):
        file = self.directory / f"MegaMek-Launcher-{VERSION}-windows-x64.msi"
        target = self.root / "target"
        target.write_bytes(file.read_bytes())
        file.unlink()
        try:
            file.symlink_to(target)
        except (OSError, NotImplementedError) as error:
            self.skipTest(f"Host cannot create symlinks: {error}")
        with self.assertRaisesRegex(release.ReleaseError, "regular file"):
            self.assets()

    @patch("builtins.print")
    def test_publication_binds_tested_commit_and_becomes_stable_only_after_all_uploads(self, output):
        github = FakeGitHub()
        assets = self.assets()
        release.publish(github, VERSION, COMMIT, assets)
        writes = [(method, endpoint, data) for method, endpoint, data in github.calls if method != "GET"]
        self.assertEqual("refs/tags/v" + VERSION, writes[0][2]["ref"])
        self.assertEqual(COMMIT, writes[0][2]["sha"])
        self.assertTrue(writes[1][2]["draft"], "upload staging is not a draft-review step")
        self.assertEqual(COMMIT, writes[1][2]["target_commitish"])
        self.assertEqual(10, sum("/assets?name=" in endpoint for _, endpoint, _ in writes))
        self.assertEqual(("PATCH", f"{release.API}/releases/37",
                          {"draft": False, "prerelease": False, "make_latest": "true"}), writes[-1])
        self.assertFalse(github.release["draft"])
        self.assertFalse(github.release["prerelease"])
        self.assertEqual(f"{release.API}/releases/latest", github.calls[-1][1])
        self.assertEqual(10, len(github.release["assets"]))
        output.assert_called_once_with(
            f"Published https://github.com/{release.REPOSITORY}/releases/tag/v{VERSION}")

    def test_failed_upload_is_not_retried_or_published(self):
        github = FakeGitHub()
        github.fail_upload = True
        with self.assertRaisesRegex(release.ReleaseError, "failed upload"):
            release.publish(github, VERSION, COMMIT, self.assets())
        self.assert_no_publication(github)
        self.assertTrue(github.release["draft"])
        self.assertEqual(1, sum("/assets?name=" in endpoint for _, endpoint, _ in github.calls))
        self.assertFalse(any(method == "DELETE" for method, _, _ in github.calls))

    def test_staged_metadata_drift_and_missing_digest_prevent_publication(self):
        for field, value in (("digest", None), ("digest", "sha256:" + "b" * 64),
                             ("state", "new"), ("size", 1), ("id", 999),
                             ("browser_download_url", "https://example.org/installer.msi")):
            with self.subTest(field=field):
                github = FakeGitHub()
                github.mutate_staged = lambda item: item["assets"][0].update({field: value})
                with self.assertRaises(release.ReleaseError):
                    release.publish(github, VERSION, COMMIT, self.assets())
                self.assert_no_publication(github)

    def test_latest_release_mismatch_fails_without_republication(self):
        github = FakeGitHub()
        github.mutate_latest = lambda item: item.update(id=38)
        with self.assertRaisesRegex(release.ReleaseError, "not the latest"):
            release.publish(github, VERSION, COMMIT, self.assets())
        self.assertEqual(1, sum(method == "PATCH" for method, _, _ in github.calls))

    @patch("builtins.print")
    def test_tag_and_asset_identity_guards_reject_malformed_and_duplicate_ids(self, output):
        with self.assertRaises(release.ReleaseError):
            release.verify_tag({"ref": "refs/tags/v" + VERSION, "object": None}, VERSION, COMMIT)
        github = FakeGitHub()
        release.publish(github, VERSION, COMMIT, self.assets())
        github.release["assets"][1]["id"] = github.release["assets"][0]["id"]
        with self.assertRaisesRegex(release.ReleaseError, "duplicated"):
            release.verify_release(github.release, self.assets(), VERSION, False)

    def test_gh_transport_distinguishes_not_found_from_permissions_and_validates_json(self):
        github = release.GitHub()
        endpoint = f"{release.API}/releases"
        cases = (
            (1, "HTTP/2.0 404 Not Found\n\n{}", True, None),
            (0, "HTTP/2.0 200 OK\ncontent-type: application/json\n\n[]", False, []),
        )
        for code, stdout, missing, expected in cases:
            with self.subTest(stdout=stdout), patch("launcher_release.subprocess.run") as run:
                run.return_value = subprocess.CompletedProcess([], code, stdout, "diagnostic")
                self.assertEqual(expected, github.request("GET", endpoint, missing_ok=missing))
        for stdout in ("HTTP/2.0 403 Forbidden\n\n{}", "no status", "HTTP/2.0 200 OK\n\nbad json"):
            with self.subTest(stdout=stdout), patch("launcher_release.subprocess.run") as run:
                run.return_value = subprocess.CompletedProcess([], 1 if "403" in stdout else 0, stdout, "error")
                with self.assertRaises(release.ReleaseError):
                    github.request("GET", endpoint, missing_ok=True)


if __name__ == "__main__":
    unittest.main()
