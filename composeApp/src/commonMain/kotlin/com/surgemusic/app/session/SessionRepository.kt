package com.surgemusic.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A harvested rutracker session: raw `Cookie` header + the User-Agent it was bound to (cf_clearance is UA-bound). */
data class Session(
    val cookieHeader: String,
    val userAgent: String,
    val savedAtEpochMs: Long,
)

/** Holds the current rutracker session; `null` = not logged in. */
interface SessionRepository {
    val session: StateFlow<Session?>
    fun save(cookieHeader: String, userAgent: String)
    fun clear()
}

/** Non-persisting impl for previews and tests. */
class InMemorySessionRepository(initial: Session? = null) : SessionRepository {
    private val _session = MutableStateFlow(initial)
    override val session: StateFlow<Session?> = _session.asStateFlow()

    override fun save(cookieHeader: String, userAgent: String) {
        _session.value = Session(cookieHeader, userAgent, nowEpochMs())
    }

    override fun clear() {
        _session.value = null
    }
}

/** Wall clock, expect/actual so commonMain stays dependency-free. */
internal expect fun nowEpochMs(): Long

/** Locale date-time for the session save timestamp (Settings screen, plan Task 9). */
internal expect fun formatTimestamp(epochMs: Long): String
