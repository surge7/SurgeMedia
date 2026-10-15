# SurgeMusic Cyberpunk Redesign — Android + macOS

Plan: full visual redesign of the Compose Multiplatform app around a cyberpunk design
system, plus proper on-screen-keyboard (IME) handling on Android and a new macOS
(JVM/Compose Desktop) target so the same UI ships on both platforms.

## Context

- `:core` is KMP (android + jvm with `jlibtorrent-macosx-arm64`) — search/session/torrent
  logic already runs on macOS.
- `:composeApp` has ONLY `androidTarget()`. All DI lives in `androidMain/AppContainer.kt`;
  `App()` (commonMain) receives ViewModels + `loginOverlay` slot + `onDownload` callback.
- Current theme is 22-line stock Material3 (`Theme.kt`), no custom colors/typography/shapes,
  no component library, no insets/IME handling anywhere.
- Screens: `App.kt` (shell, tab state, Details overlay, snackbar), `ui/search/SearchScreen.kt`,
  `ui/details/DetailsScreen.kt`, `ui/downloads/DownloadsScreen.kt`,
  `ui/settings/SettingsScreen.kt`, androidMain `ui/login/WebViewLoginScreen.kt`.
  Navigation is plain state (no library) — keep it that way.
- Uncommitted prior-session work (Details drill-down flow in App.kt/SearchScreen.kt/
  WebViewLoginScreen.kt + untracked ui/back/, ui/details/, debug manifest) is REQUIRED for
  compilation. Branch strategy ruling below.

## Research findings applied (UX)

Sources: Android docs "Set up window insets" + edge-to-edge migration skill; AOSP issue
#328691355; dark-mode UI contrast guides (Five Jars 2025); Compose neon-glow techniques
(Stack Overflow 74253100, ProAndroidDev glowing borders).

1. **Contrast**: neon colors are accents only — borders, CTAs, chips, focus rings. Body text
   is high-contrast off-white (≥ 4.5:1). All palette values below verified ≥ 4.5:1 on their
   backgrounds.
