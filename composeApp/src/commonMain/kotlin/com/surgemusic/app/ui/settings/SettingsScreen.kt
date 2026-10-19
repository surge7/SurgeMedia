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
import com.surgemusic.app.AppLanguage
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
import com.surgemusic.app.ui.strings.AppStrings
import com.surgemusic.app.ui.strings.LocalStrings
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
    val language: StateFlow<AppLanguage>

    fun setWifiOnly(enabled: Boolean)

    fun setMirror(url: String)

    fun setConcurrentDownloads(count: Int)

    fun setThemeMode(mode: ThemeMode)

    fun setLanguage(language: AppLanguage)
}

/**
 * Outcome of the last session validation / manual import, mapped to localized text by the
 * screen ([Failed.detail] stays the raw exception message; the empty-cookie import failure
 * is its own outcome so it gets its own wording).
 */
sealed interface ValidationOutcome {
    data object Idle : ValidationOutcome
    data object Ok : ValidationOutcome
    data object CookieHeaderEmpty : ValidationOutcome
    data class Failed(val detail: String?) : ValidationOutcome
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
    val language: StateFlow<AppLanguage> = settings.language

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    /** Outcome of the last validation ([ValidationOutcome.Idle] = nothing to show). */
    private val _validationStatus = MutableStateFlow<ValidationOutcome>(ValidationOutcome.Idle)
    val validationStatus: StateFlow<ValidationOutcome> = _validationStatus.asStateFlow()

    fun setWifiOnly(enabled: Boolean) = settings.setWifiOnly(enabled)

    fun setConcurrentDownloads(count: Int) = settings.setConcurrentDownloads(count)

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    fun setLanguage(language: AppLanguage) = settings.setLanguage(language)

    /** Persist an edited mirror URL (the screen commits via its "Apply" button). */
    fun applyMirror(url: String) {
        if (url.isNotBlank()) settings.setMirror(url.trim())
    }

    fun logOut() {
        sessionRepository.clear()
        _validationStatus.value = ValidationOutcome.Idle
    }

    /**
     * "Manual import (advanced)" (plan Task 9): save a Cookie header + User-Agent copied
     * from desktop devtools, then run the same validation as the "Validate" button.
     */
    fun importSession(cookieHeader: String, userAgent: String) {
        if (cookieHeader.isBlank()) {
            _validationStatus.value = ValidationOutcome.CookieHeaderEmpty
            return
        }
        sessionRepository.save(cookieHeader.trim(), userAgent.trim())
        validate()
    }

