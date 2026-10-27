#!/usr/bin/env bash
# Build the labelled review sheet: one row per concept, showing the macOS
# icon at several dock sizes plus the Android adaptive + themed variants.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

FONT="/System/Library/Fonts/Supplemental/Arial Bold.ttf"
CELL=160
LABEL_W=290
BG='#1A2038'
FRAME='#05070C'

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

concept_dir=mockups

label() {   # label <text> <file> <h> <w>
  magick -size "${4}x${3}" xc:none -font "$FONT" -pointsize 26 \
    -fill '#E8F4FD' -gravity west -annotate +8+0 "$1" "$2"
}

row() {
  local name="$1"
  local d="$concept_dir/$name"
  local cells=()
  local i=0
  for spec in "mac-256:128" "mac-64:64" "mac-32:32" "mac-16:16" "android-circle:128" "android-mono:128"; do
    local f="${spec%%:*}" sz="${spec##*:}"
    magick "$d/$f.png" -resize "${sz}x${sz}" -background "$BG" -gravity center \
      -extent "${CELL}x${CELL}" "$tmp/c$i.png"
    cells+=("$tmp/c$i.png")
    i=$((i + 1))
  done
  magick "${cells[@]}" +append "$tmp/icons-$name.png"
  label "$name" "$tmp/label-$name.png" "$CELL" "$LABEL_W"
  magick "$tmp/label-$name.png" "$tmp/icons-$name.png" +append "$tmp/row-$name.png"
}

for d in "$concept_dir"/*/; do
  row "$(basename "$d")"
done

# Column headers
hdr_cells=()
hi=0
for h in "macOS 256" "macOS 64" "macOS 32" "macOS 16" "Android circle" "Android themed"; do
  magick -size "${CELL}x44" xc:"$BG" -font "$FONT" -pointsize 19 -fill '#93A0C2' \
    -gravity center -annotate +0+0 "$h" "$tmp/h$hi.png"
  hdr_cells+=("$tmp/h$hi.png")
  hi=$((hi + 1))
done
magick "${hdr_cells[@]}" +append "$tmp/hdr.png"
label "concept" "$tmp/hdrl.png" 44 "$LABEL_W"
magick "$tmp/hdrl.png" "$tmp/hdr.png" +append "$tmp/hdrfull.png"

magick "$tmp/hdrfull.png" "$tmp"/row-*.png -append \
  -background "$FRAME" -bordercolor "$FRAME" -border 14 review-sheet.png

identify -format '%f %wx%h %b\n' review-sheet.png
