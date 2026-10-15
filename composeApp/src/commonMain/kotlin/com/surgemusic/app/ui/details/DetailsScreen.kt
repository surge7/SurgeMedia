package com.surgemusic.app.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.surgemusic.app.ui.back.PlatformBackHandler
import com.surgemusic.app.ui.components.CyberButton
import com.surgemusic.app.ui.components.CyberChip
import com.surgemusic.app.ui.components.CyberTopBar
import com.surgemusic.app.ui.components.ScanlineDivider
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.model.SizeParser

/**
 * Topic details / drill-down screen (Phase 3, post-patch).
 *
 * Replaces the Search tab's old "tap a row → start download" shortcut: a tap now opens
 * this screen first, and download is a button here. The search tab's `onShowDetails`
 * callback lifts the row into `App.selectedRow`; back (top-left button or system back)
 * returns to the search results.
 *
 * The download pipeline (`onDownload`) is supplied by `App` (same one Search used to call
 * directly). The screen surfaces three states via props:
 * - [downloading]: the request is in flight (button becomes a spinner)
 * - [error]: last attempt failed; shown inline AND as a sticky snackbar in `App`
 * - [added]: download was enqueued; the button becomes "Added — go to Downloads"
 *
 * Failure → [onRetry]; success → [onOpenDownloads] (switch to Downloads tab).
 */
@Composable
fun DetailsScreen(
    row: SearchResultRow,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onOpenDownloads: () -> Unit,
    downloading: Boolean,
    added: Boolean,
    error: String? = null,
) {
    // System back closes the details overlay (the doc's promised behavior) instead of
    // finishing the activity behind it.
    PlatformBackHandler(onBack = onBack)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            CyberTopBar(title = "Details", onBack = onBack)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleLarge,
                )
                DetailRow("Forum", row.forumName)
                DetailRow("Size", SizeParser.formatBytes(row.sizeBytes))
                DetailRow("Seeds / Leechs", "${row.seeds} ↑ ${row.leechs} ↓")
                DetailRow("Date", row.dateText)
                DetailRow("Topic ID", "#${row.topicId}")

                if (error != null) {
                    ScanlineDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "Last attempt failed",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            BottomBar(
                downloading = downloading,
                added = added,
                error = error,
                onDownload = onDownload,
                onOpenDownloads = onOpenDownloads,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun BottomBar(
    downloading: Boolean,
    added: Boolean,
    error: String?,
    onDownload: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            downloading -> {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                Text(
                    text = "Fetching torrent…",
                    style = MaterialTheme.typography.bodyMedium,
                )
                CyberChip(
                    text = "FETCHING",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            added -> {
                CyberButton(
                    text = "Added — open Downloads",
                    onClick = onOpenDownloads,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error != null -> {
                CyberButton(
                    text = "Retry",
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    emphasis = true,
                )
            }
            else -> {
                CyberButton(
                    text = "Download",
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    emphasis = true,
                )
            }
        }
    }
}
