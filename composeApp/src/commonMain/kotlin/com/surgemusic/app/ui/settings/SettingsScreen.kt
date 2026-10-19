package com.surgemusic.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import com.surgemusic.app.ThemeMode
import com.surgemusic.app.session.Session
import com.surgemusic.app.session.SessionRepository
import com.surgemusic.app.session.formatTimestamp
import com.surgemusic.app.ui.components.SurgeButton
import com.surgemusic.app.ui.components.SurgePanel
import com.surgemusic.app.ui.components.SurgeSectionHeader
import com.surgemusic.app.ui.components.SurgeSegmentedControl
import com.surgemusic.app.ui.components.SurgeTextField
import com.surgemusic.app.ui.components.SurgeTextButton
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
    val themeMode: StateFlow<ThemeMode>

    fun setWifiOnly(enabled: Boolean)

    fun setMirror(url: String)

    fun setConcurrentDownloads(count: Int)

    fun setThemeMode(mode: ThemeMode)
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
    val themeMode: StateFlow<ThemeMode> = settings.themeMode

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    /** Human-readable outcome of the last validation (null = nothing to show). */
    private val _validationStatus = MutableStateFlow<String?>(null)
    val validationStatus: StateFlow<String?> = _validationStatus.asStateFlow()

    fun setWifiOnly(enabled: Boolean) = settings.setWifiOnly(enabled)

    fun setConcurrentDownloads(count: Int) = settings.setConcurrentDownloads(count)

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

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
 * Width at or above which the settings panels flow into two columns (tablet/desktop);
 * below it they stack in one centered column like on phones.
 */
private val TwoColumnBreakpoint = 720.dp

/** Centered content cap for wide windows. */
private val MaxContentWidth = 1040.dp

