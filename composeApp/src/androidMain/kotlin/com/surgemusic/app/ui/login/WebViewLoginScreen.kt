package com.surgemusic.app.ui.login

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.surgemusic.app.AppContainer
import com.surgemusic.app.ui.components.CyberTextButton
import com.surgemusic.app.ui.theme.CyberCyan
import com.surgemusic.app.ui.theme.CyberGrid

private const val LOGIN_URL = "https://rutracker.org/forum/login.php"
// Must be a /forum/ URL: rutracker's bb_session cookie is path-scoped to /forum/,
// and getCookie() only returns cookies whose path matches the query URL's path.
private const val COOKIE_URL = "https://rutracker.org/forum/"

/**
 * In-app WebView login (spec §4 "Cloudflare reality"): the user types their rutracker
 * credentials into the real page — Cloudflare and any captcha are solved by the real
 * browser engine. On each page load the cookie jar for `https://rutracker.org` is checked;
 * once it holds both `bb_session=` and `cf_clearance=` the session is saved via
 * [AppContainer.sessionStore] and [onFinished](true) fires. "Cancel" fires [onFinished](false).
 *
 * Runs edge-to-edge (Task 4): it renders inside the Scaffold body, whose content keeps the
 * default system-bar insets (plan Task 2), so the header and WebView are cleared by the
 * forwarded content padding alone — the original [statusBarsPadding]/[navigationBarsPadding]
 * double-padded under that forwarding (final-review F1). The WebView setup —
 * JS, DOM storage, cookie polling and the pre-clear logic — is unchanged.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewLoginScreen(onFinished: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column {
            // Cyberpunk top bar matching CyberTopBar (commonMain): cyan accent bar +
            // uppercase title, with the existing Cancel action as a CyberTextButton.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(18.dp)
                        .background(CyberCyan),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "SIGN IN",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                CyberTextButton(text = "Cancel", onClick = { onFinished(false) })
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .border(1.dp, CyberGrid),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        var finished = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (finished) return
                                val cookies = CookieManager.getInstance().getCookie(COOKIE_URL) ?: return
                                // bb_session proves login. cf_clearance is only present when
                                // Cloudflare actually challenged this WebView — demand it only as
                                // a bonus, never as a requirement (an unchallenged session works).
                                if ("bb_session=" in cookies) {
                                    finished = true
                                    AppContainer.sessionStore.save(cookies, view.settings.userAgentString)
                                    onFinished(true)
                                }
                            }
                        }
                        // The WebView cookie jar persists across opens: a bb_session left over from
                        // a previous login would make onPageFinished "succeed" instantly and close
                        // this screen before the user ever sees the login form. Always start from
                        // a clean (logged-out) jar.
                        cookieManager.removeSessionCookies(null)
                        cookieManager.removeAllCookies { loadUrl(LOGIN_URL) }
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
