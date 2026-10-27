package com.surgemusic.core.downloads

import com.surgemusic.core.engine.TorrentFileInfo

/**
 * A fetched .torrent plus its parsed file list — the input of the file-chooser step
 * between "user pressed Download" and "enqueue". [files] is empty when the torrent
 * could not be parsed; callers then skip the chooser and download everything.
 *
 * (Not compared in practice; the generated `equals` treats the byte array by reference.)
 */
class PreparedTorrent(
    val torrentBytes: ByteArray,
    val files: List<TorrentFileInfo>,
)
