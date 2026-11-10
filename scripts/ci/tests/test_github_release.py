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
                                       commit="a" * 40, source=self.root, public_key=self.root / "public.pem")

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

    def test_unstable_cannot_enter_stable_publication(self):
        for command in (github.prepare_publish, github.publish):
            with self.subTest(command=command.__name__), self.assertRaisesRegex(ValueError, "stable workflow"):
                command(self.args)

    def test_only_unstable_is_published_after_upload(self):
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
                if unstable:
                    self.assertEqual(("release", "edit"), commands[2][:2])
                    self.assertIn("--draft=false", commands[2])
                    self.assertIn("--prerelease", commands[2])
                    self.assertIn("--latest=false", commands[2])
                else:
                    self.assertEqual(2, len(commands))
