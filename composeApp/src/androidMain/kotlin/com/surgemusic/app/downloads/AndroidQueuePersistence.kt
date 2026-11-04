package com.surgemusic.app.downloads

import android.content.Context
import com.surgemusic.core.downloads.PersistedDownload
import com.surgemusic.core.downloads.QueuePersistence
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * QueuePersistence on android (twin of desktopMain's `DesktopQueuePersistence`): one
 * JSON file at `context.filesDir/queue.json`; staging lives on the app-private external
 * dir (`getExternalFilesDir("staging")` — the same root AppContainer hands the
 * manager as stagingDir).
 *
 * Saves go through a tmp sibling + rename (POSIX rename is atomic) so a crash mid-write
 * can never corrupt the store; a missing or corrupt file loads as empty. Load also
 * prunes dead weight: entries whose staging dir vanished are dropped, and staging dirs
 * under the staging root that no entry references anymore are deleted so they don't
 * accumulate forever.
 */
class AndroidQueuePersistence(context: Context) : QueuePersistence {

    private val queueFile: File = File(context.filesDir, FILE)
    private val stagingRoot: File? = context.getExternalFilesDir(STAGING)

    override suspend fun load(): List<PersistedDownload> = withContext(Dispatchers.IO) {
        val text = runCatching { queueFile.readText() }.getOrNull()
            ?: return@withContext emptyList() // missing (or unreadable) file → clean start
        val entries = runCatching {
            json.decodeFromString(SERIALIZER, text)
        }.getOrDefault(emptyList()) // corrupt JSON → clean start
        val live = entries.filter { File(it.stagingPath).exists() }
        sweepOrphans(live)
        live
    }

    override suspend fun save(items: List<PersistedDownload>) {
        withContext(Dispatchers.IO) {
            runCatching {
                queueFile.parentFile?.mkdirs()
                val tmp = File.createTempFile(PREFIX, SUFFIX, queueFile.parentFile)
                tmp.writeText(json.encodeToString(SERIALIZER, items))
                if (!tmp.renameTo(queueFile)) {
                    queueFile.delete()
                    if (!tmp.renameTo(queueFile)) tmp.delete()
                }
            } // failed save is swallowed — the queue re-persists on the next transition
        }
    }

    /** Deletes staging dirs under the staging root no surviving entry references. */
    private fun sweepOrphans(live: List<PersistedDownload>) {
        val root = stagingRoot ?: return
        runCatching {
            val referenced = live.mapTo(mutableSetOf()) { File(it.stagingPath).absolutePath }
            root.listFiles().orEmpty()
                .filter { it.absolutePath !in referenced }
                .forEach { it.deleteRecursively() }
        } // orphan sweep is best-effort
    }

    private companion object {
        const val FILE = "queue.json"
        const val STAGING = "staging"
        const val PREFIX = "queue"
        const val SUFFIX = ".json.tmp"

        /** ignoreUnknownKeys: older builds' extra fields stay loadable after an upgrade. */
        val json = Json { ignoreUnknownKeys = true }
        val SERIALIZER = ListSerializer(PersistedDownload.serializer())
    }
}
