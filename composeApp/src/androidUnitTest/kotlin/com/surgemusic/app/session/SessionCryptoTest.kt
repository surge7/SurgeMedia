package com.surgemusic.app.session

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Pure-crypto round-trip tests. Uses a plain `KeyGenerator("AES")` key — NOT AndroidKeyStore —
 * so the tests run as JVM unit tests. The AndroidKeyStore key wiring in
 * [KeystoreSessionStore] is verified on the emulator.
 */
class SessionCryptoTest {

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun encryptThenDecryptRoundTrips() {
        val key = newKey()
        val plain = "bb_session=abc123; cf_clearance=xyz789; bb_guid=q; bb_ssl=1\nMozilla/5.0 (Test UA)".encodeToByteArray()

        val blob = SessionCrypto.encrypt(key, plain)

        assertContentEquals(plain, SessionCrypto.decrypt(key, blob))
    }

    @Test
    fun tamperedCiphertextThrows() {
        val key = newKey()
        val plain = "bb_session=abc123\nMozilla/5.0".encodeToByteArray()
        val tampered = SessionCrypto.encrypt(key, plain).also {
            it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() // flip a GCM tag bit
        }

        assertFailsWith<GeneralSecurityException> { SessionCrypto.decrypt(key, tampered) }
    }
}
