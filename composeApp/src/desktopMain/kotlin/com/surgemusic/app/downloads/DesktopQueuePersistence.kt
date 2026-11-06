package com.surgemusic.app.downloads

import com.surgemusic.core.downloads.PersistedDownload
import com.surgemusic.core.downloads.QueuePersistence
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * QueuePersistence on desktop (twin of androidMain's `AndroidQueuePersistence`): one
 * JSON file at `~/.surgemusic/queue.json` (same app dir as settings + staging).
 *
 * Saves are atomic — write a tmp sibling, then `ATOMIC_MOVE` over the target (plain
 * move fallback where atomic rename is unsupported) — so a crash mid-write can never
 * corrupt the store; a missing or corrupt file loads as empty. Load also prunes dead
 * weight: non-terminal entries whose staging dir vanished are dropped (their torrent
 * cannot resume), while terminal entries ([PersistedDownload.isTerminal]) are exempt —
 * their staging is legitimately deleted after finalize, and they carry the download
 * history. Staging dirs under the staging root that no non-terminal entry references
 * anymore (finished/removed downloads whose save never ran) are deleted so they don't
 * accumulate forever.
 */
class DesktopQueuePersistence(
    private val homeDir: Path = Path.of(System.getProperty("user.home")),
) : QueuePersistence {

    private val queueFile: Path = homeDir.resolve(DIR).resolve(FILE)
    private val stagingRoot: Path = homeDir.resolve(DIR).resolve(STAGING)

    override suspend fun load(): List<PersistedDownload> = withContext(Dispatchers.IO) {
        val text = runCatching { Files.readString(queueFile) }.getOrNull()
            ?: return@withContext emptyList() // missing (or unreadable) file → clean start
        val entries = runCatching {
            json.decodeFromString(SERIALIZER, text)
        }.getOrDefault(emptyList()) // corrupt JSON → clean start
        val live = entries.filter { it.isTerminal || Files.exists(Path.of(it.stagingPath)) }
        sweepOrphans(live)
        live
    }

    override suspend fun save(items: List<PersistedDownload>) {
        withContext(Dispatchers.IO) {
            runCatching {
                Files.createDirectories(queueFile.parent)
                val tmp = Files.createTempFile(queueFile.parent, PREFIX, SUFFIX)
                Files.writeString(tmp, json.encodeToString(SERIALIZER, items))
                try {
                    Files.move(tmp, queueFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp, queueFile, StandardCopyOption.REPLACE_EXISTING)
                }
            } // failed save is swallowed — the queue re-persists on the next transition
        }
    }

    /**
     * Deletes staging dirs under the staging root no surviving NON-terminal entry
     * references (terminal entries' staging is legitimately gone after finalize).
     */
    private fun sweepOrphans(live: List<PersistedDownload>) {
        runCatching {
            if (!Files.isDirectory(stagingRoot)) return@runCatching
            val referenced = live.filterNot { it.isTerminal }
                .mapTo(mutableSetOf()) { Path.of(it.stagingPath).toAbsolutePath().toString() }
            Files.list(stagingRoot).use { children ->
                children.filter { it.toAbsolutePath().toString() !in referenced }
                    .forEach { deleteTree(it) }
            }
        } // orphan sweep is best-effort
    }

    /** walk order reversed so children are removed before their directories. */
    private fun deleteTree(root: Path) {
        runCatching {
            if (!Files.exists(root)) return@runCatching
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private companion object {
        const val DIR = ".surgemusic"
        const val FILE = "queue.json"
        const val STAGING = "staging"
        const val PREFIX = "queue"
        const val SUFFIX = ".json.tmp"

        /** ignoreUnknownKeys: older builds' extra fields stay loadable after an upgrade. */
        val json = Json { ignoreUnknownKeys = true }
        val SERIALIZER = ListSerializer(PersistedDownload.serializer())
    }
}
