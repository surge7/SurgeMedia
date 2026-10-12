#!/usr/bin/env bash
# Saves live rutracker pages for parser verification. NEVER commit the output (git-ignored).
#
# rutracker.org sits behind a Cloudflare browser check (verified 2026-10-12): password
# login from curl/HTTP clients is challenge-gated. This script therefore works from an
# IMPORTED browser session: the raw Cookie header of a logged-in browser plus that
# browser's User-Agent (cf_clearance is bound to it).
#   ~/.config/surgemusic/cookies   line 1: raw Cookie header, line 2: User-Agent (optional)
#   or env: RUTRACKER_COOKIES / RUTRACKER_UA
set -euo pipefail
BASE="${RUTRACKER_BASE:-https://rutracker.org}"
COOKIES_FILE="$HOME/.config/surgemusic/cookies"
QUERY="${1:-pink floyd}"
TOPIC_ID="${2:-}"
OUT="$(dirname "$0")/../core/liveFixtures"
UA_DEFAULT="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"

if [ -n "${RUTRACKER_COOKIES:-}" ]; then
  COOKIES="$RUTRACKER_COOKIES"
  UA="${RUTRACKER_UA:-$UA_DEFAULT}"
elif [ -s "$COOKIES_FILE" ]; then
  COOKIES="$(sed -n 1p "$COOKIES_FILE")"
  UA="$(sed -n 2p "$COOKIES_FILE")"
  [ -n "$UA" ] || UA="$UA_DEFAULT"
else
  echo "no session: put the raw Cookie header from a logged-in browser into $COOKIES_FILE"
  echo "(line 1: cookies, line 2: that browser's User-Agent; chmod 600)"
  exit 2
fi

mkdir -p "$OUT"

echo "fetching search page for: $QUERY"
curl -sS -m 30 -H "Cookie: $COOKIES" -A "$UA" --get --data-urlencode "nm=$QUERY" \
  "$BASE/forum/tracker.php" -o "$OUT/search.html"
if grep -q "Just a moment" "$OUT/search.html"; then
  echo "Cloudflare-challenged: session cookies are missing or expired — re-import from a logged-in browser"
  exit 3
fi
if grep -q "frm-login\|action=\"login.php\"" "$OUT/search.html"; then
  echo "session expired (login page returned) — re-import cookies from a logged-in browser"
  exit 4
fi
echo "saved: $OUT/search.html ($(wc -c < "$OUT/search.html") bytes, $(grep -o 'tt-text' "$OUT/search.html" | wc -l | tr -d ' ') result links)"

if [ -n "$TOPIC_ID" ]; then
  echo "fetching torrent file for topic: $TOPIC_ID"
  curl -sS -m 30 -H "Cookie: $COOKIES" -A "$UA" "$BASE/forum/dl.php?t=$TOPIC_ID" -o "$OUT/topic.torrent"
  echo "saved: $OUT/topic.torrent ($(wc -c < "$OUT/topic.torrent") bytes)"
fi
