#!/usr/bin/env python3
"""GitHub operations for the build/publish workflows; never rebuild during promotion."""
import argparse
import json
import os
import subprocess
from pathlib import Path
from release import digest, version_info, verify_release


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
    _, code = version_info(tag)
    for release in releases(repo):
        if release["draft"] or release["prerelease"] or release["tag_name"] == tag:
            continue
        try:
            _, other = version_info(release["tag_name"])
        except ValueError:
            continue
        if other >= code:
            raise ValueError(f"Stable release {release['tag_name']} is not older than {tag}")


def preflight(a):
    tag, code = version_info(a.version)
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
        output.write(f"tag={tag}\ncommit={commit}\nversion_code={code}\n")


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
    if unstable:
        # Publish only after the complete verified matrix has uploaded. This path
        # has no signing secret or Pages permission and never changes Latest.
        gh("release", "edit", tag, "--repo", a.repository, "--draft=false", "--prerelease", "--latest=false")


def prepare_publish(a):
    tag, code = version_info(a.version)
    if "-unstable." in tag:
        raise ValueError("Unstable releases cannot be promoted through the stable workflow")
    default = gh("api", f"repos/{a.repository}", "--jq", ".default_branch")
    if os.environ.get("GITHUB_REF") != f"refs/heads/{default}":
        raise ValueError("Run publication from the default branch")
    selected = release_for(a.repository, tag)
    if not selected or selected["prerelease"]:
        raise ValueError("Select a draft or stable release produced by the new workflow")
    check_newer(a.repository, tag)
    a.source.mkdir(parents=True, exist_ok=True)
    gh("release", "download", tag, "--repo", a.repository, "--dir", str(a.source))
    metadata = verify_release(a.source, a.public_key)
    if metadata["repository"] != a.repository or metadata["version"] != tag:
        raise ValueError("Release repository/tag mismatch")
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
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"tag={tag}\ncommit={metadata['commit']}\nversion_code={code}\n")


def publish(a):
    tag, _ = version_info(a.version)
    if "-unstable." in tag:
        raise ValueError("Unstable releases cannot be promoted through the stable workflow")
    check_newer(a.repository, tag)
    selected = release_for(a.repository, tag)
    if not selected:
        raise ValueError("Release not found")
    if selected["draft"]:
        gh("release", "upload", tag, str(a.source / "meshapp.json"), "--repo", a.repository, "--clobber")
        gh("release", "edit", tag, "--repo", a.repository, "--draft=false", "--latest")
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
    p.add_argument("--public-key", type=Path, default=Path("config/update-public-key.pem"))
    p.add_argument("--commit")
    a = p.parse_args()
    {"preflight": preflight, "draft": draft, "prepare-publish": prepare_publish, "publish": publish}[a.command](a)
