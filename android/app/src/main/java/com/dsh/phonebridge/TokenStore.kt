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
 * Stores the long-lived device token encrypted with an Android Keystore AES-GCM key,
 * plus a plaintext copy as a fallback.
 *
 * 为什么要留明文副本：Keystore 里的密钥会「在应用不知情的时候」失效 —— 换签名重装、
 * 系统升级、部分 ROM 清理安全存储都会让 entry 消失，之后 `secretKey()` 会**新建**一把
 * 密钥，于是原本的密文永远解不开。老实现遇到这种情况会直接 `clear()`，用户看到的就是
 * 「没有已保存的登录凭证，请重新配对」—— 凭据明明在，却被我们自己删了。
 *
 * 现在：优先解密文；解不开就退回明文；两者都没有才算是真的没有凭据。
 * 明文存在应用私有 prefs 里（别的应用读不到），安全性由应用沙箱兜底。
 */
object TokenStore {
    private const val PREFS = "dsh_phone_bridge"
    private const val LEGACY_PLAIN = "bridge_token"
    private const val ENCRYPTED = "bridge_token_enc"
    private const val FALLBACK_PLAIN = "bridge_token_plain"
    private const val DEVICE_KEY = "bridge_device_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "dsh_phone_bridge_token_v1"
    private const val GCM_TAG_BITS = 128

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(ENCRYPTED, null)
        if (!encrypted.isNullOrEmpty()) {
            val decrypted = decrypt(encrypted)
            if (!decrypted.isNullOrEmpty()) return decrypted
            // 解不开 ≠ 没凭据：密钥可能被换掉了。退回明文，并顺手用新密钥重存一次。
            val fallback = prefs.getString(FALLBACK_PLAIN, "").orEmpty()
            if (fallback.isNotEmpty()) {
                save(context, fallback)
                return fallback
            }
            // 密文和明文都没有 —— 才是真的没有。
            return ""
        }
        val legacy = prefs.getString(LEGACY_PLAIN, "").orEmpty().ifEmpty {
            prefs.getString(FALLBACK_PLAIN, "").orEmpty()
        }
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
        // 先写明文兜底：加密这一步可能因为 Keystore 出问题而抛异常，
        // 那时候至少凭据还在（宁可保凭据，也不要用户重配对）。
        val editor = prefs.edit()
            .putString(FALLBACK_PLAIN, token)
            .remove(LEGACY_PLAIN)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            editor.putString(ENCRYPTED, payload)
        } catch (_: Throwable) {
            // 加密失败就只留明文；下次 get() 会尝试重新加密。
        }
        // commit() 而不是 apply()：凭据写盘要同步完成，进程被系统杀掉时也不能丢。
        editor.commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(ENCRYPTED)
            .remove(LEGACY_PLAIN)
            .remove(FALLBACK_PLAIN)
            .remove(DEVICE_KEY)
            .commit()
    }

    /**
     * 手机签发给电脑的那一半凭据（32 字节，配对时生成）。
     *
     * 和 token 是两份独立凭据：token 证明「这台手机被这台电脑接受」，
     * deviceKey 证明「这台电脑拿到的确实是这台手机签的那一份」。两份都在才连得上。
     */
    fun getDeviceKey(context: Context): ByteArray? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(DEVICE_KEY, "").orEmpty()
        if (raw.isEmpty()) return null
        return try {
            Base64.decode(raw, Base64.NO_WRAP).takeIf { it.size == 32 }
        } catch (_: Throwable) {
            null
        }
    }

    fun saveDeviceKey(context: Context, key: ByteArray) {
        if (key.size != 32) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(DEVICE_KEY, Base64.encodeToString(key, Base64.NO_WRAP))
            .commit()
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
