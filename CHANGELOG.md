# Changelog

## Unreleased

## 0.4.1 (2026-11-06)

- **Fixed the desktop login CAPTCHA loop**: browser sign-in no longer trips
  Cloudflare's automation detection — the capture attaches a single quiet DevTools
  session (no `Network.enable` churn) instead of reconnecting every 1.5 s, and the
  sign-in browser now uses a persistent profile (`~/.surgemusic/login-profile`), so
  the challenge state survives between attempts and retries start warm. If the check
  still repeats for a minute, the app now shows a hint (retry / manual cookie paste)
  instead of waiting silently.

## 0.4.0 (2026-11-06)

- **Reliability pass on the download engine**: tracker announces now send the correct
  raw `info_hash` (they were double-encoded, so the VPN tracker shim never delivered
  peers); pause/cancel/file-selection during a download's first moments can no longer
  wedge an item at 100% or leave a cancelled torrent running; disk errors now surface
  as FAILED instead of an eternal 0% spinner.
- **Downloads survive restarts**: the queue — and now the finished/failed history
  (newest 100) — is persisted to disk and restored on launch; orphaned staging folders
  are swept automatically.
- **Android 15 background-download quota**: the foreground service tracks its dataSync
  budget and pauses gracefully before the 6-hour system limit, resuming when the
  window reopens — no more force-stops mid-discography.
- **Desktop session security**: the rutracker cookie moves into the OS credential
  vault (macOS Keychain / Windows DPAPI / Linux secret-tool) with automatic migration
  from the old plaintext file; remaining file stores write atomically with 0600 perms.
- **Desktop polish**: storage-folder picker works on Windows/Linux (not just macOS),
  re-downloading no longer silently overwrites existing files (auto-renames instead),
  the app takes a single-instance lock and logs crashes to `~/.surgemusic/crash.log`,
  and UI language follows OS-language changes live.
- **Search fixes**: pagination no longer fetches the wrong query after editing the
  field, duplicate rows across pages can't crash the results list, and a new search
  cancels the stale one.
- **Settings that apply live**: the concurrent-downloads slider now affects the
  running queue; settings keys/defaults are shared across platforms.
- **Windows 11 (x64) desktop**: the same Compose Multiplatform app — identical UI,
  themes, login flow and libtorrent engine — packaged as a per-user MSI + EXE
  installer (Start-menu shortcuts, app icon, fixed upgrade UUID for clean updates).
- Native engine wired per platform from one build: each installer ships only its own
  OS's jlibtorrent native (macOS ARM64 / Windows x64 / Linux x64), selected by the
  host OS at build time.
- One-click browser sign-in on Windows: finds Chrome, Edge (preinstalled on
  Windows 11), Yandex, Brave, Chromium or Opera in `Program Files`,
  `Program Files (x86)` and `%LOCALAPPDATA%`.
- Windows window chrome follows the app theme: the caption is painted in the app's
  own colours via DWM (Windows 11) with a dark-mode flag fallback (Windows 10) —
  the Windows twin of the macOS title-bar overlay.
- Default video library on Windows/Linux is `~/Videos/SurgeMedia` (`~/Movies/`
  stays on macOS); settings labels match.
- CI gains a Windows job: the JVM test suites run with the Windows native engine,
  then the MSI/EXE installers are packaged and uploaded as build artifacts.

## 0.3.0 (2026-10-27)

- New app icon: a cyberpunk "Surge Wave" mark — a neon waveform with a trailing
  magenta echo — rebuilt as vector art on the app's own palette. The macOS `.app`
  now ships a real `.icns` (it previously fell back to the default Compose icon),
  and the Android launcher icon gains a proper Android 13 themed/monochrome layer.
- Cleaner UI: compact macOS-friendly type scale (no more all-caps/wide tracking),
  rounded corners, softer glow — same Surge identity, calmer presentation.
- Storage settings: pick your own music and video folders (native chooser on macOS,
  system folder picker on Android). Folders under Android's standard media roots stay
  MediaStore-indexed; any other folder is written via SAF. "Delete all" never sweeps a
  custom folder — deletes there stay per-release/per-download.
- Selective torrent download: multi-file torrents open a file chooser before
  downloading; only the checked files are fetched (engine-level skip priorities).
  Files are grouped into a folder tree with cascading checkboxes — one tap on an album
  folder selects the whole album.
- Grow downloads in place: "Add files" on a Downloads card opens the same chooser with
  already-downloaded files locked. Running/paused downloads pick the new folders up via
  live file-priority updates; completed downloads restart wanting only the new files and
  finalize them into the same library folder.

## 0.2.0 (2026-10-19)

- Surge UI redesign: dual-scheme theme, adaptive shell (bottom bar / rail / list-detail).
- Downloaded tab: media libraries grouped from MediaStore, per-item and bulk delete.
- Russian localization + in-app language option (System / English / Русский), applies
  instantly and persists; localized download notifications on Russian-locale devices.
- Release readiness: env-driven release signing (`SURGEMUSIC_*`), CI builds and tests
  the Android app, MIT license, bilingual privacy policy (`docs/PRIVACY.md`),
  publishing plan (`docs/PUBLISHING.md`), version sync (`Version.APP` = `versionName`).
- Earlier (0.1.x): shared core + CLI, Android app with rutracker search, in-app WebView
  login, libtorrent downloads into public media libraries, Wi-Fi-only gate, sorting,
  download history, media-info details.