2. **IME**: `enableEdgeToEdge()` + `adjustResize` + `Modifier.imePadding()` on scroll
   containers that contain text fields; never stack `navigationBarsPadding()` + `imePadding()`
   (double-padding during IME animation — AOSP #328691355). Scaffold keeps default system-bar
   insets; only IME padding is added per-screen.
3. **Adaptive layout**: `BoxWithConstraints` breakpoint at 700.dp — compact gets a bottom
   `NavigationBar`, expanded (desktop window / tablets) gets a left `NavigationRail`.
   No new dependencies for this.
4. **Glow without blur**: layered outline strokes of decreasing alpha (works identically on
   Android and Desktop JVM — no RenderEffect/blur needed).
5. **Motion restraint**: glow intensity change + 0.98 scale on press; no ambient animation
   loops except a subtle indeterminate sweep already provided by LinearProgressIndicator.

## Design spec — "CyberDeck"

### Palette (exact values)

| Token | Hex | M3 slot | Contrast on background |
|---|---|---|---|
| CyberBackground | `#0B0E17` | background / surface | — |
| CyberSurface | `#131729` | surface (cards/panels via Components) | — |
| CyberSurfaceAlt | `#1A2038` | surfaceVariant | — |
| CyberText | `#E8F4FD` | onBackground/onSurface | 14.9:1 |
| CyberTextDim | `#93A0C2` | onSurfaceVariant | 6.3:1 |
| CyberCyan | `#00E5FF` | primary | 12.7:1 |
| OnCyan | `#04121A` | onPrimary | — |
| CyberMagenta | `#FF3D81` | secondary AND error | 5.4:1 |
| OnMagenta | `#1B0712` | onSecondary/onError | — |
| CyberYellow | `#F5D90A` | tertiary | 9.8:1 |
| OnYellow | `#141003` | onTertiary | — |
| CyberGrid | `#232A4D` | outline | hairlines/borders |
| CyberGridDim | `#1A2038` | outlineVariant | dividers |
| PrimaryContainer | `#0A2A33` | primaryContainer | — |
| OnPrimaryContainer | `#7FF4FF` | onPrimaryContainer | — |
| SecondaryContainer | `#381422` | secondaryContainer | — |
| OnSecondaryContainer | `#FF9EC4` | onSecondaryContainer | — |

Dark-only: `SurgeMusicTheme` ignores `isSystemInDarkTheme()` and always applies the dark
scheme (cyberpunk IS dark).

### Shape language

Chamfered cut corners (the cyberpunk signature — NOT rounded):
- `Shapes.small`  = `CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)`
- `Shapes.medium` = `CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)`
- `Shapes.large`  = `CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp)`
All custom components use `topStart/bottomEnd` asymmetric cuts.

### Typography (built-in families only — no font binaries)

- Titles/labels: default SansSerif, `uppercase()` at call site, letterSpacing
  0.08–0.16em.
- Data (sizes, timestamps, counts, IDs, mirror URLs): `FontFamily.Monospace`.
- Scale: headlineMedium 28sp Bold (+0.04em), titleMedium 17sp SemiBold (+0.06em),
  titleLarge 20sp Bold (+0.05em), bodyLarge 15sp, bodyMedium 14sp,
  labelLarge 12sp Medium (+0.14em), labelSmall 11sp Monospace (+0.08em),
  bodySmall 12sp Monospace.

### Glow recipe (exact, cross-platform)

`Modifier.cyberGlow(color: Color, shape: Shape = MaterialTheme.shapes.medium, alpha: Float = 1f)`:
in `drawBehind`, create the outline once from `shape.createOutline(size, layoutDirection, this)`,
then `drawOutline` with `Stroke` widths 10.dp (alpha 0.05f·α), 6.dp (alpha 0.12f·α),
3.dp (alpha 0.28f·α) in `color`, plus a final 1.5.dp stroke at full `color`.
Pure strokes — no blur, no RenderEffect. α animates 1f→0.45f on press.

### Component library (`ui/components/`)

- `Modifier.cyberGlow(...)` as above; `Modifier.cyberPanel(shape)` — fills `CyberSurface`
  + 1.dp `CyberGrid` border via drawBehind.
- `CyberButton(text, onClick, emphasis: Boolean = false, modifier, enabled)` — chamfered;
  default: 12% primary fill + 1.5.dp primary border + cyan label + glow; `emphasis=true`:
  solid `CyberCyan` fill, `OnCyan` label, full glow. Press → α 0.45 + scale 0.98
  (`graphicsLayer` + `interactionSource`).
- `CyberTextButton(text, onClick, modifier, tint)` — dim text, cyan on hover/press.
- `CyberTextField(...)` — wraps `OutlinedTextField`; colors: focused border/label/cursor =
  primary, unfocused = outline, container = transparent; passes through singleLine/minLines/
  maxLines/ImeAction/KeyboardActions/placeholder.
- `CyberPanel(modifier, content)` — Column scope container with `cyberPanel` bg.
- `CyberSectionHeader(title)` — monospace 11sp uppercase, letterSpacing 0.2em, `CyberCyan`.
- `CyberChip(text, color)` — 1.dp colored border, monospace 10sp uppercase, transparent bg.
- `CyberTopBar(title, onBack: (() -> Unit)?)` — status-bar-aware top bar: optional back
  (material icon `AutoMirrored.Rounded.ArrowBack`), uppercase title with cyan left accent
  (2.dp vertical bar), `statusBarsPadding()`.
- `ScanlineDivider()` — 1.dp horizontal gradient (cyan 0.25 → transparent).
- Material widget defaults set at call sites (documented in T3 brief): Switch, Slider,
  LinearProgressIndicator, NavigationBar/Rail item colors.

## Global constraints

1. commonMain only for shared UI; platform code solely in androidMain/desktopMain.
2. No new third-party dependency except `compose.materialIconsExtended` (CMP-provided) and
   `compose.desktop.currentOs` (desktopMain only). No font binaries, no navigation library.
3. Screens use ONLY `MaterialTheme.colorScheme/typography/shapes` tokens + the component
   library. Hardcoded `Color(0x…)`/`fontSize` allowed ONLY inside `ui/theme/` and
   `ui/components/`.
4. Behavior parity: every callback, state machine, ViewModel call and screen flow stays
   identical. This is a visual + platform-layer change only.
5. Android builds: `./gradlew :composeApp:assembleDebug` must pass. Desktop:
   `./gradlew :composeApp:compileKotlinJvm` must pass (plus `:composeApp:run` launches).
6. Never stack `navigationBarsPadding()` with `imePadding()` on the same node.
7. Branch: `feature/cyberpunk-redesign`. Commit 1 on the branch is the prior session's
   uncommitted work, committed verbatim with message "wip(app): details drill-down flow
   (prior session)". Worktree NOT used — that WIP is required to compile and cannot cross
   worktrees. main stays untouched.

## Tasks

### Task 1 — Design system foundation (commonMain)
Files: rewrite `ui/../Theme.kt` → keep path `Theme.kt`; new `ui/theme/Color.kt`,
`ui/theme/Type.kt`, `ui/theme/Shape.kt`; new `ui/components/CyberComponents.kt`.
Exact palette/typography/shape/glow values from the spec above. Add
`implementation(compose.materialIconsExtended)` to commonMain. `SurgeMusicTheme` keeps its
public signature. Acceptance: compiles (`./gradlew :composeApp:assembleDebug`); no screen
changed yet; grep shows no `Color(0x` outside `ui/theme`.

### Task 2 — Adaptive app shell (App.kt)
`BoxWithConstraints` at Scaffold body: < 700.dp → bottom `NavigationBar` (cyber colors:
container `CyberBackground`, indicator `primaryContainer`, selected icon/label
`OnPrimaryContainer`→cyan, unselected `CyberTextDim`); ≥ 700.dp → `Row` with
`NavigationRail` (container transparent, selected icon cyan) + weight(1f) content.
Replace emoji+`fontSize = 20.sp` nav icons with `Icons.Rounded.Search`,
`Icons.Rounded.Download`, `Icons.Rounded.Settings`. Status bar: Scaffold content keeps
default insets; add `Modifier.statusBarsPadding()` inside content Box on compact only if
needed (Scaffold already pads). Snackbar host: custom host lambda rendering Snackbar in a
chamfered `CyberSurface` panel with 1.dp `CyberGrid` border. Tab state, overlays
(Details/login), snackbar retry logic, `onDownload` plumbing UNCHANGED. Desktop window
(≥700.dp) must show rail + tab content.

### Task 3 — Screen restyles (Search, Details, Downloads, Settings)
All four commonMain screens restyled to the spec with the component library:
- Search: `CyberTextField` (keeps ImeAction.Search + KeyboardActions) + `CyberButton`
  "Search"; results rows as `CyberPanel` rows (title 2-line `titleMedium`, metadata line in
  monospace `labelSmall` dim: size · forum · seeders if present); whole row clickable →
  details (unchanged callback), trailing `Icons.Rounded.ChevronRight` dim;
  `Modifier.imePadding()` on the results `LazyColumn` so results lift above the keyboard
  while the query row stays pinned; "Load more" → `CyberButton`; `LoginHero` → terminal
  panel (monospace dim text + cyan headline); `ErrorBanner` → magenta chip row with
  `Icons.Rounded.Warning` + retry `CyberTextButton`.
- Details: `CyberTopBar(title, onBack)`; rows: label `labelLarge` dim uppercase, value
  monospace `bodySmall`; bottom bar: spinner → `CyberChip("FETCHING", primary)`;
  Download/Retry → `CyberButton(emphasis = true)` full width; "open Downloads" →
  `CyberButton`. `Surface` bg = `CyberBackground`.
- Downloads: empty state → terminal panel (mono dim) + cyan icon; `DownloadCard` →
  `CyberPanel` with title, mono metadata, `CyberChip` status (mapping: RUNNING/PAUSED cyan,
  DONE→"COMPLETE" tertiary-yellow? — use primary cyan, ERROR magenta, other states dim),
  `LinearProgressIndicator(color = primary, trackColor = CyberGrid)`; Pause/Resume/Cancel →
  `CyberTextButton`s (Cancel tinted magenta).
- Settings: sections inside `CyberPanel`s with `CyberSectionHeader`s; `Switch` colors
  (checked track primary, unchecked track surfaceAlt); `Slider` (active primary, inactive
  CyberGrid, thumb primary); `MirrorField`/`ManualImportFields` → `CyberTextField`s; the
  outer scroll `Column` gets `Modifier.imePadding()` (contains multiline cookie field);
  validate/log-out/import buttons → `CyberButton`/`CyberTextButton`.
Acceptance: no emoji icons; no hardcoded colors/fontSizes in these files; behavior
callbacks identical; `assembleDebug` green.

### Task 4 — Android platform layer (edge-to-edge + IME + login polish)
- `MainActivity.onCreate`: call `enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(
  android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(
  android.graphics.Color.TRANSPARENT))` before setContent (androidx.activity 1.9.3 API).
- `androidMain/AndroidManifest.xml`: activity gets `android:windowSoftInputMode=
  "adjustResize"`.
- `WebViewLoginScreen`: header becomes `CyberTopBar("SIGN IN", onCancel)`; WebView sits in
  a `CyberPanel`-styled container; `Modifier.statusBarsPadding()` so content clears the
  status bar (edge-to-edge). Cookie polling logic unchanged.
- Login overlay back handling: in `App.kt` where `showLogin` is true, add
  `PlatformBackHandler(enabled = showLogin) { /* same path as cancel */ }` — cancel callback
  already exists via the overlay slot; wire it (Android actual exists in ui/back).
Acceptance: `assembleDebug` green; manifest diff shows adjustResize only; no logic change.

### Task 5 — Desktop (macOS) build target + skeleton
- `composeApp/build.gradle.kts`: add `jvm()` target (compilerOptions JvmTarget.JVM_21);
  `jvmMain` source set deps: `implementation(compose.desktop.currentOs)`; add top-level
  `compose.desktop.application { mainClass = "com.surgemusic.app.MainKt" }`; keep
  androidMain deps as-is. Create `desktopMain/kotlin/...` tree.
- `desktopMain PlatformBackHandler` actual: no-op (`actual fun PlatformBackHandler(...) {}`
  body empty — signature must match the commonMain expect).
- `desktopMain Main.kt`: `fun main() = application { Window(onCloseRequest = ::exitApplication,
  title = "SurgeMusic", state = rememberWindowState(width = 1080.dp, height = 720.dp,
  position = WindowPosition(Platform.WindowCenter))) { SurgeMusicTheme { CyberLaunchStub() } } }`
  with a private `CyberLaunchStub()` composable showing "SURGEMUSIC // DESKTOP OFFLINE" mono
  text (deleted in Task 6).
Acceptance: `./gradlew :composeApp:compileKotlinJvm` green; android still green.

### Task 6 — Desktop container + session-paste login (full wiring)
- `desktopMain/settings/DesktopSettingsStore.kt`: implements commonMain `AppSettings`
  (read `ui/settings/SettingsScreen.kt:47` for the exact interface) backed by
  `java.util.Properties` at `~/.surgemusic/settings.properties`; StateFlows with the same
  defaults as Android `SettingsStore` (wifiOnly=true, mirror="", concurrent=2 — read the
  androidMain SettingsStore to copy defaults); setters persist + emit.
- `desktopMain/session/FileSessionStore.kt`: implements `SessionRepository` (cookieHeader,
  userAgent, savedAtEpochMs persisted to `~/.surgemusic/session.properties`); `nowEpochMs()`
  desktop actual if the expect requires it (expect lives in commonMain SessionRepository.kt
  — check whether androidMain already provides `Time.android.kt`; provide `Time.desktop.kt`
  matching its pattern, plus `formatTimestamp` actual).
- `desktopMain/AppContainer.kt`: mirror of androidMain's — `JLibTorrentEngine()`,
  `RutrackerClient` with mirror-rebuild logic, `trackerClient()` re-importing session,
  `SearchViewModel`/`DownloadsViewModel`/`SettingsViewModel` (same lambdas), `DownloadManager`
  with finalize = copy staged files to `~/Music/SurgeMusic/<sanitized title>/` (create dirs,
  `Files.move` with fallback copy), staging root `~/.surgemusic/staging/<topicId>`,
  `download(row)` pipeline. NO DownloadService/NetworkGate (in-process; wifiOnly not enforced
  on desktop v1 — document in KDoc).
- `desktopMain/ui/login/DesktopLoginScreen.kt`: composable slot matching the loginOverlay
  signature `(onFinished: () -> Unit) -> Unit`; full-screen `CyberBackground` Surface +
  statusBarsPadding-irrelevant; `CyberPanel` with: title "SIGN IN", mono hint text
  explaining paste-cookie flow (open the tracker in a browser, log in, copy the `Cookie`
  header e.g. via devtools), two `CyberTextField`s (Cookie header multiline, User-Agent
  singleLine with sensible default), `CyberButton("SAVE SESSION")` →
  `AppContainer.sessionStore.save(...)` + validate via settings probe then `onFinished()`,
  `CyberTextButton("CANCEL")`.
- `Main.kt`: replace stub with real `App(searchViewModel…, downloadsViewModel…,
  settingsViewModel…, sessionRepository…, loginOverlay = { onFinished ->
  DesktopLoginScreen(onFinished) }, onDownload = { AppContainer.download(it) })`.
Acceptance: `compileKotlinJvm` green; `./gradlew :composeApp:run` launches the window with
rail shell and tabs (manual smoke by runner); no androidMain edits.

### Task 7 — Final build + audit
- `./gradlew :composeApp:assembleDebug :composeApp:compileKotlinJvm` green.
- Audit greps (fix any hit): `Color(0x` outside `ui/theme|ui/components`; `fontSize =`
  outside `ui/theme|ui/components|Type.kt`; emoji literals in commonMain; `imePadding()`
  stacked with `navigationBarsPadding()` on same node; leftover `CyberLaunchStub`.
- Verify `DetailsScreen`/`SearchScreen` diffs kept all callbacks from the WIP baseline.
- Report: files touched, grep results, build outputs.

## Review rubric notes

- Spec compliance = exact hex values, breakpoints (700.dp), and behavioral parity per task.
- "Cannot verify from diff" items (runtime behavior) resolve to build-green + code reading.
- Emulator/macOS visual smoke is controller-owned after Task 7.
