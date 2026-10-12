# SurgeMusic

Search rutracker.org for music (artists, albums, songs) and download it with a
built-in torrent client into folders your music players can see. Android app +
macOS desktop from one Kotlin Multiplatform codebase.

**Status:** Phase 1 — shared core + desktop CLI. Desktop UI and the Android app
come next (see `docs/superpowers/specs/2026-10-12-surgemusic-design.md`).

## Legal

A torrent client and a search interface are general-purpose tools. What you
download is your responsibility and depends on your jurisdiction. This project
ships no tracker indexes or content.

## Build

Requires JDK 21.

    ./gradlew :core:jvmTest :cli:build

## CLI usage

    ./gradlew :cli:installDist
    ./cli/build/install/cli/bin/cli login
    ./cli/build/install/cli/bin/cli search "pink floyd"
    ./cli/build/install/cli/bin/cli download 567890 --out ~/Music/SurgeMusic

Credentials: `RUTRACKER_USER`/`RUTRACKER_PASS` env vars, or
`~/.config/surgemusic/credentials` (username on line 1, password on line 2):

    mkdir -p ~/.config/surgemusic
    printf 'myuser\nmypassword\n' > ~/.config/surgemusic/credentials
    chmod 600 ~/.config/surgemusic/credentials

Mirror support: set `RUTRACKER_BASE=https://rutracker.net` (any mirror root).
Full mirror fail-over and proxy settings arrive with the desktop app.

## Development

- `scripts/fetch_fixtures.sh` — captures live HTML for parser verification
  (output is git-ignored; needs credentials).
- Tests: `./gradlew :core:jvmTest` — parser fixture tests, MockWebServer client
  tests, loopback engine integration test.
