#!/usr/bin/env bash
# Build platform-accurate icon mockups from the generated concepts.
#
#   ./mockups.sh [concept-name ...]      # default: all in concepts/
#
# Outputs, per concept, into mockups/<name>/:
#   mac-<size>.png          macOS squircle-masked icon at real dock sizes
#   android-<mode>.png      adaptive icon (circle / squircle / mono themed)
#
# Shapes come from shapes.py (superellipse squircle, circle, rounded rect).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

SRC_DIR="concepts"
OUT_DIR="mockups"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

BG="#0B0E17"      # CyberBackground
SPLIT=0.64       # central crop that frames the artwork

# --- shape helpers -------------------------------------------------------

svg_mask() {          # svg_mask <shape> <size> <outfile> [radius]
  local shape="$1" size="$2" out="$3" radius="${4:-0.2237}"
  local d
  d="$(python3 shapes.py "$shape" --size "$size" --radius "$radius")"
  {
    printf '<svg xmlns="http://www.w3.org/2000/svg" width="%s" height="%s" viewBox="0 0 %s %s">' \
      "$size" "$size" "$size" "$size"
    printf '<path d="%s" fill="#ffffff"/>' "$d"
    printf '</svg>'
  } > "$out.svg"
  magick -background none "$out.svg" "$out"
}

apply_mask() {       # apply_mask <image> <mask> <out>
  magick "$1" "$2" -alpha off -compose CopyOpacity -composite "$3"
}

# --- per-concept build ---------------------------------------------------

build_concept() {
  local name="$1"
  local src="$SRC_DIR/$name.jpg"
  [ -f "$src" ] || { echo "skip (missing): $src"; return; }

  local dir="$OUT_DIR/$name"
  mkdir -p "$dir"

  # Framed artwork: crop the central region so the art fills the icon
  # properly instead of floating in a large empty field.
  local side crop art
  side=$(magick identify -format '%w' "$src")
  crop=$(python3 -c "print(int($side*$SPLIT))")
  local off=$(( (side - crop) / 2 ))
  art="$WORK/$name-art.png"
  magick "$src" -crop "${crop}x${crop}+${off}+${off}" +repage "$art"

  # ---- macOS: squircle, art filling the shape ----
  # Apple grid: the squircle occupies 824/1024 of the canvas, so the shape
  # scales with the canvas and keeps its transparent margin at every size.
  local mac_side=824
  svg_mask squircle "$mac_side" "$WORK/sq-mac.png"
  magick "$art" -resize "${mac_side}x${mac_side}" "$WORK/mac-art.png"
  apply_mask "$WORK/mac-art.png" "$WORK/sq-mac.png" "$WORK/mac-shape.png"

  for s in 1024 512 256 128 64 32 16; do
    local shape_px=$(( s * 824 / 1024 ))
    magick "$WORK/mac-shape.png" -resize "${shape_px}x${shape_px}" "$WORK/mac-fit.png"
    magick -size "${s}x${s}" xc:none "$WORK/mac-fit.png" -gravity center -compose over -composite "$dir/mac-$s.png"
  done

  # ---- Android: adaptive layers ----
  # Foreground: neon art with luminance-derived alpha so it sits on the
  # flat brand background seamlessly (glows fade out correctly).
  local canvas=432 fg safe
  safe=$(python3 -c "print(int($canvas*0.61))")
  magick "$art" -resize "${safe}x${safe}" "$WORK/fg-color.png"
  magick "$WORK/fg-color.png" \( "$WORK/fg-color.png" -colorspace Gray +level-colors '0%,100%' \) \
    -alpha off -compose CopyOpacity -composite "$WORK/fg-alpha.png"

  # Background layer: flat brand colour
  magick -size "${canvas}x${canvas}" "xc:$BG" "$WORK/bg.png"
  magick "$WORK/bg.png" "$WORK/fg-alpha.png" -gravity center -compose over -composite "$WORK/adaptive.png"

  # Masks: circle, squircle, and the Android 13 themed (monochrome) variant
  svg_mask circle "$canvas" "$WORK/circ.png"
  svg_mask squircle "$canvas" "$WORK/sq-and.png"
  apply_mask "$WORK/adaptive.png" "$WORK/circ.png" "$dir/android-circle.png"
  apply_mask "$WORK/adaptive.png" "$WORK/sq-and.png" "$dir/android-squircle.png"

  # Monochrome (Android 13 themed icon): solid white silhouette whose alpha
  # comes from the artwork mask. The foreground is first centred on a
  # full-canvas layer, otherwise CopyOpacity leaves the uncovered part of
  # the white canvas opaque.
  magick -size "${canvas}x${canvas}" xc:none "$WORK/fg-alpha.png" \
    -gravity center -compose over -composite "$WORK/fg-canvas.png"
  magick "$WORK/fg-canvas.png" -alpha extract -threshold 22% -alpha off "$WORK/mono-mask.png"
  magick -size "${canvas}x${canvas}" xc:white "$WORK/mono-mask.png" \
    -compose CopyOpacity -composite "$dir/android-mono.png"

  # Small adaptive size for legibility checking
  magick "$WORK/adaptive.png" -resize 192x192 "$WORK/adaptive-192.png"
  svg_mask circle 192 "$WORK/circ192.png"
  apply_mask "$WORK/adaptive-192.png" "$WORK/circ192.png" "$dir/android-192.png"

  echo "built: $dir"
}

# --- main ----------------------------------------------------------------

mkdir -p "$OUT_DIR"
if [ "$#" -gt 0 ]; then
  targets=("$@")
else
  targets=()
  for f in "$SRC_DIR"/*.jpg; do
    targets+=("$(basename "$f" .jpg)")
  done
fi

for t in "${targets[@]}"; do
  build_concept "$t"
done

echo "done."
