# SurgeMusic — Design Spec

**Date:** 2026-10-12
**Status:** Draft, awaiting approval
**Scope:** Android app + macOS desktop app for searching rutracker.org for music and downloading it with a built-in torrent client into folders visible to music players.

---

## 1. Overview

SurgeMusic is a personal music downloader consisting of two clients — an Android app and a macOS desktop app — sharing one Kotlin codebase:

- **Search:** query rutracker.org for artists, albums, songs; browse results with size / seeders / date / forum category.
- **Download:** a built-in torrent engine (libtorrent via jlibtorrent) downloads the chosen torrent.
- **Library integration:** finished files land in a public music location — `Music/SurgeMusic/` via MediaStore on Android (visible to all music players), `~/Music/SurgeMusic/` on macOS — and are flagged to the platform media scanner.

### Success criteria

1. A user can log in with rutracker credentials once and stay logged in.
2. Searching "Pink Floyd" returns categorized music results within a few seconds.
3. Tapping a result downloads it end-to-end inside the app (no external torrent client), with progress, pause/resume, and cancellation.
4. On completion, the audio files appear in the OS music library / any installed music player without manual copying.
5. Both apps build from one repository with ~90% shared non-UI code and a shared Compose UI.

### Non-goals (v1)

- Built-in audio playback (files are for external players).
- iOS, Windows, Linux targets (architecture leaves the door open).
- RSS subscriptions / watchlists, upload/seeding management UI, torrent creation.
- Publishing to Google Play (policy-hostile to torrent clients; distribute via GitHub Releases / direct APK, optionally F-Droid).

### Legal note

A torrent client and a search interface are general-purpose tools; responsibility for what is downloaded rests with the user and varies by jurisdiction. The app ships without any bundled tracker indexes or content.

---

## 2. Approaches considered

| | A. Kotlin Multiplatform + Compose Multiplatform **(recommended)** | B. Native per platform (Kotlin/Compose + Swift/SwiftUI) | C. Flutter |
|---|---|---|---|
| Code sharing | One language, one UI, ~90% shared | Business logic shared only via a Rust/Go core, or duplicated | One language/UI |
| Torrent engine | jlibtorrent: identical Java API on Android and desktop JVM; MIT; libtorrent 2.0.x; prebuilt natives for `android-arm64`, `macosx-arm64`, etc. | Engine bindings written twice (jlibtorrent + Swift libtorrent wrapper) | No mature libtorrent binding; would need a Go/Rust FFI core anyway |
| macOS desktop | Compose Desktop (stable), jpackage → `.app`/dmg | Best native feel, native Keychain, etc. | Adequate |
| Risk | Desktop UI is Swing-backed (skia): not native-idiomatic, but proven (FrostWire-class apps) | 2× implementation cost, duplicated scrapers/UI forever | Engine risk kills it |
| Verdict | **Chosen** | Fallback if desktop polish becomes the priority | Rejected |

**Decision:** Option A. Both targets are JVM-based (Android ART + desktop JVM), so the *entire* stack — Ktor HTTP, Jsoup HTML parsing, jlibtorrent engine, SQLDelight — runs shared in an `android+jvm` intermediate source set. Only storage/notification/app-lifecycle glue differs per platform.

---

## 3. Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     composeApp (shared UI)                   │
│   Compose Multiplatform: Search / Detail / Downloads /      │
│   Library / Settings screens, ViewModels (common)           │
├────────────────────────────┬────────────────────────────────┤
│  androidMain (actuals)     │  desktopMain (actuals)         │
│  MediaStore finalize,      │  ~/Music folder output,        │
│  foreground service,       │  tray icon, directory picker,  │
│  notif progress, wifi gate │  jpackage app bundle           │
├────────────────────────────┴────────────────────────────────┤
│                     core (KMP module)                        │
│  ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌───────────────┐  │
│  │ tracker  │ │ engine   │ │ downloads │ │ data (DB,     │  │
│  │ client   │ │ (jlibtor │ │ manager   │ │ settings,     │  │
│  │ (login/  │ │ wrapper) │ │ (queue,   │ │ repositories) │  │
│  │ search/  │ │          │ │ resume)   │ │               │  │
│  │ parse)   │ │          │ │           │ │               │  │
│  └──────────┘ └──────────┘ └───────────┘ └───────────────┘  │
│  commonMain: models, use-cases      android+jvm shared:      │
│                                     Ktor, Jsoup, jlibtorrent│
└─────────────────────────────────────────────────────────────┘
```

**Modules (Gradle):**

- `core/` — KMP module, targets `androidTarget` + `jvm`. `commonMain` holds models and pure logic; an intermediate `jvmShared` source set holds Ktor/Jsoup/jlibtorrent code used by both targets.
- `composeApp/` — Compose Multiplatform application module. `commonMain` UI + ViewModels; `androidMain` / `desktopMain` entry points and platform actuals.

**Key interfaces (sketch):**

```kotlin
// core — commonMain
interface TrackerClient {
    suspend fun login(user: String, pass: String): Result<Unit>
    suspend fun search(query: SearchQuery): Result<SearchResultPage>
    suspend fun topic(topicId: Long): Result<TopicDetail>
    suspend fun torrentFile(topicId: Long): Result<ByteArray>   // .torrent bytes
    val sessionState: StateFlow<TrackerSessionState>            // logged-in / cookie age / mirror health
}

