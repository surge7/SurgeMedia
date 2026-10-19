package com.surgemusic.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.surgemusic.app.session.SessionRepository
import com.surgemusic.app.ui.back.PlatformBackHandler
import com.surgemusic.app.ui.components.SurgeSnackbarHost
import com.surgemusic.app.ui.components.ScanlineVerticalDivider
import com.surgemusic.app.ui.details.DetailsPanePlaceholder
import com.surgemusic.app.ui.details.DetailsScreen
import com.surgemusic.app.ui.downloads.DownloadsScreen
import com.surgemusic.app.ui.downloads.DownloadsViewModel
import com.surgemusic.app.ui.library.LibraryScreen
import com.surgemusic.app.ui.library.LibraryViewModel
import com.surgemusic.app.ui.search.SearchScreen
import com.surgemusic.app.ui.search.SearchViewModel
import com.surgemusic.app.ui.settings.SettingsScreen
import com.surgemusic.app.ui.settings.SettingsViewModel
import com.surgemusic.core.model.SearchResultRow
import kotlinx.coroutines.launch

/** Nav tabs; navigation is plain state (no navigation library this phase). */
enum class AppTab(val label: String, val icon: ImageVector) {
    Search("Search", Icons.Rounded.Search),
    Downloads("Downloads", Icons.Rounded.Download),
    Downloaded("Downloaded", Icons.Rounded.VideoLibrary),
    Settings("Settings", Icons.Rounded.Settings),
}

/** Width at or below which the shell uses a bottom NavigationBar (phones). */
private val CompactBreakpoint = 700.dp

/**
 * Width at or above which the Search tab switches from "details as a full-screen overlay"
 * to a list-detail layout: results list on the left, details in a permanent right pane
 * (tablets in landscape / desktop windows). Between the two breakpoints the shell shows
 * the NavigationRail but keeps the overlay drill-down — the pane pair would be too cramped.
 */
private val ListDetailBreakpoint = 840.dp

/**
 * Adaptive app shell (plan Tasks 4/6/8/9): three tabs, three width tiers —
 * - compact (<700dp): bottom NavigationBar; details drill-down is a full-screen overlay.
 * - medium (700–840dp): NavigationRail; details still a full-screen overlay.
 * - expanded (≥840dp, tablet/desktop): NavigationRail plus a list-detail Search tab —
 *   results on the left, details in an embedded right pane ([DetailsScreen] `embedded`),
 *   with the selected row glowing in the list. The rail and tabs stay usable while
 *   details are open, and the selection persists across tab switches (Gmail-style).
 *
 * [loginOverlay] is a platform slot — androidMain passes [com.surgemusic.app.ui.login.WebViewLoginScreen]
 * (`WebViewLoginScreen` is android-only); it renders over the current tab while the login flow is up.
 * Both the Search tab's "Log in" hero and the Settings tab's "Log in" button raise it.
 *
 * Search-result drill-down: tapping a row lifts the row into [selectedRow]. On compact/
 * medium widths [DetailsScreen] covers the tabs; on expanded widths it renders in the
 * right pane. The Details screen owns the Download button, so [onDownload] is only invoked
 * from there. A failure surfaces two ways — inline on the details screen AND as a long
 * snackbar with a Retry action — so a transient tracker timeout (e.g. `dl.php` socket
 * timeout) is impossible to miss.
 *
 * [onDownload] is the platform download pipeline (Task 8; androidMain: fetch .torrent →
 * enqueue → start the foreground service).
 */
