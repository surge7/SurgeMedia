package com.surgemusic.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.surgemusic.app.session.SessionRepository
import com.surgemusic.app.ui.downloads.DownloadsScreen
import com.surgemusic.app.ui.downloads.DownloadsViewModel
import com.surgemusic.app.ui.search.SearchScreen
import com.surgemusic.app.ui.search.SearchViewModel
import com.surgemusic.core.model.SearchResultRow
import kotlinx.coroutines.launch

/** Bottom-nav tabs; navigation is plain state (no navigation library this phase). */
enum class AppTab(val label: String, val icon: String) {
    Search("Search", "🔍"),
    Downloads("Downloads", "⬇"),
    Settings("Settings", "⚙"),
}

/**
 * App shell (plan Tasks 4/6/8): three-tab bottom navigation; Search and Downloads tabs
 * are real; Settings remains a placeholder until Task 9.
 *
 * [loginOverlay] is a platform slot — androidMain passes [com.surgemusic.app.ui.login.WebViewLoginScreen]
 * (`WebViewLoginScreen` is android-only); it renders over the current tab while the login flow is up.
 *
 * [onDownload] is the platform download pipeline (Task 8; androidMain: fetch .torrent →
 * enqueue → start the foreground service). Tapping a search result calls it; a failure is
 * surfaced as a snackbar.
 */
@Composable
fun App(
    searchViewModel: SearchViewModel,
    downloadsViewModel: DownloadsViewModel,
    sessionRepository: SessionRepository,
    loginOverlay: @Composable (onFinished: (Boolean) -> Unit) -> Unit,
    onDownload: suspend (SearchResultRow) -> Result<Unit>,
) {
    var currentTab by remember { mutableStateOf(AppTab.Search) }
    var showLogin by remember { mutableStateOf(false) }
    val session by sessionRepository.session.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    SurgeMusicTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = { Text(tab.icon, fontSize = 20.sp) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                when (currentTab) {
                    AppTab.Search -> SearchScreen(
                        viewModel = searchViewModel,
                        loggedIn = session != null,
                        onDownload = { row ->
                            scope.launch {
                                onDownload(row).onFailure { e ->
                                    snackbarHostState.showSnackbar(
                                        "Download failed: ${e.message ?: "unknown error"}",
                                    )
                                }
                            }
                        },
                        onLogin = { showLogin = true },
                    )
                    AppTab.Downloads -> DownloadsScreen(viewModel = downloadsViewModel)
                    AppTab.Settings -> CenteredText("Settings")
                }
                if (showLogin) {
                    loginOverlay { showLogin = false }
                }
            }
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, textAlign = TextAlign.Center)
    }
}

