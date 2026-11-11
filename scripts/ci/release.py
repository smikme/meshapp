#!/usr/bin/env python3
"""Release contracts shared by GitHub Actions and local verification (stdlib only)."""
import argparse
import base64
import hashlib
import io
import json
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path
from versioning import ReleaseVersion, package_version

PLATFORMS = {
    "linux-x86_64": ("deb", "AppImage", "flatpak"),
    "windows-x86_64": ("msi",),
    "macos-aarch64": ("dmg",),
    "macos-x86_64": ("dmg",),
}


def version_info(value):
    version = ReleaseVersion.parse(value)
    return version.tag, version.code


def version_metadata(tag):
    version = ReleaseVersion.parse(tag)
    return dict(releaseVersion=version.base, channel=version.channel, buildNumber=version.build,
                packageVersion=package_version(version.build) if version.build else version.base)


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def public_der(path):
    # Parsing SPKI avoids depending on Apple's LibreSSL for build-only jobs.
    text = re.sub(r"-----[^\n]+-----|\s", "", path.read_text(encoding="utf-8"))
    value = base64.b64decode(text, validate=True)
    if len(value) != 44 or not value.startswith(bytes.fromhex("302a300506032b6570032100")):
        raise ValueError("Expected an Ed25519 X.509 public key")
    return value


def verify_payload(path, tag, code, public_key, require_build=False):
    """Check the trust root and version actually embedded in the distributed JAR."""
    with zipfile.ZipFile(path) as payload:
        candidates = [n for n in payload.namelist() if re.fullmatch(r"lib/MeshApp-.+\.jar", n)]
        if len(candidates) != 1:
            raise ValueError(f"{path.name}: expected one MeshApp JAR")
        with zipfile.ZipFile(io.BytesIO(payload.read(candidates[0]))) as app:
            props = dict(line.split("=", 1) for line in app.read("version.properties").decode().splitlines() if "=" in line)
            if props.get("version") != tag or props.get("versionCode") != str(code):
                raise ValueError(f"{path.name}: embedded version does not match the release")
            if require_build and any(props.get(k) != str(v) for k, v in version_metadata(tag).items()):
                raise ValueError(f"{path.name}: embedded build metadata does not match the release")
            key = app.read("update/ed25519-public-key.txt").decode()
            key = re.sub(r"-----[^\n]+-----|\s", "", key)
            if base64.b64decode(key, validate=True) != public_der(public_key):
                raise ValueError(f"{path.name}: embedded update key does not match")


def artifact_name(tag, platform, extension):
    # Preserve the established self-update suffix used by the Java updater.
    if extension == "selfupdate.zip":
        return f"MeshApp-{tag}-{platform}-selfupdate.zip"
    return f"MeshApp-{tag}-{platform}.{extension}"


def inventory(tag):
    return {artifact_name(tag, p, f): (p, f) for p, formats in PLATFORMS.items() for f in (*formats, "selfupdate.zip")}


def collect(args):
    tag, code = version_info(args.version)
    args.output.mkdir(parents=True, exist_ok=True)
    for extension in (*PLATFORMS[args.platform], "selfupdate.zip"):
        pattern = "*-selfupdate.zip" if extension == "selfupdate.zip" else f"*.{extension}"
        files = list(args.source.glob(pattern))
        if len(files) != 1:
            raise ValueError(f"Expected exactly one {pattern}, found {len(files)}")
        if extension == "selfupdate.zip":
            verify_payload(files[0], tag, code, args.public_key, require_build=ReleaseVersion.parse(tag).build > 0)
        shutil.copyfile(files[0], args.output / artifact_name(tag, args.platform, extension))


def finalize(args):
    tag, code = version_info(args.version)
    if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        raise ValueError("Expected a full commit SHA")
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repository):
        raise ValueError("Invalid repository")
    expected = inventory(tag)
    actual = {p.name for p in args.source.iterdir() if p.is_file()}
    if actual != set(expected):
        raise ValueError(f"Incomplete release: missing={set(expected) - actual}, unexpected={actual - set(expected)}")
    artifacts = []
    for name, (platform, extension) in sorted(expected.items()):
        path = args.source / name
        if extension == "selfupdate.zip":
            verify_payload(path, tag, code, args.public_key, require_build=ReleaseVersion.parse(tag).build > 0)
        if path.stat().st_size == 0:
            raise ValueError(f"Empty artifact: {name}")
        artifacts.append(dict(name=name, platform=platform, format=extension,
                              size=path.stat().st_size, sha256=digest(path)))
    write_json(args.source / "release.json", dict(schema=2, version=tag, versionCode=code, **version_metadata(tag),
               commit=args.commit, repository=args.repository,
               publicKeySha256=hashlib.sha256(public_der(args.public_key)).hexdigest(), artifacts=artifacts))
    files = sorted([args.source / n for n in expected] + [args.source / "release.json"])
    (args.source / "SHA256SUMS").write_text("".join(f"{digest(p)}  {p.name}\n" for p in files), encoding="utf-8")