    /** "Validate" button (plan Task 9): one test search through the tracker client. */
    fun validate() {
        if (_validating.value) return
        _validating.value = true
        _validationStatus.value = ValidationOutcome.Idle
        scope.launch {
            validateSession().fold(
                onSuccess = { _validationStatus.value = ValidationOutcome.Ok },
                onFailure = { e ->
                    _validationStatus.value =
                        ValidationOutcome.Failed(e.message ?: e::class.simpleName)
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
 * (theme: System / Light / Dark; language: System / English / Русский), Manual import
 * (advanced), Network (Wi-Fi-only switch, mirror URL, 1–5 concurrent downloads slider),
 * About.
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
    val language by viewModel.language.collectAsState()
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
                                language = language,
                                onLanguageChange = viewModel::setLanguage,
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
                        language = language,
                        onLanguageChange = viewModel::setLanguage,
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
    validationStatus: ValidationOutcome,
    onLogin: () -> Unit,
    onValidate: () -> Unit,
    onLogOut: () -> Unit,
) {
    val strings = LocalStrings.current
    SurgePanel(modifier = Modifier.fillMaxWidth()) {
        SurgeSectionHeader(strings.sessionHeader, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            text = when (val s = session) {
                null -> strings.notLoggedIn
                else -> strings.loggedInAt(formatTimestamp(s.savedAtEpochMs))
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            SurgeTextButton(text = strings.logIn, onClick = onLogin)
            SurgeButton(
                text = strings.validate,
                onClick = onValidate,
                enabled = !validating,
                modifier = Modifier.padding(start = 8.dp),
            )
            SurgeTextButton(
                text = strings.logOut,
                onClick = onLogOut,
                tint = MaterialTheme.colorScheme.error,
                enabled = session != null,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (validating) {
            CircularProgressIndicator(Modifier.padding(top = 8.dp))
        } else {
            val (text, ok) = when (validationStatus) {
                ValidationOutcome.Idle -> null to true
                ValidationOutcome.Ok -> strings.validationOk to true
                ValidationOutcome.CookieHeaderEmpty -> strings.importFailedEmptyCookie to false
                is ValidationOutcome.Failed ->
                    strings.sessionCheckFailed(validationStatus.detail ?: strings.unknownError) to false
            }
            if (text != null) {
                Text(
                    text = text,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

/** Theme display label; the color-matching enum names stay English (data keys). */
private fun themeLabel(mode: ThemeMode, strings: AppStrings): String = when (mode) {
    ThemeMode.SYSTEM -> strings.themeSystem
    ThemeMode.LIGHT -> strings.themeLight
    ThemeMode.DARK -> strings.themeDark
}

/** Language display label; resolved inside composition so a switch re-resolves instantly. */
private fun languageLabel(language: AppLanguage, strings: AppStrings): String = when (language) {
    AppLanguage.SYSTEM -> strings.languageSystem
    AppLanguage.ENGLISH -> strings.languageEnglish
    AppLanguage.RUSSIAN -> strings.languageRussian
}

@Composable
private fun AppearancePanel(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    val strings = LocalStrings.current
    SurgePanel(modifier = Modifier.fillMaxWidth()) {
        SurgeSectionHeader(strings.appearanceHeader, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            strings.themeCaption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SurgeSegmentedControl(
            options = ThemeMode.entries.toList(),
            selected = themeMode,
            onSelect = onThemeModeChange,
            label = { themeLabel(it, strings) },
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            strings.languageCaption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        SurgeSegmentedControl(
            options = AppLanguage.entries.toList(),
            selected = language,
            onSelect = onLanguageChange,
            label = { languageLabel(it, strings) },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ManualImportPanel(onImport: (cookieHeader: String, userAgent: String) -> Unit) {
    val strings = LocalStrings.current
    SurgePanel(modifier = Modifier.fillMaxWidth()) {
        SurgeSectionHeader(strings.manualImportHeader, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            strings.manualImportBody,
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
    val strings = LocalStrings.current
    SurgePanel(modifier = Modifier.fillMaxWidth()) {
        SurgeSectionHeader(strings.networkHeader, modifier = Modifier.padding(bottom = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.wifiOnlyLabel,
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
            text = strings.concurrentDownloads(concurrentDownloads),
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
    val strings = LocalStrings.current
    SurgePanel(modifier = Modifier.fillMaxWidth()) {
        SurgeSectionHeader(strings.aboutHeader, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            text = strings.aboutVersion(Version.APP),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = strings.aboutBody,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ManualImportFields(onImport: (cookieHeader: String, userAgent: String) -> Unit) {
    val strings = LocalStrings.current
    var cookieHeader by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    SurgeTextField(
        value = cookieHeader,
        onValueChange = { cookieHeader = it },
        label = strings.cookieHeaderLabel,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        minLines = 2,
        maxLines = 4,
    )
    SurgeTextField(
        value = userAgent,
        onValueChange = { userAgent = it },
        label = strings.userAgentLabel,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        SurgeButton(
            text = strings.importButton,
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
    val strings = LocalStrings.current
    var text by remember(committed) { mutableStateOf(committed) }
    SurgeTextField(
        value = text,
        onValueChange = { text = it },
        label = strings.mirrorUrlLabel,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        SurgeButton(
            text = strings.applyButton,
            onClick = { onApply(text) },
            enabled = text.isNotBlank() && text.trim() != committed.trim(),
        )
    }
}
