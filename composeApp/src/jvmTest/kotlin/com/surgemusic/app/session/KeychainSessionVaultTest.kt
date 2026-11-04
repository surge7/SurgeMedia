package com.surgemusic.app.session

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real macOS Keychain round trip through the `security` CLI ([KeychainSessionVault]),
 * gated in the project's assumeTrue style (cf. BrowserLoginCaptureTest): skipped on
 * non-macOS hosts or when `security` is missing. Uses a dedicated service name
 * (`SurgeMediaSessionVaultTest`) so the test never touches the app's
 * `SurgeMedia`/`session` keychain entry, and clears it before/after (finally) so a failed
 * run leaves no residue either.
 */
class KeychainSessionVaultTest {

    @Test
    fun keychainSaveLoadClearRoundTrip() {
        assumeTrue("macOS only", System.getProperty("os.name").startsWith("Mac"))
        assumeTrue("`security` CLI not found — skipping keychain test", securityOnPath())

        val vault = KeychainSessionVault(service = "SurgeMediaSessionVaultTest")
        try {
            vault.clear() // best-effort: start clean even if a previous run left an entry
            assertNull(vault.load())

            assertTrue(vault.save("bb_session=keychain-roundtrip"))
            assertEquals("bb_session=keychain-roundtrip", vault.load())

            vault.clear()
            assertNull(vault.load())
        } finally {
            vault.clear()
        }
    }

    private fun securityOnPath(): Boolean =
        System.getenv("PATH")?.split(File.pathSeparator)
            ?.any { it.isNotEmpty() && File(it, "security").canExecute() } == true
}
