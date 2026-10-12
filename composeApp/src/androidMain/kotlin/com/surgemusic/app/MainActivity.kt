package com.surgemusic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.surgemusic.app.ui.login.WebViewLoginScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.appContext = applicationContext
        setContent {
            App(
                searchViewModel = AppContainer.searchViewModel,
                sessionRepository = AppContainer.sessionStore,
                loginOverlay = { onFinished -> WebViewLoginScreen(onFinished) },
            )
        }
    }
}
