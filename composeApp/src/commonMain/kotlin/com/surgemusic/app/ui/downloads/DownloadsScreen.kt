package com.surgemusic.app.ui.downloads

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surgemusic.app.ui.components.CyberChip
import com.surgemusic.app.ui.components.CyberPanel
import com.surgemusic.app.ui.components.CyberTextButton
import com.surgemusic.app.ui.components.MediaFormatChips
import com.surgemusic.core.downloads.DownloadItem
import com.surgemusic.core.downloads.DownloadManager
import com.surgemusic.core.downloads.DownloadState
import com.surgemusic.core.model.MediaInfo
import com.surgemusic.core.model.SizeParser
import kotlinx.coroutines.flow.StateFlow

/**
 * Downloads view model (plan Task 8): a thin wrapper over [DownloadManager]'s item
 * state with pause/resume/cancel pass-through.
 */
class DownloadsViewModel(private val manager: DownloadManager) {
    val items: StateFlow<List<DownloadItem>> = manager.items

    fun pause(id: String) = manager.pause(id)

    fun resume(id: String) = manager.resume(id)

    fun cancel(id: String) = manager.cancel(id)
}

/**
 * Downloads tab (plan Task 8): one card per queue item — title, forum, progress bar,
 * status chip, speed + peers while downloading, Pause/Resume toggle and Cancel.
 * Empty state: "No downloads yet — search for music".
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    modifier: Modifier = Modifier,
) {
    val items by viewModel.items.collectAsState()

    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CyberPanel(modifier = Modifier.padding(24.dp)) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No downloads yet — search for music",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.request.id }) { item ->
            DownloadCard(
                item = item,
                onPause = { viewModel.pause(item.request.id) },
                onResume = { viewModel.resume(item.request.id) },
                onCancel = { viewModel.cancel(item.request.id) },
            )
        }
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val active = item.state in ACTIVE_STATES
    CyberPanel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = item.request.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.request.forumName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        val mediaInfo = remember(item.request.id) { MediaInfo.fromTitle(item.request.title, item.request.forumName) }
        MediaFormatChips(mediaInfo, Modifier.padding(top = 4.dp))

        LinearProgressIndicator(
            progress = { progressFraction(item) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outline,
        )

        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CyberChip(text = statusText(item.state), color = statusColor(item.state))
            Spacer(Modifier.weight(1f))
            if (item.state == DownloadState.DOWNLOADING) {
                CyberTextButton(text = "Pause", onClick = onPause)
            }
            if (item.state == DownloadState.PAUSED) {
                CyberTextButton(text = "Resume", onClick = onResume)
            }
            if (active) {
                CyberTextButton(
                    text = "Cancel",
                    onClick = onCancel,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        val detail = statusLine(item)
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        item.outputNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Speed + peers are shown while downloading; else just the byte counters. */
private fun statusLine(item: DownloadItem): String {
    val parts = mutableListOf<String>()
    if (item.bytesDone > 0 && item.state != DownloadState.DONE) {
        parts += "${SizeParser.formatBytes(item.bytesDone)} / ${SizeParser.formatBytes(item.bytesTotal)}"
    }
    if (item.state == DownloadState.DOWNLOADING) {
        parts += "${SizeParser.formatBytes(item.downloadSpeed)}/s · ${item.peers} peers"
    }
    return parts.joinToString(" · ")
}

private fun progressFraction(item: DownloadItem): Float = when {
    item.state == DownloadState.DONE -> 1f
    item.bytesTotal > 0 -> (item.bytesDone.toFloat() / item.bytesTotal).coerceIn(0f, 1f)
    else -> 0f
}

private fun statusText(state: DownloadState): String = when (state) {
    DownloadState.QUEUED -> "Queued"
    DownloadState.DOWNLOADING -> "Downloading"
    DownloadState.PAUSED -> "Paused"
    DownloadState.FINALIZING -> "Finalizing"
    DownloadState.DONE -> "Done"
    DownloadState.FAILED -> "Failed"
    DownloadState.CANCELLED -> "Cancelled"
}

@Composable
private fun statusColor(state: DownloadState) = when (state) {
    DownloadState.DOWNLOADING -> MaterialTheme.colorScheme.primary
    DownloadState.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
    DownloadState.DONE -> MaterialTheme.colorScheme.tertiary
    DownloadState.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val ACTIVE_STATES =
    setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED)