def verify_release(directory, public_key):
    metadata = json.loads((directory / "release.json").read_text(encoding="utf-8"))
    tag, code = version_info(metadata["version"])
    if metadata.get("schema") not in (1, 2) or metadata["versionCode"] != code:
        raise ValueError("Invalid release metadata/version code")
    if metadata["schema"] == 2 and any(metadata.get(k) != v for k, v in version_metadata(tag).items()):
        raise ValueError("Invalid release build metadata")
    if metadata["publicKeySha256"] != hashlib.sha256(public_der(public_key)).hexdigest():
        raise ValueError("Release was built with a different update key")
    expected = inventory(tag)
    assets = metadata["artifacts"]
    if len(assets) != len(expected) or {a["name"] for a in assets} != set(expected):
        raise ValueError("Release artifact inventory is incomplete or duplicated")
    for a in assets:
        if (a["platform"], a["format"]) != expected[a["name"]]:
            raise ValueError("Artifact platform/format mismatch")
        path = directory / a["name"]
        if path.is_symlink() or path.stat().st_size != a["size"] or digest(path) != a["sha256"]:
            raise ValueError(f"Artifact changed after build: {a['name']}")
        if a["format"] == "selfupdate.zip":
            verify_payload(path, tag, code, public_key, require_build=metadata["schema"] == 2 and ReleaseVersion.parse(tag).build > 0)
    return metadata


def manifest(args):
    channel = getattr(args, "channel", "stable")
    if ReleaseVersion.parse(args.version).channel != channel:
        raise ValueError(f"Release does not belong to the {channel} update feed")
    metadata = verify_release(args.source, args.public_key)
    if metadata["version"] != version_info(args.version)[0] or metadata["repository"] != args.repository:
        raise ValueError("Release tag/repository does not match the requested publication")
    derived = subprocess.check_output(["openssl", "pkey", "-in", str(args.private_key), "-pubout", "-outform", "DER"])
    if derived != public_der(args.public_key):
        raise ValueError("Signing secret does not match the public key embedded in the release")
    tag = metadata["version"]
    base = f"https://github.com/{args.repository}/releases/download/{tag}"
    result = dict(version=tag, versionCode=metadata["versionCode"],
                  **(version_metadata(tag) if metadata["schema"] == 2 else {}),
                  releaseNotes=args.notes.read_text(encoding="utf-8"),
                  releaseNotes_ru=args.notes_ru.read_text(encoding="utf-8") if args.notes_ru else "",
                  downloads={}, selfUpdate={})
    for a in metadata["artifacts"]:
        url = f"{base}/{a['name']}"
        if a["format"] == "selfupdate.zip":
            payload = (f"meshapp-self-update-v1\n{tag}\n{metadata['versionCode']}\nfull-archive\nzip\n"
                       f"{tag}\n{url}\n{a['sha256']}\n{a['size']}\n").encode()
            with tempfile.TemporaryDirectory() as tmp:
                body, signature = Path(tmp) / "body", Path(tmp) / "signature"
                body.write_bytes(payload)
                subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(args.private_key),
                                "-in", str(body), "-out", str(signature)], check=True)
                subprocess.run(["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(args.public_key),
                                "-in", str(body), "-sigfile", str(signature)], check=True, stdout=subprocess.DEVNULL)
                result["selfUpdate"][a["platform"]] = dict(type="full-archive", format="zip", version=tag,
                    url=url, sha256=a["sha256"], size=a["size"], signature=base64.b64encode(signature.read_bytes()).decode())
        else:
            os_name, arch = a["platform"].split("-", 1)
            result["downloads"][f"{os_name}-{a['format'].lower()}-{arch}"] = url
    # Generic links lead to a platform selection page instead of guessing CPU architecture.
    for os_name in ("linux", "windows", "macos"):
        result["downloads"][os_name] = f"https://github.com/{args.repository}/releases/tag/{tag}"
    write_json(args.output, result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("collect", "finalize", "manifest"):
        p = commands.add_parser(name)
        p.add_argument("--version", required=True)
        p.add_argument("--source", type=Path, required=True)
        p.add_argument("--public-key", type=Path, default=Path("config/update-public-key.pem"))
        if name in ("collect", "manifest"):
            p.add_argument("--output", type=Path, required=True)
        if name == "collect":
            p.add_argument("--platform", choices=PLATFORMS, required=True)
        else:
            p.add_argument("--repository", required=True)
        if name == "finalize":
            p.add_argument("--commit", required=True)
        if name == "manifest":
            p.add_argument("--channel", choices=("stable", "unstable"), default="stable")
            p.add_argument("--private-key", type=Path, required=True)
            p.add_argument("--notes", type=Path, required=True)
            p.add_argument("--notes-ru", type=Path)
    args = parser.parse_args()
    {"collect": collect, "finalize": finalize, "manifest": manifest}[args.command](args)


if __name__ == "__main__":
    main()
