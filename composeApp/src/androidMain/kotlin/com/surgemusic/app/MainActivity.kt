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
import com.surgemusic.app.ui.login.WebViewLoginScreen
import com.surgemusic.core.downloads.DownloadRequest
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.appContext = applicationContext
        AppContainer.startNetworkGate()
        // Prewarm the torrent engine off the main thread: the first lazy touch starts the
        // native libtorrent session (slow); paying it here removes the first-download jank
        // instead of blocking the UI thread at the first enqueue or service start.
        thread(name = "engine-prewarm") { AppContainer.engine }
        registerDebugEnqueueHook()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Edge-to-edge under the cyberpunk theme: transparent system bars so the dark
        // background runs under them; dark style = light (cyan-on-dark) system icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            App(
                searchViewModel = AppContainer.searchViewModel,
                downloadsViewModel = AppContainer.downloadsViewModel,
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
