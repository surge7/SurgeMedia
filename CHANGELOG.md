# Changelog

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
