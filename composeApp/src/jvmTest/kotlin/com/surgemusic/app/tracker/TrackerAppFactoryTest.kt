package com.surgemusic.app.tracker

import com.surgemusic.core.downloads.DownloadItem
import com.surgemusic.core.downloads.DownloadManager
import com.surgemusic.core.downloads.PreparedTorrent
import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.TorrentFileInfo
import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SearchResultPage
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.tracker.TrackerClient
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [TrackerAppFactory] — the AppContainer members deduplicated out of desktopMain /
 * androidMain (appShared source set): prepare()'s parse degradation, startDownload()'s
 * selected-bytes math + staging/wantedFiles passthrough + post-enqueue hook ordering,
 * the validate probe's one-test-search semantics, and the tracker client singleton's
 * mirror-rebuild / per-call session import.
 */
class TrackerAppFactoryTest {

    private val row = SearchResultRow(
        topicId = 6918211L,
        title = "Artist - Album (2020) [FLAC]",
        forumId = 1337L,
        forumName = "Rock",
        sizeBytes = 5_000L,
        seeds = 10,
        leechs = 2,
        dateText = "today",
    )

    /** Stub tracker whose search/torrentFile answers the test fixes. */
    private class StubClient(
        private val torrent: Result<ByteArray> = Result.success(ByteArray(0)),
        private val page: Result<SearchResultPage> = Result.success(SearchResultPage(emptyList(), 0)),
    ) : TrackerClient {
        val searchQueries = mutableListOf<SearchQuery>()

        override suspend fun login(username: String, password: String): Result<Unit> = Result.success(Unit)

        override suspend fun search(query: SearchQuery): Result<SearchResultPage> {
            searchQueries += query
            return page
        }

        override suspend fun torrentFile(topicId: Long): Result<ByteArray> = torrent
    }

    /**
     * Scriptable [TorrentEngine] test double (FakeEngine-style, cf. :core's
     * DownloadManagerTest): parseFiles is configurable, start calls are recorded in a
     * StateFlow so tests can await the manager's pump across dispatcher threads.
     */
    private class RecordingEngine : TorrentEngine {

        data class StartCall(
            val id: String,
            val torrentBytes: ByteArray,
            val targetDir: String,
            val wantedFiles: Set<Int>,
        )

        @Volatile var files: List<TorrentFileInfo> = emptyList()

        /** When set, [parseFiles] throws this (prepare must degrade; enqueue tolerates). */
        @Volatile var parseError: RuntimeException? = null

        private val _started = MutableStateFlow<List<StartCall>>(emptyList())
        val started: StateFlow<List<StartCall>> = _started.asStateFlow()

        override fun parseFiles(torrentBytes: ByteArray): List<TorrentFileInfo> {
            parseError?.let { throw it }
            return files
        }

        override fun start(id: String, torrentBytes: ByteArray, targetDir: String, wantedFiles: Set<Int>) {
            _started.value += StartCall(id, torrentBytes, targetDir, wantedFiles)
        }

        override fun setWantedFiles(id: String, wantedFiles: Set<Int>) {}
        override fun pause(id: String) {}
        override fun resume(id: String) {}
        override fun cancel(id: String) {}
        override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
        override fun listenPort(): Int = 0
        override fun close() {}
    }

    private fun manager(engine: RecordingEngine): DownloadManager = DownloadManager(
        engine = engine,
        maxConcurrent = MutableStateFlow(5),
        persistence = null,
        finalize = { _: DownloadItem, _: String -> Result.success("unused") },
    )

    /** The manager's pump runs engine.start on Dispatchers.Default — wait for [id]. */
    private suspend fun RecordingEngine.awaitStart(id: String): RecordingEngine.StartCall =
        withTimeout(5_000) { started.first { calls -> calls.any { it.id == id } } }
            .last { it.id == id }

    private fun item(manager: DownloadManager): DownloadItem =
        assertNotNull(manager.items.value.single(), "expected exactly one queued item")

    // ---------------------------------------------------------------- prepare

    @Test
    fun prepareReturnsTorrentBytesAndParsedFiles() = runBlocking {
        val bytes = "d8:announce...e".toByteArray()
        val files = listOf(
            TorrentFileInfo(0, "01 - Track.flac", 100),
            TorrentFileInfo(1, "02 - Track.flac", 200),
        )
        val engine = RecordingEngine().apply { this.files = files }

        val prepared = TrackerAppFactory.prepare(row, StubClient(torrent = Result.success(bytes)), engine)
            .getOrThrow()

        assertTrue(prepared.torrentBytes.contentEquals(bytes), "torrent bytes must pass through")
        assertEquals(files, prepared.files)
    }

    @Test
    fun prepareDegradesParseFailureToEmptyFileList() = runBlocking {
        val bytes = "garbage".toByteArray()
        val engine = RecordingEngine().apply { parseError = IllegalStateException("bad metadata") }

        val prepared = TrackerAppFactory.prepare(row, StubClient(torrent = Result.success(bytes)), engine)
            .getOrThrow()

        assertEquals(emptyList(), prepared.files, "parse failure must degrade to an empty list")
        assertTrue(prepared.torrentBytes.contentEquals(bytes), "torrent bytes must survive the degradation")
    }

    @Test
    fun preparePropagatesTorrentFetchFailure() = runBlocking {
        val failure = TrackerError("expired session", TrackerErrorKind.AUTH)

        val result = TrackerAppFactory.prepare(
            row,
            StubClient(torrent = Result.failure(failure)),
            RecordingEngine(),
        )

        assertEquals("expired session", result.exceptionOrNull()?.message)
    }

