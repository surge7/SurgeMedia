package com.surgemusic.app.session

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM helpers over an explicit [SecretKey]. Pure javax.crypto so they are unit-testable
 * on the JVM; the AndroidKeyStore key provisioning lives in [KeystoreSessionStore].
 *
 * Encrypt NEVER passes a caller-provided IV: AndroidKeyStore keys forbid it
 * ("Caller-provided IV not permitted") — the cipher generates the IV (12 bytes),
 * works identically for software and Keystore keys, and it is prepended to the output.
 */
object SessionCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Returns `iv || ciphertext` (the GCM tag is appended to the ciphertext). */
    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected GCM IV length ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    /** @throws GeneralSecurityException when the blob was tampered with or is corrupt. */
    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "ciphertext too short" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
