package com.surgemusic.app.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surgemusic.app.ui.components.SurgeTextButton
import com.surgemusic.app.ui.strings.LocalStrings
import com.surgemusic.core.downloads.TorrentNode
import com.surgemusic.core.downloads.buildTorrentTree
import com.surgemusic.core.downloads.fileIndices
import com.surgemusic.core.engine.TorrentFileInfo
import com.surgemusic.core.model.SizeParser

/**
 * One flattened row of the chooser's folder tree. Depth drives the indentation; folders
 * render a tri-state checkbox and an expand affordance, files a plain checkbox.
 */
private sealed interface TreeRow {
    val depth: Int

    data class FolderRow(override val depth: Int, val node: TorrentNode.Folder) : TreeRow
    data class LeafRow(override val depth: Int, val node: TorrentNode.File) : TreeRow
}

/**
 * Torrent file chooser, two modes:
 *
 * - Initial (Details screen's Download button): every file starts checked.
 * - Add ([lockedIndices] non-empty, Downloads screen's "Add files"): already-downloaded
 *   files are checked and disabled, and only the newly checked indices reach [onConfirm].
 *
 * Files are shown as a folder tree — albums usually live in one folder each, so checking
 * the folder row selects (or adds) the whole album without per-file work. Folder rows
 * cascade into their children with a tri-state checkbox; the first level starts expanded.
 * Confirm requires a non-empty *new* selection.
 */
@Composable
fun TorrentFilesDialog(
    files: List<TorrentFileInfo>,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
    title: String = LocalStrings.current.chooseFilesTitle,
    confirmLabel: String = LocalStrings.current.download,
    lockedIndices: Set<Int> = emptySet(),
) {
    val strings = LocalStrings.current
    val selected = remember(files, lockedIndices) {
        mutableStateMapOf<Int, Boolean>().apply {
            files.forEach { put(it.index, it.index in lockedIndices) }
        }
    }
    val tree = remember(files) { buildTorrentTree(files) }
    var expandedFolders by remember(files) {
        mutableStateOf(tree.mapNotNull { (it as? TorrentNode.Folder)?.path }.toSet())
    }
    val freeFiles = remember(files, lockedIndices) { files.filter { it.index !in lockedIndices } }
    val newSelected = freeFiles.filter { selected[it.index] == true }
    val newSelectedBytes = newSelected.sumOf { it.sizeBytes }
    val freeTotalBytes = freeFiles.sumOf { it.sizeBytes }

    // Folder toggle: flip every selectable (non-locked) file under it; locked files keep
    // their always-on state and are excluded from the count so "checked" can never mean
    // "will be re-downloaded".
    fun folderIndices(folder: TorrentNode.Folder): List<Int> =
        folder.fileIndices().filterNot { it in lockedIndices }

    val rows = buildList {
        fun walk(nodes: List<TorrentNode>, depth: Int) {
            for (node in nodes) {
                when (node) {
                    is TorrentNode.Folder -> {
                        add(TreeRow.FolderRow(depth, node))
                        if (node.path in expandedFolders) walk(node.children, depth + 1)
                    }

                    is TorrentNode.File -> add(TreeRow.LeafRow(depth, node))
                }
            }
        }
        walk(tree, 0)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    text = strings.filesSummary(
                        selected = newSelected.size,
                        total = freeFiles.size,
                        selectedSize = SizeParser.formatBytes(newSelectedBytes),
                        totalSize = SizeParser.formatBytes(freeTotalBytes),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    SurgeTextButton(
                        text = strings.selectAll,
                        onClick = { freeFiles.forEach { selected[it.index] = true } },
                    )
                    SurgeTextButton(
                        text = strings.selectNone,
                        onClick = { freeFiles.forEach { selected[it.index] = false } },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier.padding(top = 8.dp).heightIn(max = 340.dp),
                ) {
                    items(rows, key = { row ->
                        when (row) {
                            is TreeRow.FolderRow -> "f:${row.node.path}"
                            is TreeRow.LeafRow -> "i:${row.node.file.index}"
                        }
                    }) { row ->
                        when (row) {
                            is TreeRow.FolderRow -> {
                                val indices = folderIndices(row.node)
                                val chosen = indices.count { selected[it] == true }
                                val state = when {
                                    indices.isEmpty() -> ToggleableState.On
                                    chosen == indices.size -> ToggleableState.On
                                    chosen == 0 -> ToggleableState.Off
                                    else -> ToggleableState.Indeterminate
                                }
                                FolderRowView(
                                    row = row,
                                    state = state,
                                    expanded = row.node.path in expandedFolders,
                                    onToggleExpand = {
                                        expandedFolders = if (row.node.path in expandedFolders) {
                                            expandedFolders - row.node.path
                                        } else {
                                            expandedFolders + row.node.path
                                        }
                                    },
                                    onToggleSelect = {
                                        if (indices.isNotEmpty()) {
                                            val allOn = chosen == indices.size
                                            indices.forEach { selected[it] = !allOn }
                                        }
                                    },
                                )
                            }

                            is TreeRow.LeafRow -> {
                                val locked = row.node.file.index in lockedIndices
                                LeafRowView(
                                    row = row,
                                    checked = selected[row.node.file.index] == true,
                                    locked = locked,
                                    downloadedLabel = strings.downloaded,
                                    onToggle = { selected[row.node.file.index] = it },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            SurgeTextButton(
                text = confirmLabel,
                onClick = { onConfirm(newSelected.map { it.index }.toSet()) },
                enabled = newSelected.isNotEmpty(),
            )
        },
        dismissButton = {
            SurgeTextButton(text = strings.cancel, onClick = onDismiss)
        },
    )
}

@Composable
private fun FolderRowView(
    row: TreeRow.FolderRow,
    state: ToggleableState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp * row.depth)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onToggleExpand() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = state,
            onClick = onToggleSelect,
            colors = checkboxColors(),
        )
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 2.dp)
                .rotate(if (expanded) 0f else -90f)
                .size(20.dp),
        )
        Text(
            text = row.node.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 4.dp)
                .weight(1f),
        )
        Text(
            text = SizeParser.formatBytes(row.node.sizeBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun LeafRowView(
    row: TreeRow.LeafRow,
    checked: Boolean,
    locked: Boolean,
    downloadedLabel: String,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp * (row.depth + 1))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (!locked) onToggle(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = !locked,
            colors = checkboxColors(),
        )
        Text(
            text = row.node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (locked) downloadedLabel else SizeParser.formatBytes(row.node.file.sizeBytes),
            style = MaterialTheme.typography.labelMedium,
            color = if (locked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun checkboxColors() = CheckboxDefaults.colors(
    checkedColor = MaterialTheme.colorScheme.primary,
    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
    uncheckedColor = MaterialTheme.colorScheme.outline,
)
