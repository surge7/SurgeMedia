package com.surgemusic.app.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surgemusic.app.downloads.DownloadFilesCleaner
import com.surgemusic.app.downloads.NoopDownloadFilesCleaner
import com.surgemusic.app.ui.components.SurgeChip
import com.surgemusic.app.ui.components.SurgeConfirmDialog
import com.surgemusic.app.ui.components.SurgePanel
import com.surgemusic.app.ui.components.SurgeTextButton
import com.surgemusic.app.ui.components.MediaFormatChips
import com.surgemusic.app.ui.components.SurgeResultBanner
import com.surgemusic.core.downloads.DownloadItem
import com.surgemusic.core.downloads.DownloadManager
import com.surgemusic.core.downloads.DownloadState
import com.surgemusic.core.model.MediaInfo
import com.surgemusic.core.model.SizeParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Downloads view model (plan Task 8): a thin wrapper over [DownloadManager]'s item
 * state with pause/resume/cancel pass-through, plus history management — [remove] /
 * [clearCompleted] trim the list, and [deleteFiles] / [deleteAllFiles] run the platform
 * [DownloadFilesCleaner] and flash the result into [message] (consumed by the screen's
 * banner). Plain class with `StateFlow`s on `Dispatchers.Main`, same shape as
 * SearchViewModel; tests/previews use [NoopDownloadFilesCleaner].
 */
class DownloadsViewModel(
    private val manager: DownloadManager,
    private val cleaner: DownloadFilesCleaner = NoopDownloadFilesCleaner,
) {
    val items: StateFlow<List<DownloadItem>> = manager.items

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _message = MutableStateFlow<String?>(null)

    /** Last delete/cleanup result line; null = nothing to banner. Dismiss via [consumeMessage]. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun pause(id: String) = manager.pause(id)

    fun resume(id: String) = manager.resume(id)

    fun cancel(id: String) = manager.cancel(id)

    fun remove(id: String) = manager.remove(id)

    fun clearCompleted() = manager.clearCompleted()

    /** Deletes this item's finalized files, then drops it from the history. */
    fun deleteFiles(item: DownloadItem) {
        scope.launch {
            cleaner.deleteFor(item).onSuccess { n ->
                manager.remove(item.request.id)
                flash("Deleted $n file(s)")
            }.onFailure {
                flash("Delete failed: ${it.message}")
            }
        }
    }

    /** Deletes every file the app finalized and clears the finished history. */
    fun deleteAllFiles() {
        scope.launch {
            cleaner.deleteAll().onSuccess { n ->
                manager.clearCompleted()
                flash("Deleted $n file(s)")
            }.onFailure {
                flash("Delete failed: ${it.message}")
            }
        }
    }

    private fun flash(text: String) {
        _message.value = text
    }
}

/** Centered content cap for wide windows — beyond this the card grid just widens margins. */
private val MaxContentWidth = 1080.dp

/**
 * Downloads tab (plan Task 8): one card per queue item — title, forum, progress bar,
 * status chip, speed + peers while downloading, Pause/Resume toggle and Cancel;
 * terminal cards add Remove (and Delete files when DONE). The header offers
 * Clear history and Delete all files (the latter behind a confirmation). Empty state:
 * "No downloads yet — find a movie, album or show".
 *
 * Tablet/desktop: the cards flow into an adaptive grid (one ~340dp+ card per column, so
 * phones stay single-column while tablets get two or three), and the whole column is
 * capped at [MaxContentWidth] and centered so cards don't stretch absurdly on wide windows.
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    modifier: Modifier = Modifier,
) {
    val items by viewModel.items.collectAsState()

    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SurgePanel(modifier = Modifier.padding(24.dp)) {
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
                        text = "No downloads yet — find a movie, album or show",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        return
    }

    val message by viewModel.message.collectAsState()
    var pendingDeleteItem by remember { mutableStateOf<DownloadItem?>(null) }
    var showDeleteAll by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.fillMaxSize().widthIn(max = MaxContentWidth)) {
            message?.let { text ->
                SurgeResultBanner(text = text, onDismiss = viewModel::consumeMessage)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                SurgeTextButton(text = "Clear history", onClick = viewModel::clearCompleted)
                SurgeTextButton(
                    text = "Delete all files",
                    onClick = { showDeleteAll = true },
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.request.id }) { item ->
                    DownloadCard(
                        item = item,
                        onPause = { viewModel.pause(item.request.id) },
                        onResume = { viewModel.resume(item.request.id) },
                        onCancel = { viewModel.cancel(item.request.id) },
                        onRemove = { viewModel.remove(item.request.id) },
                        onDeleteFiles = { pendingDeleteItem = item },
                    )
                }
            }
        }
    }

    pendingDeleteItem?.let { item ->
        SurgeConfirmDialog(
            text = "Delete the downloaded files for this item from your device?",
            onConfirm = {
                pendingDeleteItem = null
                viewModel.deleteFiles(item)
            },
            onDismiss = { pendingDeleteItem = null },
        )
    }

    if (showDeleteAll) {
        SurgeConfirmDialog(
            text = "Delete ALL files Surge Media downloaded? This cannot be undone.",
            onConfirm = {
                showDeleteAll = false
                viewModel.deleteAllFiles()
            },
            onDismiss = { showDeleteAll = false },
        )
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onDeleteFiles: () -> Unit,
) {
    val active = item.state in ACTIVE_STATES
    SurgePanel(
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
            SurgeChip(text = statusText(item.state), color = statusColor(item.state))
            Spacer(Modifier.weight(1f))
            if (item.state == DownloadState.DOWNLOADING) {
                SurgeTextButton(text = "Pause", onClick = onPause)
            }
            if (item.state == DownloadState.PAUSED) {
                SurgeTextButton(text = "Resume", onClick = onResume)
            }
            if (active) {
                SurgeTextButton(
                    text = "Cancel",
                    onClick = onCancel,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (item.state in TERMINAL_STATES) {
                SurgeTextButton(
                    text = "Remove",
                    onClick = onRemove,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (item.state == DownloadState.DONE) {
                SurgeTextButton(
                    text = "Delete files",
                    onClick = onDeleteFiles,
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

private val TERMINAL_STATES =
    setOf(DownloadState.DONE, DownloadState.FAILED, DownloadState.CANCELLED)
