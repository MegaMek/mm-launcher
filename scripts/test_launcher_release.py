# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later

import copy
import base64
import os
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
    "GITHUB_RUN_ID": "123",
}


class FakeGitHub:
    def __init__(self, version=VERSION):
        self.version = version
        self.calls = []
        self.reference = None
        self.candidate_reference = None
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
            reference = {"ref": data["ref"], "object": {"type": "commit", "sha": data["sha"], "url": "metadata"}}
            if data["ref"].startswith("refs/heads/release-candidates/"):
                self.candidate_reference = reference
            else:
                self.reference = reference
            return copy.deepcopy(reference)
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
        if method == "GET" and (endpoint.endswith("/releases/37") or "/releases/tags/" in endpoint):
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
        self.original = original
        self.candidate_commit = None
        self.unknown_push = False
        self.mutate_blob = None

    def request(self, method, endpoint, data=None, file=None, missing_ok=False):
        if (method, endpoint) not in (
                ("GET", f"{release.API}/git/ref/heads/main"),
                ("GET", f"{release.API}/git/commits/{COMMIT}"),
                ("GET", f"{release.API}/git/commits/" + "d" * 40),
                ("GET", f"{release.API}/git/blobs/{release.blob_sha(self.original)}"),
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
            if endpoint.endswith("d" * 40):
                return copy.deepcopy(self.candidate_commit)
            return {"sha": COMMIT, "tree": {"sha": "b" * 40}}
        if method == "GET" and "/git/blobs/" in endpoint:
            blob = {"sha": release.blob_sha(self.original), "encoding": "base64",
                    "content": base64.encodebytes(self.original.encode("utf-8")).decode("ascii")}
            if self.mutate_blob:
                self.mutate_blob(blob)
            return blob
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
                      "message": data["message"],
                      "parents": [{"sha": parent, "url": "metadata"} for parent in data["parents"]]}
            if self.mutate_commit:
                self.mutate_commit(result)
            self.candidate_commit = result
            return copy.deepcopy(result)
        if self.concurrent_push:
            self.main["object"]["sha"] = "e" * 40
            raise release.ReleaseError("Simulated concurrent non-fast-forward push")
        if self.fail_push:
            raise release.ReleaseError("Simulated protected-branch permission denial")
        self.main["object"]["sha"] = data["sha"]
        if self.unknown_push:
            raise release.ReleaseError("Simulated lost main-update response")
        return copy.deepcopy(self.main)


