package com.surgemusic.app.ui.search

import com.surgemusic.core.model.SearchResultPage
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Search screen state machine (plan Task 6). */
sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Ready(val hasMore: Boolean) : SearchUiState
    data class Error(val message: String, val needsLogin: Boolean) : SearchUiState
}

/** Client-side sort modes for loaded search results (no server-side `o=`/`s=` params); labels live in the UI. */
enum class SearchSort { RELEVANCE, SEEDS, SIZE, DATE, TITLE }

/** 3-letter month abbreviations (Russian first, Latin aliases) → month number; matched lowercased. */
private val MONTHS: Map<String, Int> = buildMap {
    listOf("Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек")
        .forEachIndexed { i, m -> put(m.lowercase(), i + 1) }
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        .forEachIndexed { i, m -> put(m.lowercase(), i + 1) }
}

/**
 * Pure commonMain date key for rutracker's `dateText` column (`d-MMM-yy`, Russian 3-letter
 * months like `2-Окт-26`; Latin `Jan`..`Dec` accepted case-insensitively; 2-digit year → 20yy).
 * Returns a comparable `y*10000 + m*100 + d` Long, with `Сегодня`/`Вчера` pinned above every
 * real date, or `null` when nothing parses (callers sink those rows to the bottom).
 */
internal fun parseRutrackerDate(text: String): Long? {
    val t = text.trim()
    return when (t.lowercase()) {
        "сегодня" -> Long.MAX_VALUE
        "вчера" -> Long.MAX_VALUE - 1
        else -> {
            val parts = t.split("-")
            if (parts.size != 3) return null
            val day = parts[0].toIntOrNull() ?: return null
            val month = MONTHS[parts[1].lowercase()] ?: return null
            val rawYear = parts[2].toIntOrNull() ?: return null
            if (day !in 1..31 || rawYear < 0) return null
            val year = if (rawYear < 100) 2000 + rawYear else rawYear
            year * 10_000L + month * 100L + day
        }
    }
}

/**
 * Search view model: query, accumulated results and the [SearchUiState] machine.
 *
 * The search function is constructor-injected (`(query, start) -> Result<SearchResultPage>`)
 * so tests run against a fake (plan Task 6 Step 1); production wires
 * `RutrackerClient.search`. Launched on `Dispatchers.Main` — tests override it via
 * `Dispatchers.setMain`. Plain class with `StateFlow`s, no lifecycle-viewmodel dependency.
 *
 * Pagination: rutracker's `start=` offset grows by the returned row count; a page that
 * comes back empty means there is nothing more (`Ready(hasMore = rows.isNotEmpty())`).
 * `retry()` re-runs the last attempted operation (first search or load-more).
 */
class SearchViewModel(
    query: String = "",
    private val searchFn: suspend (query: String, start: Int) -> Result<SearchResultPage>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _query = MutableStateFlow(query)
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResultRow>>(emptyList())
    val results: StateFlow<List<SearchResultRow>> = _results.asStateFlow()

    private val _sort = MutableStateFlow(SearchSort.RELEVANCE)
    val sort: StateFlow<SearchSort> = _sort.asStateFlow()

    /**
     * [results] re-sorted client-side by the current [SearchSort]; [results] itself stays in
     * raw tracker (relevance) order as pages append. RELEVANCE keeps that original order
     * because `sortedWith` is stable and its comparator reports all rows equal.
     */
    val sortedResults: StateFlow<List<SearchResultRow>> =
        combine(results, _sort) { rows, sort -> rows.sortedWith(comparatorFor(sort)) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Start offset for the next loadMore; grows with each successful page. */
    private var nextStart: Int = 0

    private enum class Pending { NONE, SEARCH, LOAD_MORE }

    private var pending: Pending = Pending.NONE

    fun onQueryChange(value: String) {
        _query.value = value
    }

    /** Switch the client-side sort; [sortedResults] recomputes over already-loaded pages, no re-fetch. */
    fun setSort(s: SearchSort) {
        _sort.value = s
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isEmpty() || _uiState.value is SearchUiState.Loading) return
        pending = Pending.SEARCH
        _uiState.value = SearchUiState.Loading
        scope.launch {
            searchFn(q, 0).fold(
                onSuccess = { page ->
                    _results.value = page.rows
                    nextStart = page.start + page.rows.size
                    _uiState.value = SearchUiState.Ready(hasMore = page.rows.isNotEmpty())
                },
                onFailure = { e -> _uiState.value = e.toErrorState() },
            )
        }
    }

    fun loadMore() = loadMoreInternal(force = false)

    fun retry() {
        when (pending) {
            Pending.SEARCH -> search()
            Pending.LOAD_MORE -> loadMoreInternal(force = true) // bypass the Ready-only guard after a failure
            Pending.NONE -> Unit
        }
    }

    private fun loadMoreInternal(force: Boolean) {
        val state = _uiState.value
        if (state is SearchUiState.Loading) return
        if (!force) {
            val ready = state as? SearchUiState.Ready ?: return
            if (!ready.hasMore) return
        }
        val q = _query.value.trim()
        if (q.isEmpty()) return
        pending = Pending.LOAD_MORE
        _uiState.value = SearchUiState.Loading
        scope.launch {
            searchFn(q, nextStart).fold(
                onSuccess = { page ->
                    _results.value = _results.value + page.rows
                    nextStart = page.start + page.rows.size
                    _uiState.value = SearchUiState.Ready(hasMore = page.rows.isNotEmpty())
                },
                onFailure = { e -> _uiState.value = e.toErrorState() },
            )
        }
    }

    /** Auth-expired sessions (spec §4) surface as a login prompt; everything else is a plain error. */
    private fun Throwable.toErrorState(): SearchUiState.Error {
        val needsLogin = this is TrackerError && kind == TrackerErrorKind.AUTH
        return SearchUiState.Error(message = message ?: "search failed", needsLogin = needsLogin)
    }

    /** Stable comparators; equal keys fall back to [SearchResultRow.topicId] ascending. */
    private fun comparatorFor(sort: SearchSort): Comparator<SearchResultRow> = when (sort) {
        // All-equal comparator: `sortedWith` is stable, so the tracker (relevance) order survives.
        SearchSort.RELEVANCE -> Comparator { _, _ -> 0 }
        SearchSort.SEEDS -> compareByDescending<SearchResultRow> { it.seeds }.thenBy { it.topicId }
        SearchSort.SIZE -> compareByDescending<SearchResultRow> { it.sizeBytes }.thenBy { it.topicId }
        SearchSort.DATE -> compareByDescending<SearchResultRow> { parseRutrackerDate(it.dateText) ?: Long.MIN_VALUE }
            .thenBy { it.topicId }
        SearchSort.TITLE -> compareBy<SearchResultRow> { it.title.lowercase() }.thenBy { it.topicId }
    }
}
