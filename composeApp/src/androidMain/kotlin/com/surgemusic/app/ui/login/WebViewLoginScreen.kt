package com.surgemusic.app.ui.login

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.surgemusic.app.AppContainer

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
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewLoginScreen(onFinished: (Boolean) -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Log in to rutracker",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onFinished(false) }) { Text("Cancel") }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
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
                        loadUrl(LOGIN_URL)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
