#!/usr/bin/env bash
# Render the SurgeMedia macOS .icns from the authored SVG artwork.
#
# Every icon size is rendered natively (Chrome rasterises the SVG at the
# exact target pixel size) so the neon bloom is not destroyed by a
# downscale. The macOS squircle is applied per size using Apple's grid:
# the shape occupies 824/1024 of the canvas, centred.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
ART_SVG="$HERE/out/surge-wave-art.svg"
BG_SVG="$HERE/out/surge-wave-bg.svg"
ICONSET_DIR="${1:-$HERE/out/SurgeMedia.iconset}"
ICNS_OUT="${2:-$HERE/out/SurgeMedia.icns}"

GRID_RATIO=824        # squircle edge within the 1024 canvas
ART_FRACTION=92       # artwork width as a percentage of the squircle

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

render_svg() {   # render_svg <svg> <size> <out.png>
  local svg="$1" size="$2" out="$3" html="$tmp/page.html"
  cat > "$html" <<EOF
<html><body style="margin:0;padding:0;background:transparent">
<img src="file://$svg" width="$size" height="$size" style="display:block">
</body></html>
EOF
  "$CHROME" --headless --disable-gpu --no-sandbox --hide-scrollbars \
    --default-background-color=00000000 --window-size="${size},${size}" \
    --screenshot="$out" "file://$html" >/dev/null 2>&1
}

squircle_mask() {  # squircle_mask <size> <out.png>
  local size="$1" out="$2"
  python3 shapes.py squircle --size "$size" > "$tmp/d.txt"
  {
    printf '<svg xmlns="http://www.w3.org/2000/svg" width="%s" height="%s" viewBox="0 0 %s %s">' \
      "$size" "$size" "$size" "$size"
    printf '<path d="'
    cat "$tmp/d.txt"
    printf '" fill="#ffffff"/></svg>'
  } > "$tmp/sq.svg"
  magick -background none "$tmp/sq.svg" "$out"
}

build_icon() {    # build_icon <canvas-size> <out.png>
  local size="$1" out="$2"
  local sq art
  sq=$(( size * GRID_RATIO / 1024 ))
  art=$(( sq * ART_FRACTION / 100 ))

  render_svg "$BG_SVG" "$sq" "$tmp/bg.png"
  render_svg "$ART_SVG" "$art" "$tmp/art.png"
  squircle_mask "$sq" "$tmp/mask.png"

  magick "$tmp/bg.png" "$tmp/art.png" -gravity center -compose over -composite "$tmp/comb.png"
  magick "$tmp/comb.png" "$tmp/mask.png" -alpha off -compose CopyOpacity -composite "$tmp/shape.png"
  magick -size "${size}x${size}" xc:none "$tmp/shape.png" -gravity center \
    -compose over -composite "$out"
}

mkdir -p "$ICONSET_DIR"

# Apple icon set: every size, including the @2x variants.
set -- \
  "icon_16x16.png 16" \
  "icon_16x16@2x.png 32" \
  "icon_32x32.png 32" \
  "icon_32x32@2x.png 64" \
  "icon_128x128.png 128" \
  "icon_128x128@2x.png 256" \
  "icon_256x256.png 256" \
  "icon_256x256@2x.png 512" \
  "icon_512x512.png 512" \
  "icon_512x512@2x.png 1024"

for spec in "$@"; do
  name="${spec%% *}"
  size="${spec##* }"
  build_icon "$size" "$ICONSET_DIR/$name"
  printf "  %-22s %sx%s\n" "$name" "$size" "$size"
done

iconutil -c icns -o "$ICNS_OUT" "$ICONSET_DIR"
echo "wrote $ICNS_OUT"
identify -format "icns entries: %n\n" "$ICNS_OUT" 2>/dev/null || true