interface TorrentEngine : AutoCloseable {
    fun add(meta: TorrentMeta, options: EngineOptions): EngineHandle
    val events: SharedFlow<EngineEvent>                         // progress, peers, errors, completed
}

interface OutputLocation {                 // expect/actual per platform
    suspend fun finalize(files: List<StagedFile>): Result<LibraryEntry>  // move into public music dir + media-scan
}
```

`TorrentEngine` is deliberately thin and implemented once against jlibtorrent in `jvmShared`; the interface exists so ViewModels and tests can run against a `FakeEngine`.

---

## 4. Rutracker integration

No official API exists; the established approach (used by qBittorrent plugins, Python `rutracker-api`, Rust `rutracker_api`) is authenticated HTML scraping. Endpoints (stable for 10+ years):

| Purpose | Request |
|---|---|
| Login | `POST /forum/login.php` (`login_username`, `login_password`, `login=…`) → `bb_data` cookie (long-lived) |
| Search | `GET /forum/tracker.php?nm=<q>&f[]=<forumId>&s=<sort>&o=<dir>&start=<page>` |
| Topic page | `GET /forum/viewtopic.php?t=<topicId>` |
| .torrent file | `GET /forum/dl.php?t=<topicId>` (requires session cookie) |

**Client behavior:**

- Base URL configurable; default `https://rutracker.org`, mirror list user-editable (rutracker.net etc.); automatic fail-over on connect errors.
- `bb_data` cookie persisted; transparent re-login when a download/topic request returns a login redirect.
- Captcha / anti-bot response → explicit actionable error ("Login blocked by captcha — retry later or solve in browser"); never silent failure. Optional SOCKS5 proxy for tracker traffic.
- **Parsing:** Jsoup with all selectors isolated in `SearchPageParser` / `TopicPageParser`. Domain models: `SearchResultRow(title, topicId, forumId, forumName, sizeBytes, seeds, leechs, date,)`, `TopicDetail(description, tracks?, coverUrl?, infoHashIfPresent)`.
- **Categories:** curated constants file mapping music forums (lossless, MP3-by-genre, etc.) to filter chips. Exact IDs verified at implementation from the forum tree; treated as data, not code, so updates are trivial.
- **Search UX:** free-text search (matches rutracker title search `nm=`), category filter, sort (seeds / date / size). Artist/album/song disambiguation is intentionally left to rutracker's own title search — no client-side entity resolution in v1.

**Robustness:** parser unit tests run against saved HTML fixtures (checked into `core/testFixtures`); a "layout changed" breakage produces a typed `ParseError` naming the failed selector, not a crash.

---

## 5. Torrent engine

**Library:** jlibtorrent `2.0.13.x` (MIT; wraps libtorrent 2.0.x; prebuilt natives from `https://dl.frostwire.com/maven`: `jlibtorrent-android-arm64` / `-x86_64` for the app, `jlibtorrent-macosx-arm64` / `-x86_64` for desktop — universal dmg via two artifacts).

**Engine wrapper responsibilities:**

- Single `SessionManager` with sensible defaults: encrypted handshake, DHT on, LSD on, uTP enabled, active downloads limit 3, per-torrent connection cap; listen port random, UPnP/NAT-PMP port mapping on desktop, disabled on cellular-only Android.
- `EngineOptions` per download: `sequential` (piece priority front-loading — nice for large FLAC archives), `downloadOnlyExtensions` (music-first file priority: pick `.flac/.mp3/.cue` before artwork/logs), bandwidth limits (global settings screen).
- Emits `EngineEvent.Progress(torrentId, bytesDone, bytesTotal, dlSpeed, peers, seeds)` → `SharedFlow` → ViewModels; persists resume data on pause/app-stop.
- **Staging:** all writes go to a private staging dir (`context.getExternalFilesDir("staging")` on Android; `~/.local-state`… `~/Library/Application Support/SurgeMusic/staging` on macOS) — libtorrent requires real random-access file paths, and scoped storage forbids writing MediaStore URIs in place.

