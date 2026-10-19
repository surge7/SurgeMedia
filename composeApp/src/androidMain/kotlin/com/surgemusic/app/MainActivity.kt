package com.surgemusic.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.surgemusic.app.ui.login.WebViewLoginScreen
import com.surgemusic.core.downloads.DownloadRequest
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    /** Notifications (API 33+) + media reads (Downloaded tab): one best-effort prompt at launch. */
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.appContext = applicationContext
        AppContainer.startNetworkGate()
        // Prewarm the torrent engine off the main thread: the first lazy touch starts the
        // native libtorrent session (slow); paying it here removes the first-download jank
        // instead of blocking the UI thread at the first enqueue or service start.
        thread(name = "engine-prewarm") { AppContainer.engine }
        registerDebugEnqueueHook()
        requestPermissionsAtLaunch()
        // Edge-to-edge baseline (transparent bars); the SideEffect below re-styles the
        // system-bar icons whenever the effective theme changes — the light theme needs
        // dark icons, the dark theme light ones.
        enableEdgeToEdge()
        setContent {
            // Effective theme = Settings ▸ Appearance preference (System / Light / Dark).
            val themeMode by AppContainer.settingsViewModel.themeMode.collectAsState()
            val dark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            SideEffect {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) {
                    SystemBarStyle.dark(transparent)
                } else {
                    SystemBarStyle.light(transparent, transparent)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            App(
                searchViewModel = AppContainer.searchViewModel,
                downloadsViewModel = AppContainer.downloadsViewModel,
                libraryViewModel = AppContainer.libraryViewModel,
                settingsViewModel = AppContainer.settingsViewModel,
                sessionRepository = AppContainer.sessionStore,
                loginOverlay = { onFinished -> WebViewLoginScreen(onFinished) },
                onDownload = { row -> AppContainer.download(row) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        AppContainer.startDownloadService()
        AppContainer.healNetworkGate()
    }

    /**
     * One best-effort prompt for everything optional: notifications (API 33+) and the
     * media reads behind the Downloaded tab (media the app finalized is always visible
     * to it; the reads cover rows MediaStore no longer attributes to this install).
     * Already-granted/denied permissions are filtered out so nothing re-prompts.
     */
    private fun requestPermissionsAtLaunch() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                @Suppress("DEPRECATION")
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }

    /**
     * DIAGNOSTIC: debug-only torrent enqueue for on-device verification. Lets an adb
     * broadcast drive the exact production enqueue→service path with any small .torrent
     * (base64 in the `torrentB64` extra) — no rutracker session needed:
     * `adb shell am broadcast -a com.surgemusic.app.DEBUG_ENQUEUE --es title "diag"
     *    --el sizeBytes 9343070 --es torrentB64 <b64>`
     * Registered only when the APK is debuggable. Exported is required for adb (shell
     * uid ≠ app uid; a NOT_EXPORTED receiver silently drops shell broadcasts, verified
     * on API 36) — acceptable for a debug-only, app-specific action.
     */
    private fun registerDebugEnqueueHook() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    // Optional in-process DNS probe: libtorrent's bundled resolver fails on
                    // some devices while Android's (bionic) resolver works — compare both.
                    intent.getStringExtra("resolveHost")?.let { host ->
                        Thread {
                            try {
                                val addrs = java.net.InetAddress.getAllByName(host)
                                Log.d(TAG, "DEBUG_ENQUEUE resolveHost $host -> ${addrs.joinToString()}")
                            } catch (e: Exception) {
                                Log.w(TAG, "DEBUG_ENQUEUE resolveHost $host FAILED: $e")
                            }
                        }.start()
                        return
                    }
                    val b64 = intent.getStringExtra("torrentB64")
                    if (b64.isNullOrEmpty()) {
                        Log.w(TAG, "DEBUG_ENQUEUE: missing torrentB64 extra")
                        return
                    }
                    val bytes = try {
                        Base64.decode(b64, Base64.DEFAULT)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "DEBUG_ENQUEUE: bad base64", e)
                        return
                    }
                    val title = intent.getStringExtra("title") ?: "diag"
                    val size = intent.getLongExtra("sizeBytes", 0L)
                    val staging = File(getExternalFilesDir("staging"), "0").absolutePath
                    Log.d(TAG, "DEBUG_ENQUEUE: enqueueing '$title' (${bytes.size} torrent bytes, size=$size) → $staging")
                    AppContainer.downloadManager.enqueue(
                        DownloadRequest(id = "t-debug", topicId = 0, title = title, forumName = "diag", sizeBytes = size),
                        bytes,
                        staging,
                    )
                    AppContainer.startDownloadService()
                    Log.d(TAG, "DEBUG_ENQUEUE: enqueued + service started")
                }
            },
            IntentFilter(DEBUG_ENQUEUE_ACTION),
            Context.RECEIVER_EXPORTED,
        )
    }

    private companion object {
        const val TAG = "SurgeDiag"
        const val DEBUG_ENQUEUE_ACTION = "com.surgemusic.app.DEBUG_ENQUEUE"
    }
}
