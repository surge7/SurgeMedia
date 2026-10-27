#!/usr/bin/env bash
# Render Android adaptive-icon previews from the authored 108dp SVG.
#
# surge-wave-android.svg carries the exact VectorDrawable coordinates, so
# these previews are faithful rather than approximate. The launcher masks a
# 72dp circle and guarantees a 66dp safe zone; both are drawn as overlays.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
FG_SVG="$HERE/out/surge-wave-android.svg"
BG_SVG="$HERE/out/surge-wave-android-bg.svg"
MONO_SVG="$HERE/out/surge-wave-mono.svg"
OUT_DIR="${1:-$HERE/out/android}"

PX=432          # 108dp at 4x
MASK=288        # 72dp mask at 4x
SAFE=264        # 66dp safe zone at 4x

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

shape_mask() {   # shape_mask <squircle|circle> <canvas> <shape-size> <out.png>
  local shape="$1" canvas="$2" size="$3" out="$4"
  python3 shapes.py "$shape" --size "$size" > "$tmp/d.txt"
  {
    printf '<svg xmlns="http://www.w3.org/2000/svg" width="%s" height="%s" viewBox="0 0 %s %s">' \
      "$canvas" "$canvas" "$canvas" "$canvas"
    printf '<g transform="translate(%s,%s)"><path d="' \
      "$(( (canvas - size) / 2 ))" "$(( (canvas - size) / 2 ))"
    cat "$tmp/d.txt"
    printf '" fill="#ffffff"/></g></svg>'
  } > "$tmp/shape.svg"
  magick -background none "$tmp/shape.svg" "$out"
}

mkdir -p "$OUT_DIR"

# --- adaptive foreground over the gradient background --------------------
render_svg "$BG_SVG" "$PX" "$tmp/bg.png"
render_svg "$FG_SVG" "$PX" "$tmp/fg.png"
magick "$tmp/bg.png" "$tmp/fg.png" -compose over -composite "$tmp/adaptive.png"

shape_mask circle "$PX" "$MASK" "$tmp/circ.png"
shape_mask squircle "$PX" "$MASK" "$tmp/sq.png"
magick "$tmp/adaptive.png" "$tmp/circ.png" -alpha off -compose CopyOpacity -composite "$OUT_DIR/adaptive-circle.png"
magick "$tmp/adaptive.png" "$tmp/sq.png" -alpha off -compose CopyOpacity -composite "$OUT_DIR/adaptive-squircle.png"

# Safe-zone guide (66dp) drawn over the adaptive icon.
shape_mask circle "$PX" "$SAFE" "$tmp/safe.png"
magick "$tmp/adaptive.png" "$tmp/safe.png" -alpha off -compose CopyOpacity -composite "$tmp/with-safe.png"
magick "$tmp/with-safe.png" -stroke '#F5D90A' -strokewidth 2 -fill none \
  -draw "circle $((PX/2)),$((PX/2)) $((PX/2)),$((PX/2-$SAFE/2))" "$OUT_DIR/adaptive-safezone.png"

# --- Android 13 themed (monochrome) variant ------------------------------
# The themed layer must keep the wave silhouette *and* respect the launcher
# mask. CopyOpacity replaces the destination alpha outright, so the two
# masks are multiplied first and the result applied once.
render_svg "$MONO_SVG" "$PX" "$tmp/mono.png"
magick "$tmp/mono.png" -alpha extract -threshold 22% -alpha off "$tmp/mono-mask.png"
magick "$tmp/mono-mask.png" \( "$tmp/circ.png" -alpha off \) \
  -compose Multiply -composite "$tmp/combined-mask.png"
magick -size "${PX}x${PX}" xc:white "$tmp/combined-mask.png" \
  -compose CopyOpacity -composite "$OUT_DIR/themed-circle.png"

# --- small sizes ---------------------------------------------------------
for s in 192 96 48; do
  magick "$tmp/adaptive.png" -resize "${s}x${s}" "$tmp/a.png"
  shape_mask circle "$s" "$(( s * MASK / PX ))" "$tmp/c.png"
  magick "$tmp/a.png" "$tmp/c.png" -alpha off -compose CopyOpacity -composite "$OUT_DIR/adaptive-${s}.png"
done

echo "wrote previews to $OUT_DIR"
ls -1 "$OUT_DIR"
