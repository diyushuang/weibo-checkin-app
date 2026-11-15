package com.wcm.checkin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 账号密码的本地加密存储。
 *
 * - 明文**永不**落盘：账号密码序列化后立刻用 Android Keystore 里的 AES-256-GCM 密钥加密，
 *   SharedPreferences 里只有 `v1:<iv>:<ciphertext>`；
 * - 密钥不可导出，只存在于系统 Keystore；本 App 被卸载 / 数据被清除时一并失效；
 * - 解密失败（换机恢复、Keystore 异常）抛 [CredentialUnreadableException]，调用方据此暂停自动重登，
 *   **绝不降级成明文**；
 * - 日志只记录 uid 与密文长度，不记录账号、密码或密文内容。
 */
object CredentialVault {

    private const val TAG = "CredentialVault"
    private const val PREFS = "wcm_credentials"
    private const val KEY_PREFIX = "cred_"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "wcm_credential_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION = "v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否已保存过凭证（不解密，不抛异常）。 */
    fun has(ctx: Context, uid: String): Boolean =
        SafePrefs.string(prefs(ctx), KEY_PREFIX + uid).isNotEmpty()

    /**
     * 保存（覆盖）账号密码密文。空账号 / 空密码直接拒绝，避免存一份没用的凭证。
     */
    fun save(ctx: Context, uid: String, username: String, password: String) {
        require(username.isNotBlank() && password.isNotEmpty()) { "empty credential" }
        val payload = JSONObject()
            .put("u", username.trim())
            .put("p", password)
            .toString()
        val token = encrypt(payload)
        prefs(ctx).edit { putString(KEY_PREFIX + uid, token) }
        Log.i(TAG, "凭证已加密保存 uid=$uid cipherLength=${token.length}")
    }

    /**
     * 读取并解密凭证。返回 null = 没保存过；解密失败抛 [CredentialUnreadableException]。
     */
    fun load(ctx: Context, uid: String): Pair<String, String>? {
        val token = SafePrefs.string(prefs(ctx), KEY_PREFIX + uid)
        if (token.isEmpty()) return null
        val plain = try {
            decrypt(token)
        } catch (e: Exception) {
            Log.w(TAG, "凭证解密失败 uid=$uid")
            throw CredentialUnreadableException()
        }
        return try {
            val obj = JSONObject(plain)
            val u = obj.optString("u", "")
            val p = obj.optString("p", "")
            if (u.isBlank() || p.isEmpty()) throw CredentialUnreadableException()
            u to p
        } catch (e: CredentialUnreadableException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "凭证内容格式异常 uid=$uid")
            throw CredentialUnreadableException()
        }
    }

    /** 删除凭证密文；不抛异常。 */
    fun clear(ctx: Context, uid: String) {
        prefs(ctx).edit { remove(KEY_PREFIX + uid) }
        Log.i(TAG, "已删除凭证 uid=$uid")
    }

    // ---- Keystore 加解密 ----

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "$VERSION:${encode(iv)}:${encode(body)}"
    }

    private fun decrypt(token: String): String {
        val parts = token.split(':')
        require(parts.size == 3 && parts[0] == VERSION) { "bad token" }
        val iv = decode(parts[1])
        val body = decode(parts[2])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)

    private fun decode(text: String): ByteArray =
        Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE)
}
