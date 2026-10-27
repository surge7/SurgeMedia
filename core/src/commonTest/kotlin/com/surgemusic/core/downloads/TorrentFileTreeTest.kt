package com.surgemusic.core.downloads

import com.surgemusic.core.engine.TorrentFileInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TorrentFileTreeTest {

    private fun file(index: Int, path: String, sizeBytes: Long = 1_000L) =
        TorrentFileInfo(index, path, sizeBytes)

    @Test
    fun albumFoldersGroupTheirTracks() {
        val tree = buildTorrentTree(
            listOf(
                file(0, "Album A/01.flac", 10),
                file(1, "Album A/02.flac", 20),
                file(2, "Album B/01.mp3", 30),
            ),
        )
        assertEquals(2, tree.size)
        val a = assertIs<TorrentNode.Folder>(tree[0])
        assertEquals("Album A", a.name)
        assertEquals("Album A", a.path)
        assertEquals(2, a.fileCount)
        assertEquals(30, a.sizeBytes)
        assertEquals(setOf(0, 1), a.fileIndices())
        val b = assertIs<TorrentNode.Folder>(tree[1])
        assertEquals(setOf(2), b.fileIndices())
    }

    @Test
    fun looseRootFilesSitBesideFolderRoots() {
        val tree = buildTorrentTree(
            listOf(
                file(0, "Album A/01.flac"),
                file(1, "cover.jpg", 5),
            ),
        )
        assertEquals(2, tree.size)
        assertIs<TorrentNode.Folder>(tree[0])
        val loose = assertIs<TorrentNode.File>(tree[1])
        assertEquals("cover.jpg", loose.name)
        assertEquals(setOf(1), loose.fileIndices())
    }

    @Test
    fun nestedFoldersKeepTheirPathsAndAggregateSizes() {
        val tree = buildTorrentTree(
            listOf(
                file(0, "Artist/Disc1/01.flac", 10),
                file(1, "Artist/Disc2/01.flac", 20),
                file(2, "Artist/covers/front.jpg", 3),
            ),
        )
        val artist = assertIs<TorrentNode.Folder>(tree.single())
        assertEquals("Artist", artist.path)
        assertEquals(3, artist.fileCount)
        assertEquals(33, artist.sizeBytes)
        // folders before files, each case-insensitive by name → covers, Disc1, Disc2
        val covers = assertIs<TorrentNode.Folder>(artist.children[0])
        assertEquals("Artist/covers", covers.path)
        val disc1 = assertIs<TorrentNode.Folder>(artist.children[1])
        assertEquals("Artist/Disc1", disc1.path)
        assertEquals(1, covers.fileCount)
    }

    @Test
    fun siblingsSortFoldersFirstCaseInsensitive() {
        val tree = buildTorrentTree(
            listOf(
                file(0, "b album/01.flac"),
                file(1, "Album A/01.flac"),
                file(2, "zebra.jpg"),
                file(3, "apple.jpg"),
            ),
        )
        assertEquals(listOf("Album A", "b album", "apple.jpg", "zebra.jpg"), tree.map { it.name })
    }

    @Test
    fun rootOnlyFilesAreFlatTopLevelNodes() {
        val tree = buildTorrentTree(listOf(file(0, "01.flac"), file(1, "02.flac")))
        assertEquals(2, tree.size)
        assertTrue(tree.all { it is TorrentNode.File })
        assertEquals(setOf(0, 1), tree.flatMap { it.fileIndices() }.toSet())
    }
}
