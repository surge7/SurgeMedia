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
