package com.surgemusic.core.downloads

import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.EngineSnapshot
import com.surgemusic.core.engine.TorrentEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Scriptable [TorrentEngine] test double: start/pause/resume/cancel calls are recorded
 * (as thread-safe [StateFlow]s so tests can await them across dispatcher threads), and
 * tests push engine events into the injectable [events] [MutableSharedFlow] with `tryEmit`
 * (extraBufferCapacity makes that non-suspending and lossless for a single collector).
 */
class FakeEngine : TorrentEngine {

    data class StartCall(val id: String, val torrentBytes: ByteArray, val targetDir: String)

    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)

    private val _started = MutableStateFlow<List<StartCall>>(emptyList())
    val started: StateFlow<List<StartCall>> = _started.asStateFlow()
    private val _paused = MutableStateFlow<List<String>>(emptyList())
    val paused: StateFlow<List<String>> = _paused.asStateFlow()
    private val _resumed = MutableStateFlow<List<String>>(emptyList())
    val resumed: StateFlow<List<String>> = _resumed.asStateFlow()
    private val _cancelled = MutableStateFlow<List<String>>(emptyList())
    val cancelled: StateFlow<List<String>> = _cancelled.asStateFlow()

    override fun start(id: String, torrentBytes: ByteArray, targetDir: String) {
        _started.value += StartCall(id, torrentBytes, targetDir)
    }

    override fun pause(id: String) {
        _paused.value += id
    }

    override fun resume(id: String) {
        _resumed.value += id
    }

    override fun cancel(id: String) {
        _cancelled.value += id
    }

    override fun snapshot(id: String): EngineSnapshot? = null

    override fun listenPort(): Int = 0

    override fun close() {}
}
