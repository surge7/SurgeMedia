package com.surgemusic.app.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SessionRepository] backed by a SharedPreferences blob (`session_v1`) encrypted with an
 * AES-256-GCM key that lives in AndroidKeyStore under the alias `surgemusic_session`.
 * The key never leaves the keystore; only the ciphertext is persisted. The plaintext
 * layout is `"cookieHeader\nuserAgent"`. Decrypts on init; a corrupt/undecryptable blob
 * (e.g. no keystore restore after reinstall) is treated as logged out.
 */
class KeystoreSessionStore(context: Context) : SessionRepository {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val key: SecretKey = getOrCreateKey()
    private val _session = MutableStateFlow(readSession())
    override val session: StateFlow<Session?> = _session.asStateFlow()

    override fun save(cookieHeader: String, userAgent: String) {
        val savedAt = nowEpochMs()
        val blob = SessionCrypto.encrypt(key, "$cookieHeader\n$userAgent".encodeToByteArray())
        prefs.edit()
            .putString(PREF_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putLong(PREF_SAVED_AT, savedAt)
            .apply()
        _session.value = Session(cookieHeader, userAgent, savedAt)
    }

    override fun clear() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private fun readSession(): Session? {
        val blob64 = prefs.getString(PREF_BLOB, null) ?: return null
        val savedAt = prefs.getLong(PREF_SAVED_AT, 0L)
        return runCatching {
            val plain = SessionCrypto.decrypt(key, Base64.decode(blob64, Base64.NO_WRAP)).decodeToString()
            val separator = plain.indexOf('\n')
            require(separator >= 0) { "malformed session payload" }
            Session(
                cookieHeader = plain.take(separator),
                userAgent = plain.substring(separator + 1),
                savedAtEpochMs = savedAt,
            )
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "surgemusic_session"
        const val PREF_BLOB = "session_v1"
        const val PREF_SAVED_AT = "session_v1_saved_at"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "surgemusic_session"
        const val KEY_SIZE_BITS = 256
    }
}
