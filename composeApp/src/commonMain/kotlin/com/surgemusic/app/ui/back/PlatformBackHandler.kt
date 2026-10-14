package com.surgemusic.app.ui.back

import androidx.compose.runtime.Composable

/**
 * Multiplatform seam for intercepting the system back gesture inside a Compose screen.
 * On Android it delegates to androidx.activity's BackHandler; platforms without a back
 * concept (desktop) make it a no-op.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
