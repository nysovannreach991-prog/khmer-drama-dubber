#!/bin/bash
# Render an HTML design to an exact-size PNG with headless Chrome.
#   render.sh page.html WIDTH HEIGHT out.png [transparent]
# Headless Chrome's page area is smaller than its window, so render big and crop.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
CH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
TMP="$(mktemp -d)"
BG=(); [ "$5" = transparent ] && BG=(--default-background-color=00000000)
WW=$(( $2 > 500 ? $2 + 40 : 540 )); WH=$(( $3 + 200 ))
"$CH" --headless=new --disable-gpu --hide-scrollbars --no-first-run --force-device-scale-factor=1 \
  --user-data-dir="$TMP/prof" "${BG[@]}" --window-size=$WW,$WH --screenshot="$TMP/full.png" \
  "file://$HERE/$1" >/dev/null 2>&1
ffmpeg -v error -y -i "$TMP/full.png" -vf "crop=$2:$3:0:0" -frames:v 1 "$4"
rm -rf "$TMP"
