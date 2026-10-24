# Surge Media

Search rutracker.org for movies, video, music and books, and download them with a
built-in torrent client straight into your media libraries (audio → `Music/`,
video → `Movies/`). Dark and light themes (System / Light / Dark in Settings),
English and Russian UI (System / English / Русский in Settings ▸ Appearance).
Android app + macOS desktop from one Kotlin Multiplatform codebase.

**Status:** 0.2.0 — Android app live-verified against rutracker.org; production
releases via GitHub / GitVerse / RuStore (see [`docs/PUBLISHING.md`](docs/PUBLISHING.md)).

## Legal

A torrent client and a search interface are general-purpose tools. What you
download is your responsibility and depends on your jurisdiction. This project
ships no tracker indexes or content.

## Build

Requires JDK 21.

    ./gradlew :core:jvmTest :cli:build

## Android app

Build and install the debug APK:

    ./gradlew :composeApp:assembleDebug
    adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk

**Release build** (store-signed; without the env vars it falls back to the debug
keystore — testing only):

    export SURGEMUSIC_KEYSTORE_PATH=… SURGEMUSIC_KEYSTORE_PASSWORD=… \
           SURGEMUSIC_KEY_ALIAS=… SURGEMUSIC_KEY_PASSWORD=…
    ./gradlew :composeApp:assembleRelease

Publishing to GitHub / GitVerse / RuStore: [`docs/PUBLISHING.md`](docs/PUBLISHING.md).
Privacy policy (EN/RU): [`docs/PRIVACY.md`](docs/PRIVACY.md). License: MIT.

**Localization:** the UI ships English and Russian. Settings ▸ Appearance ▸ Language
(System / English / Русский) switches instantly and survives restarts; download
notifications follow the OS locale.

**Login:** the app cannot log in with a password (rutracker.org is behind a
Cloudflare browser check), so it logs in through your browser instead.
Android: Search or Settings → **Log in** opens a real in-app browser on
`rutracker.org/forum/login.php` — type your credentials there, the browser
engine solves Cloudflare and any captcha; as soon as the session cookies
appear they are saved (encrypted with an AndroidKeyStore key). Desktop: the
same screen's **Sign in via browser** launches your Chrome/Edge/Yandex window
on the login page in a throwaway profile and captures the session
automatically the moment you log in (nothing installed, your normal profile
untouched, temp profile deleted afterwards). The session survives app
restarts; if it ever expires, repeat the click — or paste a Cookie header +
User-Agent from a signed-in browser under Settings → Manual import (advanced)
and press **Validate** (Safari/Firefox users; both values must come from the
same browser). Step-by-step per browser: [docs/LOGIN.md](docs/LOGIN.md).

**Settings:** mirror URL, Wi-Fi-only downloads (auto-pauses on metered
networks), and 1–5 concurrent downloads.

**Storage:** finished downloads are copied into the public
`Music/SurgeMusic/<album>/` folder via MediaStore, so every installed music
player sees them without any manual copying. Temporary files download into
the app's private staging area first and are cleaned up on completion.

## Sessions (Cloudflare) — read this first

rutracker.org sits behind a Cloudflare browser check, so the app can't log in
with username/password directly. It uses an **imported browser session**
instead (verified working end-to-end):

1. Log into rutracker.org in your browser.
2. Open DevTools → Network → reload the page → click the first request →
   copy the full **Cookie** request header (a long string starting with
   `bb_guid=…; bb_ssl=…; bb_session=…; cf_clearance=…`).
3. Save it (plus that browser's User-Agent) to the session file:

       mkdir -p ~/.config/surgemusic
       printf 'bb_guid=…; bb_session=…; cf_clearance=…\nMozilla/5.0 … Chrome/…\n' \
         > ~/.config/surgemusic/cookies
       chmod 600 ~/.config/surgemusic/cookies

   (line 1: raw Cookie header, line 2: your browser's User-Agent). Env
   equivalents: `RUTRACKER_COOKIES` + `RUTRACKER_UA`.

**The Cookie and the User-Agent must come from the same browser** —
Cloudflare binds `cf_clearance` to the exact User-Agent it was issued for, so
a cookie from Chrome with a User-Agent from Firefox is rejected. Exact
click-by-click steps for each browser (Chrome/Edge/Yandex, Firefox, Safari —
including how to enable Safari's developer menu) are in
[docs/LOGIN.md](docs/LOGIN.md); the desktop app's Sign In dialog points there
too.

When the session expires you'll get an AUTH error — just re-import fresh
cookies (on desktop, one click of **Sign in via browser** does it). Password
login (`RUTRACKER_USER`/`RUTRACKER_PASS` or
`~/.config/surgemusic/credentials`) still works for mirrors that don't run the
Cloudflare check. The desktop **Sign In** dialog prefers the automatic browser
flow (Chrome/Edge/Yandex — temp profile, auto-capture, auto-close) and keeps
the manual Cookie + User-Agent fields as the fallback; the detailed
walkthrough for both lives in [docs/LOGIN.md](docs/LOGIN.md).

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
