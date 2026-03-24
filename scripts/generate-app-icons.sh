#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE_SVG="$ROOT_DIR/src/main/resources/logo/meshapp-official.svg"
LOGO_DIR="$ROOT_DIR/src/main/resources/logo"
MACOS_DIR="$ROOT_DIR/src/main/resources/macos"
DOCS_DIR="$ROOT_DIR/docs/logo"
LINUX_TRAY_DIR="$ROOT_DIR/src/main/resources/tray/linux"

require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required tool: $1" >&2
    exit 1
  fi
}

require_tool rsvg-convert
require_tool magick
require_tool sips

render_png_from() {
  local source_svg="$1"
  local size="$2"
  local output="$3"
  local raw_output="${output%.png}.raw.png"
  rsvg-convert \
    --keep-aspect-ratio \
    --width "$size" \
    --height "$size" \
    --output "$raw_output" \
    "$source_svg"
  sips -s format png "$raw_output" --out "$output" >/dev/null
  rm -f "$raw_output"
}

render_png() {
  local size="$1"
  local output="$2"
  render_png_from "$SOURCE_SVG" "$size" "$output"
}

render_linux_tray_png() {
  local size="$1"
  local output="$2"
  local inner_size=$(( size * 11 / 16 ))
  if [[ "$inner_size" -lt 1 ]]; then
    inner_size=1
  fi
  magick \
    "$LOGO_DIR/icon_512.png" \
    -alpha on \
    -type TrueColorAlpha \
    -filter Lanczos \
    -resize "${inner_size}x${inner_size}" \
    -background none \
    -gravity center \
    -extent "${size}x${size}" \
    "PNG32:$output"
}

render_png 16 "$LOGO_DIR/icon_16.png"
render_png 32 "$LOGO_DIR/icon_32.png"
render_png 64 "$LOGO_DIR/icon_64.png"
render_png 128 "$LOGO_DIR/icon_128.png"
render_png 256 "$LOGO_DIR/icon_256.png"
render_png 512 "$LOGO_DIR/icon_512.png"
render_png 1024 "$LOGO_DIR/icon_1024.png"
render_png 256 "$DOCS_DIR/MeshApp.png"

mkdir -p "$LINUX_TRAY_DIR"
for size in 16 20 22 24 32 48 64; do
  render_linux_tray_png "$size" "$LINUX_TRAY_DIR/icon_${size}.png"
done

tmp_dir="$(mktemp -d)"
python3 - <<'PY' "$LOGO_DIR/MeshApp.icns" "$LOGO_DIR"
import struct
import sys
from pathlib import Path

output = Path(sys.argv[1])
root = Path(sys.argv[2])
entries = [
    ("icp4", root / "icon_16.png"),
    ("icp5", root / "icon_32.png"),
    ("icp6", root / "icon_64.png"),
    ("ic07", root / "icon_128.png"),
    ("ic08", root / "icon_256.png"),
    ("ic09", root / "icon_512.png"),
    ("ic10", root / "icon_1024.png"),
]

body = bytearray()
for code, path in entries:
    data = path.read_bytes()
    body += code.encode("ascii")
    body += struct.pack(">I", len(data) + 8)
    body += data

output.write_bytes(b"icns" + struct.pack(">I", len(body) + 8) + body)
PY
cp "$LOGO_DIR/MeshApp.icns" "$MACOS_DIR/MeshApp.icns"

icon_48="$tmp_dir/icon_48.png"
render_png 48 "$icon_48"
magick \
  "$LOGO_DIR/icon_16.png" \
  "$LOGO_DIR/icon_32.png" \
  "$icon_48" \
  "$LOGO_DIR/icon_64.png" \
  "$LOGO_DIR/icon_128.png" \
  "$LOGO_DIR/icon_256.png" \
  "$LOGO_DIR/MeshApp.ico"
