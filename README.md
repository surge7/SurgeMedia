# SurgeMusic

Search rutracker.org for music (artists, albums, songs) and download it with a
built-in torrent client into folders your music players can see. Android app +
macOS desktop from one Kotlin Multiplatform codebase.

**Status:** Phase 1 — shared core + desktop CLI, live-verified against
rutracker.org. Desktop UI and the Android app come next
(see `docs/superpowers/specs/2026-10-12-surgemusic-design.md`).

## Legal

A torrent client and a search interface are general-purpose tools. What you
download is your responsibility and depends on your jurisdiction. This project
ships no tracker indexes or content.

## Build

Requires JDK 21.

    ./gradlew :core:jvmTest :cli:build

## Sessions (Cloudflare) — read this first

rutracker.org sits behind a Cloudflare browser check, so the app can't log in
with username/password directly. It uses an **imported browser session**
instead (verified working end-to-end):

1. Log into rutracker.org in your browser.
2. Open devtools → Network → click any `forum/` request → copy the full
   **Cookie** request header (a long string starting with `bb_guid=…;
   bb_ssl=…; bb_session=…; cf_clearance=…`).
3. Save it (plus that browser's User-Agent) to the session file:

       mkdir -p ~/.config/surgemusic
       printf 'bb_guid=…; bb_session=…; cf_clearance=…\nMozilla/5.0 … Chrome/…\n' \
         > ~/.config/surgemusic/cookies
       chmod 600 ~/.config/surgemusic/cookies

   (line 1: raw Cookie header, line 2: your browser's User-Agent — `cf_clearance`
   is bound to it). Env equivalents: `RUTRACKER_COOKIES` + `RUTRACKER_UA`.

When the session expires you'll get an AUTH error — just re-import fresh
cookies. Password login (`RUTRACKER_USER`/`RUTRACKER_PASS` or
`~/.config/surgemusic/credentials`) still works for mirrors that don't run the
Cloudflare check. The desktop app (Phase 2) will do the login in an embedded
browser window so none of this is manual.

## CLI usage

    ./gradlew :cli:installDist
    ./cli/build/install/cli/bin/cli session                 # validate the imported session
    ./cli/build/install/cli/bin/cli search "pink floyd"
    ./cli/build/install/cli/bin/cli download 6918211 --out ~/Music/SurgeMusic

Downloads land in the output folder (default `~/Music/SurgeMusic`) via the
built-in libtorrent engine — ready for any music player.

Mirror support: set `RUTRACKER_BASE=https://<mirror>` (any mirror root).

## Development

- `scripts/fetch_fixtures.sh [query] [topicId]` — captures live HTML (and
  optionally a .torrent) for parser verification using the imported session
  (output is git-ignored).
- Tests: `./gradlew :core:jvmTest` — parser fixture tests (live-verified
  markup), MockWebServer client tests (session import, Windows-1251 decoding,
  auth-expiry redirects), loopback engine integration test.
