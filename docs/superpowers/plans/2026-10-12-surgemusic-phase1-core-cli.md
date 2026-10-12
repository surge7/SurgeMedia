# SurgeMusic Phase 1 — Core + Desktop CLI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the shared core (rutracker scraper client + jlibtorrent engine) and a desktop CLI that logs in, searches rutracker for music, and downloads a chosen torrent end-to-end into a music folder — Phases 0–2 of the spec.

**Architecture:** Gradle monorepo with a KMP `:core` module (commonMain models/interfaces, jvmMain Jsoup+Ktor scraper and jlibtorrent engine — the android target and jvmShared restructure arrive in the Phase-3 plan) and a plain-JVM `:cli` app. Desktop-first per the confirmed build order.

**Tech Stack:** Kotlin 2.1.20, Gradle 8.14, Ktor 3.1.1 (OkHttp engine), Jsoup 1.19.1, jlibtorrent 2.0.13.6 (MIT; natives `jlibtorrent-macosx-arm64` from `https://dl.frostwire.com/maven`), kotlin-test + MockWebServer.

**Spec:** `docs/superpowers/specs/2026-10-12-surgemusic-design.md` — the plan argues from the spec; read both.

## Global Constraints

- Package root `com.surgemusic`; module paths exactly as in each task.
- Dependency versions exactly as in `gradle/libs.versions.toml` (Task 1 creates it; do not add others without need).
- JVM toolchain **21** (host JDK); CI uses Java 21.
- Tests must pass: `./gradlew :core:jvmTest :cli:build` before every commit.
- Credentials NEVER committed: `~/.config/surgemusic/credentials` is the dev file (git-ignored); `core/liveFixtures/` is git-ignored.
- One commit per task, conventional commits (`feat:`, `test:`, `build:`, `docs:`).
- TDD: write the failing test, see it fail, implement, see it pass, commit.
- Legal notice (from spec §1) appears in README.
- If a jlibtorrent method name differs from this plan (versions vary), consult the jlibtorrent javadoc for the closest equivalent and note the substitution in the commit message.

---

### Task 1: Gradle monorepo scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `.gitignore`
- Create: `core/build.gradle.kts`, `core/src/commonMain/kotlin/com/surgemusic/core/Version.kt`
- Create: `cli/build.gradle.kts`, `cli/src/main/kotlin/com/surgemusic/cli/Main.kt`
- Create: `scripts/bootstrap-gradle.sh`

**Interfaces:**
- Produces: buildable multi-module project; `com.surgemusic.core.Version object { const val APP = "0.1.0" }`; CLI entry `com.surgemusic.cli.MainKt` printing version.

- [ ] **Step 1: Create root files**

`settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://dl.frostwire.com/maven") { name = "frostwire" }
    }
}
rootProject.name = "SurgeMusic"
include(":core", ":cli")
```

`build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
```

`gradle/libs.versions.toml`:
```toml
[versions]
kotlin = "2.1.20"
coroutines = "1.10.2"
ktor = "3.1.1"
jsoup = "1.19.1"
serializationJson = "1.8.1"
jlibtorrent = "2.0.13.6"
okhttp = "4.12.0"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serializationJson" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
jlibtorrent = { module = "com.frostwire:jlibtorrent", version.ref = "jlibtorrent" }
jlibtorrent-macosx-arm64 = { module = "com.frostwire:jlibtorrent-macosx-arm64", version.ref = "jlibtorrent" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

`.gitignore`:
```
.gradle/
build/
.kotlin/
core/liveFixtures/
*.torrent
.DS_Store
local.properties
```

- [ ] **Step 2: Create module build files**

`core/build.gradle.kts`:
```kotlin
plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    jvmToolchain(21)
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.jsoup)
            implementation(libs.jlibtorrent)
            implementation(libs.jlibtorrent.macosx.arm64)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mockwebserver)
        }
    }
}
```

`cli/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
}
application { mainClass = "com.surgemusic.cli.MainKt" }
```

`core/src/commonMain/kotlin/com/surgemusic/core/Version.kt`:
```kotlin
package com.surgemusic.core
object Version { const val APP = "0.1.0" }
```

`cli/src/main/kotlin/com/surgemusic/cli/Main.kt`:
```kotlin
package com.surgemusic.cli
import com.surgemusic.core.Version
fun main() = println("SurgeMusic ${Version.APP}")
```

- [ ] **Step 3: Bootstrap Gradle wrapper**

`scripts/bootstrap-gradle.sh` (chmod +x):
```bash
#!/usr/bin/env bash
set -euo pipefail
if ! command -v gradle >/dev/null; then
  echo "installing gradle via brew"
  brew install gradle
fi
gradle wrapper --gradle-version 8.14
```
Run: `./scripts/bootstrap-gradle.sh`
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/*` created. Verify `./gradlew --version` prints Gradle 8.14.

- [ ] **Step 4: Build and verify**

Run: `./gradlew :core:build :cli:run`
Expected: BUILD SUCCESSFUL, stdout `SurgeMusic 0.1.0`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "build: gradle monorepo scaffold (core KMP jvm + cli)"
```

---

### Task 2: Domain models, size parsing, tracker/engine contracts

**Files:**
- Create: `core/src/commonMain/kotlin/com/surgemusic/core/model/Models.kt`
- Create: `core/src/commonMain/kotlin/com/surgemusic/core/model/SizeParser.kt`
- Create: `core/src/commonMain/kotlin/com/surgemusic/core/tracker/TrackerClient.kt`
- Create: `core/src/commonMain/kotlin/com/surgemusic/core/engine/TorrentEngine.kt`
- Test: `core/src/commonTest/kotlin/com/surgemusic/core/model/SizeParserTest.kt`

**Interfaces:**
- Produces (all later tasks rely on these EXACT names):
  - `data class SearchResultRow(topicId: Long, title: String, forumId: Long, forumName: String, sizeBytes: Long, seeds: Int, leechs: Int, dateText: String)`
  - `data class SearchResultPage(rows: List<SearchResultRow>, start: Int)`
  - `data class SearchQuery(text: String, forumId: Long? = null, start: Int = 0)`
  - `object SizeParser { fun parseBytes(text: String): Long; fun formatBytes(bytes: Long): String }`
  - `enum class TrackerErrorKind { NETWORK, AUTH, CAPTCHA, PARSE, HTTP }`, `class TrackerError(message: String, val kind: TrackerErrorKind, val selector: String? = null) : Exception(message)`
  - `interface TrackerClient { suspend fun login(username: String, password: String): Result<Unit>; suspend fun search(query: SearchQuery): Result<SearchResultPage>; suspend fun torrentFile(topicId: Long): Result<ByteArray> }`
  - `sealed interface EngineEvent` with `Progress(bytesDone: Long, bytesTotal: Long, downloadSpeed: Long, peers: Int)`, `Completed`, `Failed(message: String)`
  - `interface TorrentEngine { fun download(torrentBytes: ByteArray, targetDir: String, extraPeers: List<Pair<String, Int>> = emptyList()): Flow<EngineEvent>; fun listenPort(): Int; fun close() }`

- [ ] **Step 1: Write the failing test**

`core/src/commonTest/kotlin/com/surgemusic/core/model/SizeParserTest.kt`:
```kotlin
package com.surgemusic.core.model
import kotlin.test.Test
import kotlin.test.assertEquals

