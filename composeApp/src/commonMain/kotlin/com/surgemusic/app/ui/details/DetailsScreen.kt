package com.surgemusic.app.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.surgemusic.app.ui.back.PlatformBackHandler
import com.surgemusic.app.ui.components.SurgeButton
import com.surgemusic.app.ui.components.SurgeChip
import com.surgemusic.app.ui.components.SurgePanel
import com.surgemusic.app.ui.components.SurgeTopBar
import com.surgemusic.app.ui.components.ScanlineDivider
import com.surgemusic.app.ui.components.surgePanel
import com.surgemusic.app.ui.strings.LocalStrings
import com.surgemusic.core.model.MediaInfo
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
 * Two render modes share one content body:
 * - overlay (default): a full-screen [Surface] covering the tabs — phones and
 *   medium-width windows. The top bar shows a back arrow; system back closes it.
 * - [embedded]: the right pane of the tablet/desktop (≥840dp) list-detail layout — a
 *   bordered cyber panel instead of a full-screen surface. The top bar shows a close X;
 *   system back clears the selection (the pane is part of the layout, not navigation).
 *
 * The download pipeline (`onDownload`) is supplied by `App` (same one Search used to call
 * directly). The screen surfaces three states via props:
 * - [downloading]: the request is in flight (button becomes a spinner)
 * - [error]: last attempt failed; shown inline AND as a sticky snackbar in `App`
 * - [added]: download was enqueued; the button becomes "Added — go to Downloads"
 *
 * Failure → retry inline; success → [onOpenDownloads] (switch to Downloads tab).
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
    embedded: Boolean = false,
) {
    // Overlay mode: system back closes the drill-down. Embedded mode: back clears the
    // pane selection. Any login-overlay back handler in `App` composes after this screen
    // and therefore outranks it while the login flow is up.
    PlatformBackHandler(onBack = onBack)

    if (embedded) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .surgePanel()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            DetailsContent(
                row = row,
                embedded = embedded,
                onBack = onBack,
                onDownload = onDownload,
                onOpenDownloads = onOpenDownloads,
                downloading = downloading,
                added = added,
                error = error,
            )
        }
    } else {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                DetailsContent(
                    row = row,
                    embedded = embedded,
                    onBack = onBack,
                    onDownload = onDownload,
                    onOpenDownloads = onOpenDownloads,
                    downloading = downloading,
                    added = added,
                    error = error,
                )
            }
        }
    }
}

/** Empty state for the details pane while no result is selected (list-detail layout). */
@Composable
fun DetailsPanePlaceholder(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SurgePanel(modifier = Modifier.padding(24.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ManageSearch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = strings.detailsPlaceholderTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = strings.detailsPlaceholderBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.DetailsContent(
    row: SearchResultRow,
    embedded: Boolean,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onOpenDownloads: () -> Unit,
    downloading: Boolean,
    added: Boolean,
    error: String?,
) {
    val strings = LocalStrings.current
    SurgeTopBar(title = strings.detailsTitle, onBack = onBack, closeIcon = embedded)

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
        DetailRow(strings.detailForum, row.forumName)
        DetailRow(strings.detailSize, SizeParser.formatBytes(row.sizeBytes))
        val mediaInfo = remember(row.topicId) { MediaInfo.fromTitle(row.title, row.forumName) }
        DetailRow(strings.detailFormat, mediaInfo.summary())
        DetailRow(strings.detailSeedsLeechs, "${row.seeds} ↑ ${row.leechs} ↓")
        DetailRow(strings.detailDate, row.dateText)
        DetailRow(strings.detailTopicId, "#${row.topicId}")

        if (error != null) {
            ScanlineDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = strings.lastAttemptFailed,
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

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
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
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            downloading -> {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                Text(
                    text = strings.fetchingTorrent,
                    style = MaterialTheme.typography.bodyMedium,
                )
                SurgeChip(
                    text = strings.fetchingLabel,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            added -> {
                SurgeButton(
                    text = strings.addedOpenDownloads,
                    onClick = onOpenDownloads,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error != null -> {
                SurgeButton(
                    text = strings.actionRetry,
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    emphasis = true,
                )
            }
            else -> {
                SurgeButton(
                    text = strings.download,
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    emphasis = true,
                )
            }
        }
    }
}
