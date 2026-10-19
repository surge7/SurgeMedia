package com.surgemusic.app.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.surgemusic.app.ui.components.SurgeButton
import com.surgemusic.app.ui.components.SurgePanel
import com.surgemusic.app.ui.components.SurgeTextField
import com.surgemusic.app.ui.components.SurgeTextButton
import com.surgemusic.app.ui.strings.LocalStrings
import com.surgemusic.core.tracker.jvm.RutrackerClient
import kotlinx.coroutines.launch

/**
 * Desktop sign-in (plan Task 6) — the paste-cookie twin of androidMain's
 * [com.surgemusic.app.ui.login.WebViewLoginScreen]: desktop has no embedded browser, so
 * the user copies their browser's raw Cookie header + User-Agent (cf_clearance is
 * UA-bound) into the panel. SAVE validates the cookie non-blank, persists through
 * [AppContainer.sessionStore], runs the shared validate probe ([AppContainer.validateSession] —
 * plan Task 6: "save(...) + validate via settings probe") and exits via [onFinished] only
 * when the probe passes; a failed probe keeps the panel open with the reason inline, the
 * same contract as the Settings tab's manual import. CANCEL exits directly. Both paths
 * share the single `() -> Unit` exit (the android overlay distinguishes them with a
 * Boolean) — Main maps it to `onFinished(false)`, which the shell only uses to hide the
 * overlay; session state drives the UI.
 */
@Composable
fun DesktopLoginScreen(onFinished: () -> Unit) {
    val strings = LocalStrings.current
    var cookieHeader by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SurgePanel(
                modifier = Modifier.widthIn(max = 480.dp).padding(16.dp),
                contentPadding = PaddingValues(20.dp),
            ) {
                // SurgeTopBar look: primary accent bar + uppercase titleMedium.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(18.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.signInTitle.uppercase(),
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
                SurgeTextField(
                    value = cookieHeader,
                    onValueChange = { cookieHeader = it },
                    label = strings.cookieHeaderLabel,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
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
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SurgeTextButton(text = strings.cancel, onClick = onFinished)
                    SurgeButton(
                        text = strings.saveSession,
                        onClick = {
                            checking = true
                            saveError = null
                            AppContainer.sessionStore.save(cookieHeader.trim(), userAgent.trim())
                            scope.launch {
                                AppContainer.validateSession().fold(
                                    onSuccess = { onFinished() },
                                    onFailure = { e ->
                                        checking = false
                                        saveError = e.message ?: e::class.simpleName
                                    },
                                )
                            }
                        },
                        enabled = cookieHeader.isNotBlank() && !checking,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                } else if (saveError != null) {
                    Text(
                        text = strings.sessionCheckFailed(saveError.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