class SizeParserTest {
    @Test fun parsesRussianUnits() {
        assertEquals(1L, SizeParser.parseBytes("1 Б"))
        assertEquals(1024L, SizeParser.parseBytes("2 КБ"))
        assertEquals((1.36 * 1024 * 1024 * 1024).toLong(), SizeParser.parseBytes("1.36 ГБ"))
        assertEquals(742 * 1024 * 1024L, SizeParser.parseBytes("742 МБ"))
        assertEquals(2L * 1024 * 1024 * 1024 * 1024, SizeParser.parseBytes("2 ТБ"))
    }
    @Test fun parsesEnglishUnitsAndNbsp() {
        assertEquals((1.5 * 1024 * 1024).toLong(), SizeParser.parseBytes("1.5\u00A0MB"))
        assertEquals(512L * 1024, SizeParser.parseBytes("512.00 KB"))
    }
    @Test fun parsesCommaDecimals() {
        assertEquals((1.25 * 1024).toLong(), SizeParser.parseBytes("1,25 КБ"))
    }
    @Test fun parsesTwoPartRange() {
        // rutracker sometimes renders "700 МБ+700 МБ" style; take the first token
        assertEquals(700L * 1024 * 1024, SizeParser.parseBytes("700 МБ + 700 МБ"))
    }
    @Test fun formatsRoundTrip() {
        val bytes = 1_468_003_328L
        val text = SizeParser.formatBytes(bytes)
        assertEquals(bytes, SizeParser.parseBytes(text))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests 'com.surgemusic.core.model.SizeParserTest'`
Expected: COMPILATION ERROR (unresolved `SizeParser`).

- [ ] **Step 3: Implement**

`core/src/commonMain/kotlin/com/surgemusic/core/model/Models.kt`:
```kotlin
package com.surgemusic.core.model

data class SearchResultRow(
    val topicId: Long,
    val title: String,
    val forumId: Long,
    val forumName: String,
    val sizeBytes: Long,
    val seeds: Int,
    val leechs: Int,
    val dateText: String,
)

data class SearchResultPage(val rows: List<SearchResultRow>, val start: Int)

data class SearchQuery(
    val text: String,
    val forumId: Long? = null,
    val start: Int = 0,
)
```

`core/src/commonMain/kotlin/com/surgemusic/core/model/SizeParser.kt`:
```kotlin
package com.surgemusic.core.model

object SizeParser {
    private val pattern = Regex("([\\d.,]+)\\s*([КкMмGгTtKk]?[БбBb]|КБ|МБ|ГБ|ТБ|KB|MB|GB|TB|KiB|MiB|GiB|TiB)")

    fun parseBytes(text: String): Long {
        val cleaned = text.replace("\u00A0", " ").replace(160.toChar(), ' ')
        val match = pattern.find(cleaned) ?: return 0L
        val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2].trim().uppercase()
        val mult = when (unit.firstOrNull()) {
            'К', 'K' -> 1024L
            'М', 'M' -> 1024L * 1024
            'Г', 'G' -> 1024L * 1024 * 1024
            'Т', 'T' -> 1024L * 1024 * 1024 * 1024
            else -> 1L
        }
        return (number * mult).toLong()
    }

    fun formatBytes(bytes: Long): String {
        var value = bytes.toDouble()
        val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var i = 0
        while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
        val s = if (value >= 100 || i == 0) value.toLong().toString() else String.format("%.2f", value)
        return "$s ${units[i]}"
    }
}
```

`core/src/commonMain/kotlin/com/surgemusic/core/tracker/TrackerClient.kt`:
```kotlin
package com.surgemusic.core.tracker

import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SearchResultPage

enum class TrackerErrorKind { NETWORK, AUTH, CAPTCHA, PARSE, HTTP }

class TrackerError(
    message: String,
    val kind: TrackerErrorKind,
    val selector: String? = null,
) : Exception(message)

interface TrackerClient {
    suspend fun login(username: String, password: String): Result<Unit>
    suspend fun search(query: SearchQuery): Result<SearchResultPage>
    suspend fun torrentFile(topicId: Long): Result<ByteArray>
}
```

`core/src/commonMain/kotlin/com/surgemusic/core/engine/TorrentEngine.kt`:
```kotlin
package com.surgemusic.core.engine

import kotlinx.coroutines.flow.Flow

sealed interface EngineEvent {
    data class Progress(val bytesDone: Long, val bytesTotal: Long, val downloadSpeed: Long, val peers: Int) : EngineEvent
    data object Completed : EngineEvent
    data class Failed(val message: String) : EngineEvent
}

interface TorrentEngine {
    fun download(torrentBytes: ByteArray, targetDir: String, extraPeers: List<Pair<String, Int>> = emptyList()): Flow<EngineEvent>
    fun listenPort(): Int
    fun close()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest`
Expected: PASS (all SizeParserTest tests green).

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -m "feat(core): domain models, size parser, tracker/engine contracts"
```

---

### Task 3: RutrackerClient — login

**Files:**
- Create: `core/src/jvmMain/kotlin/com/surgemusic/core/tracker/jvm/RutrackerClient.kt`
- Test: `core/src/jvmTest/kotlin/com/surgemusic/core/tracker/jvm/RutrackerClientLoginTest.kt`

**Interfaces:**
- Consumes: `TrackerClient`, `TrackerError` (Task 2).
- Produces: `class RutrackerClient(baseUrl: String = RutrackerClient.DEFAULT_BASE_URL) : TrackerClient, AutoCloseable` with `companion object { const val DEFAULT_BASE_URL = "https://rutracker.org"; const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36" }`. Internal: `internal fun cookieHeader(): String` (used by later tests via same-module access if needed).

- [ ] **Step 1: Write the failing tests**

`core/src/jvmTest/kotlin/com/surgemusic/core/tracker/jvm/RutrackerClientLoginTest.kt`:
```kotlin
package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RutrackerClientLoginTest {
    private val server = MockWebServer()
    private lateinit var client: RutrackerClient

    @AfterTest fun tearDown() { ::client.takeIf { it.isInitialized() }?.close(); server.shutdown() }

    private fun newClient() = RutrackerClient(baseUrl = server.url("/").toString().trimEnd('/')).also { client = it }

    @Test fun loginCapturesBbDataCookie() = runTest {
        server.enqueue(
            MockResponse().addHeader("Set-Cookie", "bb_data=abc123; path=/; domain=.rutracker.org")
                .setBody("<html><body>logged in</body></html>")
        )
        val result = newClient().login("user", "pass")
        assertTrue(result.isSuccess, "login should succeed: ${result.exceptionOrNull()}")
        val req = server.takeRequest()
        assertEquals("/forum/login.php", req.path?.substringBefore('?'))
        assertTrue(req.body.readUtf8().contains("login_username=user"))
    }

    @Test fun loginFailureWithoutCookieIsAuthError() = runTest {
        server.enqueue(
            MockResponse().setBody("""<html><form id="frm-login" action="login.php"><span class="error">Неверный пароль</span></form></html>""")
        )
        val result = newClient().login("user", "wrong")
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as TrackerError
        assertEquals(TrackerErrorKind.AUTH, err.kind)
    }

    @Test fun captchaPageIsCaptchaError() = runTest {
        server.enqueue(MockResponse().setBody("<html>please complete the CAPTCHA</html>"))
        val result = newClient().login("user", "pass")
        val err = result.exceptionOrNull() as TrackerError
        assertEquals(TrackerErrorKind.CAPTCHA, err.kind)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests 'com.surgemusic.core.tracker.jvm.RutrackerClientLoginTest'`
Expected: COMPILATION ERROR (unresolved `RutrackerClient`).

- [ ] **Step 3: Implement**

`core/src/jvmMain/kotlin/com/surgemusic/core/tracker/jvm/RutrackerClient.kt`:
```kotlin
package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SearchResultPage
import com.surgemusic.core.tracker.TrackerClient
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RutrackerClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) : TrackerClient, AutoCloseable {

    private val http = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }
    @Volatile private var bbData: String? = null

    override suspend fun login(username: String, password: String): Result<Unit> = runCatchingCtx {
        val resp = withContext(Dispatchers.IO) {
            http.post("$baseUrl/forum/login.php") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(Parameters.build {
                    append("login_username", username)
                    append("login_password", password)
                    append("login", "Вход")
                }))
            }
        }
        val body = resp.bodyAsText()
        val cookie = resp.headers.values("Set-Cookie")
            .firstNotNullOfOrNull { Regex("bb_data=([^;]+)").find(it)?.groupValues?.get(1) }
        when {
            cookie != null -> bbData = cookie
            body.contains("captcha", ignoreCase = true) ->
                throw TrackerError("login requires captcha; try again later or use a mirror", TrackerErrorKind.CAPTCHA)
            else -> throw TrackerError(parseLoginError(body) ?: "login failed (no bb_data cookie)", TrackerErrorKind.AUTH)
        }
    }

    internal fun cookieHeader(): String = "bb_data=" + (bbData ?: throw TrackerError("not logged in", TrackerErrorKind.AUTH))

    private fun parseLoginError(html: String): String? =
        Regex("<span class=\"error\"[^>]*>([^<]+)</span>").find(html)?.groupValues?.get(1)?.trim()

    override fun close() = http.close()

    private companion object {
        // Placeholder to keep the compiler happy while search/torrentFile land in Tasks 5/7.
    }

    private suspend fun <T> notImplementedYet(): T = throw NotImplementedError()
    override suspend fun search(query: SearchQuery): Result<SearchResultPage> = runCatching { notImplementedYet() }
    override suspend fun torrentFile(topicId: Long): Result<ByteArray> = runCatching { notImplementedYet() }
}

/** runCatching variant that does not catch CancellationException. */
private inline fun <T> runCatchingCtx(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
```
Note: set the browser User-Agent on every request in this class by adding `header("User-Agent", USER_AGENT)` inside each request builder; add `const val USER_AGENT` and `const val DEFAULT_BASE_URL = "https://rutracker.org"` to the companion object (fill the placeholder companion shown above).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -m "feat(core): rutracker login with cookie capture and typed errors"
```

---

### Task 4: SearchPageParser (Jsoup, fixture-driven)

**Files:**
- Create: `core/src/jvmMain/kotlin/com/surgemusic/core/tracker/jvm/SearchPageParser.kt`
- Test: `core/src/jvmTest/kotlin/com/surgemusic/core/tracker/jvm/SearchPageParserTest.kt`
- Test fixture: `core/src/jvmTest/resources/fixtures/search_two_rows.html`

**Interfaces:**
- Consumes: `SearchResultRow`, `SearchResultPage`, `SizeParser` (Task 2).
- Produces: `object SearchPageParser { fun parse(html: String, start: Int): SearchResultPage }`; throws `TrackerError(PARSE, selector=...)` on structural failure.

- [ ] **Step 1: Create the fixture**

`core/src/jvmTest/resources/fixtures/search_two_rows.html` (modeled on rutracker `tracker.php` markup):
```html
<!DOCTYPE html><html><head><meta charset="utf-8"></head><body>
<table id="tor-tbl" class="forumline table">
<tbody>
<tr class="tCenter">
  <td class="row1"><a href="#" title="Torrent icon"></a></td>
  <td class="row4 f-name"><a href="viewforum.php?f=1234">Музыка (lossless)</a></td>
  <td class="row4 tLeft"><div class="t-title"><a class="tt-link" href="viewtopic.php?t=567890">Pink Floyd - The Dark Side of the Moon (1973) [FLAC&lt;Remaster&gt;]</a></div></td>
  <td class="row4 small tCenter nowrap"><a title="Размер" href="#">1.36&nbsp;ГБ</a></td>
  <td class="row4 seedmed"><b>34</b></td>
  <td class="row4 leechmed"><b>2</b></td>
  <td class="row4 small">2024-05-01&nbsp;12:33</td>
</tr>
<tr class="tCenter">
  <td class="row1"><a href="#" title="Torrent icon"></a></td>
  <td class="row4 f-name"><a href="viewforum.php?f=22">Музыка (MP3)</a></td>
  <td class="row4 tLeft"><div class="t-title"><a class="tt-link" href="viewtopic.php?t=991002">Pink Floyd - The Wall (1979) [MP3]</a></div></td>
  <td class="row4 small tCenter nowrap"><a title="Размер" href="#">742.32&nbsp;МБ</a></td>
  <td class="row4 seedmed"><b>12</b></td>
  <td class="row4 leechmed"><b>0</b></td>
  <td class="row4 small">2023-11-02&nbsp;09:12</td>
</tr>
</tbody></table>
</body></html>
```

- [ ] **Step 2: Write the failing tests**

`core/src/jvmTest/kotlin/com/surgemusic/core/tracker/jvm/SearchPageParserTest.kt`:
```kotlin
package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.model.SizeParser
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearchPageParserTest {

    private fun fixture(): String =
        javaClass.getResource("/fixtures/search_two_rows.html")!!.readText()

    @Test fun parsesAllRows() {
        val page = SearchPageParser.parse(fixture(), start = 0)
        assertEquals(2, page.rows.size)
        assertEquals(0, page.start)
        val first = page.rows[0]
        assertEquals(567890L, first.topicId)
        assertEquals("Pink Floyd - The Dark Side of the Moon (1973) [FLAC<Remaster>]", first.title)
        assertEquals(1234L, first.forumId)
        assertEquals("Музыка (lossless)", first.forumName)
        assertEquals(SizeParser.parseBytes("1.36 ГБ"), first.sizeBytes)
        assertEquals(34, first.seeds)
        assertEquals(2, first.leechs)
        assertEquals("2024-05-01 12:33", first.dateText)
        val second = page.rows[1]
        assertEquals(991002L, second.topicId)
        assertEquals(12, second.seeds)
        assertEquals(0, second.leechs)
    }

    @Test fun loginFormPageThrowsAuth() {
        val err = assertFailsWith<TrackerError> {
            SearchPageParser.parse("""<html><form id="frm-login" action="login.php"></form></html>""", start = 0)
        }
        assertEquals(TrackerErrorKind.AUTH, err.kind)
    }

    @Test fun unknownMarkupThrowsParseErrorNamingSelector() {
        val err = assertFailsWith<TrackerError> { SearchPageParser.parse("<html><body>garbage</body></html>", start = 0) }
        assertEquals(TrackerErrorKind.PARSE, err.kind)
        assertTrue(err.selector.isNullOrEmpty().not())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests 'com.surgemusic.core.tracker.jvm.SearchPageParserTest'`
Expected: COMPILATION ERROR (unresolved `SearchPageParser`).

- [ ] **Step 4: Implement**

`core/src/jvmMain/kotlin/com/surgemusic/core/tracker/jvm/SearchPageParser.kt`:
```kotlin
package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.model.SearchResultPage
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.model.SizeParser
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import org.jsoup.Jsoup

object SearchPageParser {

    fun parse(html: String, start: Int): SearchResultPage {
        if (html.contains("id=\"frm-login\"") || html.contains("action=\"login.php\"")) {
            throw TrackerError("session expired (login form returned)", TrackerErrorKind.AUTH)
        }
        val doc = Jsoup.parse(html)
        val table = doc.selectFirst("#tor-tbl")
            ?: throw TrackerError("search table not found — site layout changed?", TrackerErrorKind.PARSE, selector = "#tor-tbl")
        val rows = table.select("tbody tr").mapNotNull { tr ->
            val link = tr.selectFirst("a.tt-link") ?: return@mapNotNull null
            val href = link.attr("href")
            val topicId = Regex("t=(\\d+)").find(href)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            val forumLink = tr.selectFirst("td.f-name a")
            val forumId = forumLink?.attr("href")?.let { Regex("f=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
            val sizeText = tr.selectFirst("a[title=Размер]")?.text() ?: "0 Б"
            val seeds = tr.selectFirst("td.seedmed b")?.text()?.trim()?.toIntOrNull() ?: 0
            val leechs = tr.selectFirst("td.leechmed b")?.text()?.trim()?.toIntOrNull() ?: 0
            val dateText = tr.select("td").lastOrNull()?.text()?.replace("\u00A0", " ")?.trim() ?: ""
            SearchResultRow(
                topicId = topicId,
                title = link.text().trim(),
                forumId = forumId,
                forumName = forumLink?.text()?.trim() ?: "",
                sizeBytes = SizeParser.parseBytes(sizeText),
                seeds = seeds,
                leechs = leechs,
                dateText = dateText,
            )
        }
        return SearchResultPage(rows = rows, start = start)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:jvmTest`
Expected: PASS. IMPORTANT: `loginFormPageThrowsAuth` asserts the AUTH check happens before the `#tor-tbl` lookup.

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -m "feat(core): jsoup search page parser with fixture tests"
```

---

### Task 5: RutrackerClient — search

**Files:**
- Modify: `core/src/jvmMain/kotlin/com/surgemusic/core/tracker/jvm/RutrackerClient.kt` (replace the `search` stub)
- Test: `core/src/jvmTest/kotlin/com/surgemusic/core/tracker/jvm/RutrackerClientSearchTest.kt`

**Interfaces:**
- Consumes: `SearchPageParser` (Task 4), login state from Task 3.
- Produces: working `RutrackerClient.search`.

- [ ] **Step 1: Write the failing test**

`core/src/jvmTest/kotlin/com/surgemusic/core/tracker/jvm/RutrackerClientSearchTest.kt`:
```kotlin
package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RutrackerClientSearchTest {
    private val server = MockWebServer()
    private lateinit var client: RutrackerClient

    @AfterTest fun tearDown() { ::client.takeIf { it.isInitialized() }?.close(); server.shutdown() }

    private fun fixture() = javaClass.getResource("/fixtures/search_two_rows.html")!!.readText()

    @Test fun searchParsesResultsAndSendsCookie() = runTest {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "bb_data=ok").setBody("<html>ok</html>"))
        server.enqueue(MockResponse().setBody(fixture()))
        val c = RutrackerClient(baseUrl = server.url("/").toString().trimEnd('/')).also { client = it }
        assertTrue(c.login("user", "pass").isSuccess)

        val result = c.search(SearchQuery(text = "pink floyd", start = 0))
        assertTrue(result.isSuccess, "${result.exceptionOrNull()}")
        assertEquals(2, result.getOrNull()!!.rows.size)

        val searchReq = server.takeRequest(2) // skip the login request
        assertTrue(searchReq.path!!.startsWith("/forum/tracker.php"))
        assertTrue(searchReq.path!!.contains("nm=pink+floyd") || searchReq.path!!.contains("nm=pink%20floyd"))
        assertTrue(searchReq.headers["Cookie"]!!.contains("bb_data=ok"))
        assertTrue(searchReq.headers["User-Agent"]!!.startsWith("Mozilla/5.0"))
    }

    @Test fun searchWithoutLoginFailsClosed() = runTest {
        val c = RutrackerClient(baseUrl = server.url("/").toString().trimEnd('/')).also { client = it }
        val result = c.search(SearchQuery(text = "x"))
        assertTrue(result.isFailure)
        assertEquals(TrackerErrorKind.AUTH, (result.exceptionOrNull() as TrackerError).kind)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests 'com.surgemusic.core.tracker.jvm.RutrackerClientSearchTest'`
Expected: FAILURE (search not implemented).

- [ ] **Step 3: Implement** — replace the `search` stub in `RutrackerClient`:

```kotlin
override suspend fun search(query: SearchQuery): Result<SearchResultPage> = runCatchingCtx {
    require(query.text.isNotBlank()) { "query text must not be blank" }
    val html = withContext(Dispatchers.IO) {
        http.get("$baseUrl/forum/tracker.php") {
            header("User-Agent", USER_AGENT)
            header("Cookie", cookieHeader())
            url.parameters.append("nm", query.text)
            url.parameters.append("start", query.start.toString())
            query.forumId?.let { url.parameters.append("f[]", it.toString()) }
        }.bodyAsText()
    }
    SearchPageParser.parse(html, query.start)
}
```
(`url.parameters.append` — if this Ktor version rejects it, use `parameter("nm", query.text)` etc. directly on the request builder; both compile on Ktor 3.x. Remove the stub and its `notImplementedYet` helper once both stubs are gone.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -m "feat(core): rutracker search wired to parser"
```

---

### Task 6: RutrackerClient — torrentFile

**Files:**
- Modify: `core/src/jvmMain/kotlin/com/surgemusic/core/tracker/jvm/RutrackerClient.kt` (replace the `torrentFile` stub; remove `notImplementedYet`)
- Test: `core/src/jvmTest/kotlin/com/surgemusic/core/tracker/jvm/RutrackerClientTorrentFileTest.kt`

**Interfaces:**
- Consumes: login state (Task 3).
- Produces: working `RutrackerClient.torrentFile(topicId)` returning raw `.torrent` bytes.

- [ ] **Step 1: Write the failing test**

`core/src/jvmTest/kotlin/com/surgemusic/core/tracker/jvm/RutrackerClientTorrentFileTest.kt`:
```kotlin
package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RutrackerClientTorrentFileTest {
    private val server = MockWebServer()
    private lateinit var client: RutrackerClient

    @AfterTest fun tearDown() { ::client.takeIf { it.isInitialized() }?.close(); server.shutdown() }

    private suspend fun loggedInClient(): RutrackerClient {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "bb_data=ok").setBody("<html>ok</html>"))
        val c = RutrackerClient(baseUrl = server.url("/").toString().trimEnd('/')).also { client = it }
        assertTrue(c.login("user", "pass").isSuccess)
        return c
    }

    @Test fun returnsTorrentBytes() = runTest {
        val c = loggedInClient()
        val torrentBytes = "d8:announce29:https://bt.t-ru.org/announce10:info.dict4:infod6:lengthi1024eee"
            .toByteArray(Charsets.ISO_8859_1)
        server.enqueue(MockResponse().setBody(Buffer().write(torrentBytes)))
        val result = c.torrentFile(567890L)
        assertTrue(result.isSuccess, "${result.exceptionOrNull()}")
        assertContentEquals(torrentBytes, result.getOrNull())
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/forum/dl.php"))
        assertTrue(req.path!!.contains("t=567890"))
    }

    @Test fun htmlInsteadOfTorrentIsAuthError() = runTest {
        val c = loggedInClient()
        server.enqueue(MockResponse().setBody("""<html><form id="frm-login" action="login.php"></form></html>"""))
        val result = c.torrentFile(1L)
        assertTrue(result.isFailure)
        assertEquals(TrackerErrorKind.AUTH, (result.exceptionOrNull() as TrackerError).kind)
    }

    @Test fun unexpectedHtmlIsParseError() = runTest {
        val c = loggedInClient()
        server.enqueue(MockResponse().setBody("<html>some other page</html>"))
        val result = c.torrentFile(1L)
        assertEquals(TrackerErrorKind.PARSE, (result.exceptionOrNull() as TrackerError).kind)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests 'com.surgemusic.core.tracker.jvm.RutrackerClientTorrentFileTest'`
Expected: FAILURE.

- [ ] **Step 3: Implement** — replace the `torrentFile` stub:

```kotlin
override suspend fun torrentFile(topicId: Long): Result<ByteArray> = runCatchingCtx {
    val resp = withContext(Dispatchers.IO) {
        http.get("$baseUrl/forum/dl.php") {
            header("User-Agent", USER_AGENT)
            header("Cookie", cookieHeader())
            url.parameters.append("t", topicId.toString())
        }
    }
    val bytes = resp.readBytes()
    if (bytes.isNotEmpty() && bytes[0] == 'd'.code.toByte()) bytes
    else {
        val text = String(bytes, Charsets.UTF_8)
        when {
            text.contains("captcha", ignoreCase = true) ->
                throw TrackerError("download requires captcha; retry later", TrackerErrorKind.CAPTCHA)
            text.contains("frm-login") || text.contains("login.php", ignoreCase = true) ->
                throw TrackerError("session expired while fetching torrent", TrackerErrorKind.AUTH)
            else -> throw TrackerError("unexpected dl.php response", TrackerErrorKind.PARSE)
        }
    }
}
```
(`resp.readBytes()` is the Ktor 3 name; if absent use `resp.bodyAsBytes()`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest`
Expected: PASS (entire module).

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -m "feat(core): torrent file download with content validation"
```

---

### Task 7: jlibtorrent engine wrapper

**Files:**
- Create: `core/src/jvmMain/kotlin/com/surgemusic/core/engine/jvm/JLibTorrentEngine.kt`
- Test: `core/src/jvmTest/kotlin/com/surgemusic/core/engine/jvm/EngineSmokeTest.kt`

**Interfaces:**
- Consumes: `TorrentEngine`, `EngineEvent` (Task 2).
- Produces: `class JLibTorrentEngine : TorrentEngine` — constructor starts a libtorrent `SessionManager`; `download()` adds the torrent and polls status into a Flow until finished.

- [ ] **Step 1: Write the failing smoke test** (unit-level; the real loopback transfer is Task 8)

`core/src/jvmTest/kotlin/com/surgemusic/core/engine/jvm/EngineSmokeTest.kt`:
```kotlin
package com.surgemusic.core.engine.jvm

import com.surgemusic.core.engine.EngineEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class EngineSmokeTest {
    @Test fun downloadEmitsProgressAndCompletesForLocalSeed() {
        // Uses the loopback harness from Task 8's TestTorrentFactory if present;
        // minimal version here: download from a locally seeded session.
        val harness = LoopbackHarness()
        try {
            val events = runBlocking {
                withTimeout(180_000) {
                    harness.leecherDownload().transformWhile { e ->
                        emit(e)
                        e !is EngineEvent.Completed && e !is EngineEvent.Failed
                    }.toList()
                }
            }
            assertTrue(events.last() is EngineEvent.Completed, "last=$events")
            assertTrue(events.any { it is EngineEvent.Progress && it.bytesTotal > 0 })
        } finally { harness.close() }
    }
}
```
This test intentionally will not compile until Task 8's `LoopbackHarness` exists — implement Task 7's engine first, then Task 8 makes the test green. For Task 7's own red-green cycle, temporarily gate it behind `@kotlin.test.Ignore` and remove the annotation in Task 8. State this explicitly in the commit message.

- [ ] **Step 2: Implement the engine**

`core/src/jvmMain/kotlin/com/surgemusic/core/engine/jvm/JLibTorrentEngine.kt`:
```kotlin
package com.surgemusic.core.engine.jvm

import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.TorrentStatus
import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.TorrentEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class JLibTorrentEngine : TorrentEngine {

    private val session = SessionManager()

    init { session.start() }

    override fun listenPort(): Int = session.listenPort()

    override fun download(
        torrentBytes: ByteArray,
        targetDir: String,
        extraPeers: List<Pair<String, Int>>,
    ): Flow<EngineEvent> = flow {
        val info = TorrentInfo(torrentBytes)
        val dir = File(targetDir).apply { mkdirs() }
        session.download(info, dir)

        val handle = withTimeoutOrNull(20_000) {
            while (true) {
                val h = session.find(info.infoHash())
                if (h != null) return@withTimeoutOrNull h
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: run {
            emit(EngineEvent.Failed("torrent handle not added within timeout"))
            return@flow
        }

        extraPeers.forEach { (ip, port) -> runCatching { handle.addPeer(ip, port) } }

        while (true) {
            val st: TorrentStatus = handle.status()
            if (isFinished(st)) {
                emit(EngineEvent.Completed)
                return@flow
            }
            emit(
                EngineEvent.Progress(
                    bytesDone = st.totalWantedDone(),
                    bytesTotal = st.totalWanted(),
                    downloadSpeed = st.downloadPayloadRate(),
                    peers = st.numPeers(),
                )
            )
            delay(1000)
        }
    }

    private fun isFinished(st: TorrentStatus): Boolean =
        runCatching { st.flags().is_finished() }.getOrDefault(false) ||
            runCatching { st.isFinished() }.getOrDefault(false) ||
            st.progress() >= 1.0f

    override fun close() = session.stop()
}
```
Note the deliberate `runCatching` on the finished-check: jlibtorrent versions differ in which wrapper spellings exist (`is_finished()` on the swig flags object vs `isFinished()` on the wrapper). Keep whichever compiles, drop the others, and note it in the commit.

- [ ] **Step 3: Compile check**

Run: `./gradlew :core:compileKotlinJvm`
Expected: BUILD SUCCESSFUL. If `handle.addPeer(ip, port)` does not exist in this jlibtorrent build, look for `addPeer(String, Int)` overloads, else `handle.swig().connect_peer(...)`; as a last resort delete the `extraPeers` loop entirely (LSD will still connect localhost sessions in Task 8's test) and record the substitution.

- [ ] **Step 4: Commit**

```bash
git add core/
git commit -m "feat(core): jlibtorrent engine wrapper with progress flow"
```

---

### Task 8: Loopback transfer integration test + bencode test factory

**Files:**
- Create: `core/src/jvmTest/kotlin/com/surgemusic/core/engine/jvm/TestTorrentFactory.kt`
- Create: `core/src/jvmTest/kotlin/com/surgemusic/core/engine/jvm/LoopbackHarness.kt`
- Modify: `core/src/jvmTest/kotlin/com/surgemusic/core/engine/jvm/EngineSmokeTest.kt` (remove `@Ignore` if it was added in Task 7)

**Interfaces:**
- Consumes: `JLibTorrentEngine` (Task 7).
- Produces (test-only): `object TestTorrentFactory { fun create(rootDir: Path, fileNames: List<String>, pieceLength: Int = 32768): ByteArray }` (multi-file torrent covering the given files under `rootDir`, name = `rootDir.fileName`); `class LoopbackHarness { fun leecherDownload(): Flow<EngineEvent>; fun close() }` which seeds from a temp source dir on one engine and downloads on another.

- [ ] **Step 1: Implement TestTorrentFactory** (bencode + SHA1)

`core/src/jvmTest/kotlin/com/surgemusic/core/engine/jvm/TestTorrentFactory.kt`:
```kotlin
package com.surgemusic.core.engine.jvm

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

object TestTorrentFactory {

    fun create(rootDir: Path, fileNames: List<String>, pieceLength: Int = 32768): ByteArray {
        val files = fileNames.map { rootDir.resolve(it) }
        val allBytes = ByteArray(files.sumOf { it.fileSize().toInt() })
        var offset = 0
        for (f in files) {
            val b = f.readBytes()
            System.arraycopy(b, 0, allBytes, offset, b.size)
            offset += b.size
        }
        val md = MessageDigest.getInstance("SHA-1")
        val pieces = ByteArrayOutputStream()
        var i = 0
        while (i < allBytes.size) {
            val len = minOf(pieceLength, allBytes.size - i)
            pieces.write(md.digest(allBytes.copyOfRange(i, i + len)))
            i += len
        }
        val info = mapOf(
            "piece length" to pieceLength.toLong(),
            "pieces" to pieces.toByteArray(),
            "name" to rootDir.fileName.toString(),
            "files" to files.map { mapOf("length" to it.fileSize(), "path" to listOf(it.name)) },
        )
        return bencode(mapOf("info" to info))
    }

    fun bencode(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, value: Any) {
        when (value) {
            is Long, is Int -> out.write("i${value}e".toByteArray())
            is ByteArray -> out.write("${value.size}:".toByteArray()).also { out.write(value) }
            is String -> write(out, value.toByteArray(Charsets.UTF_8))
            is Map<*, *> -> {
                out.write('d'.code)
                value.entries.sortedBy { (k, _) -> k.toString() }.forEach { (k, v) ->
                    write(out, k.toString()); write(out, v!!)
                }
                out.write('e'.code)
            }
            is List<*> -> {
                out.write('l'.code)
                value.forEach { write(out, it!!) }
                out.write('e'.code)
            }
            else -> throw IllegalArgumentException("cannot bencode ${value::class}")
        }
    }

    /** Convenience: list of regular file names directly under dir. */
    fun regularFilesUnder(dir: Path): List<String> =
        dir.listDirectoryEntries().filter { !it.isDirectory() }.map { it.name }.sorted()
}
```

- [ ] **Step 2: Implement LoopbackHarness**

`core/src/jvmTest/kotlin/com/surgemusic/core/engine/jvm/LoopbackHarness.kt`:
```kotlin
package com.surgemusic.core.engine.jvm

import com.surgemusic.core.engine.EngineEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.writeBytes

class LoopbackHarness : AutoCloseable {

    private val seedEngine = JLibTorrentEngine()
    private val leechEngine = JLibTorrentEngine()
    val sourceDir: Path = Files.createTempDirectory("surgemusic-seed")
    val targetDir: Path = Files.createTempDirectory("surgemusic-leech")
    private val torrentBytes: ByteArray

    init {
        (sourceDir / "track01.mp3").writeBytes(ByteArray(700_000) { (it * 31 + 7).toByte() })
        (sourceDir / "track02.mp3").writeBytes(ByteArray(300_000) { (it * 17 + 3).toByte() })
        torrentBytes = TestTorrentFactory.create(sourceDir, listOf("track01.mp3", "track02.mp3"))
        // Seed: adding the torrent where complete files already exist turns the handle into a seeder.
        runBlocking {
            withTimeout(60_000) {
                seedEngine.download(torrentBytes, sourceDir.toAbsolutePath().toString()).collect { }
            }
            // wait for the seed listen port to bind
            withTimeout(15_000) {
                while (seedEngine.listenPort() == 0) Thread.sleep(200)
            }
        }
    }

    fun leecherDownload(): Flow<EngineEvent> =
        leechEngine.download(
            torrentBytes,
            targetDir.toAbsolutePath().toString(),
            extraPeers = listOf("127.0.0.1" to seedEngine.listenPort()),
        )

    override fun close() {
        seedEngine.close()
        leechEngine.close()
        sourceDir.toFile().deleteRecursively()
        targetDir.toFile().deleteRecursively()
    }
}
```
Note: seeding via `download()` relies on libtorrent verifying the already-complete files and flipping to seeding; the init-block `collect { }` just waits out that check (it emits `Completed` immediately once verified).

- [ ] **Step 3: Enable and extend the smoke test**

Remove any `@Ignore` from `EngineSmokeTest`, and add this stronger assertion test in the same file:
```kotlin
@Test fun downloadedFilesMatchSource() {
    val harness = LoopbackHarness()
    try {
        runBlocking {
            withTimeout(180_000) {
                harness.leecherDownload().collect { }
            }
        }
        val src = TestTorrentFactory.regularFilesUnder(harness.sourceDir)
        val dst = TestTorrentFactory.regularFilesUnder(harness.targetDir.resolve(harness.sourceDir.fileName.toString()))
        assertEquals(src, dst)
        src.forEach { name ->
            assertContentEquals(
                (harness.sourceDir / name).toFile().readBytes(),
                (harness.targetDir / harness.sourceDir.fileName / name).toFile().readBytes(),
            )
        }
    } finally { harness.close() }
}
```
(add imports `kotlin.io.path.div`, `kotlin.test.assertContentEquals`, `kotlin.test.assertEquals`).

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:jvmTest`
Expected: PASS within the 180 s budget (loopback transfer of ~1 MB). If peers never connect, verify LSD fallback by removing `extraPeers` — if that also fails, inspect alerts and record findings; do NOT reduce the assertion.

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -m "test(core): loopback seed/leech integration test with bencode factory"
```

---

### Task 9: Desktop CLI (login / search / download)

**Files:**
- Modify: `cli/src/main/kotlin/com/surgemusic/cli/Main.kt` (full rewrite)
- Create: `cli/src/main/kotlin/com/surgemusic/cli/Credentials.kt`

**Interfaces:**
- Consumes: `RutrackerClient`, `JLibTorrentEngine`, `SizeParser`, `EngineEvent` (Tasks 3–8).
- Produces: CLI with commands:
  - `login` — verifies credentials, prints result
  - `search <query>` — logs in, prints numbered results
  - `download <topicId> [--out DIR]` — logs in, fetches torrent, downloads into `DIR` (default `~/Music/SurgeMusic`), prints progress
  - env overrides: `RUTRACKER_USER`/`RUTRACKER_PASS`, `RUTRACKER_BASE`

- [ ] **Step 1: Implement Credentials loader**

`cli/src/main/kotlin/com/surgemusic/cli/Credentials.kt`:
```kotlin
package com.surgemusic.cli

import java.nio.file.Files
import java.nio.file.Path

data class Credentials(val username: String, val password: String)

fun loadCredentials(): Credentials? {
    val envUser = System.getenv("RUTRACKER_USER")
    val envPass = System.getenv("RUTRACKER_PASS")
    if (!envUser.isNullOrBlank() && !envPass.isNullOrBlank()) return Credentials(envUser, envPass)
    val file = Path.of(System.getProperty("user.home"), ".config", "surgemusic", "credentials")
    if (Files.isRegularFile(file)) {
        val lines = Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 2) return Credentials(lines[0], lines[1])
    }
    return null
}
```

- [ ] **Step 2: Implement Main**

`cli/src/main/kotlin/com/surgemusic/cli/Main.kt` (full rewrite):
```kotlin
package com.surgemusic.cli

import com.surgemusic.core.Version
import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.jvm.JLibTorrentEngine
import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SizeParser
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.jvm.RutrackerClient
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    when (args.getOrNull(0)?.lowercase()) {
        "login" -> cmdLogin()
        "search" -> cmdSearch(args.getOrNull(1) ?: return usage("search requires a query"))
        "download" -> cmdDownload(args.drop(1))
        "version" -> println("SurgeMusic ${Version.APP}")
        else -> usage(null)
    }
}

private fun newClient(): RutrackerClient =
    RutrackerClient(baseUrl = System.getenv("RUTRACKER_BASE")?.takeIf { it.isNotBlank() } ?: RutrackerClient.DEFAULT_BASE_URL)

private fun requireCredentials(): Credentials =
    loadCredentials() ?: run {
        System.err.println(
            "No credentials. Either set RUTRACKER_USER/RUTRACKER_PASS or create\n" +
                "~/.config/surgemusic/credentials with username on line 1, password on line 2 (chmod 600)."
        )
        exitProcess(2)
    }

private suspend fun cmdLogin() {
    val creds = requireCredentials()
    newClient().use { client ->
        client.login(creds.username, creds.password)
            .onSuccess { println("login OK") }
            .onFailure { e -> println("login FAILED: ${describe(e)}"); exitProcess(1) }
    }
}

private suspend fun cmdSearch(query: String) {
    val creds = requireCredentials()
    newClient().use { client ->
        client.login(creds.username, creds.password).getOrElse { e -> println("login FAILED: ${describe(e)}"); exitProcess(1) }
        client.search(SearchQuery(text = query)).fold(
            onSuccess = { page ->
                if (page.rows.isEmpty()) { println("no results for \"$query\""); return }
                page.rows.forEach { r ->
                    println("[${r.topicId}] ${r.title}")
                    println("      ${r.forumName} · ${SizeParser.formatBytes(r.sizeBytes)} · \u2191${r.seeds} \u2193${r.leechs} · ${r.dateText}")
                }
                println("\ndownload with: surgemusic download <topicId>")
            },
            onFailure = { e -> println("search FAILED: ${describe(e)}"); exitProcess(1) },
        )
    }
}

private suspend fun cmdDownload(args: List<String>) {
    val topicId = args.getOrNull(0)?.toLongOrNull() ?: return usage("download requires a numeric topicId")
    val outDir = args.drop(1).option("--out") ?: Path.of(System.getProperty("user.home"), "Music", "SurgeMusic").toString()
    val creds = requireCredentials()

    newClient().use { client ->
        client.login(creds.username, creds.password).getOrElse { e -> println("login FAILED: ${describe(e)}"); exitProcess(1) }
        val torrentBytes = client.torrentFile(topicId).getOrElse { e -> println("torrent fetch FAILED: ${describe(e)}"); exitProcess(1) }
        println("torrent fetched (${torrentBytes.size} bytes) -> $outDir")
        JLibTorrentEngine().use { engine ->
            engine.download(torrentBytes, outDir).collect { event ->
                when (event) {
                    is EngineEvent.Progress -> print(
                        "\r${SizeParser.formatBytes(event.bytesDone)} / ${SizeParser.formatBytes(event.bytesTotal)}  " +
                            "${SizeParser.formatBytes(event.downloadSpeed)}/s  peers=${event.peers}   "
                    )
                    is EngineEvent.Completed -> println("\ndownload complete: $outDir")
                    is EngineEvent.Failed -> { println("\ndownload FAILED: ${event.message}"); exitProcess(1) }
                }
            }
        }
    }
}

private fun List<String>.option(name: String): String? {
    val i = indexOf(name)
    return if (i >= 0 && i + 1 < size) this[i + 1] else null
}

private fun describe(e: Throwable): String =
    if (e is TrackerError) "${e.kind.name}: ${e.message}" + (e.selector?.let { " (selector=$it)" } ?: "") else e.message ?: e.toString()

private fun usage(error: String?): Nothing {
    error?.let { System.err.println("error: $it") }
    println(
        """
        SurgeMusic ${Version.APP}
        usage:
          surgemusic login
          surgemusic search <query>
          surgemusic download <topicId> [--out DIR]
        env: RUTRACKER_USER, RUTRACKER_PASS, RUTRACKER_BASE
        credentials file: ~/.config/surgemusic/credentials (user\npass, chmod 600)
        """.trimIndent()
    )
    exitProcess(if (error == null) 0 else 2)
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :cli:build`
Expected: BUILD SUCCESSFUL. (Wire the `cli` app's application scripts: `./gradlew :cli:installDist` produces `cli/build/install/cli/bin/cli` — use that binary in Task 10.)

- [ ] **Step 4: Commit**

```bash
git add cli/
git commit -m "feat(cli): login/search/download commands over core"
```

---

### Task 10: Live fixtures script + live verification

**Files:**
- Create: `scripts/fetch_fixtures.sh` (chmod +x)

**Interfaces:**
- Consumes: running CLI (Task 9); real credentials file.
- Produces: `core/liveFixtures/search.html` (+ optional manual inspection) and a selector-verification pass over `SearchPageParser`.

- [ ] **Step 1: Create the script**

`scripts/fetch_fixtures.sh`:
```bash
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
```

- [ ] **Step 2: Run live verification** (requires the user's credentials file — if absent, SKIP steps 2–3 and note this in the final report; do not block)

```bash
chmod 700 ~/.config/surgemusic/credentials 2>/dev/null || true
./scripts/fetch_fixtures.sh "pink floyd"
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli search "pink floyd"
```
Expected: numbered-ish result rows with topicIds.

- [ ] **Step 3: Selector reconciliation (if needed)**

If `search` prints zero rows or a PARSE error:
1. `grep -c 'tor-tbl' core/liveFixtures/search.html` — if 0, the table id changed: open the HTML, find the results table, update `#tor-tbl` in `SearchPageParser` AND in the synthetic fixture + tests.
2. Same procedure for `a.tt-link`, `td.f-name a`, `td.seedmed b`, `td.leechmed b`, `a[title=Размер]`.
3. Re-run `./gradlew :core:jvmTest` (fixtures tests must still pass with the updated fixture).

- [ ] **Step 4: Commit**

```bash
git add scripts/
git commit -m "build: live fixture capture script"
```

---

### Task 11: README + CI

**Files:**
- Create: `README.md`, `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: everything above.
- Produces: docs + CI running all JVM tests on push.

- [ ] **Step 1: README**

`README.md`:
```markdown
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
```

- [ ] **Step 2: CI**

`.github/workflows/ci.yml`:
```yaml
name: ci
on:
  push: { branches: [main] }
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :core:jvmTest :cli:build
```

- [ ] **Step 3: Verify + commit**

Run: `./gradlew :core:jvmTest :cli:build`
```bash
git add README.md .github/
git commit -m "docs: readme with usage and legal note; add CI"
```

---

## Plan self-review (already applied)

1. **Spec coverage:** Phase 0 (Task 1), Phase 1 tracker core (Tasks 2–6), Phase 2 engine + CLI (Tasks 7–9), live verification (Task 10), CI/docs (Task 11). Desktop UI, Android, mirrors/proxy UI, DB persistence → plans 2–4 per spec roadmap.
2. **Placeholders:** none; every step has code or an exact command.
3. **Type consistency:** `SearchResultRow/SearchResultPage/SearchQuery/SizeParser/TrackerError(kind,selector)/TrackerClient/EngineEvent/TorrentEngine.download(bytes,dir,extraPeers)/JLibTorrentEngine/RutrackerClient(baseUrl)` used identically across tasks.
