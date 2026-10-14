package com.surgemusic.app.ui.back

import androidx.compose.runtime.Composable

/** Desktop has no system back gesture; the seam stays a no-op (plan Task 5). */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no-op
}