class FakeCiGitHub(FakeBumpGitHub):
    def __init__(self, original):
        super().__init__(original)
        self.ci_run = {"id": 123, "event": "workflow_dispatch", "head_branch": "main",
                       "head_sha": COMMIT, "path": ".github/workflows/launcher-release.yml",
                       "repository": {"full_name": release.REPOSITORY},
                       "status": "in_progress", "conclusion": None}
        names = ["prepare", "installers / Release tooling contracts", "publish"]
        names += [f"installers / Native installers - {platform}"
                  for platform in ("windows-x64", "linux-x64", "macos-intel", "macos-apple-silicon")]
        self.jobs = [{"name": name, "run_id": 123, "head_sha": COMMIT,
                      "status": "completed", "conclusion": "success"} for name in names]
        self.jobs.append({"name": "finalize", "run_id": 123, "head_sha": COMMIT,
                          "status": "in_progress", "conclusion": None})
        self.job_response = None

    def request(self, method, endpoint, data=None, file=None, missing_ok=False):
        if method == "GET" and ("/git/ref/heads/release-candidates/" in endpoint
                                or "/actions/runs/" in endpoint):
            self.calls.append((method, endpoint, copy.deepcopy(data)))
            if "/git/ref/heads/release-candidates/" in endpoint:
                return copy.deepcopy(self.candidate_reference)
            if "/jobs?" in endpoint:
                if self.job_response is not None:
                    return copy.deepcopy(self.job_response)
                page = int(endpoint.rsplit("=", 1)[1])
                return {"total_count": len(self.jobs),
                        "jobs": copy.deepcopy(self.jobs[(page - 1) * 100:page * 100])}
            return copy.deepcopy(self.ci_run)
        return super().request(method, endpoint, data, file, missing_ok)


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

    def make_assets(self, version):
        directory = self.root / ("assets-" + version)
        directory.mkdir(exist_ok=True)
        for suffix in release.INSTALLERS:
            name = f"MegaMek-Launcher-{version}-{suffix}"
            content = name.encode("ascii")
            (directory / name).write_bytes(content)
            sha = hashlib.sha256(content).hexdigest()
            (directory / (name + ".sha256")).write_text(f"{sha}  {name}\n", encoding="ascii")
        return release.validate_files(directory, version)

    def ci_fixture(self):
        (self.root / "build.gradle.kts").write_bytes(f'version = "{VERSION}"\n'.encode("utf-8"))
        github = FakeCiGitHub((self.root / "build.gradle.kts").read_text())
        version, commit, _ = release.prepare_candidate(github, self.root, ENVIRONMENT)
        with patch("builtins.print"):
            release.publish(github, version, commit, self.make_assets(version))
        (self.root / "build.gradle.kts").write_bytes(f'version = "{version}"\n'.encode("utf-8"))
        github.calls.clear()
        environment = {**ENVIRONMENT, "GITHUB_EVENT_NAME": "push", "GITHUB_SHA": commit}
        return github, environment

    def ci_plan(self, github, environment):
        with patch("launcher_release.verify_checkout") as checkout:
            result = release.verified_release_push(github, self.root, environment)
        if environment.get("GITHUB_EVENT_NAME") == "push" and not environment.get("CI_SOURCE_COMMIT"):
            checkout.assert_called_once_with(self.root, environment["GITHUB_SHA"])
        self.assertTrue(all(method == "GET" for method, _, _ in github.calls))
        return result

    def test_release_finalization_push_reuses_builds_before_the_workflow_finishes(self):
        github, environment = self.ci_fixture()
        reused, reason = self.ci_plan(github, environment)
        self.assertTrue(reused)
        self.assertIn("actions/runs/123", reason)
        self.assertEqual("in_progress", github.ci_run["status"])

    def test_pr_manual_reusable_and_fork_ci_always_build_without_provenance_requests(self):
        for changes in ({"GITHUB_EVENT_NAME": "pull_request"}, {"GITHUB_EVENT_NAME": "workflow_dispatch"},
                        {"CI_SOURCE_COMMIT": "d" * 40}, {"GITHUB_REPOSITORY": "someone/mm-launcher"},
                        {"GITHUB_REF": "refs/heads/feature"}):
            github = FakeGitHub()
            with self.subTest(changes=changes), patch("launcher_release.verify_checkout") as checkout:
                reused, _ = release.verified_release_push(github, self.root,
                        {**ENVIRONMENT, "GITHUB_EVENT_NAME": "push", **changes})
            self.assertFalse(reused)
            self.assertEqual([], github.calls)
            checkout.assert_not_called()

    def test_ordinary_pushes_and_unpublished_candidates_never_skip_installers(self):
        for state in ("ordinary", "duplicate-marker", "missing", "draft", "prerelease", "other-tag-sha"):
            github, environment = self.ci_fixture()
            if state == "ordinary":
                github.candidate_commit["message"] = "Regular code change by the release bot"
            elif state == "duplicate-marker":
                github.candidate_commit["message"] += "\nLauncher-Release-Run: 456"
            elif state == "missing":
                github.release = None
            elif state in ("draft", "prerelease"):
                github.release[state] = True
            else:
                github.reference["object"]["sha"] = "e" * 40
            with self.subTest(state=state):
                self.assertFalse(self.ci_plan(github, environment)[0])

    def test_spoofed_release_trailer_cannot_skip_without_matching_branch_run_and_tree(self):
        for drift in ("branch", "run-id", "workflow", "base", "event", "repository", "tree"):
            github, environment = self.ci_fixture()
            if drift == "branch":
                github.candidate_reference["object"]["sha"] = "e" * 40
            elif drift == "run-id":
                github.ci_run["id"] = 456
            elif drift == "workflow":
                github.ci_run["path"] = ".github/workflows/launcher-archives.yml"
            elif drift == "base":
                github.ci_run["head_sha"] = "e" * 40
            elif drift == "event":
                github.ci_run["event"] = "push"
            elif drift == "repository":
                github.ci_run["repository"]["full_name"] = "someone/mm-launcher"
            else:
                github.changed_tree["tree"][1]["sha"] = "e" * 40
            with self.subTest(drift=drift), self.assertRaises(release.ReleaseError):
                self.ci_plan(github, environment)

    def test_every_required_release_job_must_have_succeeded_to_reuse_installers(self):
        for name in ("prepare", "installers / Release tooling contracts", "publish",
                     "installers / Native installers - windows-x64",
                     "installers / Native installers - linux-x64",
                     "installers / Native installers - macos-intel",
                     "installers / Native installers - macos-apple-silicon"):
            github, environment = self.ci_fixture()
            next(job for job in github.jobs if job["name"] == name)["conclusion"] = "failure"
            with self.subTest(name=name):
                self.assertFalse(self.ci_plan(github, environment)[0])

    def test_latest_job_inventory_supports_failed_finalization_reruns_and_pagination(self):
        github, environment = self.ci_fixture()
        github.ci_run["run_attempt"] = 2
        github.jobs[-1]["run_attempt"] = 2
        for index in range(93):
            github.jobs.append({"name": f"extra-{index}", "run_id": 123, "head_sha": COMMIT,
                                "status": "completed", "conclusion": "success", "run_attempt": 1})
        self.assertTrue(self.ci_plan(github, environment)[0])
        self.assertEqual(2, sum("/jobs?filter=latest" in endpoint for _, endpoint, _ in github.calls))

    def test_malformed_or_partial_release_job_inventories_fail_explicitly(self):
        for failure in ("shape", "partial", "duplicate", "wrong-sha", "wrong-run", "bound"):
            github, environment = self.ci_fixture()
            if failure == "shape":
                github.job_response = {"total_count": True, "jobs": []}
            elif failure == "partial":
                github.job_response = {"total_count": 100, "jobs": github.jobs}
            elif failure == "duplicate":
                github.jobs.append(copy.deepcopy(github.jobs[0]))
            elif failure == "wrong-sha":
                github.jobs[0]["head_sha"] = "e" * 40
            elif failure == "wrong-run":
                github.jobs[0]["run_id"] = 456
            else:
                github.jobs += [{"name": f"extra-{index}", "run_id": 123, "head_sha": COMMIT}
                                for index in range(100)]
            with self.subTest(failure=failure), patch("launcher_release.MAX_PAGES", 1), \
                    self.assertRaises(release.ReleaseError):
                self.ci_plan(github, environment)

    def test_release_reuse_rejects_missing_or_malformed_public_assets(self):
        for failure in ("missing", "name", "duplicate", "digest", "size", "msi-limit", "url", "id"):
            github, environment = self.ci_fixture()
            asset = github.release["assets"][0]
            if failure == "missing":
                github.release["assets"].pop()
            elif failure == "name":
                asset["name"] = []
            elif failure == "duplicate":
                github.release["assets"][1] = copy.deepcopy(asset)
            elif failure == "digest":
                asset["digest"] = "sha256:wrong"
            elif failure == "size":
                asset["size"] = 0
            elif failure == "msi-limit":
                asset["size"] = release.MSI_LIMIT + 1
            elif failure == "url":
                asset["browser_download_url"] = "https://unofficial.invalid/file.msi"
            else:
                asset["id"] = True
            with self.subTest(failure=failure), self.assertRaises(release.ReleaseError):
                self.ci_plan(github, environment)

    @patch("builtins.print")
    def test_ci_plan_cli_records_exact_skip_output_and_explains_it_in_the_summary(self, output):
        github, environment = self.ci_fixture()
        outputs = self.root / "ci-output.txt"
        summary = self.root / "ci-summary.txt"
        with patch.dict(os.environ, {**environment, "GITHUB_OUTPUT": str(outputs),
                                     "GITHUB_STEP_SUMMARY": str(summary)}), \
                patch("launcher_release.Path.cwd", return_value=self.root), \
                patch("launcher_release.GitHub", return_value=github), \
                patch("launcher_release.verify_checkout"), \
                patch("launcher_release.sys.argv", ["launcher_release.py", "ci-plan"]):
            release.main()
        self.assertEqual("build_installers=false\n", outputs.read_text())
        self.assertIn("actions/runs/123", summary.read_text())
        output.assert_called_once()
        self.assertTrue(output.call_args.args[0].startswith("::notice::"))

    @patch("builtins.print")
    def test_ci_plan_cli_explicitly_preserves_ordinary_builds(self, output):
        github, environment = self.ci_fixture()
        github.candidate_commit["message"] = "Ordinary main change"
        outputs = self.root / "ci-output.txt"
        with patch.dict(os.environ, {**environment, "GITHUB_OUTPUT": str(outputs),
                                     "GITHUB_STEP_SUMMARY": ""}), \
                patch("launcher_release.Path.cwd", return_value=self.root), \
                patch("launcher_release.GitHub", return_value=github), \
                patch("launcher_release.verify_checkout"), \
                patch("launcher_release.sys.argv", ["launcher_release.py", "ci-plan"]):
            release.main()
        self.assertEqual("build_installers=true\n", outputs.read_text())
        self.assertIn("Ordinary main change", output.call_args.args[0])

    def test_ci_plan_failure_never_exports_a_skip_decision(self):
        github, environment = self.ci_fixture()
        github.job_response = {"total_count": 100, "jobs": github.jobs}
        outputs = self.root / "ci-output.txt"
        with patch.dict(os.environ, {**environment, "GITHUB_OUTPUT": str(outputs)}), \
                patch("launcher_release.Path.cwd", return_value=self.root), \
                patch("launcher_release.GitHub", return_value=github), \
                patch("launcher_release.verify_checkout"), \
                patch("launcher_release.sys.argv", ["launcher_release.py", "ci-plan"]), \
                self.assertRaises(release.ReleaseError):
            release.main()
        self.assertFalse(outputs.exists())

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

    def test_candidate_changes_only_version_file_and_never_advances_main(self):
        github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
        version, commit, base = release.prepare_candidate(github, self.root, ENVIRONMENT)
        self.assertEqual(("0.14.6", "d" * 40, COMMIT), (version, commit, base))
        writes = [(method, endpoint, data) for method, endpoint, data in github.calls if method != "GET"]
        self.assertEqual(3, len(writes))
        self.assertEqual({"base_tree": "b" * 40, "tree": [
            {"path": "build.gradle.kts", "mode": "100644", "type": "blob",
             "content": 'version = "0.14.6"\n'}]}, writes[0][2])
        self.assertEqual([COMMIT], writes[1][2]["parents"])
        self.assertEqual("c" * 40, writes[1][2]["tree"])
        self.assertEqual(("POST", f"{release.API}/git/refs",
                          {"ref": "refs/heads/release-candidates/0.14.6/123", "sha": commit}), writes[2])
        self.assertEqual(COMMIT, github.main["object"]["sha"])
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
                    release.prepare_candidate(github, self.root, ENVIRONMENT)
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
                    release.prepare_candidate(github, self.root, ENVIRONMENT)
                self.assertFalse(any(method == "PATCH" for method, _, _ in github.calls))
                self.assertEqual(COMMIT, github.main["object"]["sha"])

    @patch("builtins.print")
    def test_denied_or_concurrent_finalization_is_not_forced_retried_or_rolled_back(self, output):
        for failure in ("permission", "concurrent"):
            with self.subTest(failure=failure):
                github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
                version, commit, base = release.prepare_candidate(github, self.root, ENVIRONMENT)
                assets = self.make_assets(version)
                release.publish(github, version, commit, assets)
                github.fail_push = failure == "permission"
                github.concurrent_push = failure == "concurrent"
                with self.assertRaisesRegex(release.ReleaseError, "synchronization pending"):
                    release.finalize_main(github, version, commit, base, assets)
                self.assertEqual(1, sum(method == "PATCH" and endpoint.endswith("/git/refs/heads/main")
                                        for method, endpoint, _ in github.calls))
                self.assertFalse(any(method == "DELETE" for method, _, _ in github.calls))
                expected = "e" * 40 if failure == "concurrent" else COMMIT
                self.assertEqual(expected, github.main["object"]["sha"])
                self.assertFalse(github.release["draft"], "a published release must never be rolled back")

    @patch("builtins.print")
    def test_candidate_cli_exports_base_and_tested_commit_without_modifying_main(self, output):
        github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
        outputs = self.root / "outputs"
        with patch("launcher_release.GitHub", return_value=github), \
                patch("launcher_release.Path.cwd", return_value=self.root), \
                patch("launcher_release.verify_checkout") as checkout, \
                patch.dict(release.os.environ, {**ENVIRONMENT, "GITHUB_OUTPUT": str(outputs)}, clear=True), \
                patch("launcher_release.sys.argv", ["launcher_release.py", "candidate"]):
            release.main()
        checkout.assert_called_once_with(self.root, COMMIT)
        self.assertEqual("version=0.14.6\ncommit=" + "d" * 40 + "\nbase=" + COMMIT + "\n", outputs.read_text())
        output.assert_called_once_with(
            "Prepared launcher 0.14.6 at " + "d" * 40 + "; main remains at " + COMMIT)

    def test_candidate_run_identity_is_required_before_any_write(self):
        for run in (None, "0", "../main", "123\n"):
            github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
            with self.subTest(run=run), self.assertRaises(release.ReleaseError):
                release.prepare_candidate(github, self.root, {**ENVIRONMENT, "GITHUB_RUN_ID": run})
            self.assertEqual([], github.calls)

    @patch("builtins.print")
    def test_main_advances_only_after_confirmed_publication_and_finalize_is_read_only_on_retry(self, output):
        github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
        version, commit, base = release.prepare_candidate(github, self.root, ENVIRONMENT)
        assets = self.make_assets(version)
        self.assertEqual(base, github.main["object"]["sha"], "a failed build cannot consume the version")
        release.publish(github, version, commit, assets)
        self.assertEqual(base, github.main["object"]["sha"], "publication and synchronization are separate")
        release.finalize_main(github, version, commit, base, assets)
        self.assertEqual(commit, github.main["object"]["sha"])
        publication = next(i for i, call in enumerate(github.calls)
                           if call[0:2] == ("PATCH", f"{release.API}/releases/37"))
        synchronization = next(i for i, call in enumerate(github.calls)
                               if call[0:2] == ("PATCH", f"{release.API}/git/refs/heads/main"))
        self.assertLess(publication, synchronization)
        before = len(github.calls)
        release.finalize_main(github, version, commit, base, assets)
        self.assertTrue(all(method == "GET" for method, _, _ in github.calls[before:]))
        self.assertEqual(1, sum(endpoint.endswith("/git/refs/heads/main") and method == "PATCH"
                                for method, endpoint, _ in github.calls))

    def test_failed_upload_or_unpublished_draft_never_advances_main(self):
        github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
        version, commit, base = release.prepare_candidate(github, self.root, ENVIRONMENT)
        assets = self.make_assets(version)
        github.fail_upload = True
        with self.assertRaises(release.ReleaseError):
            release.publish(github, version, commit, assets)
        with self.assertRaises(release.ReleaseError):
            release.finalize_main(github, version, commit, base, assets)
        self.assertEqual(base, github.main["object"]["sha"])
        self.assertFalse(any(method == "PATCH" and "/git/refs/" in endpoint
                             for method, endpoint, _ in github.calls))

    @patch("builtins.print")
    def test_finalize_rejects_source_drift_tag_drift_missing_assets_and_changed_main(self, output):
        for failure in ("source", "parent", "tag", "assets", "latest", "main"):
            with self.subTest(failure=failure):
                github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
                version, commit, base = release.prepare_candidate(github, self.root, ENVIRONMENT)
                assets = self.make_assets(version)
                release.publish(github, version, commit, assets)
                if failure == "source":
                    github.changed_tree["tree"][1]["sha"] = "e" * 40
                elif failure == "parent":
                    github.candidate_commit["parents"] = [{"sha": "e" * 40}]
                elif failure == "tag":
                    github.reference["object"]["sha"] = "e" * 40
                elif failure == "assets":
                    github.release["assets"].pop()
                elif failure == "latest":
                    github.mutate_latest = lambda item: item.update(id=99)
                else:
                    github.main["object"]["sha"] = "e" * 40
                with self.assertRaisesRegex(release.ReleaseError, "synchronization pending"):
                    release.finalize_main(github, version, commit, base, assets)
                self.assertFalse(any(method == "PATCH" and "/git/refs/" in endpoint
                                     for method, endpoint, _ in github.calls))

    def test_malformed_candidate_metadata_and_blobs_fail_explicitly_without_main_writes(self):
        for failure in ("parents", "tree", "encoding", "blob-sha", "blob-bytes"):
            with self.subTest(failure=failure):
                github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
                version, commit, base = release.prepare_candidate(github, self.root, ENVIRONMENT)
                if failure == "parents":
                    github.candidate_commit["parents"] = [None]
                elif failure == "tree":
                    github.candidate_commit["tree"] = []
                elif failure == "encoding":
                    github.mutate_blob = lambda blob: blob.update(content="not base64!")
                elif failure == "blob-sha":
                    github.mutate_blob = lambda blob: blob.update(sha="e" * 40)
                else:
                    github.mutate_blob = lambda blob: blob.update(
                        content=base64.b64encode(b"wrong source").decode("ascii"))
                with self.assertRaises(release.ReleaseError):
                    release.verify_candidate(github, version, commit, base)
                self.assertFalse(any(method == "PATCH" for method, _, _ in github.calls))

    @patch("builtins.print")
    def test_unknown_main_write_outcome_can_be_confirmed_without_repeating_the_write(self, output):
        github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
        version, commit, base = release.prepare_candidate(github, self.root, ENVIRONMENT)
        assets = self.make_assets(version)
        release.publish(github, version, commit, assets)
        github.unknown_push = True
        with self.assertRaisesRegex(release.ReleaseError, "synchronization pending"):
            release.finalize_main(github, version, commit, base, assets)
        self.assertEqual(commit, github.main["object"]["sha"])
        before = len(github.calls)
        release.finalize_main(github, version, commit, base, assets)
        self.assertTrue(all(method == "GET" for method, _, _ in github.calls[before:]))

    @patch("builtins.print")
    def test_finalize_cli_uses_exact_candidate_base_and_existing_artifacts(self, output):
        github = FakeBumpGitHub((self.root / "build.gradle.kts").read_text())
        version, commit, base = release.prepare_candidate(github, self.root, ENVIRONMENT)
        assets = self.make_assets(version)
        release.publish(github, version, commit, assets)
        (self.root / "build.gradle.kts").write_text('version = "' + version + '"\n')
        with patch.dict(os.environ, ENVIRONMENT), patch("launcher_release.Path.cwd", return_value=self.root), \
                patch("launcher_release.GitHub", return_value=github), \
                patch("launcher_release.verify_checkout") as checkout, \
                patch("launcher_release.sys.argv", ["launcher_release.py", "finalize",
                      "--version", version, "--commit", commit, "--base", base,
                      "--assets", str(self.root / ("assets-" + version))]):
            release.main()
        checkout.assert_called_once_with(self.root, commit)
        self.assertEqual(commit, github.main["object"]["sha"])

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
        version, commit, _ = release.prepare_candidate(github, self.root, ENVIRONMENT)
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
