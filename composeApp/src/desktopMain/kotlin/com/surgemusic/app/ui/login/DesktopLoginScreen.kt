package com.surgemusic.app.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.surgemusic.app.AppContainer
import com.surgemusic.app.login.BrowserLoginCapture
import com.surgemusic.app.ui.components.SurgeButton
import com.surgemusic.app.ui.components.SurgePanel
import com.surgemusic.app.ui.components.SurgeSectionHeader
import com.surgemusic.app.ui.components.SurgeTextField
import com.surgemusic.app.ui.components.SurgeTextButton
import com.surgemusic.app.ui.strings.LocalStrings
import com.surgemusic.core.tracker.jvm.RutrackerClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Desktop sign-in — two paths over one panel:
 *
 * 1. **Sign in via browser** (primary): [BrowserLoginCapture] launches the user's real
 *    Chromium browser (Chrome/Edge/Yandex) on the tracker's login page in a throwaway
 *    profile with the DevTools debug port open. The user signs in there — Cloudflare
 *    passes because it is a real browser — and the app polls the cookie jar over CDP
 *    until `bb_session` appears, pairs it with that browser's own User-Agent, closes
 *    the window, then persists + validates through the shared probe
 *    ([AppContainer.sessionStore] / [AppContainer.validateSession]) and exits via
 *    [onFinished]. This is the desktop twin of androidMain's WebViewLoginScreen.
 * 2. **Manual import** (fallback for Safari/Firefox): paste the raw Cookie header +
 *    User-Agent (see docs/LOGIN.md) — the original flow, unchanged: SAVE persists,
 *    validates, and exits only when the probe passes.
 *
 * CANCEL cancels any in-flight browser capture (which also closes the browser window)
 * and exits. The login URL follows the configured mirror, same as the tracker client.
 */
@Composable
fun DesktopLoginScreen(onFinished: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    // Browser-flow state.
    var browserJob by remember { mutableStateOf<Job?>(null) }
    var browserStatus by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Manual-import state (original flow).
    var cookieHeader by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    var manualChecking by remember { mutableStateOf(false) }
    var manualError by remember { mutableStateOf<String?>(null) }

    val browserRunning = browserJob?.isActive == true

    fun startBrowserLogin() {
        error = null
        val browser = BrowserLoginCapture.findChromiumBrowser()
        if (browser == null) {
            error = strings.signInBrowserNotFound
            return
        }
        val base = AppContainer.settings.mirror.value.trim()
            .ifBlank { RutrackerClient.DEFAULT_BASE_URL }
            .trimEnd('/')
        browserStatus = strings.signInBrowserWaiting
        browserJob = scope.launch {
            when (val result = BrowserLoginCapture.capture(browser = browser, loginUrl = "$base/forum/login.php")) {
                is BrowserLoginCapture.Result.Success -> {
                    browserStatus = strings.signInBrowserValidating
                    AppContainer.sessionStore.save(
                        result.session.cookieHeader,
                        result.session.userAgent,
                    )
                    AppContainer.validateSession().fold(
                        onSuccess = { onFinished() },
                        onFailure = { e ->
                            browserStatus = null
                            error = strings.sessionCheckFailed(e.message ?: strings.unknownError)
                        },
                    )
                }
                BrowserLoginCapture.Result.BrowserClosed -> {
                    browserStatus = null
                    error = strings.signInBrowserClosed
                }
                is BrowserLoginCapture.Result.Error -> {
                    browserStatus = null
                    error = strings.signInBrowserLaunchFailed(result.message)
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SurgePanel(
                modifier = Modifier.widthIn(max = 480.dp).padding(16.dp),
                contentPadding = PaddingValues(20.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // SurgeTopBar look: primary accent bar + titleMedium.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(18.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = strings.signInTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = strings.desktopLoginBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (browserRunning) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp).height(18.dp).width(18.dp))
                        }
                        SurgeTextButton(
                            text = strings.cancel,
                            onClick = {
                                browserJob?.cancel()
                                onFinished()
                            },
                        )
                        SurgeButton(
                            text = strings.signInViaBrowser,
                            onClick = { startBrowserLogin() },
                            enabled = !browserRunning,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                    if (browserStatus != null) {
                        Text(
                            text = browserStatus.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (error != null) {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    SurgeSectionHeader(strings.manualImportHeader, modifier = Modifier.padding(bottom = 4.dp))
                    Text(
                        text = strings.manualImportBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SurgeTextField(
                        value = cookieHeader,
                        onValueChange = { cookieHeader = it },
                        label = strings.cookieHeaderLabel,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        minLines = 2,
                        maxLines = 4,
                    )
                    SurgeTextField(
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        label = strings.userAgentLabel,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true,
                        placeholder = RutrackerClient.USER_AGENT,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (manualChecking) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp).height(18.dp).width(18.dp))
                        }
                        SurgeButton(
                            text = strings.saveSession,
                            onClick = {
                                manualChecking = true
                                manualError = null
                                AppContainer.sessionStore.save(cookieHeader.trim(), userAgent.trim())
                                scope.launch {
                                    AppContainer.validateSession().fold(
                                        onSuccess = { onFinished() },
                                        onFailure = { e ->
                                            manualChecking = false
                                            manualError = e.message ?: e::class.simpleName
                                        },
                                    )
                                }
                            },
                            enabled = cookieHeader.isNotBlank() && !manualChecking && !browserRunning,
                        )
                    }
                    if (manualError != null) {
                        Text(
                            text = strings.sessionCheckFailed(manualError.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
