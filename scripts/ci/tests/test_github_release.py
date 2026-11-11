import argparse
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location("github_release", Path(__file__).resolve().parents[1] / "github-release.py")
github = importlib.util.module_from_spec(spec)
spec.loader.exec_module(github)


class GithubReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.args = argparse.Namespace(version="v2.4.0-unstable.1", repository="smikme/meshapp",
                                       commit="a" * 40, source=self.root, public_key=self.root / "public.pem",
                                       feeds_dir=self.root / "feeds")
        env = patch.dict(os.environ, GITHUB_RUN_NUMBER="1")
        env.start()
        self.addCleanup(env.stop)

    def test_stable_build_requires_default_branch(self):
        self.args.version = "2.4.0"
        with patch.dict(os.environ, GITHUB_REF="refs/heads/feature/test"), patch.object(github, "gh", return_value="main"):
            with self.assertRaisesRegex(ValueError, "default branch"):
                github.preflight(self.args)

    def test_unstable_build_accepts_feature_branch(self):
        output = self.root / "output"
        with patch.dict(os.environ, GITHUB_REF="refs/heads/feature/test", GITHUB_OUTPUT=str(output)), \
                patch.object(github, "gh", return_value="main"), \
                patch.object(github, "run", return_value=self.args.commit), \
                patch.object(github, "release_for", return_value=None), \
                patch.object(github, "check_newer"), \
                patch.object(github.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "")):
            github.preflight(self.args)
        self.assertIn("tag=v2.4.0-unstable.1", output.read_text())
        self.assertIn("version_code=2003999", output.read_text())

    def test_both_channels_require_default_branch_for_signing(self):
        for tag in ("v2.4.0+build.1", "v2.4.0-unstable.1"):
            self.args.version = tag
            with patch.dict(os.environ, GITHUB_REF="refs/heads/unstable/2.4.0"), patch.object(github, "gh", return_value="main"):
                with self.assertRaisesRegex(ValueError, "default branch"):
                    github.prepare_publish(self.args)

    def test_both_channels_remain_draft_until_signed(self):
        for tag, unstable in (("v2.4.0", False), ("v2.4.0-unstable.1", True)):
            with self.subTest(tag=tag):
                self.args.version = tag
                metadata = dict(version=tag, commit=self.args.commit, repository=self.args.repository)
                (self.root / "package.zip").write_bytes(b"verified fixture")
                with patch.object(github, "verify_release", return_value=metadata), \
                        patch.object(github, "release_for", return_value=None), \
                        patch.object(github.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, self.args.commit)), \
                        patch.object(github, "gh") as api:
                    github.draft(self.args)
                commands = [call.args for call in api.call_args_list]
                self.assertIn("--draft", commands[0])
                self.assertEqual(("release", "upload"), commands[1][:2])
                self.assertEqual(2, len(commands))
                self.assertEqual(unstable, "--prerelease" in commands[0])

    def test_same_run_retries_keep_identity_and_new_runs_advance(self):
        self.args.version = "2.4.0"
        output = self.root / "output"
        with patch.object(github, "gh", return_value="main"), patch.object(github, "run", return_value=self.args.commit), \
                patch.object(github, "release_for", return_value=None), patch.object(github, "check_newer"), \
                patch.object(github.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "")):
            for run, attempt, expected in ((126, 1, "v2.4.0+build.126"), (126, 2, "v2.4.0+build.126"), (127, 1, "v2.4.0+build.127")):
                output.write_text("")
                with patch.dict(os.environ, GITHUB_REF="refs/heads/main", GITHUB_OUTPUT=str(output),
                                GITHUB_RUN_NUMBER=str(run), GITHUB_RUN_ATTEMPT=str(attempt)):
                    github.preflight(self.args)
                self.assertIn(f"tag={expected}\n", output.read_text())
                self.assertIn(f"build_number={run}\n", output.read_text())

    def test_rejects_native_installer_downgrade_even_across_channels(self):
        published = [dict(tag_name="v2.4.0-unstable.127", draft=False, prerelease=True)]
        with patch.object(github, "releases", return_value=published):
            with self.assertRaisesRegex(ValueError, "Published build"):
                github.check_newer(self.args.repository, "v2.4.0+build.126")
            github.check_newer(self.args.repository, "v2.4.0+build.128")

    def test_cannot_republish_older_release_with_a_higher_build(self):
        published = [dict(tag_name="v2.5.0+build.126", draft=False, prerelease=False)]
        with patch.object(github, "releases", return_value=published):
            with self.assertRaisesRegex(ValueError, "Published release"):
                github.check_newer(self.args.repository, "v2.4.0+build.127")

    def test_signed_unstable_publication_never_changes_latest(self):
        with patch.object(github, "check_newer"), \
                patch.object(github, "release_for", return_value=dict(draft=True, prerelease=True)), \
                patch.object(github, "gh") as api:
            github.publish(self.args)
        commands = [call.args for call in api.call_args_list]
        self.assertIn("--prerelease", commands[-1])
        self.assertIn("--latest=false", commands[-1])
        self.assertNotIn("--latest", commands[-1])

    def test_feed_recovery_selects_latest_signed_build_in_each_channel(self):
        import json
        releases = [dict(tag_name=tag, draft=False, prerelease="unstable" in tag,
                         assets=[dict(name="meshapp.json")] if signed else [])
                    for tag, signed in (("v2.4.0+build.20", True), ("v2.4.0+build.21", True),
                                        ("v2.5.0-unstable.22", True), ("v2.5.0-unstable.23", False))]
        def download(*args):
            tag = args[2]
            (Path(args[-1]) / "meshapp.json").write_text(json.dumps(dict(version=tag, versionCode=github.version_info(tag)[1])))
        with patch.object(github, "releases", return_value=releases), patch.object(github, "gh", side_effect=download):
            github.preserve_feeds(self.args.repository, self.args.feeds_dir)
        self.assertEqual("v2.4.0+build.21", json.loads((self.args.feeds_dir / "stable.json").read_text())["version"])
        self.assertEqual("v2.5.0-unstable.22", json.loads((self.args.feeds_dir / "unstable.json").read_text())["version"])

    def test_prepare_checks_build_commit_and_preserves_feeds_before_signing(self):
        self.args.source = self.root / "download"
        self.args.source.mkdir()
        output = self.root / "output"
        metadata = dict(version=self.args.version, repository=self.args.repository, commit=self.args.commit,
                        artifacts=[dict(name="package.zip")])
        def download(*args):
            if args[0] == "api": return "main"
            (self.args.source / "package.zip").write_bytes(b"verified separately")
            (self.args.source / "release.json").write_text("metadata")
            files = [self.args.source / name for name in ("package.zip", "release.json")]
            (self.args.source / "SHA256SUMS").write_text("".join(f"{github.digest(p)}  {p.name}\n" for p in files))
        with patch.dict(os.environ, GITHUB_REF="refs/heads/main", GITHUB_OUTPUT=str(output), EXPECTED_RELEASE_COMMIT=self.args.commit), \
                patch.object(github, "gh", side_effect=download), patch.object(github, "check_newer"), \
                patch.object(github, "release_for", return_value=dict(prerelease=True, draft=True, body="notes")), \
                patch.object(github, "verify_release", return_value=metadata), \
                patch.object(github, "run", return_value=self.args.commit), patch.object(github, "preserve_feeds") as preserve:
            github.prepare_publish(self.args)
            preserve.assert_called_once_with(self.args.repository, self.args.feeds_dir)
            self.assertIn("channel=unstable\n", output.read_text())
            self.assertEqual("notes", (self.args.source / "notes.md").read_text())
            with patch.dict(os.environ, EXPECTED_RELEASE_COMMIT="b" * 40):
                with self.assertRaisesRegex(ValueError, "completed build commit"):
                    github.prepare_publish(self.args)
