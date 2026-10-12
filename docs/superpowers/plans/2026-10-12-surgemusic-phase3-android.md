# SurgeMusic Phase 3 — Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The SurgeMusic Android app: Compose UI (Search / Downloads / Settings), in-app WebView login that solves the Cloudflare reality automatically, downloads via the built-in engine in a foreground service, and files finalized into `Music/SurgeMusic/` via MediaStore so every music player sees them.

**Architecture:** Extend the Phase-1 `:core` KMP module with an `androidTarget` and an intermediate `jvmShared` source set (current `jvmMain` code — RutrackerClient, SearchPageParser, JLibTorrentEngine — becomes shared by desktop-JVM and Android). New `:composeApp` module (Compose Multiplatform): `commonMain` holds screens + view models (reused later by the desktop UI), `androidMain` holds the Activity, foreground service, WebView login, MediaStore finalizer and encrypted session store. Manual DI via an `AppContainer` singleton. (Order change vs. spec roadmap recorded in spec §15: user moved Android ahead of the desktop UI.)

**Tech Stack:** everything from Phase 1 + AGP 8.10.1, Compose Multiplatform 1.8.1 (Kotlin 2.1.20, compose compiler plugin), activity-compose 1.9.3. compileSdk 36, targetSdk 35, minSdk 29.

**Spec:** `docs/superpowers/specs/2026-10-12-surgemusic-design.md` (esp. §4 "Cloudflare reality", §6 storage, §8 UI). The plan argues from the spec; read both.

## Global Constraints

