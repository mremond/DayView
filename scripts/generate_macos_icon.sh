#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE_SVG="${1:-$ROOT_DIR/artwork/dayview-icon-reference.svg}"
OUTPUT_ICNS="${2:-$ROOT_DIR/artwork/dayview.icns}"
ICONSET_DIR="$(mktemp -d)/dayview.iconset"

cleanup() {
  rm -rf "$(dirname "$ICONSET_DIR")"
}
trap cleanup EXIT

if ! command -v rsvg-convert >/dev/null 2>&1; then
  echo "rsvg-convert is required (brew install librsvg)." >&2
  exit 1
fi

mkdir -p "$ICONSET_DIR" "$(dirname "$OUTPUT_ICNS")"

render() {
  local pixels="$1"
  local filename="$2"
  rsvg-convert -w "$pixels" -h "$pixels" "$SOURCE_SVG" -o "$ICONSET_DIR/$filename"
}

render 16 icon_16x16.png
render 32 icon_16x16@2x.png
render 32 icon_32x32.png
render 64 icon_32x32@2x.png
render 128 icon_128x128.png
render 256 icon_128x128@2x.png
render 256 icon_256x256.png
render 512 icon_256x256@2x.png
render 512 icon_512x512.png
render 1024 icon_512x512@2x.png

if ! iconutil -c icns -o "$OUTPUT_ICNS" "$ICONSET_DIR"; then
  echo "iconutil rejected the valid iconset; trying the Pillow fallback." >&2
  python3 - "$ICONSET_DIR" "$OUTPUT_ICNS" <<'PY'
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError as error:
    raise SystemExit(
        "iconutil failed and the Pillow fallback is unavailable "
        "(python3 -m pip install Pillow)."
    ) from error

iconset = Path(sys.argv[1])
output = Path(sys.argv[2])
files = [
    "icon_16x16@2x.png",
    "icon_32x32@2x.png",
    "icon_128x128.png",
    "icon_128x128@2x.png",
    "icon_256x256@2x.png",
    "icon_512x512.png",
]
images = [Image.open(iconset / filename) for filename in files]
master = Image.open(iconset / "icon_512x512@2x.png")
master.save(output, append_images=images)
PY
fi
xattr -c "$OUTPUT_ICNS" 2>/dev/null || true
echo "Generated $OUTPUT_ICNS"