    // ------------------------------------------------------------ startDownload

    private fun prepared(vararg files: TorrentFileInfo) = PreparedTorrent("t".toByteArray(), files.toList())

    @Test
    fun startDownloadSizesTheRequestByTheSelection() = runBlocking {
        val engine = RecordingEngine()
            .apply { files = listOf(TorrentFileInfo(0, "a", 100), TorrentFileInfo(1, "b", 200)) }
        val downloadManager = manager(engine)

        TrackerAppFactory.startDownload(
            row, prepared(engine.files[0], engine.files[1]), setOf(0),
            downloadManager, stagingRoot = { "/staging-$it" },
        )

        assertEquals(100L, item(downloadManager).request.sizeBytes, "size must be the selected payload")
        downloadManager.close()
    }

    @Test
    fun startDownloadSumsEveryWantedFile() = runBlocking {
        val engine = RecordingEngine()
            .apply { files = listOf(TorrentFileInfo(0, "a", 100), TorrentFileInfo(1, "b", 200)) }
        val downloadManager = manager(engine)

        TrackerAppFactory.startDownload(
            row, prepared(engine.files[0], engine.files[1]), setOf(0, 1),
            downloadManager, stagingRoot = { "/staging-$it" },
        )

        assertEquals(300L, item(downloadManager).request.sizeBytes)
        downloadManager.close()
    }

    @Test
    fun startDownloadFallsBackToRowSizeForEmptySelection() = runBlocking {
        val engine = RecordingEngine()
            .apply { files = listOf(TorrentFileInfo(0, "a", 100), TorrentFileInfo(1, "b", 200)) }
        val downloadManager = manager(engine)

        TrackerAppFactory.startDownload(
            row, prepared(engine.files[0], engine.files[1]), emptySet(),
            downloadManager, stagingRoot = { "/staging-$it" },
        )

        assertEquals(row.sizeBytes, item(downloadManager).request.sizeBytes)
        downloadManager.close()
    }

    @Test
    fun startDownloadPassesStagingRootAndWantedFilesToTheEngine() = runBlocking {
        val engine = RecordingEngine()
            .apply { files = listOf(TorrentFileInfo(0, "a", 100), TorrentFileInfo(1, "b", 200)) }
        val downloadManager = manager(engine)

        TrackerAppFactory.startDownload(
            row, prepared(engine.files[1]), setOf(1),
            downloadManager, stagingRoot = { "/staging-$it" },
        )

        val start = engine.awaitStart("t-${row.topicId}")
        assertEquals("/staging-${row.topicId}", start.targetDir)
        assertEquals(setOf(1), start.wantedFiles)
        downloadManager.close()
    }

    @Test
    fun startDownloadRunsOnEnqueuedAfterTheItemIsQueued() = runBlocking {
        val downloadManager = manager(RecordingEngine())
        var itemsAtHook = -1
        var topicIdAtHook = -1L

        TrackerAppFactory.startDownload(
            row, prepared(), emptySet(),
            downloadManager, stagingRoot = { "/staging-$it" },
            onEnqueued = { topicId ->
                topicIdAtHook = topicId
                // The android FGS start rides this hook — the item must already be queued.
                itemsAtHook = downloadManager.items.value.size
            },
        )

        assertEquals(row.topicId, topicIdAtHook)
        assertEquals(1, itemsAtHook, "onEnqueued must run after the enqueue landed")
        downloadManager.close()
    }

    // ----------------------------------------------------------- validateSession

    @Test
    fun validateSessionProbesWithOneTestSearch() = runBlocking {
        val client = StubClient()

        val result = TrackerAppFactory.validateSession { client }

        assertNull(result.exceptionOrNull())
        assertEquals(listOf(SearchQuery(text = "test")), client.searchQueries)
    }

    @Test
    fun validateSessionSurfacesSearchFailure() = runBlocking {
        val client = StubClient(
            page = Result.failure(TrackerError("cf challenge", TrackerErrorKind.CAPTCHA)),
        )

        val result = TrackerAppFactory.validateSession { client }

        assertEquals("cf challenge", result.exceptionOrNull()?.message)
    }

    // ------------------------------------------------------------ trackerClient

    @Test
    fun trackerClientIsASingletonPerBaseUrlAndReimportsSessionEveryCall() {
        val base = "https://tracker-factory-test.example"
        var sessionReads = 0

        val first = TrackerAppFactory.trackerClient(base) { sessionReads++; "bb_session=1" to "UA" }
        assertEquals(1, sessionReads)
        val again = TrackerAppFactory.trackerClient(base) { sessionReads++; "bb_session=2" to "UA" }
        assertSame(first, again, "same base URL must reuse the client")
        assertEquals(2, sessionReads, "session is re-imported on every call")

        val rebuilt = TrackerAppFactory.trackerClient("$base/mirror") { null }
        assertNotSame(first, rebuilt, "a changed base URL must rebuild the client")

        first.close()
        rebuilt.close()
    }

    @Test
    fun trackerClientWithNoSessionSkipsImport() {
        val base = "https://tracker-factory-test-no-session.example"
        val client = TrackerAppFactory.trackerClient(base) { null }

        assertSame(client, TrackerAppFactory.trackerClient(base) { null })

        client.close()
    }
}