/**
 * Settings tab (plan Task 9): Session (status, Log in / Validate / Log out), Appearance
 * (theme: System / Light / Dark), Manual import (advanced), Network (Wi-Fi-only switch,
 * mirror URL, 1–5 concurrent downloads slider), About.
 *
 * "Log in" calls [onLogin] — `App` renders the platform `WebViewLoginScreen` over the tab.
 * Validation results are shown inline (commonMain has no toasts).
 *
 * Tablet/desktop (≥ [TwoColumnBreakpoint]): Session + Network in the left column, Manual
 * import + About in the right, so the whole tab fits without scrolling; the block is
 * capped at [MaxContentWidth] and centered.
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
    val themeMode by viewModel.themeMode.collectAsState()
    val validating by viewModel.validating.collectAsState()
    val validationStatus by viewModel.validationStatus.collectAsState()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val twoColumns = maxWidth >= TwoColumnBreakpoint
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = MaxContentWidth)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (twoColumns) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            SessionPanel(
                                session = session,
                                validating = validating,
                                validationStatus = validationStatus,
                                onLogin = onLogin,
                                onValidate = viewModel::validate,
                                onLogOut = viewModel::logOut,
                            )
                            ScanlineDivider(Modifier.padding(vertical = 16.dp))
                            NetworkPanel(
                                wifiOnly = wifiOnly,
                                onWifiOnlyChange = viewModel::setWifiOnly,
                                mirror = mirror,
                                onApplyMirror = viewModel::applyMirror,
                                concurrentDownloads = concurrentDownloads,
                                onConcurrentChange = viewModel::setConcurrentDownloads,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            AppearancePanel(
                                themeMode = themeMode,
                                onThemeModeChange = viewModel::setThemeMode,
                            )
                            ScanlineDivider(Modifier.padding(vertical = 16.dp))
                            ManualImportPanel(onImport = viewModel::importSession)
                            ScanlineDivider(Modifier.padding(vertical = 16.dp))
                            AboutPanel()
                        }
                    }
                } else {
                    SessionPanel(
                        session = session,
                        validating = validating,
                        validationStatus = validationStatus,
                        onLogin = onLogin,
                        onValidate = viewModel::validate,
                        onLogOut = viewModel::logOut,
                    )
                    ScanlineDivider(Modifier.padding(vertical = 16.dp))
                    AppearancePanel(
                        themeMode = themeMode,
                        onThemeModeChange = viewModel::setThemeMode,
                    )
                    ScanlineDivider(Modifier.padding(vertical = 16.dp))
                    ManualImportPanel(onImport = viewModel::importSession)
                    ScanlineDivider(Modifier.padding(vertical = 16.dp))
                    NetworkPanel(
                        wifiOnly = wifiOnly,
                        onWifiOnlyChange = viewModel::setWifiOnly,
                        mirror = mirror,
                        onApplyMirror = viewModel::applyMirror,
                        concurrentDownloads = concurrentDownloads,
                        onConcurrentChange = viewModel::setConcurrentDownloads,
                    )
                    ScanlineDivider(Modifier.padding(vertical = 16.dp))
                    AboutPanel()
                }
            }
        }
    }
}

@Composable
private fun SessionPanel(
    session: Session?,
    validating: Boolean,
    validationStatus: String?,
    onLogin: () -> Unit,
    onValidate: () -> Unit,
    onLogOut: () -> Unit,
) {
    SurgePanel(modifier = Modifier.fillMaxWidth()) {
        SurgeSectionHeader("Session", modifier = Modifier.padding(bottom = 4.dp))
        Text(
            text = when (val s = session) {
                null -> "Not logged in"
                else -> "Logged in (saved ${formatTimestamp(s.savedAtEpochMs)})"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            SurgeTextButton(text = "Log in", onClick = onLogin)
            SurgeButton(
                text = "Validate",
                onClick = onValidate,
                enabled = !validating,
                modifier = Modifier.padding(start = 8.dp),
            )
            SurgeTextButton(
                text = "Log out",
                onClick = onLogOut,
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
}

@Composable
private fun AppearancePanel(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    SurgePanel(modifier = Modifier.fillMaxWidth()) {
        SurgeSectionHeader("Appearance", modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "Theme — System follows your device's dark-mode setting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SurgeSegmentedControl(
            options = ThemeMode.entries.toList(),
            selected = themeMode,
            onSelect = onThemeModeChange,
            label = { it.label },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ManualImportPanel(onImport: (cookieHeader: String, userAgent: String) -> Unit) {
    SurgePanel(modifier = Modifier.fillMaxWidth()) {
        SurgeSectionHeader("Manual import (advanced)", modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "Paste the raw Cookie header and User-Agent from a logged-in desktop browser " +
                "(devtools → Network → any forum/ request). cf_clearance is bound to the User-Agent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ManualImportFields(onImport = onImport)
    }
}

@Composable
private fun NetworkPanel(
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    mirror: String,
    onApplyMirror: (String) -> Unit,
    concurrentDownloads: Int,
    onConcurrentChange: (Int) -> Unit,
) {
    SurgePanel(modifier = Modifier.fillMaxWidth()) {
        SurgeSectionHeader("Network", modifier = Modifier.padding(bottom = 4.dp))
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
                onCheckedChange = onWifiOnlyChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
        MirrorField(committed = mirror, onApply = onApplyMirror)
        Text(
            text = "Concurrent downloads: $concurrentDownloads",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = concurrentDownloads.toFloat(),
            onValueChange = { onConcurrentChange(it.roundToInt()) },
            valueRange = 1f..5f,
            steps = 3, // discrete stops at 1, 2, 3, 4, 5
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
                thumbColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun AboutPanel() {
    SurgePanel(modifier = Modifier.fillMaxWidth()) {
        SurgeSectionHeader("About", modifier = Modifier.padding(bottom = 4.dp))
        Text(
            text = "Surge Media ${Version.APP}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Movies, video and music, straight from rutracker to your library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ManualImportFields(onImport: (cookieHeader: String, userAgent: String) -> Unit) {
    var cookieHeader by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    SurgeTextField(
        value = cookieHeader,
        onValueChange = { cookieHeader = it },
        label = "Cookie header",
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        minLines = 2,
        maxLines = 4,
    )
    SurgeTextField(
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
        SurgeButton(
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
    SurgeTextField(
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
        SurgeButton(
            text = "Apply",
            onClick = { onApply(text) },
            enabled = text.isNotBlank() && text.trim() != committed.trim(),
        )
    }
}
