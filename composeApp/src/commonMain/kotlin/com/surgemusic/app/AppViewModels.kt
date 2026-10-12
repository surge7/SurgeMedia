package com.surgemusic.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Placeholder view models so the tab screens compile before the real ones land.
 * Replaced by real VMs in Tasks 6 (search), 8 (downloads) and 9 (settings).
 */
class SearchPlaceholderViewModel(initialQuery: String = "") {
    private val _query = MutableStateFlow(initialQuery)
    val query: StateFlow<String> = _query.asStateFlow()
}

class DownloadsPlaceholderViewModel(statusText: String = "No downloads yet") {
    private val _status = MutableStateFlow(statusText)
    val status: StateFlow<String> = _status.asStateFlow()
}

class SettingsPlaceholderViewModel(wifiOnly: Boolean = true) {
    private val _wifiOnly = MutableStateFlow(wifiOnly)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()
}
