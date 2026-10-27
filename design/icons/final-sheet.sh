#!/usr/bin/env bash
# Final verification sheet for the SurgeMedia icon.
#
# Every row is one append of equal-sized 150x150 cells plus a 250x150 label,
# so all rows are exactly 1000px wide.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

FONT="/System/Library/Fonts/Supplemental/Arial Bold.ttf"
CELL=150
LABEL_W=250
DARK='#131729'
LIGHT='#EDEFF5'
FRAME='#05070C'

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

ICNS_DIR="out/SurgeMedia.iconset"
AND_DIR="out/android"

declare -a CELLS=()
n=0

cell() {   # cell <image> <size> <bg>
  n=$((n + 1))
  local f="$tmp/cell-$n.png"
  magick "$1" -resize "$2x$2" -background "$3" -gravity center \
    -extent "${CELL}x${CELL}" "$f"
  CELLS+=("$f")
}

caption() {  # caption <text>
  n=$((n + 1))
  local f="$tmp/cell-$n.png"
  magick -size "${CELL}x${CELL}" xc:"$DARK" -font "$FONT" -pointsize 16 -fill '#93A0C2' \
    -gravity north -annotate +0+8 "$1" "$f"
  CELLS+=("$f")
}

pad() {      # pad  (empty dark cell)
  n=$((n + 1))
  local f="$tmp/cell-$n.png"
  magick -size "${CELL}x${CELL}" xc:"$DARK" "$f"
  CELLS+=("$f")
}

row() {      # row <out>
  local out="$1"
  magick -size "${LABEL_W}x${CELL}" xc:"$DARK" -font "$FONT" -pointsize 24 \
    -fill '#E8F4FD' -gravity west -annotate +12+0 "${ROW_LABEL:-}" "$tmp/rowlabel.png"
  magick "$tmp/rowlabel.png" ${CELLS[@]+"${CELLS[@]}"} +append \
    -background "$FRAME" "$out"
  CELLS=()
}

row_with_label() {  # row_with_label <label> <label-bg> <out>
  local text_fill='#E8F4FD'
  if [ "$2" = "$LIGHT" ]; then text_fill='#131729'; fi
  magick -size "${LABEL_W}x${CELL}" xc:"$2" -font "$FONT" -pointsize 24 \
    -fill "$text_fill" -gravity west -annotate +12+0 "$1" "$tmp/rowlabel.png"
  magick "$tmp/rowlabel.png" ${CELLS[@]+"${CELLS[@]}"} +append \
    -background "$FRAME" "$3"
  CELLS=()
}

# ---- macOS rows ---------------------------------------------------------
mac_row() {  # mac_row <bg> <label> <out>
  cell "$ICNS_DIR/icon_512x512.png" 112 "$1"
  cell "$ICNS_DIR/icon_128x128.png" 80 "$1"
  cell "$ICNS_DIR/icon_32x32@2x.png" 56 "$1"
  cell "$ICNS_DIR/icon_32x32.png" 32 "$1"
  cell "$ICNS_DIR/icon_16x16.png" 16 "$1"
  row_with_label "$2" "$1" "$3"
}
mac_row "$DARK" "macOS (dark)" "$tmp/mrow-d.png"
mac_row "$LIGHT" "macOS (light)" "$tmp/mrow-l.png"

# ---- Android row --------------------------------------------------------
cell "$AND_DIR/adaptive-circle.png" 112 "$DARK"
cell "$AND_DIR/adaptive-squircle.png" 112 "$DARK"
cell "$AND_DIR/themed-circle.png" 112 "$DARK"
cell "$AND_DIR/adaptive-safezone.png" 112 "$DARK"
cell "$AND_DIR/adaptive-48.png" 48 "$DARK"
row_with_label "Android" "$DARK" "$tmp/arow.png"

# ---- previous icon, rendered from the old VectorDrawable paths -----------
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
cat > "$tmp/prev.svg" <<'SVGEOF'
<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
  <rect width="108" height="108" fill="#0B0E17"/>
  <path d="M38,32 L38,76 L76,54 Z" fill="#00E5FF"/>
  <path d="M57,36 L45,57 L53,57 L49,74 L67,50 L58,50 Z" fill="#FFFFFF"/>
</svg>
SVGEOF
cat > "$tmp/prev.html" <<HTMLEOF
<html><body style="margin:0;padding:0;background:#0B0E17">
<img src="file://$tmp/prev.svg" width="220" height="220" style="display:block">
</body></html>
HTMLEOF
"$CHROME" --headless --disable-gpu --no-sandbox --hide-scrollbars \
  --window-size=220,220 --screenshot="$tmp/prev-android.png" "file://$tmp/prev.html" >/dev/null 2>&1
python3 shapes.py squircle --size 220 > "$tmp/d.txt"
{
  printf '<svg xmlns="http://www.w3.org/2000/svg" width="220" height="220" viewBox="0 0 220 220"><path d="'
  cat "$tmp/d.txt"
  printf '" fill="#ffffff"/></svg>'
} > "$tmp/sq.svg"
magick -background none "$tmp/sq.svg" "$tmp/mask220.png"
magick "$tmp/prev-android.png" \( "$tmp/mask220.png" -alpha off \) \
  -compose CopyOpacity -composite "$tmp/prev-mac.png"

cell "$tmp/prev-mac.png" 112 "$DARK"
cell "$tmp/prev-android.png" 112 "$DARK"
pad; pad; pad
row_with_label "previous icon" "$DARK" "$tmp/prow.png"

# ---- caption rows -------------------------------------------------------
caption "macOS 512"; caption "macOS 128"; caption "macOS 64"; caption "macOS 32"; caption "macOS 16"
row_with_label "" "$DARK" "$tmp/caps1.png"
caption "adaptive circle"; caption "adaptive squircle"; caption "themed (A13)"; caption "safe zone 66dp"; caption "48px launcher"
row_with_label "" "$DARK" "$tmp/caps2.png"
caption "macOS before"; caption "Android before"; caption ""; caption ""; caption ""
row_with_label "" "$DARK" "$tmp/caps3.png"

# ---- assemble -----------------------------------------------------------
magick "$tmp/caps1.png" "$tmp/mrow-d.png" "$tmp/caps1.png" "$tmp/mrow-l.png" \
       "$tmp/caps2.png" "$tmp/arow.png" "$tmp/caps3.png" "$tmp/prow.png" \
       -append -background "$FRAME" -bordercolor "$FRAME" -border 14 final-sheet.png

identify -format '%f %wx%h %b\n' final-sheet.png
