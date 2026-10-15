package com.surgemusic.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.surgemusic.app.session.Session
import com.surgemusic.app.session.SessionRepository
import com.surgemusic.app.session.formatTimestamp
import com.surgemusic.app.ui.components.CyberButton
import com.surgemusic.app.ui.components.CyberPanel
import com.surgemusic.app.ui.components.CyberSectionHeader
import com.surgemusic.app.ui.components.CyberTextField
import com.surgemusic.app.ui.components.CyberTextButton
import com.surgemusic.app.ui.components.ScanlineDivider
import com.surgemusic.core.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The slice of app settings the Settings tab drives (plan Task 9). Lives in commonMain so
 * the screen compiles there; androidMain's `SettingsStore` implements it directly.
 */
interface AppSettings {
    val wifiOnly: StateFlow<Boolean>
    val mirror: StateFlow<String>
    val concurrentDownloads: StateFlow<Int>

    fun setWifiOnly(enabled: Boolean)

    fun setMirror(url: String)

    fun setConcurrentDownloads(count: Int)
}

/**
 * Settings view model (plan Task 9): session status + log out, manual cookie import,
 * network preferences, and the injected session-validation probe
 * (`client.search(SearchQuery("test"))` in production). Same pattern as [com.surgemusic.app.ui.search.SearchViewModel]:
 * plain class with `StateFlow`s, constructor-injected suspend function, no lifecycle-viewmodel dependency.
 */
class SettingsViewModel(
    private val sessionRepository: SessionRepository,
    private val settings: AppSettings,
    private val validateSession: suspend () -> Result<Unit>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val session: StateFlow<Session?> = sessionRepository.session
    val wifiOnly: StateFlow<Boolean> = settings.wifiOnly
    val mirror: StateFlow<String> = settings.mirror
    val concurrentDownloads: StateFlow<Int> = settings.concurrentDownloads

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    /** Human-readable outcome of the last validation (null = nothing to show). */
    private val _validationStatus = MutableStateFlow<String?>(null)
    val validationStatus: StateFlow<String?> = _validationStatus.asStateFlow()

    fun setWifiOnly(enabled: Boolean) = settings.setWifiOnly(enabled)

    fun setConcurrentDownloads(count: Int) = settings.setConcurrentDownloads(count)

    /** Persist an edited mirror URL (the screen commits via its "Apply" button). */
    fun applyMirror(url: String) {
        if (url.isNotBlank()) settings.setMirror(url.trim())
    }

    fun logOut() {
        sessionRepository.clear()
        _validationStatus.value = null
    }

    /**
     * "Manual import (advanced)" (plan Task 9): save a Cookie header + User-Agent copied
     * from desktop devtools, then run the same validation as the "Validate" button.
     */
    fun importSession(cookieHeader: String, userAgent: String) {
        if (cookieHeader.isBlank()) {
            _validationStatus.value = "Import failed: the cookie header is empty."
            return
        }
        sessionRepository.save(cookieHeader.trim(), userAgent.trim())
        validate()
    }

    /** "Validate" button (plan Task 9): one test search through the tracker client. */
    fun validate() {
        if (_validating.value) return
        _validating.value = true
        _validationStatus.value = null
        scope.launch {
            validateSession().fold(
                onSuccess = { _validationStatus.value = "Session OK — test search succeeded." },
                onFailure = { e ->
                    _validationStatus.value =
                        "Session check failed: ${e.message ?: e::class.simpleName ?: "unknown error"}"
                },
            )
            _validating.value = false
        }
    }
}

