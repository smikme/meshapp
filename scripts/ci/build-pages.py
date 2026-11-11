#!/usr/bin/env python3
"""Build the static documentation/store site without changing the stable update feed."""
import argparse
import json
import re
import shutil
from pathlib import Path


def build(source, output, base_url, manifest=None, channel="stable", feeds_dir=None):
    output.mkdir(parents=True, exist_ok=True)
    (output / ".nojekyll").touch()
    shutil.copyfile(source / "index.html", output / "index.html")
    for language in ("en", "ru"):
        target = output / "store" / language
        target.mkdir(parents=True, exist_ok=True)
        entries, ids = [], set()
        for script in sorted((source / "store" / language).glob("*.json")):
            if not re.fullmatch(r"[A-Za-z0-9_.-]+\.json", script.name):
                raise ValueError(f"Invalid catalog filename: {script.name}")
            data = json.loads(script.read_text(encoding="utf-8"))
            if data.get("format") != "meshapp-lua-script" or data.get("version") != 1 or not data.get("name") or not data.get("guid"):
                raise ValueError(f"Invalid script export: {script}")
            if data["guid"] in ids:
                raise ValueError(f"Duplicate script GUID: {script}")
            ids.add(data["guid"])
            shutil.copyfile(script, target / script.name)
            entries.append(dict(name=script.name, type="file", download_url=f"{base_url.rstrip('/')}/store/{language}/{script.name}"))
        (target / "catalog.json").write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if channel not in ("stable", "unstable"):
        raise ValueError("Unknown update channel")
    for name in ("stable", "unstable"):
        previous = feeds_dir / f"{name}.json" if feeds_dir else None
        feed = output / "updates" / name / "meshapp.json"
        if previous and previous.is_file():
            feed.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(previous, feed)
        else:
            feed.unlink(missing_ok=True)
    if manifest:
        feed = output / "updates" / channel / "meshapp.json"
        feed.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(manifest, feed)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--source", type=Path, default=Path("site"))
    p.add_argument("--output", type=Path, default=Path("build/pages"))
    p.add_argument("--base-url", default="https://smikme.github.io/meshapp")
    p.add_argument("--manifest", type=Path)
    p.add_argument("--channel", choices=("stable", "unstable"), default="stable")
    p.add_argument("--feeds-dir", type=Path)
    a = p.parse_args()
    build(a.source, a.output, a.base_url, a.manifest, a.channel, a.feeds_dir)
