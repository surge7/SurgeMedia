package com.surgemusic.core.engine.jvm

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

object TestTorrentFactory {

    fun create(rootDir: Path, fileNames: List<String>, pieceLength: Int = 32768): ByteArray {
        val files = fileNames.map { rootDir.resolve(it) }
        val allBytes = ByteArray(files.sumOf { it.fileSize().toInt() })
        var offset = 0
        for (f in files) {
            val b = f.readBytes()
            System.arraycopy(b, 0, allBytes, offset, b.size)
            offset += b.size
        }
        val md = MessageDigest.getInstance("SHA-1")
        val pieces = ByteArrayOutputStream()
        var i = 0
        while (i < allBytes.size) {
            val len = minOf(pieceLength, allBytes.size - i)
            pieces.write(md.digest(allBytes.copyOfRange(i, i + len)))
            i += len
        }
        val info = mapOf(
            "piece length" to pieceLength.toLong(),
            "pieces" to pieces.toByteArray(),
            "name" to rootDir.fileName.toString(),
            "files" to files.map { mapOf("length" to it.fileSize(), "path" to listOf(it.name)) },
        )
        return bencode(mapOf("info" to info))
    }

    fun bencode(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, value: Any) {
        when (value) {
            is Long, is Int -> out.write("i${value}e".toByteArray())
            is ByteArray -> out.write("${value.size}:".toByteArray()).also { out.write(value) }
            is String -> write(out, value.toByteArray(Charsets.UTF_8))
            is Map<*, *> -> {
                out.write('d'.code)
                value.entries.sortedBy { (k, _) -> k.toString() }.forEach { (k, v) ->
                    write(out, k.toString()); write(out, v!!)
                }
                out.write('e'.code)
            }
            is List<*> -> {
                out.write('l'.code)
                value.forEach { write(out, it!!) }
                out.write('e'.code)
            }
            else -> throw IllegalArgumentException("cannot bencode ${value::class}")
        }
    }

    /** Convenience: list of regular file names directly under dir. */
    fun regularFilesUnder(dir: Path): List<String> =
        dir.listDirectoryEntries().filter { !it.isDirectory() }.map { it.name }.sorted()
}