/**
 * Settings tab (plan Task 9): Session (status, Log in / Validate / Log out), Manual import
 * (advanced), Network (Wi-Fi-only switch, mirror URL, 1–5 concurrent downloads slider), About.
 *
 * "Log in" calls [onLogin] — `App` renders the platform `WebViewLoginScreen` over the tab.
 * Validation results are shown inline (commonMain has no toasts).
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onLogin: () -> Unit = {},
) {
    val session by viewModel.session.collectAsState()
    val wifiOnly by viewModel.wifiOnly.collectAsState()
    val mirror by viewModel.mirror.collectAsState()
    val concurrentDownloads by viewModel.concurrentDownloads.collectAsState()
    val validating by viewModel.validating.collectAsState()
    val validationStatus by viewModel.validationStatus.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        CyberPanel(modifier = Modifier.fillMaxWidth()) {
            CyberSectionHeader("Session", modifier = Modifier.padding(bottom = 4.dp))
            Text(
                text = when (val s = session) {
                    null -> "Not logged in"
                    else -> "Logged in (saved ${formatTimestamp(s.savedAtEpochMs)})"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                CyberTextButton(text = "Log in", onClick = onLogin)
                CyberButton(
                    text = "Validate",
                    onClick = viewModel::validate,
                    enabled = !validating,
                    modifier = Modifier.padding(start = 8.dp),
                )
                CyberTextButton(
                    text = "Log out",
                    onClick = viewModel::logOut,
                    tint = MaterialTheme.colorScheme.error,
                    enabled = session != null,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (validating) {
                CircularProgressIndicator(Modifier.padding(top = 8.dp))
            } else if (validationStatus != null) {
                Text(
                    text = validationStatus.orEmpty(),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (validationStatus.orEmpty().startsWith("Session OK")) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        ScanlineDivider(Modifier.padding(vertical = 16.dp))
        CyberPanel(modifier = Modifier.fillMaxWidth()) {
            CyberSectionHeader("Manual import (advanced)", modifier = Modifier.padding(bottom = 4.dp))
            Text(
                "Paste the raw Cookie header and User-Agent from a logged-in desktop browser " +
                    "(devtools → Network → any forum/ request). cf_clearance is bound to the User-Agent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ManualImportFields(onImport = viewModel::importSession)
        }

        ScanlineDivider(Modifier.padding(vertical = 16.dp))
        CyberPanel(modifier = Modifier.fillMaxWidth()) {
            CyberSectionHeader("Network", modifier = Modifier.padding(bottom = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Download on Wi-Fi only",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = viewModel::setWifiOnly,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
            MirrorField(committed = mirror, onApply = viewModel::applyMirror)
            Text(
                text = "Concurrent downloads: $concurrentDownloads",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Slider(
                value = concurrentDownloads.toFloat(),
                onValueChange = { viewModel.setConcurrentDownloads(it.roundToInt()) },
                valueRange = 1f..5f,
                steps = 3, // discrete stops at 1, 2, 3, 4, 5
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outline,
                    thumbColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }

        ScanlineDivider(Modifier.padding(vertical = 16.dp))
        CyberPanel(modifier = Modifier.fillMaxWidth()) {
            CyberSectionHeader("About", modifier = Modifier.padding(bottom = 4.dp))
            Text(
                text = "SurgeMusic ${Version.APP}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ManualImportFields(onImport: (cookieHeader: String, userAgent: String) -> Unit) {
    var cookieHeader by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    CyberTextField(
        value = cookieHeader,
        onValueChange = { cookieHeader = it },
        label = "Cookie header",
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        minLines = 2,
        maxLines = 4,
    )
    CyberTextField(
        value = userAgent,
        onValueChange = { userAgent = it },
        label = "User-Agent",
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        CyberButton(
            text = "Import",
            onClick = {
                onImport(cookieHeader, userAgent)
                cookieHeader = ""
                userAgent = ""
            },
            enabled = cookieHeader.isNotBlank(),
        )
    }
}

/**
 * Mirror URL with local edit state; "Apply" commits (per-keystroke writes would churn
 * SharedPreferences and rebuild the tracker client on half-typed URLs).
 */
@Composable
private fun MirrorField(committed: String, onApply: (String) -> Unit) {
    var text by remember(committed) { mutableStateOf(committed) }
    CyberTextField(
        value = text,
        onValueChange = { text = it },
        label = "Mirror URL",
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        CyberButton(
            text = "Apply",
            onClick = { onApply(text) },
            enabled = text.isNotBlank() && text.trim() != committed.trim(),
        )
    }
}
