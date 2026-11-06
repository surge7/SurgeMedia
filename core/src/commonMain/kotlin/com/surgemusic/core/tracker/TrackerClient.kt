package com.surgemusic.core.tracker

import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SearchResultPage

enum class TrackerErrorKind { NETWORK, AUTH, CAPTCHA, PARSE, HTTP }

class TrackerError(
    message: String,
    val kind: TrackerErrorKind,
    val selector: String? = null,
) : Exception(message)

/**
 * The tracker seam: every production caller (TrackerAppFactory, the platform AppContainers,
 * the Settings validate probe) depends on this interface rather than the concrete rutracker
 * implementation, so the tracker is swappable and tests can install fakes implementing
 * exactly these members. [AutoCloseable] so callers own the lifetime uniformly
 * (implementations release their HTTP engine in [close]).
 */
interface TrackerClient : AutoCloseable {
    suspend fun login(username: String, password: String): Result<Unit>
    suspend fun search(query: SearchQuery): Result<SearchResultPage>
    suspend fun torrentFile(topicId: Long): Result<ByteArray>

    /**
     * Cloudflare-era session import: the raw Cookie header captured from a logged-in
     * browser plus the User-Agent it was harvested with (the clearance cookie is bound
     * to that UA). Blank [userAgent] keeps the implementation's current one.
     */
    fun importSession(cookieHeader: String, userAgent: String)
}
