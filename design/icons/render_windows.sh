#!/usr/bin/env bash
# Render the SurgeMedia Windows .ico from the authored SVG artwork.
#
# Mirrors render_macos.sh — same source SVGs, same Apple squircle grid, every size
# rendered natively so the neon bloom is not destroyed by a downscale — so the Windows
# installer/exe icon cannot drift from the macOS one. Only the container differs: a
# multi-image .ico (16→256, 256 stored PNG-compressed) instead of .iconset/.icns.
#
# Runs on the macOS design machine (Chrome + ImageMagick, like render_macos.sh); the
# generated SurgeMedia.ico is committed, so CI never needs this.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
ART_SVG="$HERE/out/surge-wave-art.svg"
BG_SVG="$HERE/out/surge-wave-bg.svg"
ICO_OUT="${1:-$HERE/out/SurgeMedia.ico}"
APP_RES="$HERE/../../composeApp/src/desktopMain/resources/icons/SurgeMedia.ico"

GRID_RATIO=824        # squircle edge within the 1024 canvas
ART_FRACTION=92       # artwork width as a percentage of the squircle

# Windows .ico sizes: classic 16/32/48 plus the modern set up to 256.
SIZES=(16 24 32 48 64 128 256)

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

pngs=()
for size in "${SIZES[@]}"; do
  build_icon "$size" "$tmp/icon_${size}.png"
  pngs+=("$tmp/icon_${size}.png")
  printf "  icon_%sx%s.png\n" "$size" "$size"
done

# magick picks PNG compression for the 256 entry automatically; smaller sizes stay BMP.
magick "${pngs[@]}" "$ICO_OUT"

mkdir -p "$(dirname "$APP_RES")"
cp "$ICO_OUT" "$APP_RES"
echo "wrote $ICO_OUT"
echo "wrote $APP_RES"
identify "$APP_RES" 2>/dev/null | head -1 || true
