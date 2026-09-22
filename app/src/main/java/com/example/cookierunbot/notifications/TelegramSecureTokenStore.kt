package com.example.cookierunbot.notifications

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM secret storage whose non-exportable key is owned by Android Keystore. */
internal class TelegramSecureTokenStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun loadToken(): String? {
        val encrypted = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun saveToken(token: String): Boolean = runCatching {
        if (token.isBlank()) {
            preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).commit()
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            preferences.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
        }
    }.getOrDefault(false)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFERENCES = "telegram_secure_secrets"
        private const val KEY_CIPHERTEXT = "bot_token_ciphertext"
        private const val KEY_IV = "bot_token_iv"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "robotrun.telegram.bot_token.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal fun migrateTelegramToken(
    secureToken: String?,
    plainToken: String?,
    writeSecure: (String) -> Boolean,
    removePlain: () -> Unit,
): String {
    if (!secureToken.isNullOrBlank()) {
        if (!plainToken.isNullOrBlank()) removePlain()
        return secureToken
    }
    if (!plainToken.isNullOrBlank() && writeSecure(plainToken)) {
        removePlain()
        return plainToken
    }
    return ""
}