**Proxy:** optional SOCKS5 applied to both tracker (Ktor) and peer traffic (libtorrent proxy settings) — one toggle.

---

## 6. Download pipeline & platform storage

```
search → pick topic → fetch .torrent → parse metadata (file list shown,
user picks files/priorities) → engine downloads to staging → on complete:
OutputLocation.finalize() → media scan → Library entry
```

**Android (minSdk 29, scoped storage only):**

1. Staged files copied into `MediaStore.Audio` with `RELATIVE_PATH = Music/SurgeMusic/<artist-dir>`, `IS_PENDING=1` while copying, then `IS_PENDING=0` → MediaScanner picks the files up; every mainstream music player sees them.
2. Copy (not move) then delete staging — crossing volumes forces this anyway; disk headroom is checked before start (`IOException` with ENOSPC surfaced as "Not enough space").
3. A **foreground service** keeps active downloads alive, posting progress notifications; a network callback auto-pauses when the connection drops or leaves Wi‑Fi (default `Wi‑Fi only` toggle in settings; metered-network detection).
4. Battery-doze note: long downloads require the user to disable battery optimization for the app — shown once as a priming hint, not required.

**macOS desktop:**

1. Output dir defaults to `~/Music/SurgeMusic` (changeable via native directory picker); files written directly (staging on same volume → instant rename).
2. Optional "Reveal in Finder" action on completed items; no Music.app import automation in v1 (users point Music/VLC/Swinsian at the folder or drag in).

**Completion also:** writes metadata sidecar (`album.nfo`-style JSON: topic URL, forum, date downloaded) inside the destination folder for provenance.

---

## 7. Data model (SQLDelight)

```
downloads(id INTEGER PK, topic_id INTEGER, title TEXT, forum_name TEXT,
          size_bytes INTEGER, status TEXT,   -- QUEUED|DOWNLOADING|PAUSED|FINALIZING|DONE|ERROR
          progress REAL, sequential INT, added_at INTEGER, completed_at INTEGER,
          staging_dir TEXT, output_dir TEXT, error TEXT)
download_files(id INTEGER PK, download_id FK, index_in_torrent INT,
               name TEXT, size_bytes INTEGER, priority TEXT,   -- SKIP|NORMAL|HIGH
               output_path TEXT)
search_history(id PK, query TEXT, category TEXT, at INTEGER)
library_items(id PK, download_id FK, title, artist_guess, file_count,
              total_bytes, output_dir, completed_at)
```

Settings (multiplatform-settings): credentials (encrypted), base URL + mirrors, proxy, Wi‑Fi-only, max concurrent downloads, bandwidth caps, output directory (desktop).

---

## 8. UI (Compose Multiplatform, shared)

| Screen | Contents |
|---|---|
| **Search** | query field, category chips (All / Lossless / MP3 / …), sort menu, results list (title, forum, size, 🌱 seeds, date); pull/refresh; pagination via `start=` |
| **Topic detail** | description, cover, file list with per-file checkboxes/priority, size/seeds, **Download** button |
| **Downloads** | active/queued/paused/done sections; progress bar, speed, peers; pause/resume/cancel/delete; "open folder" (desktop) / "open in player" intent (Android) |
| **Library** | completed albums with provenance metadata |
| **Settings** | account (login/logout, cookie status), mirrors, proxy, network policy (Android), storage location, bandwidth, engine advanced |

Navigation: `Voyager` or `deeplink`-less simple `NavHost`-equivalent (Decompose) — pick Decompose at implementation. ViewModels in `commonMain`, driven by `Result`-typed use-cases; progress via `StateFlow`.

Platform UI actuals: Android `Material3` dynamic theming; desktop gets a macOS-appropriate dark/light theme, tray icon with "pause all / quit", standard menu bar (File/Edit/Window stubs).

---

## 9. Security

- Credentials stored encrypted at rest: Android Keystore-wrapped key + EncryptedSharedPreferences-equivalent (DataStore + Tink AEAD); desktop: macOS Keychain via `security` CLI wrapper (v1 acceptable: `chmod 600` prefs file; Keychain flagged as fast-follow).
- No telemetry, no accounts beyond rutracker's, all traffic HTTPS + user-configurable proxy.
- `.torrent` files are parsed by libtorrent (not executed); file extension allowlist warning if user enables "download everything".

