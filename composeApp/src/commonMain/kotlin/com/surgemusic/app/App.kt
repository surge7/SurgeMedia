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
import com.surgemusic.app.ui.components.CyberSnackbarHost
import com.surgemusic.app.ui.details.DetailsScreen
import com.surgemusic.app.ui.downloads.DownloadsScreen
import com.surgemusic.app.ui.downloads.DownloadsViewModel
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
    Settings("Settings", Icons.Rounded.Settings),
}

/** Width at or below which the shell switches from NavigationRail to bottom NavigationBar. */
private val CompactBreakpoint = 700.dp

/**
 * Adaptive app shell (plan Tasks 4/6/8/9): three tabs — bottom NavigationBar on compact
 * windows, a NavigationRail on expanded ones (desktop window / tablet). Search, Downloads
 * and Settings tabs are all real.
 *
 * [loginOverlay] is a platform slot — androidMain passes [com.surgemusic.app.ui.login.WebViewLoginScreen]
 * (`WebViewLoginScreen` is android-only); it renders over the current tab while the login flow is up.
 * Both the Search tab's "Log in" hero and the Settings tab's "Log in" button raise it.
 *
 * Search-result drill-down: tapping a row on the Search tab lifts the row into [selectedRow]
 * and renders [DetailsScreen] over the tabs. The Details screen owns the Download button, so
 * [onDownload] is only invoked from there. A failure surfaces two ways — inline on the
 * details screen AND as a long snackbar with a Retry action — so a transient tracker timeout
 * (e.g. `dl.php` socket timeout) is impossible to miss.
 *
 * [onDownload] is the platform download pipeline (Task 8; androidMain: fetch .torrent →
 * enqueue → start the foreground service).
 */
@Composable
fun App(
    searchViewModel: SearchViewModel,
    downloadsViewModel: DownloadsViewModel,
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

    SurgeMusicTheme {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxWidth < CompactBreakpoint

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { CyberSnackbarHost(hostState = snackbarHostState) },
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
                val tabContent: @Composable () -> Unit = {
                    when (currentTab) {
                        AppTab.Search -> SearchScreen(
                            viewModel = searchViewModel,
                            loggedIn = session != null,
                            onShowDetails = { row ->
                                selectedRow = row
                                detailsError = null
                                detailsDownloading = false
                                detailsAdded = false
                            },
                            onLogin = { showLogin = true },
                        )
                        AppTab.Downloads -> DownloadsScreen(viewModel = downloadsViewModel)
                        AppTab.Settings -> SettingsScreen(
                            viewModel = settingsViewModel,
                            onLogin = { showLogin = true },
                        )
                    }
                    if (showLogin) {
                        loginOverlay { showLogin = false }
                    }

                    val row = selectedRow
                    if (row != null) {
                        DetailsScreen(
                            row = row,
                            onBack = {
                                selectedRow = null
                                detailsError = null
                                detailsDownloading = false
                                detailsAdded = false
                            },
                            onOpenDownloads = {
                                selectedRow = null
                                detailsError = null
                                detailsDownloading = false
                                detailsAdded = false
                                currentTab = AppTab.Downloads
                            },
                            downloading = detailsDownloading,
                            added = detailsAdded,
                            error = detailsError,
                            onDownload = {
                                scope.launch { runDownload(row) }
                            },
                        )
                    }
                }

                if (compact) {
                    Box(
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    ) {
                        tabContent()
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    ) {
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
                        Box(modifier = Modifier.weight(1f)) {
                            tabContent()
                        }
                    }
                }
            }
        }
    }
}
