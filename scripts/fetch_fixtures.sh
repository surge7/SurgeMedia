#!/usr/bin/env bash
# Saves live rutracker pages for parser verification. NEVER commit the output (git-ignored).
set -euo pipefail
BASE="${RUTRACKER_BASE:-https://rutracker.org}"
CREDS="${SURGEMUSIC_CREDENTIALS:-$HOME/.config/surgemusic/credentials}"
QUERY="${1:-pink floyd}"
OUT="$(dirname "$0")/../core/liveFixtures"
UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

[ -f "$CREDS" ] || { echo "credentials file not found: $CREDS"; exit 2; }
USER_="$(sed -n 1p "$CREDS")"
PASS_="$(sed -n 2p "$CREDS")"
mkdir -p "$OUT"
JAR="$(mktemp)"

echo "logging in..."
curl -sS -c "$JAR" -A "$UA" \
  -d "login_username=$USER_" \
  --data-urlencode "login_password=$PASS_" \
  -d "login=Вход" \
  "$BASE/forum/login.php" -o /dev/null
grep -q bb_data "$JAR" || { echo "login failed (no bb_data cookie; captcha?)"; exit 3; }

echo "fetching search page for: $QUERY"
curl -sS -b "$JAR" -A "$UA" --get --data-urlencode "nm=$QUERY" "$BASE/forum/tracker.php" -o "$OUT/search.html"
echo "saved: $OUT/search.html ($(wc -c < "$OUT/search.html") bytes)"
