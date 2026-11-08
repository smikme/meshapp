import argparse
import base64
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.private = self.root / "private.pem"
        self.public = self.root / "public.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "Ed25519", "-out", str(self.private)], check=True)
        subprocess.run(["openssl", "pkey", "-in", str(self.private), "-pubout", "-out", str(self.public)], check=True)
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.args = argparse.Namespace(version="v2.4.0", commit="a" * 40, repository="smikme/meshapp",
                                       source=self.assets, public_key=self.public)
        for name, (_, kind) in release.inventory("v2.4.0").items():
            path = self.assets / name
            if kind == "selfupdate.zip":
                jar = io.BytesIO()
                with zipfile.ZipFile(jar, "w") as app:
                    app.writestr("version.properties", "version=v2.4.0\nversionCode=2004000\n")
                    app.writestr("update/ed25519-public-key.txt", self.public.read_bytes())
                with zipfile.ZipFile(path, "w") as payload:
                    payload.writestr("lib/MeshApp-v2.4.0.jar", jar.getvalue())
            else:
                path.write_bytes(b"test installer " + name.encode())

    def test_versions_are_monotonic_and_packager_compatible(self):
        self.assertLess(release.version_info("2.4.999")[1], release.version_info("2.5.0")[1])
        self.assertEqual(release.version_info("2.4.0"), ("v2.4.0", 2004000))
        for invalid in ("2.4.0-beta", "2.4.1000", "256.0.0", "2.256.0", "2.04.0", "../../tag", "0.0.0"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                release.version_info(invalid)

    def test_rejects_incomplete_matrix(self):
        (self.assets / "MeshApp-v2.4.0-macos-x86_64.dmg").unlink()
        with self.assertRaisesRegex(ValueError, "Incomplete release"):
            release.finalize(self.args)

    def test_rejects_modified_packages_after_build(self):
        release.finalize(self.args)
        (self.assets / "MeshApp-v2.4.0-windows-x86_64.msi").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "changed after build"):
            release.verify_release(self.assets, self.public)

    def test_rejects_wrong_embedded_key(self):
        another = self.root / "other.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "Ed25519", "-out", str(another)], check=True)
        subprocess.run(["openssl", "pkey", "-in", str(another), "-pubout", "-out", str(self.public)], check=True)
        with self.assertRaisesRegex(ValueError, "embedded update key"):
            release.finalize(self.args)

    def test_signed_manifest_matches_java_wire_format_and_routes_both_macs(self):
        release.finalize(self.args)
        self.args.private_key = self.private
        self.args.output = self.root / "meshapp.json"
        self.args.notes = self.root / "notes.md"
        self.args.notes.write_text("New release", encoding="utf-8")
        self.args.notes_ru = None
        release.manifest(self.args)
        manifest = json.loads(self.args.output.read_text())
        self.assertEqual(4, len(manifest["selfUpdate"]))
        self.assertIn("macos-dmg-aarch64", manifest["downloads"])
        self.assertIn("macos-dmg-x86_64", manifest["downloads"])
        self.assertNotEqual(manifest["downloads"]["macos-dmg-aarch64"], manifest["downloads"]["macos-dmg-x86_64"])
        artifact = manifest["selfUpdate"]["linux-x86_64"]
        body = ("meshapp-self-update-v1\nv2.4.0\n2004000\nfull-archive\nzip\nv2.4.0\n"
                f"{artifact['url']}\n{artifact['sha256']}\n{artifact['size']}\n").encode()
        payload = self.root / "payload"
        signature = self.root / "signature"
        payload.write_bytes(body)
        signature.write_bytes(base64.b64decode(artifact["signature"]))
        command = ["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(self.public),
                   "-in", str(payload), "-sigfile", str(signature)]
        self.assertEqual(0, subprocess.run(command, capture_output=True).returncode)
        payload.write_bytes(body.replace(b"2004000", b"2004001"))
        self.assertNotEqual(0, subprocess.run(command, capture_output=True).returncode)

    def test_catalog_build_keeps_signed_feed_and_does_not_invent_scripts(self):
        spec = importlib.util.spec_from_file_location("pages", Path(__file__).resolve().parents[1] / "build-pages.py")
        pages = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pages)
        source, output = self.root / "site", self.root / "pages"
        for language in ("ru", "en"):
            (source / "store" / language).mkdir(parents=True)
        (source / "index.html").write_text("MeshApp")
        manifest = self.root / "feed.json"
        manifest.write_text('{"signed": "unchanged"}')
        pages.build(source, output, "https://smikme.github.io/meshapp", manifest)
        self.assertEqual(manifest.read_bytes(), (output / "updates/stable/meshapp.json").read_bytes())
        self.assertEqual([], json.loads((output / "store/ru/catalog.json").read_text()))


if __name__ == "__main__":
    unittest.main()
