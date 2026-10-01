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
    def __init__(self, version=VERSION):
        self.version = version
        self.calls = []
        self.reference = None
        self.release = None
        self.inventory = []
        self.existing_tag = None
        self.fail_upload = False
        self.draft_tag = "untagged-abe406fdea29a73d5316"
        self.mutate_upload = None
        self.mutate_staged = None
        self.mutate_published = None
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
            self.release = {"id": 37, **data, "assets": [],
                            "html_url": f"https://github.com/{release.REPOSITORY}"
                                        f"/releases/tag/{self.draft_tag}"}
            return copy.deepcopy(self.release)
        if method == "POST" and "/assets?name=" in endpoint:
            if self.fail_upload:
                raise release.ReleaseError("Simulated failed upload")
            metadata = {
                "id": 100 + len(self.release["assets"]), "name": file.name,
                "size": file.stat().st_size, "state": "uploaded",
                "digest": "sha256:" + hashlib.sha256(file.read_bytes()).hexdigest(),
                "browser_download_url": f"https://github.com/{release.REPOSITORY}"
                                        f"/releases/download/{self.draft_tag}/{file.name}",
            }
            if self.mutate_upload:
                self.mutate_upload(metadata)
            self.release["assets"].append(metadata)
            return copy.deepcopy(metadata)
        if method == "GET" and endpoint.endswith("/releases/37"):
            result = copy.deepcopy(self.release)
            if self.mutate_staged:
                self.mutate_staged(result)
            return result
        if method == "PATCH" and endpoint.endswith("/releases/37"):
            self.release.update(data)
            self.release["html_url"] = (f"https://github.com/{release.REPOSITORY}"
                                        f"/releases/tag/v{self.version}")
            for asset in self.release["assets"]:
                asset["browser_download_url"] = (
                    f"https://github.com/{release.REPOSITORY}/releases/download/"
                    f"v{self.version}/{asset['name']}")
            if self.mutate_published:
                self.mutate_published(self.release)
            return copy.deepcopy(self.release)
        if method == "GET" and endpoint.endswith("/releases/latest"):
            result = copy.deepcopy(self.release)
            if self.mutate_latest:
                self.mutate_latest(result)
            return result
        raise AssertionError(f"Unexpected request: {method} {endpoint}")


class FakeBumpGitHub(FakeGitHub):
    def __init__(self, original):
        super().__init__("0.14.6")
        self.main = {"ref": "refs/heads/main", "object": {"type": "commit", "sha": COMMIT}}
        self.tree = {
            "sha": "b" * 40, "truncated": False,
            "tree": [
                {"path": "build.gradle.kts", "mode": "100644", "type": "blob",
                 "sha": release.blob_sha(original)},
                {"path": "README.md", "mode": "100644", "type": "blob", "sha": "f" * 40},
            ],
        }
        self.mutate_tree = None
        self.changed_tree = None
        self.mutate_commit = None
        self.fail_push = False
        self.concurrent_push = False

    def request(self, method, endpoint, data=None, file=None, missing_ok=False):
        if (method, endpoint) not in (
                ("GET", f"{release.API}/git/ref/heads/main"),
                ("GET", f"{release.API}/git/commits/{COMMIT}"),
                ("GET", f"{release.API}/git/trees/" + "b" * 40),
                ("GET", f"{release.API}/git/trees/" + "c" * 40),
                ("POST", f"{release.API}/git/trees"),
                ("POST", f"{release.API}/git/commits"),
                ("PATCH", f"{release.API}/git/refs/heads/main")):
            return super().request(method, endpoint, data, file, missing_ok)
        self.calls.append((method, endpoint, copy.deepcopy(data)))
        if method == "GET" and endpoint.endswith("/git/ref/heads/main"):
            return copy.deepcopy(self.main)
        if method == "GET" and "/git/commits/" in endpoint:
            return {"sha": COMMIT, "tree": {"sha": "b" * 40}}
        if method == "GET" and "/git/trees/" in endpoint:
            return copy.deepcopy(self.changed_tree if endpoint.endswith("c" * 40) else self.tree)
        if method == "POST" and endpoint.endswith("/git/trees"):
            result = copy.deepcopy(self.tree)
            result["sha"] = "c" * 40
            result["tree"][0]["sha"] = release.blob_sha(data["tree"][0]["content"])
            if self.mutate_tree:
                self.mutate_tree(result)
            self.changed_tree = result
            return {"sha": result["sha"]}
        if method == "POST" and endpoint.endswith("/git/commits"):
            result = {"sha": "d" * 40, "tree": {"sha": data["tree"]},
                      "parents": [{"sha": parent} for parent in data["parents"]]}
            if self.mutate_commit:
                self.mutate_commit(result)
            return result
        if self.concurrent_push:
            self.main["object"]["sha"] = "e" * 40
            raise release.ReleaseError("Simulated concurrent non-fast-forward push")
        if self.fail_push:
            raise release.ReleaseError("Simulated protected-branch permission denial")
        self.main["object"]["sha"] = data["sha"]
        return copy.deepcopy(self.main)


class LauncherReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "build.gradle.kts").write_text(f'version = "{VERSION}"\n', encoding="utf-8", newline="\n")
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

    def test_patch_preparation_is_read_only_and_preserves_source_formatting(self):
        original = '// fixture\r\nversion \t= "0.14.5"  \r\nother = "0.14.5"\r\n'
        (self.root / "build.gradle.kts").write_bytes(original.encode("utf-8"))
        github = FakeBumpGitHub(original)
        version, commit, content, updated = release.prepare_patch(github, self.root, ENVIRONMENT)
        self.assertEqual("0.14.6", version)
        self.assertEqual(COMMIT, commit)
        self.assertEqual(original, content)
        self.assertEqual(original.replace('version \t= "0.14.5"', 'version \t= "0.14.6"'), updated)
        self.assertTrue(all(method == "GET" for method, _, _ in github.calls))
        self.assertEqual(original.encode("utf-8"), (self.root / "build.gradle.kts").read_bytes())

    def test_patch_increment_preserves_major_minor_and_enforces_msi_ceiling(self):
        for current, expected in (("0.14.5", "0.14.6"), ("1.2.9", "1.2.10"),
                                  ("255.255.65534", "255.255.65535")):
            with self.subTest(current=current):
                self.assertEqual(expected, release.next_patch_version(current))
        for current in ("0.14.65535", "00.14.5", "0.14.5-SNAPSHOT", "v0.14.5"):
            with self.subTest(current=current), self.assertRaises(release.ReleaseError):
                release.next_patch_version(current)

    def test_blob_identity_matches_the_standard_git_object_hash(self):
        self.assertEqual("d670460b4b4aece5915caf5c68d12f560a9fe3e4",
                         release.blob_sha("test content\n"))

    def test_patch_bump_changes_only_version_file_and_fast_forwards_main_once(self):
        github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
        version, commit = release.bump_patch(github, self.root, ENVIRONMENT)
        self.assertEqual(("0.14.6", "d" * 40), (version, commit))
        writes = [(method, endpoint, data) for method, endpoint, data in github.calls if method != "GET"]
        self.assertEqual(3, len(writes))
        self.assertEqual({"base_tree": "b" * 40, "tree": [
            {"path": "build.gradle.kts", "mode": "100644", "type": "blob",
             "content": 'version = "0.14.6"\n'}]}, writes[0][2])
        self.assertEqual([COMMIT], writes[1][2]["parents"])
        self.assertEqual("c" * 40, writes[1][2]["tree"])
        self.assertEqual(("PATCH", f"{release.API}/git/refs/heads/main",
                          {"sha": commit, "force": False}), writes[2])
        self.assertEqual(commit, github.main["object"]["sha"])
        self.assertIsNone(github.reference)
        self.assertIsNone(github.release)

    def test_bump_rejects_stale_main_existing_candidate_and_wrong_source_before_writes(self):
        for failure in ("stale-main", "used-version", "wrong-file", "truncated-tree", "wrong-mode"):
            with self.subTest(failure=failure):
                github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
                if failure == "stale-main":
                    github.main["object"]["sha"] = "e" * 40
                elif failure == "used-version":
                    github.inventory = [{"tag_name": "v0.14.6", "draft": True, "prerelease": False}]
                elif failure == "wrong-file":
                    github.tree["tree"][0]["sha"] = "e" * 40
                elif failure == "truncated-tree":
                    github.tree["truncated"] = True
                else:
                    github.tree["tree"][0]["mode"] = "100755"
                with self.assertRaises(release.ReleaseError):
                    release.bump_patch(github, self.root, ENVIRONMENT)
                self.assertTrue(all(method == "GET" for method, _, _ in github.calls))

    def test_bump_rejects_tree_drift_or_wrong_parent_without_updating_main(self):
        for failure in ("extra-change", "wrong-parent", "missing-parent"):
            with self.subTest(failure=failure):
                github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
                if failure == "extra-change":
                    github.mutate_tree = lambda tree: tree["tree"][1].update(sha="e" * 40)
                elif failure == "wrong-parent":
                    github.mutate_commit = lambda commit: commit.update(parents=[{"sha": "e" * 40}])
                else:
                    github.mutate_commit = lambda commit: commit.update(parents=[])
                with self.assertRaises(release.ReleaseError):
                    release.bump_patch(github, self.root, ENVIRONMENT)
                self.assertFalse(any(method == "PATCH" for method, _, _ in github.calls))
                self.assertEqual(COMMIT, github.main["object"]["sha"])

    def test_denied_or_concurrent_push_is_not_forced_retried_or_rolled_back(self):
        for failure in ("permission", "concurrent"):
            with self.subTest(failure=failure):
                github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
                github.fail_push = failure == "permission"
                github.concurrent_push = failure == "concurrent"
                with self.assertRaises(release.ReleaseError):
                    release.bump_patch(github, self.root, ENVIRONMENT)
                self.assertEqual(1, sum(method == "PATCH" for method, _, _ in github.calls))
                self.assertFalse(any(method == "DELETE" for method, _, _ in github.calls))
                expected = "e" * 40 if failure == "concurrent" else COMMIT
                self.assertEqual(expected, github.main["object"]["sha"])
                self.assertIsNone(github.reference)

    @patch("builtins.print")
    def test_bump_cli_exports_new_version_and_commit_not_the_dispatch_commit(self, output):
        github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
        outputs = self.root / "outputs"
        with patch("launcher_release.GitHub", return_value=github), \
                patch("launcher_release.Path.cwd", return_value=self.root), \
                patch("launcher_release.verify_checkout") as checkout, \
                patch.dict(release.os.environ, {**ENVIRONMENT, "GITHUB_OUTPUT": str(outputs)}, clear=True), \
                patch("launcher_release.sys.argv", ["launcher_release.py", "bump"]):
            release.main()
        checkout.assert_called_once_with(self.root, COMMIT)
        self.assertEqual("version=0.14.6\ncommit=" + "d" * 40 + "\n", outputs.read_text())
        output.assert_called_once_with(
            "Committed launcher 0.14.6 to main at " + "d" * 40 + "; no release published yet")

    def test_checkout_guard_requires_the_bumped_commit_even_with_original_dispatch_context(self):
        (self.root / "build.gradle.kts").write_text('version = "0.14.6"\n')
        self.assertEqual("d" * 40,
                         release.validate_source("0.14.6", self.root, ENVIRONMENT, "d" * 40))
        with patch("launcher_release.subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, COMMIT + "\n", "")
            with self.assertRaisesRegex(release.ReleaseError, "exact prepared commit"):
                release.verify_checkout(self.root, "d" * 40)
            run.return_value = subprocess.CompletedProcess([], 0, "d" * 40 + "\n", "")
            release.verify_checkout(self.root, "d" * 40)

    @patch("builtins.print")
    def test_publish_cli_builds_new_version_assets_and_tags_bumped_not_dispatched_commit(self, output):
        github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
        version, commit = release.bump_patch(github, self.root, ENVIRONMENT)
        (self.root / "build.gradle.kts").write_bytes(f'version = "{version}"\n'.encode("utf-8"))
        directory = self.root / "new-assets"
        directory.mkdir()
        for suffix in release.INSTALLERS:
            name = f"MegaMek-Launcher-{version}-{suffix}"
            content = name.encode("ascii")
            (directory / name).write_bytes(content)
            sha = hashlib.sha256(content).hexdigest()
            (directory / (name + ".sha256")).write_text(f"{sha}  {name}\n", encoding="ascii")
        with patch("launcher_release.GitHub", return_value=github), \
                patch("launcher_release.Path.cwd", return_value=self.root), \
                patch("launcher_release.subprocess.run") as git, \
                patch.dict(release.os.environ, ENVIRONMENT, clear=True), \
                patch("launcher_release.sys.argv", ["launcher_release.py", "publish",
                                                   "--version", version, "--commit", commit,
                                                   "--assets", str(directory)]):
            git.return_value = subprocess.CompletedProcess([], 0, commit + "\n", "")
            release.main()
        self.assertEqual("0.14.6", version)
        self.assertEqual("d" * 40, github.reference["object"]["sha"])
        self.assertNotEqual(COMMIT, github.reference["object"]["sha"])
        self.assertEqual("v0.14.6", github.release["tag_name"])
        self.assertFalse(github.release["draft"])
        self.assertEqual(10, len(github.release["assets"]))
        self.assertTrue(all(asset["name"].startswith("MegaMek-Launcher-0.14.6-")
                            for asset in github.release["assets"]))
        output.assert_called_once_with(
            f"Published https://github.com/{release.REPOSITORY}/releases/tag/v0.14.6")

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

    @patch("builtins.print")
    def test_draft_uploads_also_accept_final_versioned_urls(self, output):
        github = FakeGitHub()
        github.draft_tag = "v" + VERSION
        release.publish(github, VERSION, COMMIT, self.assets())
        self.assertFalse(github.release["draft"])
        self.assertEqual(10, len(github.release["assets"]))
        self.assertEqual(1, sum(method == "PATCH" for method, _, _ in github.calls))

    def test_draft_upload_urls_must_match_the_same_release_and_asset(self):
        name = f"MegaMek-Launcher-{VERSION}-windows-x64.msi"
        prefix = f"https://github.com/{release.REPOSITORY}/releases/download/"
        urls = (
            f"https://example.org/{name}",
            f"https://github.com/Someone/mm-launcher/releases/download/untagged-abc/{name}",
            prefix + f"untagged-abc/{name}",
            prefix + f"v0.14.6/{name}",
            prefix + f"untagged-abe406fdea29a73d5316/wrong.msi",
            prefix + f"untagged-abe406fdea29a73d5316/{name}?download=1",
            prefix + f"untagged-abe406fdea29a73d5316/{name}#fragment",
            prefix + f"untagged-abe406fdea29a73d5316/../{name}",
        )
        for url in urls:
            with self.subTest(url=url):
                github = FakeGitHub()
                github.mutate_upload = lambda item: item.update(browser_download_url=url)
                with self.assertRaisesRegex(release.ReleaseError, "Uploaded asset"):
                    release.publish(github, VERSION, COMMIT, self.assets())
                self.assert_no_publication(github)
                self.assertTrue(github.release["draft"])
                self.assertEqual(1, sum("/assets?name=" in endpoint for _, endpoint, _ in github.calls))

    def test_staged_draft_url_must_stay_bound_to_its_release(self):
        github = FakeGitHub()
        github.mutate_staged = lambda item: item.update(
            html_url=f"https://github.com/{release.REPOSITORY}/releases/tag/untagged-abc")
        with self.assertRaisesRegex(release.ReleaseError, "Uploaded asset"):
            release.publish(github, VERSION, COMMIT, self.assets())
        self.assert_no_publication(github)

    def test_published_and_latest_assets_require_final_versioned_urls(self):
        name = f"MegaMek-Launcher-{VERSION}-windows-x64.msi"
        temporary_url = (f"https://github.com/{release.REPOSITORY}/releases/download/"
                         f"untagged-abe406fdea29a73d5316/{name}")
        for phase in ("mutate_published", "mutate_latest"):
            with self.subTest(phase=phase):
                github = FakeGitHub()
                setattr(github, phase, lambda item: item["assets"][0].update(
                    browser_download_url=temporary_url))
                with self.assertRaisesRegex(release.ReleaseError, "Uploaded asset"):
                    release.publish(github, VERSION, COMMIT, self.assets())
                self.assertFalse(github.release["draft"])
                self.assertEqual(1, sum(method == "PATCH" for method, _, _ in github.calls))
                self.assertFalse(any(method == "DELETE" for method, _, _ in github.calls))

    def test_staged_metadata_drift_and_missing_digest_prevent_publication(self):
        for field, value in (("digest", None), ("digest", "sha256:" + "b" * 64),
                             ("state", "new"), ("size", 1), ("id", 999),
                             ("browser_download_url", "https://example.org/installer.msi"),
                             ("browser_download_url", [])):
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
