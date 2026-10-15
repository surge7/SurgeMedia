package com.surgemusic.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.surgemusic.app.ui.login.WebViewLoginScreen

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.appContext = applicationContext
        AppContainer.startNetworkGate()
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
    }
}