@Composable
fun App(
    searchViewModel: SearchViewModel,
    downloadsViewModel: DownloadsViewModel,
    libraryViewModel: LibraryViewModel,
    settingsViewModel: SettingsViewModel,
    sessionRepository: SessionRepository,
    loginOverlay: @Composable (onFinished: (Boolean) -> Unit) -> Unit,
    onDownload: suspend (SearchResultRow) -> Result<Unit>,
) {
    var currentTab by remember { mutableStateOf(AppTab.Search) }
    var showLogin by remember { mutableStateOf(false) }
    var selectedRow by remember { mutableStateOf<SearchResultRow?>(null) }

    // Details download state — scoped to whichever row is currently open in DetailsScreen.
    // Reset whenever the user opens a new row or closes the details.
    var detailsError by remember { mutableStateOf<String?>(null) }
    var detailsDownloading by remember { mutableStateOf(false) }
    var detailsAdded by remember { mutableStateOf(false) }

    val session by sessionRepository.session.collectAsState()
    val themeMode by settingsViewModel.themeMode.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Sticky retry: the snackbar with "Retry" can outlive the initial download attempt. The
    // download function captures the *current* row via state read, so the retry action can
    // re-trigger the same lambda after the snackbar fades.
    suspend fun runDownload(row: SearchResultRow) {
        detailsDownloading = true
        detailsError = null
        detailsAdded = false
        onDownload(row).fold(
            onSuccess = {
                detailsDownloading = false
                detailsAdded = true
                snackbarHostState.showSnackbar(
                    message = "Added to Downloads.",
                    duration = SnackbarDuration.Short,
                )
            },
            onFailure = { e ->
                detailsDownloading = false
                val message = e.message ?: e::class.simpleName ?: "unknown error"
                detailsError = message
                val retryResult = snackbarHostState.showSnackbar(
                    message = "Download failed: $message",
                    actionLabel = "Retry",
                    duration = SnackbarDuration.Long,
                )
                if (retryResult == SnackbarResult.ActionPerformed) {
                    runDownload(row)
                }
            },
        )
    }

    val openDetails: (SearchResultRow) -> Unit = { row ->
        selectedRow = row
        detailsError = null
        detailsDownloading = false
        detailsAdded = false
    }
    val closeDetails = {
        selectedRow = null
        detailsError = null
        detailsDownloading = false
        detailsAdded = false
    }
    val openDownloadsFromDetails = {
        closeDetails()
        currentTab = AppTab.Downloads
    }

    SurgeMediaTheme(themeMode = themeMode) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxWidth < CompactBreakpoint
            val twoPane = maxWidth >= ListDetailBreakpoint

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SurgeSnackbarHost(hostState = snackbarHostState) },
                // A full-screen overlay (Details drill-down or the login WebView) would leave the
                // nav bar visible but its taps masked by the overlay — tabs would switch invisibly.
                // Hide the bar while either overlay is up. On expanded layouts there is no bottom
                // bar at all (the rail lives inside the content row).
                bottomBar = {
                    if (compact && selectedRow == null && !showLogin) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.background,
                        ) {
                            AppTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = { currentTab = tab },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label.uppercase()) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                // Login layer — rendered after (over) the tab area in every tier, so its back
                // handler also composes last and outranks any details handler while login is up.
                val loginLayer: @Composable () -> Unit = {
                    if (showLogin) {
                        loginOverlay { showLogin = false }
                    }
                    // System back closes the login overlay (same action as its Cancel path),
                    // not the activity behind it.
                    PlatformBackHandler(enabled = showLogin) { showLogin = false }
                }

                val tabContent: @Composable () -> Unit = {
                    when (currentTab) {
                        AppTab.Search -> SearchScreen(
                            viewModel = searchViewModel,
                            loggedIn = session != null,
                            selectedTopicId = selectedRow?.topicId,
                            onShowDetails = openDetails,
                            onLogin = { showLogin = true },
                        )
                        AppTab.Downloads -> DownloadsScreen(viewModel = downloadsViewModel)
                        AppTab.Downloaded -> LibraryScreen(viewModel = libraryViewModel)
                        AppTab.Settings -> SettingsScreen(
                            viewModel = settingsViewModel,
                            onLogin = { showLogin = true },
                        )
                    }
                }

                // Details in either render mode; null selection renders nothing (the expanded
                // tier substitutes [DetailsPanePlaceholder] beside the list).
                val detailsContent: @Composable (embedded: Boolean) -> Unit = { embedded ->
                    val row = selectedRow
                    if (row != null) {
                        DetailsScreen(
                            row = row,
                            onBack = closeDetails,
                            onOpenDownloads = openDownloadsFromDetails,
                            downloading = detailsDownloading,
                            added = detailsAdded,
                            error = detailsError,
                            onDownload = { scope.launch { runDownload(row) } },
                            embedded = embedded,
                        )
                    }
                }

                if (compact) {
                    Box(
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    ) {
                        tabContent()
                        loginLayer()
                        detailsContent(false)
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    ) {
                        // Rail visibility: on the medium tier a details drill-down is still a
                        // full-screen overlay, so hide the rail while it (or login) is up — it
                        // would otherwise stay interactive beside the overlay and switch tabs
                        // invisibly beneath it (final-review F3). On the expanded tier details
                        // live in a pane next to the rail, so only the login overlay hides it.
                        if (!showLogin && (twoPane || selectedRow == null)) {
                            NavigationRail(
                                containerColor = Color.Transparent,
                                header = {
                                    Box(
                                        modifier = Modifier
                                            .width(2.dp)
                                            .height(24.dp)
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                },
                            ) {
                                AppTab.entries.forEach { tab ->
                                    NavigationRailItem(
                                        selected = currentTab == tab,
                                        onClick = { currentTab = tab },
                                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                                        label = { Text(tab.label.uppercase()) },
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                }
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            if (twoPane && currentTab == AppTab.Search) {
                                // List-detail: the tab content above IS the Search screen for
                                // this tab, so it slots straight into the list pane. The
                                // selection survives tab switches — returning to Search
                                // restores the pane.
                                Row(modifier = Modifier.fillMaxSize()) {
                                    Box(modifier = Modifier.weight(0.42f)) {
                                        tabContent()
                                    }
                                    ScanlineVerticalDivider(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(0.58f)
                                            .padding(
                                                start = 8.dp,
                                                top = 8.dp,
                                                end = 16.dp,
                                                bottom = 8.dp,
                                            ),
                                    ) {
                                        if (selectedRow != null) {
                                            detailsContent(true)
                                        } else {
                                            DetailsPanePlaceholder()
                                        }
                                    }
                                }
                                loginLayer()
                            } else {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    tabContent()
                                    loginLayer()
                                    // Medium tier only: the details drill-down is a full-screen
                                    // overlay. On the expanded tier other tabs render plain
                                    // (the selection just waits in the Search pane).
                                    if (!twoPane) {
                                        detailsContent(false)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
