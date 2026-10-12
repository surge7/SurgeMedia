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