---

## 10. Error handling

| Failure | Behavior |
|---|---|
| Mirror unreachable | rotate mirror list → typed `TrackerOffline` state with retry |
| Cookie expired | silent re-login once, then `AuthRequired` state |
| Captcha at login | explicit error + guidance; no captcha-solving |
| HTML layout change | `ParseError(selector)` — user sees "Site layout changed, update required" and logs |
| No seeds / stalled | status chip "stalled, N seeds known"; alert after 30 min at 0 progress |
| Disk full | pre-flight check + typed error |
| Engine native crash | caught per-download, download → ERROR, engine restarted lazily |

---

## 11. Testing

- **Parser tests:** golden HTML fixtures → models; negative fixtures for layout drift.
- **Tracker client:** MockWebServer for login redirect / cookie / captcha flows.
- **Download manager:** `FakeEngine` scriptable events (progress, completion, error) → ViewModel/state assertions.
- **Engine integration (desktop JVM only):** create real torrents of temp files, loopback seed+leech, assert file integrity + sequential ordering.
- **UI:** Compose screenshot tests for Search/Downloads states; manual matrix: Android 14/15 emulator (available via this environment's android-emulator tooling) + macOS host.
- **Storage finalize:** Robolectric-free instrumentation on emulator against MediaStore; desktop plain JUnit against temp dirs.

---

## 12. Project structure & toolchain

```
SurgeMusic/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
├── core/                    # KMP: commonMain, jvmShared(android+jvm), androidMain, jvmMain(+testFixtures html)
├── composeApp/              # commonMain UI/VMs, androidMain (Activity, service, MediaStore), desktopMain (main.kt, tray, dir picker)
├── desktop packaging: jpackage → SurgeMusic.app + dmg (notarization optional, personal use can ad-hoc sign)
└── docs/
```

Toolchain: Kotlin 2.x, AGP 8.x, Compose Multiplatform (stable desktop), Ktor 2/3 (OkHttp engine), Jsoup, SQLDelight 2.x, multiplatform-settings, kotlinx-datetime, kotlinx-serialization, Coroutines. CI (GitHub Actions): JVM tests + assembleDebug + desktop dist; Android instrumentation on emulator.

---

## 13. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| rutracker HTML/layout drift breaks parser | Medium | selectors isolated + fixture tests + typed ParseError; category IDs as data |
| Captcha / anti-bot on login or search | Medium | cookie reuse minimizes logins; mirrors; clear UX; proxy support |
| jlibtorrent maven repo (`dl.frostwire.com`) availability | Low | pin exact version; vendor natives into repo if it disappears |
| Play-distribution impossible | Certain | by design: GitHub Releases / F-Droid path |
| Scoped-storage copy doubles I/O for huge FLAC sets | Medium | acceptable v1; fast-follow: direct-to-MediaStore streaming via file descriptor bridge |
| libtorrent RAM on low-end Android | Low | alert threshold, cap active torrents |
| Legal exposure | User-dependent | general-purpose tool, no bundled index, in-app notice |

---

## 14. Roadmap

1. **Phase 0 — bootstrap:** Gradle monorepo, CI, version catalog, CI fixtures harness.
2. **Phase 1 — tracker core:** login/search/topic/dl client + parsers + fixture tests (desktop CLI harness to eyeball results).
3. **Phase 2 — engine:** jlibtorrent wrapper, staging downloads, resume, events; desktop CLI "download by topic id" end-to-end.
4. **Phase 3 — desktop app:** full Compose UI, settings, output finalize, tray, packaging (dmg).
5. **Phase 4 — Android app:** UI wiring, foreground service, Wi‑Fi gate, MediaStore finalize, notification actions; emulator verification.
6. **Phase 5 — hardening:** mirrors fail-over, proxy, sequential/file-priority UX, library screen, error states polish, instrumentation tests.
7. **Phase 6 — distribution:** GitHub Releases (APK + dmg), README/legal notice.

---

## 15. Assumptions & open questions

- User has (or will create) a rutracker account; guest mode is not supported (downloads require login).
- UI language: English first; ru strings as fast-follow (audience is Russian-speaking).
- minSdk 29 / macOS 13+ (Apple Silicon primary; Intel build included since jlibtorrent ships both).
- Sequential download and file priorities are per-download toggles, default off.
- Open Q (fast-follow, not blocking): Keychain storage on desktop; Music.app auto-import; F-Droid inclusion.
