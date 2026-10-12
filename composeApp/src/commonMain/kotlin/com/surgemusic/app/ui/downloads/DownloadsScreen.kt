package com.surgemusic.app.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surgemusic.core.downloads.DownloadItem
import com.surgemusic.core.downloads.DownloadManager
import com.surgemusic.core.downloads.DownloadState
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
        Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "No downloads yet — search for music",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(items, key = { it.request.id }) { item ->
            DownloadCard(
                item = item,
                onPause = { viewModel.pause(item.request.id) },
                onResume = { viewModel.resume(item.request.id) },
                onCancel = { viewModel.cancel(item.request.id) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = item.request.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.request.forumName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        LinearProgressIndicator(
            progress = { progressFraction(item) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = statusLine(item),
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(item.state),
                modifier = Modifier.weight(1f),
            )
            if (item.state == DownloadState.DOWNLOADING) {
                TextButton(onClick = onPause) { Text("Pause") }
            }
            if (item.state == DownloadState.PAUSED) {
                TextButton(onClick = onResume) { Text("Resume") }
            }
            if (active) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
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

/** Speed + peers are shown while downloading; else just the state (plus byte counters). */
private fun statusLine(item: DownloadItem): String {
    val parts = mutableListOf(statusText(item.state))
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
    DownloadState.FAILED -> MaterialTheme.colorScheme.error
    DownloadState.DONE -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val ACTIVE_STATES =
    setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED)
