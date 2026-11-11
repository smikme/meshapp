#!/usr/bin/env python3
"""GitHub operations for the build/publish workflows; never rebuild during promotion."""
import argparse
import json
import os
import subprocess
import tempfile
from pathlib import Path
from release import digest, version_info, verify_release
from versioning import ReleaseVersion, with_build_number


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def gh(*args):
    return run("gh", *args)


def releases(repo):
    # --slurp keeps each REST page distinct; flatten after pagination.
    return [r for page in json.loads(gh("api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100")) for r in page]


def release_for(repo, tag):
    return next((r for r in releases(repo) if r["tag_name"] == tag), None)


def check_newer(repo, tag):
    target = ReleaseVersion.parse(tag)
    for release in releases(repo):
        if release["draft"] or release["tag_name"] == tag:
            continue
        try:
            other = ReleaseVersion.parse(release["tag_name"])
        except ValueError:
            continue
        if (target.channel == "unstable" or other.channel == "stable") and other.key >= target.key:
            raise ValueError(f"Published release {release['tag_name']} is not older than {tag}")
        if target.build and other.build >= target.build:
            raise ValueError(f"Published build {other.build} is not older than {target.build}; start a new Build release run")


def preflight(a):
    version = with_build_number(a.version, int(os.environ["GITHUB_RUN_NUMBER"]))
    tag, code = version.tag, version.code
    default = gh("api", f"repos/{a.repository}", "--jq", ".default_branch")
    ref_name = os.environ.get("GITHUB_REF", "")
    if not ref_name.startswith("refs/heads/"):
        raise ValueError("Build releases from a branch")
    if "-unstable." not in tag and ref_name != f"refs/heads/{default}":
        raise ValueError("Run this workflow from the default branch")
    commit = run("git", "rev-parse", "HEAD")
    existing = release_for(a.repository, tag)
    if existing and not existing["draft"]:
        raise ValueError("Published releases cannot be rebuilt")
    ref = subprocess.run(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"], capture_output=True, text=True)
    if ref.returncode == 0 and ref.stdout.strip() != commit:
        raise ValueError("The version tag already refers to a different commit")
    check_newer(a.repository, tag)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"tag={tag}\ncommit={commit}\nversion_code={code}\nbuild_number={version.build}\nchannel={version.channel}\n")


