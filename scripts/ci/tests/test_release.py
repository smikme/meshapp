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
from versioning import ReleaseVersion, with_build_number, package_version, MAX_BUILD_NUMBER


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
        self.assertEqual(release.version_info("2.4.0-unstable.1"), ("v2.4.0-unstable.1", 2003999))
        self.assertLess(release.version_info("2.4.0-unstable.999")[1], release.version_info("2.4.0")[1])
        for invalid in ("2.4.0-beta", "2.4.0-unstable.0", "2.4.0-unstable.01", "2.4.1000", "256.0.0", "2.256.0", "2.04.0", "../../tag", "0.0.0"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                release.version_info(invalid)

    def test_unstable_cannot_generate_a_stable_feed(self):
        self.args.version = "2.4.0-unstable.1"
        with self.assertRaisesRegex(ValueError, "stable update feed"):
            release.manifest(self.args)

    def test_build_order_and_installer_rollover(self):
        versions = ["v2.4.0-unstable.9", "v2.4.0-unstable.10", "v2.4.0", "v2.4.0+build.11", "v2.4.0+build.12", "v2.4.1-unstable.13"]
        keys = [ReleaseVersion.parse(v).key for v in versions]
        self.assertEqual(keys, sorted(keys))
        self.assertEqual("v2.4.0+build.126", with_build_number("2.4.0", 126).tag)
        self.assertEqual("v2.4.0-unstable.127", with_build_number("2.4.0-unstable", 127).tag)
        self.assertEqual("3.0.126", package_version(126))
        self.assertEqual("3.0.65535", package_version(65535))
        self.assertEqual("3.1.0", package_version(65536))
        self.assertEqual("255.255.65535", package_version(MAX_BUILD_NUMBER))
        with self.assertRaises(ValueError):
            package_version(MAX_BUILD_NUMBER + 1)

    def seed_numbered_release(self, version):
        for file in self.assets.iterdir():
            file.unlink()
        self.args.version = version
        tag, code = release.version_info(version)
        metadata = release.version_metadata(tag)
        for name, (_, kind) in release.inventory(tag).items():
            if kind == "selfupdate.zip":
                jar = io.BytesIO()
                with zipfile.ZipFile(jar, "w") as app:
                    props = dict(version=tag, versionCode=code, **metadata)
                    app.writestr("version.properties", "".join(f"{k}={v}\n" for k, v in props.items()))
                    app.writestr("update/ed25519-public-key.txt", self.public.read_bytes())
                with zipfile.ZipFile(self.assets / name, "w") as payload:
                    payload.writestr(f"lib/MeshApp-{tag}.jar", jar.getvalue())
            else:
                (self.assets / name).write_bytes(b"installer")

    def test_numbered_release_metadata_and_signature_cover_build_identity(self):
        for tag in ("v2.4.0+build.126", "v2.4.0-unstable.127"):
            with self.subTest(tag=tag):
                self.seed_numbered_release(tag)
                release.finalize(self.args)
                self.args.channel = ReleaseVersion.parse(tag).channel
                self.args.private_key = self.private
                self.args.output = self.root / "meshapp.json"
                self.args.notes = self.root / "notes.md"
                self.args.notes.write_text("release", encoding="utf-8")
                self.args.notes_ru = None
                release.manifest(self.args)
                manifest = json.loads(self.args.output.read_text())
                self.assertEqual(ReleaseVersion.parse(tag).build, manifest["buildNumber"])
                self.assertEqual(self.args.channel, manifest["channel"])
                artifact = manifest["selfUpdate"]["linux-x86_64"]
                body = (f"meshapp-self-update-v1\n{tag}\n{manifest['versionCode']}\nfull-archive\nzip\n"
                        f"{tag}\n{artifact['url']}\n{artifact['sha256']}\n{artifact['size']}\n").encode()
                payload, signature = self.root / "body", self.root / "signature"
                payload.write_bytes(body)
                signature.write_bytes(base64.b64decode(artifact["signature"]))
                command = ["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(self.public),
                           "-in", str(payload), "-sigfile", str(signature)]
                self.assertEqual(0, subprocess.run(command, capture_output=True).returncode)
                payload.write_bytes(body.replace(tag.encode(), tag.replace(str(manifest["buildNumber"]), "999").encode()))
                self.assertNotEqual(0, subprocess.run(command, capture_output=True).returncode)

    def test_rejects_mixed_build_metadata(self):
        self.seed_numbered_release("v2.4.0+build.126")
        release.finalize(self.args)
        path = self.assets / "release.json"
        data = json.loads(path.read_text())
        data["buildNumber"] = 127
        release.write_json(path, data)
        with self.assertRaisesRegex(ValueError, "build metadata"):
            release.verify_release(self.assets, self.public)

    def test_rejects_a_platform_payload_from_another_build(self):
        self.seed_numbered_release("v2.4.0+build.127")
        path127 = self.assets / release.artifact_name(self.args.version, "macos-aarch64", "selfupdate.zip")
        other = path127.read_bytes()
        self.seed_numbered_release("v2.4.0+build.126")
        path126 = self.assets / release.artifact_name(self.args.version, "macos-aarch64", "selfupdate.zip")
        path126.write_bytes(other)
        with self.assertRaisesRegex(ValueError, "embedded version"):
            release.finalize(self.args)

    def test_rejects_incomplete_matrix(self):
        (self.assets / "MeshApp-v2.4.0-macos-x86_64.dmg").unlink()
        with self.assertRaisesRegex(ValueError, "Incomplete release"):
            release.finalize(self.args)

    def test_collects_linux_arm64_packages(self):
        source, output = self.root / "jpackage", self.root / "collected"
        source.mkdir()
        files = {
            "meshapp_2.4.0_arm64.deb": "MeshApp-v2.4.0-linux-aarch64.deb",
            "MeshApp-v2.4.0-aarch64.AppImage": "MeshApp-v2.4.0-linux-aarch64.AppImage",
            "MeshApp-v2.4.0-aarch64.flatpak": "MeshApp-v2.4.0-linux-aarch64.flatpak",
            "MeshApp-v2.4.0-linux-aarch64-selfupdate.zip": "MeshApp-v2.4.0-linux-aarch64-selfupdate.zip",
        }
        for original, published in files.items():
            (source / original).write_bytes((self.assets / published).read_bytes())
        release.collect(argparse.Namespace(version=self.args.version, platform="linux-aarch64",
                                           source=source, output=output, public_key=self.public))
        self.assertEqual(set(files.values()), {p.name for p in output.iterdir()})
        for original, published in files.items():
            self.assertEqual((source / original).read_bytes(), (output / published).read_bytes())

    def test_requires_every_linux_arm64_package(self):
        for suffix in (".deb", ".AppImage", ".flatpak", "-selfupdate.zip"):
            with self.subTest(suffix=suffix):
                path = self.assets / f"MeshApp-v2.4.0-linux-aarch64{suffix}"
                content = path.read_bytes()
                path.unlink()
                try:
                    with self.assertRaisesRegex(ValueError, "Incomplete release"):
                        release.finalize(self.args)
                finally:
                    path.write_bytes(content)

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

    def test_signed_manifest_matches_java_wire_format_and_routes_architectures(self):
        release.finalize(self.args)
        self.args.private_key = self.private
        self.args.output = self.root / "meshapp.json"
        self.args.notes = self.root / "notes.md"
        self.args.notes.write_text("New release", encoding="utf-8")
        self.args.notes_ru = None
        release.manifest(self.args)
        manifest = json.loads(self.args.output.read_text())
        self.assertEqual({"linux-x86_64", "linux-aarch64", "windows-x86_64", "macos-aarch64", "macos-x86_64"},
                         set(manifest["selfUpdate"]))
        self.assertIn("macos-dmg-aarch64", manifest["downloads"])
        self.assertIn("macos-dmg-x86_64", manifest["downloads"])
        self.assertNotEqual(manifest["downloads"]["macos-dmg-aarch64"], manifest["downloads"]["macos-dmg-x86_64"])
        for arch in ("x86_64", "aarch64"):
            with self.subTest(arch=arch):
                base = "https://github.com/smikme/meshapp/releases/download/v2.4.0"
                for extension in ("deb", "AppImage", "flatpak"):
                    self.assertEqual(f"{base}/MeshApp-v2.4.0-linux-{arch}.{extension}",
                                     manifest["downloads"][f"linux-{extension.lower()}-{arch}"])
                artifact = manifest["selfUpdate"][f"linux-{arch}"]
                self.assertEqual(f"{base}/MeshApp-v2.4.0-linux-{arch}-selfupdate.zip", artifact["url"])
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

    def test_catalog_build_publishes_repository_scripts_for_both_languages(self):
        spec = importlib.util.spec_from_file_location("pages", Path(__file__).resolve().parents[1] / "build-pages.py")
        pages = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pages)
        source, output = self.root / "site", self.root / "pages"
        source.mkdir()
        (source / "index.html").write_text("MeshApp")
        for language, name in (("ru", "Пинг-бот"), ("en", "Ping Bot")):
            directory = self.root / "meshappstore" / language
            directory.mkdir(parents=True)
            script = directory / "PingBot.meshapp-script.json"
            script.write_text(json.dumps(dict(
                format="meshapp-lua-script", version=1,
                guid="96c3f915-87c6-435e-a6c5-c7ffe6f94d1b", name=name,
                codeLines=["mesh.log('ping')"],
            ), ensure_ascii=False), encoding="utf-8")
        pages.build(source, output, "https://smikme.github.io/meshapp/")

        for language in ("ru", "en"):
            script = self.root / "meshappstore" / language / "PingBot.meshapp-script.json"
            target = output / "store" / language
            self.assertEqual(script.read_bytes(), (target / script.name).read_bytes())
            self.assertEqual([dict(
                name=script.name, type="file",
                download_url=f"https://smikme.github.io/meshapp/store/{language}/{script.name}",
            )], json.loads((target / "catalog.json").read_text()))

    def test_catalog_build_keeps_signed_feed_and_does_not_invent_scripts(self):
        spec = importlib.util.spec_from_file_location("pages", Path(__file__).resolve().parents[1] / "build-pages.py")
        pages = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pages)
        source, output = self.root / "site", self.root / "pages"
        for language in ("ru", "en"):
            (self.root / "meshappstore" / language).mkdir(parents=True)
        source.mkdir()
        (source / "index.html").write_text("MeshApp")
        manifest = self.root / "feed.json"
        manifest.write_text('{"signed": "unchanged"}')
        pages.build(source, output, "https://smikme.github.io/meshapp", manifest)
        self.assertEqual(manifest.read_bytes(), (output / "updates/stable/meshapp.json").read_bytes())
        self.assertEqual([], json.loads((output / "store/ru/catalog.json").read_text()))
        feeds = self.root / "feeds"
        feeds.mkdir()
        (feeds / "stable.json").write_bytes(manifest.read_bytes())
        unstable = self.root / "unstable.json"
        unstable.write_text('{"signed": "unstable"}')
        pages.build(source, output, "https://smikme.github.io/meshapp", unstable, "unstable", feeds)
        self.assertEqual(manifest.read_bytes(), (output / "updates/stable/meshapp.json").read_bytes())
        self.assertEqual(unstable.read_bytes(), (output / "updates/unstable/meshapp.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
