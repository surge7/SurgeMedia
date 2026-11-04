package com.surgemusic.app.session

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FileSessionStore] × [SessionVault] integration against a throwaway home dir:
 *
 * - vault round-trip: a vault-backed save deletes the plaintext `session.properties`, and
 *   a fresh store over the same vault reloads the identical [Session];
 * - one-time migration: a pre-existing plaintext file is loaded, copied into the vault,
 *   and the plaintext file is then deleted;
 * - failure fallback: when the vault save fails, the 0600 plaintext file stays and the
 *   next store still loads from it.
 *
 * The vault flush/migration runs on the store's daemon persist thread (same discipline as
 * the plain-file path), so the disk-side assertions poll (15s — slow CI disks), matching
 * [com.surgemusic.app.settings.DesktopSettingsStoreTest]'s polling style.
 */
class FileSessionStoreVaultTest {

    /** Recording fake: configurable save failure, remembers the last payload and clears. */
    private class FakeVault(private val fail: Boolean = false) : SessionVault {
        var payload: String? = null
        var clearCount = 0

        override fun save(payload: String): Boolean {
            if (fail) return false
            this.payload = payload
            return true
        }

        override fun load(): String? = payload

        override fun clear() {
            payload = null
            clearCount++
        }
    }

    @Test
    fun vaultRoundTripDeletesPlaintextAndClearsVault() {
        val home = createTempDirectory("surge-vault-test")
        try {
            val props = home.resolve(".surgemusic/session.properties")
            val vault = FakeVault()
            val store = FileSessionStore(home, vault)

            store.save("bb_session=abc", "UA/1")
            val savedAt = store.session.value!!.savedAtEpochMs
            pollUntil { vault.payload != null && Files.notExists(props) }
            assertTrue(Files.notExists(props), "plaintext file must be deleted after a vault save")

            // Fresh store over the same home: the session comes back from the vault.
            val reloaded = FileSessionStore(home, vault)
            assertEquals(Session("bb_session=abc", "UA/1", savedAt), reloaded.session.value)

            store.clear()
            pollUntil { vault.clearCount >= 1 && vault.payload == null }
            assertNull(store.session.value)
            assertTrue(Files.notExists(props), "clear must leave no plaintext file either")
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun migratesExistingPlaintextFileToVault() {
        val home = createTempDirectory("surge-vault-test")
        try {
            val props = home.resolve(".surgemusic/session.properties")
            Files.createDirectories(props.parent)
            Files.writeString(props, "cookie_header=bb_session=old\nuser_agent=UA/old\nsaved_at_epoch_ms=1234\n")

            val vault = FakeVault()
            val store = FileSessionStore(home, vault)
            // Loaded from the plaintext fallback...
            assertEquals(Session("bb_session=old", "UA/old", 1234), store.session.value)
            // ...and migrated in the background: vault payload lands, plaintext goes away.
            pollUntil { vault.payload != null && Files.notExists(props) }

            val reloaded = FileSessionStore(home, vault)
            assertEquals(Session("bb_session=old", "UA/old", 1234), reloaded.session.value)
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun vaultSaveFailureFallsBackToPlaintextFile() {
        val home = createTempDirectory("surge-vault-test")
        try {
            val props = home.resolve(".surgemusic/session.properties")
            val vault = FakeVault(fail = true)
            val store = FileSessionStore(home, vault)

            store.save("bb_session=abc", "UA/1")
            pollUntil { Files.exists(props) }
            assertNull(vault.payload, "failed vault must hold no payload")
            assertTrue(Files.exists(props), "failed vault save must keep the plaintext fallback")

            // Fresh store: vault empty → file path; its (failing) migration attempt must
            // still not delete the fallback file.
            val reloaded = FileSessionStore(home, vault)
            assertEquals("bb_session=abc", reloaded.session.value?.cookieHeader)
            Thread.sleep(200) // let any migration attempt hit the failing vault first
            assertTrue(Files.exists(props), "failing migration must keep the plaintext fallback")
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    /** Polls [condition] until true or 15s elapse (async persist thread; slow CI disks). */
    private fun pollUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within 15s" }
            Thread.sleep(25)
        }
    }
}