def draft(a):
    tag, _ = version_info(a.version)
    unstable = "-unstable." in tag
    metadata = verify_release(a.source, a.public_key)
    if metadata["version"] != tag or metadata["commit"] != a.commit or metadata["repository"] != a.repository:
        raise ValueError("Release metadata differs from the selected build")
    existing = release_for(a.repository, tag)
    if existing and not existing["draft"]:
        raise ValueError("Published releases cannot be overwritten")
    if existing and existing["prerelease"] != unstable:
        raise ValueError("Existing draft has a different release channel")
    ref = subprocess.run(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"], capture_output=True, text=True)
    if ref.returncode == 0:
        if ref.stdout.strip() != a.commit:
            raise ValueError("Refusing to move an existing tag")
    else:
        subprocess.run(["git", "tag", tag, a.commit], check=True)
        subprocess.run(["git", "push", "origin", f"refs/tags/{tag}"], check=True)
    if not existing:
        options = ["--prerelease", "--latest=false"] if unstable else []
        gh("release", "create", tag, "--repo", a.repository, "--verify-tag", "--draft", "--title", f"MeshApp {tag}", "--generate-notes", *options)
    files = sorted(str(p) for p in a.source.iterdir() if p.is_file())
    gh("release", "upload", tag, "--repo", a.repository, "--clobber", *files)
    # Both channels stay draft until the trusted publication workflow signs them.


def preserve_feeds(repo, directory):
    """Recover both last signed manifests so a Pages deploy never drops the other channel."""
    directory.mkdir(parents=True, exist_ok=True)
    newest = {}
    for item in releases(repo):
        if item["draft"] or not any(a["name"] == "meshapp.json" for a in item.get("assets", [])):
            continue
        try:
            version = ReleaseVersion.parse(item["tag_name"])
        except ValueError:
            continue
        if version.channel not in newest or version.key > newest[version.channel].key:
            newest[version.channel] = version
    for channel in ("stable", "unstable"):
        target = directory / f"{channel}.json"
        target.unlink(missing_ok=True)
        if channel not in newest:
            continue
        version = newest[channel]
        with tempfile.TemporaryDirectory() as tmp:
            gh("release", "download", version.tag, "--repo", repo, "--pattern", "meshapp.json", "--dir", tmp)
            source = Path(tmp) / "meshapp.json"
            data = json.loads(source.read_text(encoding="utf-8"))
            if data["version"] != version.tag or data["versionCode"] != version.code:
                raise ValueError("Published feed identity does not match its release")
            target.write_bytes(source.read_bytes())


def prepare_publish(a):
    tag, code = version_info(a.version)
    version = ReleaseVersion.parse(tag)
    default = gh("api", f"repos/{a.repository}", "--jq", ".default_branch")
    if os.environ.get("GITHUB_REF") != f"refs/heads/{default}":
        raise ValueError("Run publication from the default branch")
    selected = release_for(a.repository, tag)
    if not selected or selected["prerelease"] != (version.channel == "unstable"):
        raise ValueError("Select a release matching the requested channel")
    check_newer(a.repository, tag)
    a.source.mkdir(parents=True, exist_ok=True)
    gh("release", "download", tag, "--repo", a.repository, "--dir", str(a.source))
    metadata = verify_release(a.source, a.public_key)
    if metadata["repository"] != a.repository or metadata["version"] != tag:
        raise ValueError("Release repository/tag mismatch")
    if os.environ.get("EXPECTED_RELEASE_COMMIT") and metadata["commit"] != os.environ["EXPECTED_RELEASE_COMMIT"]:
        raise ValueError("Release does not match the completed build commit")
    if run("git", "rev-parse", f"refs/tags/{tag}^{{commit}}") != metadata["commit"]:
        raise ValueError("Release tag no longer matches the built commit")
    allowed = {x["name"] for x in metadata["artifacts"]} | {"release.json", "SHA256SUMS", "meshapp.json"}
    if {p.name for p in a.source.iterdir()} - allowed:
        raise ValueError("Unexpected release assets; review the draft before publication")
    sums = dict(line.split("  ", 1)[::-1] for line in (a.source / "SHA256SUMS").read_text().splitlines())
    for name in allowed - {"SHA256SUMS", "meshapp.json"}:
        if sums.get(name) != digest(a.source / name):
            raise ValueError(f"Checksum mismatch: {name}")
    (a.source / "notes.md").write_text(selected.get("body") or f"MeshApp {tag}", encoding="utf-8")
    preserve_feeds(a.repository, a.feeds_dir)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"tag={tag}\ncommit={metadata['commit']}\nversion_code={code}\nchannel={version.channel}\n")


def publish(a):
    tag, _ = version_info(a.version)
    unstable = ReleaseVersion.parse(tag).channel == "unstable"
    check_newer(a.repository, tag)
    selected = release_for(a.repository, tag)
    if not selected or selected["prerelease"] != unstable:
        raise ValueError("Release not found")
    if selected["draft"]:
        gh("release", "upload", tag, str(a.source / "meshapp.json"), "--repo", a.repository, "--clobber")
        options = ["--prerelease", "--latest=false"] if unstable else ["--latest"]
        gh("release", "edit", tag, "--repo", a.repository, "--draft=false", *options)
    else:
        # A Pages deploy can be retried after publication, including with immutable releases.
        original = a.source / "published-manifest"
        original.mkdir()
        gh("release", "download", tag, "--repo", a.repository, "--pattern", "meshapp.json", "--dir", str(original))
        if (original / "meshapp.json").read_bytes() != (a.source / "meshapp.json").read_bytes():
            raise ValueError("Published update metadata is immutable; cannot change release notes or signatures")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=("preflight", "draft", "prepare-publish", "publish"))
    p.add_argument("--version", required=True)
    p.add_argument("--repository", required=True)
    p.add_argument("--source", type=Path, default=Path("build/release"))
    p.add_argument("--feeds-dir", type=Path, default=Path("build/update-feeds"))
    p.add_argument("--public-key", type=Path, default=Path("config/update-public-key.pem"))
    p.add_argument("--commit")
    a = p.parse_args()
    {"preflight": preflight, "draft": draft, "prepare-publish": prepare_publish, "publish": publish}[a.command](a)