- All Phase-1 constraints still apply (package `com.surgemusic`, versions exactly from `gradle/libs.versions.toml`, credentials never committed, conventional commits, TDD).
- Android build config: `compileSdk = 36`, `targetSdk = 35`, `minSdk = 29`; applicationId/namespace `com.surgemusic.app`; jvmTarget 17 on Android targets (desktop JVM target keeps 21).
- **No database in this phase** — the DownloadManager is an in-memory state machine (spec §7 persistence is a hardening-phase item; deviation recorded).
- **No navigation library** — three-tab bottom navigation via simple state (spec §8 deferred Decompose; deviation recorded).
- View models are plain classes with `StateFlow` (no lifecycle-viewmodel dependency).
- **Never start the emulator in workflow tasks** — emulator verification happens in the main session after the build.
- Gate for every task: `./gradlew :core:jvmTest :core:testDebugUnitTest :composeApp:assembleDebug` (skip `:composeApp:assembleDebug` for Tasks 1–3 where the module doesn't exist yet).
- Manifest permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`.

---

### Task 1: Core gains the Android target + jvmShared source set

**Files:**
- Modify: `gradle/libs.versions.toml`, `core/build.gradle.kts`
- Create: `core/src/androidMain/AndroidManifest.xml`
- Move: `core/src/jvmMain/**` → `core/src/jvmShared/**` (mechanical), `core/src/jvmTest/**` → `core/src/jvmSharedTest/**` **except** `EngineSmokeTest.kt`, `LoopbackHarness.kt`, `TestTorrentFactory.kt` which stay in `jvmTest` (jlibtorrent natives for macOS must not load in Android unit tests).

**Interfaces:**
- Produces: `:core` builds for `jvm()` + `androidTarget()` with the Phase-1 API unchanged (`RutrackerClient`, `SearchPageParser`, `JLibTorrentEngine`, `TorrentEngine`, `TrackerClient`, models) now available on Android.

- [ ] **Step 1: Extend the version catalog**

Add to `gradle/libs.versions.toml`:
```toml
[versions]
# existing versions stay; add:
agp = "8.10.1"

[libraries]
# add:
jlibtorrent-android-arm64 = { module = "com.frostwire:jlibtorrent-android-arm64", version.ref = "jlibtorrent" }
jlibtorrent-android-x86_64 = { module = "com.frostwire:jlibtorrent-android-x86_64", version.ref = "jlibtorrent" }

[plugins]
# add:
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
```

- [ ] **Step 2: Rewrite `core/build.gradle.kts`**

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        // shared by desktop JVM and Android: Ktor, Jsoup, engine
        val jvmShared by creating { dependsOn(commonMain) }
        jvmMain {
            dependsOn(jvmShared)
            dependencies { implementation(libs.jlibtorrent.macosx.arm64) }
        }
        androidMain {
            dependsOn(jvmShared)
            dependencies {
                implementation(libs.jlibtorrent)
                implementation(libs.jlibtorrent.android.arm64)
                implementation(libs.jlibtorrent.android.x86_64)
            }
        }
        jvmShared.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.jsoup)
            implementation(libs.jlibtorrent)
        }
        val jvmSharedTest by creating { dependsOn(commonTest) }
        jvmTest {
            dependsOn(jvmSharedTest)
            // loopback engine tests stay here (macOS natives)
        }
        androidUnitTest { dependsOn(jvmSharedTest) }
        jvmSharedTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mockwebserver)
        }
    }
    jvmToolchain(21)
}

android {
    namespace = "com.surgemusic.core"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
}
```
`core/src/androidMain/AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 3: Move sources** (`git mv core/src/jvmMain core/src/jvmShared`, then `git mv core/src/jvmTest/kotlin/com/surgemusic/core/engine/jvm core/src/jvmTest/kotlin/com/surgemusic/core/engine/jvm-desktop-tmp` — put `EngineSmokeTest.kt`, `LoopbackHarness.kt`, `TestTorrentFactory.kt` back under `core/src/jvmTest/kotlin/com/surgemusic/core/engine/jvm/`, move everything else to `core/src/jvmSharedTest/`). Packages do not change.

- [ ] **Step 4: Run the gate**

Run: `./gradlew :core:jvmTest :core:testDebugUnitTest :cli:build`
Expected: BUILD SUCCESSFUL; the same 25 tests run green in BOTH `jvmTest` and `testDebugUnitTest` (engine loopback tests only in jvmTest).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "build(core): android target + jvmShared source set (desktop+android share scraper and engine)"
```

---

### Task 2: Engine API v2 — start/pause/resume/cancel with event stream

**Files:**
- Modify: `core/src/commonMain/kotlin/com/surgemusic/core/engine/TorrentEngine.kt`
- Modify: `core/src/jvmShared/kotlin/com/surgemusic/core/engine/jvm/JLibTorrentEngine.kt`
- Modify: `core/src/jvmTest/kotlin/com/surgemusic/core/engine/jvm/EngineSmokeTest.kt`, `LoopbackHarness.kt`
- Modify: `cli/src/main/kotlin/com/surgemusic/cli/Main.kt`

**Interfaces (produces; Tasks 3–8 depend on these EXACT names):**
```kotlin
enum class EngineState { DOWNLOADING, PAUSED, DONE, FAILED }

data class EngineSnapshot(
    val id: String, val state: EngineState,
    val bytesDone: Long, val bytesTotal: Long,
    val downloadSpeed: Long, val peers: Int,
)

sealed interface EngineEvent {
    data class Added(val id: String) : EngineEvent
    data class Progress(val id: String, val bytesDone: Long, val bytesTotal: Long, val downloadSpeed: Long, val peers: Int) : EngineEvent
    data class Completed(val id: String) : EngineEvent
    data class Failed(val id: String, val message: String) : EngineEvent
}

interface TorrentEngine : AutoCloseable {
    fun start(id: String, torrentBytes: ByteArray, targetDir: String)
    fun pause(id: String)
    fun resume(id: String)
    fun cancel(id: String)   // removes the torrent; no further events for id
    val events: kotlinx.coroutines.flow.SharedFlow<EngineEvent>
    fun snapshot(id: String): EngineSnapshot?
    fun listenPort(): Int
}
```
The old `download(...): Flow<EngineEvent>` API is REMOVED (CLI is the only consumer and is rewritten in this task).

- [ ] **Step 1: Update the loopback test first (red)** — rewrite `EngineSmokeTest` around the new API:
```kotlin
@Test fun downloadEmitsProgressAndCompletesForLocalSeed() {
    val harness = LoopbackHarness()
    try {
        val events = mutableListOf<EngineEvent>()
        runBlocking {
            withTimeout(180_000) {
                val job = launch {
                    harness.seedEngine.events.collect { events.add(it) }
                }
                harness.leechEngine.events.collect { }
                // collect until Completed for LEECH_ID seen:
            }
            // simplest: subscribe leech engine events BEFORE starting, then start and takeUntil Completed
        }
    } finally { harness.close() }
}
```
Concrete pattern (use this, it is simpler): expose in `LoopbackHarness`:
```kotlin
val seedEngine = JLibTorrentEngine(); val leechEngine = JLibTorrentEngine()
fun startSeed() = seedEngine.start("seed", torrentBytes, sourceDir.toAbsolutePath().toString())
fun startLeech() = leechEngine.start("leech", torrentBytes, targetDir.toAbsolutePath().toString(), extraPeers = listOf("127.0.0.1" to seedEngine.listenPort()))
```
Test body: `runBlocking { withTimeout(180_000) { leechEngine.events.takeWhile { it !is EngineEvent.Completed && it !is EngineEvent.Failed }.toList() } }` after `startSeed(); startLeech()`, asserting the last collected element after a second collect is `Completed("leech")` and some `Progress("leech", …)` had `bytesTotal > 0`. Keep the file-integrity assertions from Phase 1 (`downloadedFilesMatchSource`).

- [ ] **Step 2: Implement engine v2** (`JLibTorrentEngine` in jvmShared):

```kotlin
class JLibTorrentEngine : TorrentEngine {
    private val session = SessionManager()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<EngineEvent> = _events.asSharedFlow()
    private data class Active(val info: TorrentInfo, var handle: Handle?)
    private val active = ConcurrentHashMap<String, Active>()
    private val completed = ConcurrentHashMap.newKeySet<String>()

    init {
        session.start()
        scope.launch {
            while (isActive) {
                active.forEach { (id, a) ->
                    val h = a.handle ?: return@forEach
                    if (id in completed) return@forEach
                    runCatching {
                        val st = h.status()
                        val done = isFinished(st)
                        _events.tryEmit(
                            if (done) EngineEvent.Completed(id)
                            else EngineEvent.Progress(id, st.totalWantedDone(), st.totalWanted(),
                                st.downloadPayloadRate(), st.numPeers())
                        )
                        if (done) { completed.add(id); _events.tryEmit(EngineEvent.Completed(id)) }
                    }
                }
                delay(1000)
            }
        }
    }

    override fun start(id: String, torrentBytes: ByteArray, targetDir: String) {
        val info = TorrentInfo(torrentBytes)
        val dir = File(targetDir).apply { mkdirs() }
        active[id] = Active(info, null)
        session.download(info, dir)
        scope.launch { // wait for the handle to appear
            val deadline = System.currentTimeMillis() + 20_000
            while (active[id]?.handle == null && System.currentTimeMillis() < deadline) {
                val h = session.find(info.infoHash())
                if (h != null) { active[id]?.handle = h; _events.tryEmit(EngineEvent.Added(id)); break }
                delay(200)
            }
            if (active[id]?.handle == null) {
                active.remove(id)
                _events.tryEmit(EngineEvent.Failed(id, "torrent handle not added within timeout"))
            }
        }
    }
    // NOTE: emit Completed exactly once — do NOT emit it both inline and via the completed-set branch; pick one (the poll loop decides completion; the inline `if (done)` in the Progress construction is the same branch — emit Completed only from there, and guard with the completed set).

    override fun pause(id: String) { active[id]?.handle?.let { runCatching { it.pause() } } }
    override fun resume(id: String) { active[id]?.handle?.let { runCatching { it.resume() } } }
    override fun cancel(id: String) {
        active.remove(id)?.handle?.let { runCatching { session.remove(it) } }
    }
    override fun snapshot(id: String): EngineSnapshot? {
        val h = active[id]?.handle ?: return null
        return runCatching {
            val st = h.status()
            EngineSnapshot(id, when { isFinished(st) -> EngineState.DONE; st.flags().is_paused() || st.isPaused() -> EngineState.PAUSED; else -> EngineState.DOWNLOADING },
                st.totalWantedDone(), st.totalWanted(), st.downloadPayloadRate(), st.numPeers())
        }.getOrNull()
    }
    override fun listenPort(): Int = session.listenPort()
    override fun close() { scope.cancel(); runCatching { session.stop() } }
    private fun isFinished(st: TorrentStatus): Boolean =
        runCatching { st.flags().is_finished() }.getOrDefault(false) ||
            runCatching { st.isFinished() }.getOrDefault(false) || st.progress() >= 1.0f
}
```
(jlibtorrent method-name caveats from Phase 1 apply: `is_paused()`/`isPaused()` — keep whichever compiles; jlibtorrent `Handle.pause()/resume()` and `SessionManager.remove(Handle)` exist.)

- [ ] **Step 3: Rewrite the CLI download command** to the new API:
```kotlin
val engine = JLibTorrentEngine()
try {
    val id = "cli-$topicId"
    val collected = mutableListOf<EngineEvent>()
    engine.events
        .onEach { ev -> if (ev is EngineEvent.Progress && ev.id == id) printProgress(ev)
                        else if (ev is EngineEvent.Completed && ev.id == id) println("\ndownload complete: $outDir")
                        else if (ev is EngineEvent.Failed && ev.id == id) { println("\ndownload FAILED: ${ev.message}"); exitProcess(1) } }
        .takeWhile { !(it is EngineEvent.Completed && it.id == id) && !(it is EngineEvent.Failed && it.id == id) }
        .launchIn(this)   // main is runBlocking scope
    engine.start(id, torrentBytes, outDir)
    joinAll() / or simply: engine.events.filter { it.id == id }.takeWhile { ... }.collect { ... } AFTER start — replay=0 may miss early events; use a SharedFlow subscription started BEFORE engine.start (launch collector first, then start, then await a CompletableDeferred completed by the collector)
```
Use the robust pattern: create `val done = CompletableDeferred<Boolean>()`; launch collector that updates progress and completes `done` on Completed/Failed; call `engine.start(...)`; `done.await()`. Print helpers unchanged (`SizeParser.formatBytes`).

- [ ] **Step 4: Run the gate** — `./gradlew :core:jvmTest :core:testDebugUnitTest :cli:build` → green (loopback test proves start/Completed end-to-end).

- [ ] **Step 5: Commit** — `feat(core): engine api v2 (start/pause/resume/cancel + shared event stream)`

---

### Task 3: DownloadManager — queue + state machine over the engine

**Files:**
- Create: `core/src/commonMain/kotlin/com/surgemusic/core/downloads/DownloadManager.kt`
- Test: `core/src/jvmSharedTest/kotlin/com/surgemusic/core/downloads/DownloadManagerTest.kt` (runs on both JVM and Android unit tests)
- Test: `core/src/jvmSharedTest/kotlin/com/surgemusic/core/downloads/FakeEngine.kt`

**Interfaces (produces; Tasks 6–8 depend on these EXACT names):**
```kotlin
enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, FINALIZING, DONE, FAILED, CANCELLED }

data class DownloadRequest(
    val id: String,            // engine id, e.g. "t-6918211"
    val topicId: Long,
    val title: String,
    val forumName: String,
    val sizeBytes: Long,
)

data class DownloadItem(
    val request: DownloadRequest,
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDone: Long = 0,
    val bytesTotal: Long = request.sizeBytes,
    val downloadSpeed: Long = 0,
    val peers: Int = 0,
    val error: String? = null,
    val outputNote: String? = null,   // set on DONE: where files landed
)

class DownloadManager(
    engine: TorrentEngine,
    private val maxConcurrent: Int = 3,
    private val finalize: suspend (item: DownloadItem, stagingDir: String) -> Result<String>,
) {
    val items: StateFlow<List<DownloadItem>>
    fun enqueue(request: DownloadRequest, torrentBytes: ByteArray, stagingDir: String)
    fun pause(id: String); fun resume(id: String); fun cancel(id: String)
    fun pauseAll()
}
```
Behavior (test each): enqueue → QUEUED; pump starts up to `maxConcurrent` (engine.start called for that many); `Progress` updates item; `Completed` → FINALIZING → finalize() success → DONE (+outputNote) / failure → FAILED; finalize runs off the concurrency count; `Failed` → FAILED with error; pause → engine.pause + PAUSED (does not free a slot); cancel → engine.cancel + CANCELLED (frees the slot, next QUEUED starts); pauseAll.

`FakeEngine`: implements `TorrentEngine`; `start()` records calls and lets tests push events via an injectable `MutableSharedFlow`; `pause/resume/cancel` recorded.

- [ ] **Step 1: Write failing tests** (cases: `enqueuedItemStartsWhenSlotFree`, `concurrencyLimitHoldsAtMaxConcurrent`, `completedItemFinalizesThenDone`, `finalizeFailureMarksFailed`, `cancelFreesSlotForNextQueued`, `pausePauseAllTransitions`).
- [ ] **Step 2: Run to verify red** — `./gradlew :core:jvmTest --tests 'com.surgemusic.core.downloads.*'`
- [ ] **Step 3: Implement** `DownloadManager` (single `CoroutineScope(SupervisorJob() + Dispatchers.Default)`; `_items` updated via `update(id) { copy(...) }`; `pump()` after every state change; `engine.events` collected in `init`).
- [ ] **Step 4: Run the gate** — `./gradlew :core:jvmTest :core:testDebugUnitTest :cli:build` → green everywhere.
- [ ] **Step 5: Commit** — `feat(core): download manager with queue, concurrency and finalize hook`

---

### Task 4: composeApp module scaffold — tabs, theme, DI, manifest

**Files:**
- Modify: `settings.gradle.kts` (include `:composeApp`), `gradle/libs.versions.toml`
- Create: `composeApp/build.gradle.kts`, `composeApp/src/androidMain/AndroidManifest.xml`
- Create: `composeApp/src/commonMain/kotlin/com/surgemusic/app/App.kt` (root composable: 3-tab bottom nav `SearchTab`, `DownloadsTab`, `SettingsTab` via `mutableStateOf`), `Theme.kt` (Material3 dark/light)
- Create: `composeApp/src/commonMain/kotlin/com/surgemusic/app/AppViewModels.kt` (placeholder VMs with `StateFlow`s so screens compile)
- Create: `composeApp/src/androidMain/kotlin/com/surgemusic/app/MainActivity.kt`, `AppContainer.kt` (lazy singletons; wires nothing yet), `ui/PlatformStub.kt` if needed
- Create: `composeApp/src/androidMain/res/values/strings.xml` (`app_name` = SurgeMusic) and launcher icon: use `android:icon="@mipmap/ic_launcher"` only if a default exists — otherwise create `res/mipmap-anydpi-v26/ic_launcher.xml` adaptive icon with `res/drawable/ic_launcher_background.xml` (solid `#1D3A2E`) and a vector foreground `ic_launcher_foreground.xml` (simple white music-note path) — write the XML by hand, no image assets.

**Interfaces:**
- Consumes: `:core` (Task 1).
- Produces: `:composeApp:assembleDebug` green; `AppContainer` object with `lateinit var appContext: Context` + lazy `engine`, `sessionStore`, `settings`, `downloadManager`, `trackerClient()` (filled by Tasks 5–8); `MainActivity` shows the three tabs.

- [ ] **Step 1: Version catalog additions**
```toml
[versions]
composeMultiplatform = "1.8.1"
activityCompose = "1.9.3"

[libraries]
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }

[plugins]
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
kotlin-plugin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: `composeApp/build.gradle.kts`**
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
    jvmToolchain(21)
}

android {
    namespace = "com.surgemusic.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.surgemusic.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
```
(If the CMP 1.8.1 Gradle accessors differ — e.g. `compose.runtime` unresolved — fall back to explicit coordinates `org.jetbrains.compose.runtime:runtime:1.8.1`, `org.jetbrains.compose.foundation:foundation:1.8.1`, `org.jetbrains.compose.material3:material3:1.8.1`.)

- [ ] **Step 3: Manifest**
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:theme="@android:style/Theme.Material.NoActionBar"
        android:allowBackup="false">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service android:name=".downloads.DownloadService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
    </application>
</manifest>
```
(The `.downloads.DownloadService` class itself arrives in Task 7; to keep this task's gate green, either create a stub service class now (onStartCommand + START_STICKY, no notification yet) or omit the `<service>` entry until Task 7 — create the stub now, Task 7 completes it.)

- [ ] **Step 4: UI shell** — `App.kt`: `Scaffold` + `NavigationBar` with three `NavigationBarItem`s (icons from `materialIconsExtended`? — no: use text-only items or built-in `Icons.Default.Search`/`Icons.Default.Download`/`Icons.Default.Settings` from `compose.material.icons` which ships with material3 as `compose.materialIconsExtended`… avoid the extended pack: use `Icons.Filled.Search`, `Icons.Filled.Settings` from the core icons, and for Downloads use `Icons.Filled.Refresh`-free fallback: text label "DL" or a simple Box icon. Keep it simple: `NavigationBarItem` with `icon = { Text("🔍"/"⬇"/"⚙") }`? Emoji in code is fine.) Body switches on tab state; each tab is a placeholder `CenteredText("Search")` etc. for now.

- [ ] **Step 5: Gate + commit** — `./gradlew :composeApp:assembleDebug :core:jvmTest :core:testDebugUnitTest` → green; commit `feat(app): composeApp scaffold with three-tab shell and DI container`.

---

### Task 5: Encrypted session store + WebView login

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/surgemusic/app/session/SessionRepository.kt` (interface + InMemory impl for previews)
- Create: `composeApp/src/androidMain/kotlin/com/surgemusic/app/session/KeystoreSessionStore.kt`
- Create: `composeApp/src/androidMain/kotlin/com/surgemusic/app/ui/login/WebViewLoginScreen.kt`
- Modify: `AppContainer` (wire `sessionStore`)
- Test: `composeApp/src/androidUnitTest/kotlin/com/surgemusic/app/session/SessionCryptoTest.kt`

**Interfaces (produces):**
```kotlin
// commonMain
interface SessionRepository {
    val session: StateFlow<Session?>        // null = not logged in
    fun save(cookieHeader: String, userAgent: String)
    fun clear()
}
data class Session(val cookieHeader: String, val userAgent: String, val savedAtEpochMs: Long)
```
`KeystoreSessionStore(context)` implements it: AES-GCM key from `AndroidKeyStore` (alias `surgemusic_session`), encrypts `"cookieHeader\nuserAgent"` to a SharedPreferences blob (`session_v1`), decrypts on init; pure crypto helpers (`SessionCrypto.encrypt/decrypt` taking an explicit `SecretKey`) are what the unit test covers (fake `SecretKey` via `KeyGenerator.getInstance("AES")` — a real symmetric key, not AndroidKeyStore, so it runs in JVM unit tests).

`WebViewLoginScreen(onFinished: (Boolean) -> Unit)`: `AndroidView` with a `WebView`; JS + DOM storage + third-party cookies enabled; loads `https://rutracker.org/forum/login.php`; `onPageFinished` reads `CookieManager.getInstance().getCookie("https://rutracker.org")` — when it contains both `bb_session=` and `cf_clearance=`, saves via `AppContainer.sessionStore.save(cookies, webView.settings.userAgentString)` and calls `onFinished(true)`. A "Cancel" button calls `onFinished(false)`. The user types their rutracker credentials into the real page — Cloudflare and any captcha are handled by the real browser engine.

- [ ] **Step 1: Failing test** for `SessionCrypto` round-trip (encrypt → decrypt equals input; tampered ciphertext throws).
- [ ] **Step 2: Implement** crypto + store + WebView screen; wire `AppContainer.sessionStore`.
- [ ] **Step 3: Gate + commit** — `./gradlew :composeApp:assembleDebug :core:jvmTest :core:testDebugUnitTest`; commit `feat(app): keystore-encrypted session store + webview login`.

---

### Task 6: Search screen (results, pagination, auth-expired banner)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/surgemusic/app/ui/search/SearchScreen.kt`, `SearchViewModel.kt`
- Modify: `AppContainer.trackerClient()` (builds `RutrackerClient` with mirror from settings + `importSession` from session store when present), `App.kt` (real Search tab)

**Interfaces (consumes):** `RutrackerClient.search`, `SearchResultRow/Page`, `TrackerError(AUTH)`, `SessionRepository` (Task 5).
**Produces:** `SearchViewModel(query, searchFn: suspend (String, Int) -> Result<SearchResultPage>)` with `StateFlow`s: `query`, `results`, `uiState: SearchUiState` (`Idle`, `Loading`, `Ready(hasMore: Boolean)`, `Error(message: String, needsLogin: Boolean)`); functions `search()`, `loadMore()`, `retry()`.

Screen: `OutlinedTextField` + search button; `LazyColumn` of results (title 2 lines max, second line: forum · size (`SizeParser.formatBytes`) · ↑seeds ↓leechs · date); tapping a row → `onDownload(row)` callback (wired in Task 8; for now a no-op lambda parameter); "Load more" button at the end; `needsLogin` errors show a "Log in" button that sets `showLogin=true` → renders `WebViewLoginScreen` over the tab; pull-to-refresh NOT required.

- [ ] **Step 1: ViewModel with constructor-injected search function** (testable). Write `SearchViewModelTest` in `composeApp/src/androidUnitTest` with fake search fn: results append, loadMore appends, AUTH error sets `needsLogin`.
- [ ] **Step 2: Implement screen + wire into `App.kt`** (`AppContainer.trackerClient().search` passed in; on app start, if `sessionStore.session.value == null`, show a "Log in to rutracker" hero with a login button on the Search tab).
- [ ] **Step 3: Gate + commit** — `feat(app): search screen with pagination and login prompt`.

---

### Task 7: DownloadService + notifications + Wi-Fi gate

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/surgemusic/app/downloads/DownloadService.kt`
- Create: `composeApp/src/androidMain/kotlin/com/surgemusic/app/downloads/NetworkGate.kt`
- Modify: `AppContainer` (service start helper), `MainActivity` (request POST_NOTIFICATIONS on 33+; start service on resume)

**Behavior:**
- `DownloadService : Service` — `START_STICKY`; `onStartCommand` calls `startForeground(1, buildNotification(...))` immediately (required within 5s); observes `AppContainer.downloadManager.items`; posts an ongoing notification: title "SurgeMusic downloads", text like "2 active · 45 МБ/с · 3 queued", progress indeterminate (or `setProgress(0,0,true)`); a "Pause all" action (`PendingIntent.getService` with `ACTION_PAUSE_ALL` → `downloadManager.pauseAll()`); stops itself (and `stopForeground`) when no item is `QUEUED/DOWNLOADING/FINALIZING/PAUSED`.
- `NetworkGate(context)` — `fun register(onUnmeteredChanged: (Boolean) -> Unit)`: `ConnectivityManager.registerDefaultNetworkCallback` + `NetworkCapabilities.NET_CAPABILITY_NOT_METERED`; AppContainer wires: on lost unmetered + settings.wifiOnly → `pauseAll()`; on regained → resume items that were auto-paused (`DownloadItem` gains no field — the manager's `resume` is called for items in PAUSED whose `error == null`; acceptable v1: track auto-paused ids in the NetworkGate itself by observing items before pausing).
- `SettingsStore` (androidMain, SharedPreferences-backed): `wifiOnly: StateFlow<Boolean>` (default true), `mirror: StateFlow<String>` (default `https://rutracker.org`), `concurrentDownloads: StateFlow<Int>` (default 3). Created here (Task 8 uses it too).

- [ ] **Step 1: Implement service + gate + settings store; wire AppContainer.**
- [ ] **Step 2: Unit-test `SettingsStore`** (defaults + updates emit; plain `InMemorySharedPreferences`? — simplest: test via Robolectric-free fake by extracting a `KeyValueStore` interface implemented by SharedPreferences; test the fake).
- [ ] **Step 3: Gate + commit** — `./gradlew :composeApp:assembleDebug :core:jvmTest :core:testDebugUnitTest`; commit `feat(app): foreground download service, notifications, wifi gate, settings store`.

---

### Task 8: MediaStore finalizer + Downloads screen + full pipeline wiring

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/surgemusic/app/downloads/MediaStoreFinalizer.kt`
- Create: `composeApp/src/commonMain/kotlin/com/surgemusic/app/ui/downloads/DownloadsScreen.kt` (+ small VM wrapping `downloadManager.items`)
- Modify: `AppContainer` (`downloadManager = DownloadManager(engine, settings.concurrentDownloads.value) { item, staging -> MediaStoreFinalizer(context).finalize(item, staging) }`), `App.kt` (Downloads tab real; Search tab's `onDownload` enqueues)

**Pipeline wiring in `onDownload(row)`:**
```kotlin
val torrent = client.torrentFile(row.topicId).getOrElse { /* show snackbar */ return }
val staging = File(context.getExternalFilesDir("staging"), row.topicId.toString()).absolutePath
downloadManager.enqueue(
    DownloadRequest(id = "t-${row.topicId}", topicId = row.topicId, title = row.title, forumName = row.forumName, sizeBytes = row.sizeBytes),
    torrent, staging)
