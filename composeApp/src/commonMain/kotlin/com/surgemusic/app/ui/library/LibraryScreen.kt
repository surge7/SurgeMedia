package com.surgemusic.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.surgemusic.app.downloads.DownloadedLibrary
import com.surgemusic.app.downloads.DownloadedRelease
import com.surgemusic.app.ui.components.SurgeChip
import com.surgemusic.app.ui.components.SurgeConfirmDialog
import com.surgemusic.app.ui.components.SurgePanel
import com.surgemusic.app.ui.components.SurgeResultBanner
import com.surgemusic.app.ui.components.SurgeSectionHeader
import com.surgemusic.app.ui.components.SurgeTextButton
import com.surgemusic.core.model.SizeParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Downloaded tab view model: what the app has already finalized onto the device's media
 * libraries, read through the platform [DownloadedLibrary] seam — independent of the
 * transfer history ([core DownloadManager] items are in-memory and vanish on restart).
 * Plain class with `StateFlow`s on `Dispatchers.Main`, same shape as DownloadsViewModel.
 */
class LibraryViewModel(private val library: DownloadedLibrary) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _releases = MutableStateFlow<List<DownloadedRelease>>(emptyList())
    val releases: StateFlow<List<DownloadedRelease>> = _releases.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Last delete/refresh outcome line; null = nothing to banner. Dismiss via [consumeMessage]. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun refresh() {
        if (_loading.value) return
        _loading.value = true
        scope.launch {
            library.list().fold(
                onSuccess = { releases -> _releases.value = releases },
                onFailure = { e ->
                    flash("Could not read the library: ${e.message ?: e::class.simpleName}")
                },
            )
            _loading.value = false
        }
    }

    /** Deletes [release]'s files, then re-reads the library so the list reflects reality. */
    fun delete(release: DownloadedRelease) {
        scope.launch {
            library.delete(release).fold(
                onSuccess = { n -> flash("Deleted $n file(s) — ${release.title}") },
                onFailure = { e -> flash("Delete failed: ${e.message ?: e::class.simpleName}") },
            )
            library.list().onSuccess { _releases.value = it }
        }
    }

    fun deleteAll() {
        scope.launch {
            library.deleteAll().fold(
                onSuccess = { n -> flash("Deleted $n file(s)") },
                onFailure = { e -> flash("Delete failed: ${e.message ?: e::class.simpleName}") },
            )
            library.list().onSuccess { _releases.value = it }
        }
    }

    private fun flash(text: String) {
        _message.value = text
    }
}

/** Centered content cap for wide windows — beyond this the list just widens margins. */
private val MaxContentWidth = 1080.dp

/**
 * Downloaded tab — the always-available control surface for finished downloads: one card
 * per finalized release (title, library location, kind chip, file count, total size),
 * with per-release Delete (behind a confirmation) and a header Delete all. The list is
 * read from the device libraries on every entry, so it survives restarts, reinstalls and
 * an emptied transfer history. Empty state distinguishes "still checking" from "nothing
 * downloaded yet".
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val releases by viewModel.releases.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val message by viewModel.message.collectAsState()
    var pendingDelete by remember { mutableStateOf<DownloadedRelease?>(null) }
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
                SurgeSectionHeader("Downloaded", modifier = Modifier.padding(end = 8.dp))
                Spacer(Modifier.weight(1f))
                SurgeTextButton(text = "Refresh", onClick = viewModel::refresh)
                SurgeTextButton(
                    text = "Delete all",
                    onClick = { showDeleteAll = true },
                    tint = MaterialTheme.colorScheme.error,
                    enabled = releases.isNotEmpty(),
                    modifier = Modifier.padding(start = 16.dp),
                )
            }

            when {
                loading && releases.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                releases.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    SurgePanel(modifier = Modifier.padding(24.dp)) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VideoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                text = "Nothing downloaded yet — finished downloads land here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(releases, key = { "${it.rootId}:${it.title}" }) { release ->
                        ReleaseCard(
                            release = release,
                            onDelete = { pendingDelete = release },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { release ->
        SurgeConfirmDialog(
            text = "Delete \"${release.title}\" (${release.filesCount} file(s), " +
                "${SizeParser.formatBytes(release.totalBytes)}) from your device?",
            onConfirm = {
                pendingDelete = null
                viewModel.delete(release)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showDeleteAll) {
        SurgeConfirmDialog(
            text = "Delete ALL downloaded files from every library? This cannot be undone.",
            onConfirm = {
                showDeleteAll = false
                viewModel.deleteAll()
            },
            onDismiss = { showDeleteAll = false },
        )
    }
}

/** Card for one finalized release: title, kind chip, location · files · size, Delete. */
@Composable
private fun ReleaseCard(
    release: DownloadedRelease,
    onDelete: () -> Unit,
) {
    SurgePanel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = release.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SurgeChip(text = release.kindLabel, color = kindColor(release.kindLabel))
            Text(
                text = "${release.locationLabel} · ${release.filesCount} file(s) · " +
                    SizeParser.formatBytes(release.totalBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Spacer(Modifier.weight(1f))
            SurgeTextButton(
                text = "Delete",
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Same kind→accent mapping as MediaFormatChips: audio=primary, video=tertiary, other=secondary. */
@Composable
private fun kindColor(kindLabel: String) = when (kindLabel) {
    "Music" -> MaterialTheme.colorScheme.primary
    "Movies" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}
