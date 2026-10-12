package com.surgemusic.app.ui.search

import com.surgemusic.core.model.SearchResultPage
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SearchViewModel with a constructor-injected fake search function (plan Task 6 Step 1).
 * `Dispatchers.setMain` overrides the production `Dispatchers.Main` scope with an
 * unconfined test dispatcher, so launched work completes synchronously in the test body.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun row(topicId: Long) = SearchResultRow(
        topicId = topicId,
        title = "Artist — Album $topicId (Lossless)",
        forumId = 1862,
        forumName = "Rock",
        sizeBytes = 1_000 * topicId,
        seeds = 5,
        leechs = 1,
        dateText = "today",
    )

    @Test
    fun searchLoadsFirstPageIntoResultsAndSetsReady() {
        val page1 = SearchResultPage(rows = listOf(row(1), row(2)), start = 0)
        val vm = SearchViewModel(searchFn = { _, _ -> Result.success(page1) })

        vm.onQueryChange("pink floyd")
        vm.search()

        assertEquals(listOf(row(1), row(2)), vm.results.value)
        val state = assertIs<SearchUiState.Ready>(vm.uiState.value)
        assertTrue(state.hasMore, "a non-empty page means more pages may exist")
    }

    @Test
    fun loadMoreAppendsNextPageAndEmptyPageStopsPagination() {
        val pages = mapOf(
            0 to SearchResultPage(rows = listOf(row(1), row(2)), start = 0),
            2 to SearchResultPage(rows = listOf(row(3)), start = 2),
            3 to SearchResultPage(rows = emptyList(), start = 3),
        )
        val vm = SearchViewModel(searchFn = { _, start -> Result.success(pages.getValue(start)) })

        vm.onQueryChange("pink floyd")
        vm.search()
        vm.loadMore()

        assertEquals(listOf(row(1), row(2), row(3)), vm.results.value)
        assertIs<SearchUiState.Ready>(vm.uiState.value) // still Ready with hasMore=true

        vm.loadMore()
        val state = assertIs<SearchUiState.Ready>(vm.uiState.value)
        assertFalse(state.hasMore, "an empty page means no more results")
        assertEquals(3, vm.results.value.size)
    }

    @Test
    fun authErrorSetsNeedsLogin() {
        val vm = SearchViewModel(
            searchFn = { _, _ -> Result.failure(TrackerError("session expired", TrackerErrorKind.AUTH)) },
        )

        vm.onQueryChange("pink floyd")
        vm.search()

        val state = assertIs<SearchUiState.Error>(vm.uiState.value)
        assertTrue(state.needsLogin, "AUTH errors must ask the user to log in")
        assertEquals("session expired", state.message)
    }

    @Test
    fun nonAuthErrorDoesNotAskForLogin() {
        val vm = SearchViewModel(
            searchFn = { _, _ -> Result.failure(TrackerError("connection reset", TrackerErrorKind.NETWORK)) },
        )

        vm.onQueryChange("pink floyd")
        vm.search()

        val state = assertIs<SearchUiState.Error>(vm.uiState.value)
        assertFalse(state.needsLogin)
    }

    @Test
    fun emptyFirstPageMeansNoMoreResults() {
        val vm = SearchViewModel(searchFn = { _, _ -> Result.success(SearchResultPage(rows = emptyList(), start = 0)) })

        vm.onQueryChange("nothing matches this")
        vm.search()

        assertEquals(emptyList(), vm.results.value)
        val state = assertIs<SearchUiState.Ready>(vm.uiState.value)
        assertFalse(state.hasMore)
    }

    @Test
    fun failedLoadMoreRetriesSamePageStart() {
        var calls = 0
        val goodPage = SearchResultPage(rows = listOf(row(1)), start = 0)
        val vm = SearchViewModel(searchFn = { _, start ->
            calls++
            if (calls == 2) Result.failure(TrackerError("timeout", TrackerErrorKind.NETWORK))
            else Result.success(if (start == 0) goodPage else SearchResultPage(rows = listOf(row(2)), start = start))
        })

        vm.onQueryChange("pink floyd")
        vm.search()
        vm.loadMore() // fails

        assertIs<SearchUiState.Error>(vm.uiState.value)
        vm.retry()

        val state = assertIs<SearchUiState.Ready>(vm.uiState.value)
        assertTrue(state.hasMore)
        assertEquals(listOf(row(1), row(2)), vm.results.value)
    }
}
