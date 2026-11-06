package com.dsh.phonebridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the long-lived device token encrypted with an Android Keystore AES-GCM key.
 * The old plaintext preference is migrated once and then removed.
 */
object TokenStore {
    private const val PREFS = "dsh_phone_bridge"
    private const val LEGACY_PLAIN = "bridge_token"
    private const val ENCRYPTED = "bridge_token_enc"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "dsh_phone_bridge_token_v1"
    private const val GCM_TAG_BITS = 128

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(ENCRYPTED, null)
        if (!encrypted.isNullOrEmpty()) {
            val decrypted = decrypt(encrypted)
            if (!decrypted.isNullOrEmpty()) return decrypted
            clear(context)
            return ""
        }
        val legacy = prefs.getString(LEGACY_PLAIN, "").orEmpty()
        if (legacy.isNotEmpty()) {
            save(context, legacy)
        }
        return legacy
    }

    fun save(context: Context, token: String) {
        if (token.isEmpty()) {
            clear(context)
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(ENCRYPTED, payload).remove(LEGACY_PLAIN).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(ENCRYPTED)
            .remove(LEGACY_PLAIN)
            .apply()
    }

    private fun decrypt(payload: String): String? {
        return try {
            val parts = payload.split(':', limit = 2)
            if (parts.size != 2) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