startDownloadService()
```

**MediaStoreFinalizer** (staging layout: engine downloads into `staging/<torrentName>/…`):
```kotlin
class MediaStoreFinalizer(private val context: Context) {
    private val audioExt = setOf("flac", "mp3", "ogg", "oga", "wav", "m4a", "aac", "ape", "wv", "opus")
    private val imageExt = setOf("jpg", "jpeg", "png", "webp")

    suspend fun finalize(item: DownloadItem, stagingDir: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = File(stagingDir)                       // …/staging/<topicId>/<torrentName>/files…
            val torrentDir = root.listFiles()?.firstOrNull { it.isDirectory } ?: root
            val albumDir = sanitize(item.request.title).take(120)
            var copied = 0
            torrentDir.walkTopDown().filter { it.isFile }.sortedBy { it.absolutePath }.forEach { f ->
                val rel = f.toRelativeString(torrentDir)
                val ext = f.extension.lowercase()
                val (collection, mime) = when (ext) {
                    in audioExt -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "audio/$ext"
                    in imageExt -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "image/$ext"
                    else -> return@forEach                                  // logs / nfo skipped
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/SurgeMusic/$albumDir/${f.parentFile?.let { it.toRelativeString(torrentDir) } ?: ""}".replace("//", "/"))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(collection, values)!!
                context.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
                copied++
            }
            root.deleteRecursively()
            "$copied files → Music/SurgeMusic/$albumDir"
        }
    }
    private fun sanitize(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
}
```

**DownloadsScreen**: `LazyColumn` over `items.value`; each card: title, forum, progress `LinearProgressProgress`… use `LinearProgressIndicator(progress = { if (bytesTotal>0) bytesDone.toFloat()/bytesTotal else 0f })`, status chip text, speed + peers while downloading; buttons: Pause/Resume toggle, Cancel (X). Empty state: "No downloads yet — search for music".

- [ ] **Step 1: Implement finalizer + screen + wiring.**
- [ ] **Step 2: Unit test** the path/mime selection logic by extracting `internal fun classifyFile(name: String): Pair<String?, String>?` and `internal fun relativePathFor(...)` into a small object tested in `composeApp/src/androidUnitTest`.
- [ ] **Step 3: Gate + commit** — `feat(app): downloads screen, mediastore finalize, full search→download pipeline`.

---

### Task 9: Settings screen + manual cookie import + README

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/surgemusic/app/ui/settings/SettingsScreen.kt`
- Modify: `App.kt`, `README.md`

**Settings screen items:**
- **Session**: shows "Logged in (saved <date>)" / "Not logged in" via `sessionStore.session`; buttons: "Log in" (opens WebViewLoginScreen), "Validate" (runs `client.search(SearchQuery("test"))`; shows result toast/inline), "Log out" (clear).
- **Manual import (advanced)**: two `OutlinedTextField`s (Cookie header, User-Agent) + "Import" button → `sessionStore.save(...)` — for users who prefer copying from desktop devtools; validate afterwards like above.
- **Network**: `Switch` "Download on Wi-Fi only" (settings.wifiOnly); `OutlinedTextField` mirror URL; `Slider` 1–5 concurrent downloads.
- About: version from `Version.APP`.

**README:** add an "Android app" section: `./gradlew :composeApp:assembleDebug` → APK at `composeApp/build/outputs/apk/debug/`, install via `adb install -r`, login flow description (in-app browser → credentials → done), storage location `Music/SurgeMusic/` visible to all players.

- [ ] **Step 1: Implement screen; wire Settings tab.**
- [ ] **Step 2: Gate + commit** — `./gradlew :composeApp:assembleDebug :core:jvmTest :core:testDebugUnitTest`; commit `feat(app): settings screen with session management and manual import; docs`.

---

## Plan self-review (already applied)

1. **Spec coverage:** Android app per spec §6 (scoped storage via MediaStore, FGS, Wi-Fi gate), §8 (Search/Downloads/Settings screens), §9 (Keystore-encrypted session), §4 (WebView login solves the Cloudflare reality — the spec's planned mechanism). DB persistence and mirror fail-over remain hardening-phase items (deviations recorded above). Desktop UI reuses Tasks 3–6, 8 (commonMain).
2. **Placeholders:** none — every step names files and carries code or exact commands. Task 4's icon is hand-written XML.
3. **Type consistency:** `EngineEvent(id, …)`/`TorrentEngine.start/pause/resume/cancel/events/snapshot` (Task 2) match `FakeEngine`/`DownloadManager` (Task 3) and the service/finalizer usage (Tasks 7–8); `SessionRepository`/`Session` (Task 5) match Search/Settings usage; `DownloadRequest/Item/State` names consistent throughout.
4. **Known risk:** AGP/CMP/Kotlin version matrix — pinned to a compatible set (Kotlin 2.1.20 + CMP 1.8.1 + AGP 8.10.1 + Gradle 8.14); gate failures on version issues must be resolved by adjusting versions in `libs.versions.toml` only, never by hacking module scripts beyond these pins.
