package com.surgemusic.app.ui.search

import com.surgemusic.core.model.SearchResultPage
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Search screen state machine (plan Task 6). */
sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Ready(val hasMore: Boolean) : SearchUiState
    data class Error(val message: String, val needsLogin: Boolean) : SearchUiState
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

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Start offset for the next loadMore; grows with each successful page. */
    private var nextStart: Int = 0

    private enum class Pending { NONE, SEARCH, LOAD_MORE }

    private var pending: Pending = Pending.NONE

    fun onQueryChange(value: String) {
        _query.value = value
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
}
