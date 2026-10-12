package com.surgemusic.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/** Bottom-nav tabs; navigation is plain state (no navigation library this phase). */
enum class AppTab(val label: String, val icon: String) {
    Search("Search", "🔍"),
    Downloads("Downloads", "⬇"),
    Settings("Settings", "⚙"),
}

@Composable
fun App() {
    var currentTab by remember { mutableStateOf(AppTab.Search) }

    SurgeMusicTheme {
        Scaffold(
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
                contentAlignment = Alignment.Center,
            ) {
                when (currentTab) {
                    AppTab.Search -> CenteredText("Search")
                    AppTab.Downloads -> CenteredText("Downloads")
                    AppTab.Settings -> CenteredText("Settings")
                }
            }
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Text(text = text, textAlign = TextAlign.Center)
}
