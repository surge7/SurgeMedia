package com.surgemusic.core.downloads

import com.surgemusic.core.engine.TorrentFileInfo

/**
 * A torrent's files folded into a folder tree — the model behind the file chooser's
 * "download this folder" rows (every album lives in its own folder; selecting the
 * folder selects all files under it, no per-file work).
 */
sealed interface TorrentNode {
    val name: String

    /** A directory; [path] is the torrent-internal path without a trailing slash. */
    data class Folder(
        override val name: String,
        val path: String,
        val children: List<TorrentNode>,
        val sizeBytes: Long,
        val fileCount: Int,
    ) : TorrentNode

    /** A leaf file; [TorrentFileInfo.index] is the engine index the wanted set uses. */
    data class File(override val name: String, val file: TorrentFileInfo) : TorrentNode
}

/** Total bytes under [node]: its own size for a file, the recursive sum for a folder. */
fun TorrentNode.sizeUnder(): Long = when (this) {
    is TorrentNode.Folder -> sizeBytes
    is TorrentNode.File -> file.sizeBytes
}

/** Number of files under [node]: 1 for a file, the recursive count for a folder. */
fun TorrentNode.filesUnder(): Int = when (this) {
    is TorrentNode.Folder -> fileCount
    is TorrentNode.File -> 1
}

/** Every engine file index under [node]: itself for a file, all descendants for a folder. */
fun TorrentNode.fileIndices(): Set<Int> = when (this) {
    is TorrentNode.Folder -> children.flatMapTo(mutableSetOf()) { it.fileIndices() }
    is TorrentNode.File -> setOf(file.index)
}

/**
 * Folds [files] into [TorrentNode]s. Siblings are ordered folders first, then files,
 * both case-insensitive by name — the stable order a chooser displays. Loose files at
 * the torrent root sit at the top level alongside folder roots.
 */
fun buildTorrentTree(files: List<TorrentFileInfo>): List<TorrentNode> {
    class DirBuilder(val path: String, val name: String) {
        val dirs = LinkedHashMap<String, DirBuilder>()
        val leafs = mutableListOf<TorrentFileInfo>()
    }

    val root = DirBuilder("", "")
    for (file in files) {
        var dir = root
        val segments = file.path.split('/')
        for (i in 0 until segments.lastIndex) {
            val name = segments[i]
            val path = if (dir.path.isEmpty()) name else "${dir.path}/$name"
            dir = dir.dirs.getOrPut(name) { DirBuilder(path, name) }
        }
        dir.leafs += file
    }

    fun DirBuilder.toFolder(): TorrentNode.Folder {
        val children = dirs.values.map { it.toFolder() }.sortedBy { it.name.lowercase() } +
            leafs
                .map { TorrentNode.File(it.path.substringAfterLast('/'), it) }
                .sortedBy { it.name.lowercase() }
        return TorrentNode.Folder(
            name = name,
            path = path,
            children = children,
            sizeBytes = children.sumOf { it.sizeUnder() },
            fileCount = children.sumOf { it.filesUnder() },
        )
    }

    return root.dirs.values.map { it.toFolder() }.sortedBy { it.name.lowercase() } +
        root.leafs
            .map { TorrentNode.File(it.path.substringAfterLast('/'), it) }
            .sortedBy { it.name.lowercase() }
}
