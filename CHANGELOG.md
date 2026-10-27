# Changelog

## Unreleased

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
