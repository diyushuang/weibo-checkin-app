package com.wcm.checkin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 通用字段级加密：把一段字符串封成 `v1:<iv>:<ciphertext>`，再解回来。
 *
 * 与 [CredentialVault] 的关系：**同一个 Keystore、不同的 key alias**。
 * 为什么不合并成一个类：`CredentialVault` 的 alias（`wcm_credential_key_v1`）已经
 * 在用户设备上生成过密钥，动它要么解不开历史密码（等于让所有用户重新输密码），
 * 要么得做密钥迁移——风险远大于收益。这里另起 alias，只负责"cookie / ALC"这类字段，
 * 两边互不影响：cookie 密钥坏了也不会连累密码。
 *
 * 三条约定：
 * 1. **写失败降级为明文**（[sealOrPlain]）：Keystore 在极少数 ROM 上会抛
 *    `KeyStoreException`。此时宁可退化成改造前的行为，也不能让用户"登录成功却存不上"。
 *    降级会打一条 warn 日志，便于排查。
 * 2. **读非密文原样返回**（[open]）：历史数据里的 cookie 是明文，
 *    升级后必须照常可用——否则整张账号表一次性失效。
 * 3. 日志只记长度，不记内容。
 */
object SecretBox {

    private const val TAG = "SecretBox"
    private const val VERSION = "v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "wcm_secret_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** 是否是本类封出来的密文（用于判断"需不需要迁移"）。 */
    fun isSealed(value: String): Boolean = value.startsWith("$VERSION:")

    /**
     * 解密；**不是密文就原样返回**（兼容改造前的明文数据）。
     * 真密文却解不开时抛异常——让调用方决定是丢弃该字段还是整表不写。
     */
    fun open(token: String): String {
        if (token.isEmpty() || !isSealed(token)) return token
        val parts = token.split(':')
        require(parts.size == 3 && parts[0] == VERSION) { "bad sealed token" }
        val iv = decode(parts[1])
        val body = decode(parts[2])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    /**
     * 密钥缓存。
     *
     * 每次 `seal`/`open` 都重新 `KeyStore.load + getEntry` 是很贵的（Keystore 是系统服务，
     * 单次 ms 级）。而 [AccountStore.save] 会在一张账号表上逐条加解密——5 个账号就是
     * 10 次 Keystore 访问，且签到途中会反复写盘。缓存在进程内是安全的：
     * SecretKey 本身可长期持有，卸载 / 清数据时随 Keystore 一起失效。
     */
    @Volatile private var cachedKey: SecretKey? = null

    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val key = loadOrCreateKey()
            cachedKey = key
            return key
        }
    }

    private fun loadOrCreateKey(): SecretKey {
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

    /** 加密；失败抛异常（调用方一般用 [sealOrPlain]）。 */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "$VERSION:${encode(iv)}:${encode(body)}"
    }

    /**
     * 加密，失败降级明文（可用性优先）。
     *
     * 降级路径是**有意的**：这条链路上唯一不可接受的结果是"用户登录成功但账号没存下来"。
     */
    fun sealOrPlain(plain: String): String =
        try {
            seal(plain)
        } catch (e: Exception) {
            Log.w(TAG, "加密失败，降级明文（长度 ${plain.length}）：${e.javaClass.simpleName}")
            plain
        }

    /**
     * 安全解密：解不开返回 null，不抛异常。
     *
     * 用在"整表读取"这种**不能因为单条坏掉就丢全部**的场景。
     */
    fun openOrNull(token: String): String? =
        try {
            open(token)
        } catch (e: Exception) {
            Log.w(TAG, "解密失败（长度 ${token.length}）")
            null
        }

    /** 不解密，只判断一条记录是否还是明文（迁移用）。 */
    fun needsSeal(value: String): Boolean = value.isNotEmpty() && !isSealed(value)

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)

    private fun decode(text: String): ByteArray =
        Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE)
}

/**
 * `ALC`（SSO 长效票据）的加密存储。
 *
 * 与账号密码同目录（`wcm_credentials`）、不同前缀，便于"删账号时一起清"和
 * "只清密码不清 ALC"两种粒度。值本身用 [SecretBox] 封，key 不存在时自动生成。
 */
object AlcVault {

    private const val PREFS = "wcm_credentials"
    private const val KEY_PREFIX = "alc_"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun has(ctx: Context, uid: String): Boolean =
        SafePrefs.string(prefs(ctx), KEY_PREFIX + uid).isNotEmpty()

    fun save(ctx: Context, uid: String, alc: String) {
        val token = SecretBox.sealOrPlain(alc.trim())
        prefs(ctx).edit { putString(KEY_PREFIX + uid, token) }
    }

    /** 读取并解密；没存返回 null，解不开抛 [CredentialUnreadableException]（与密码同语义）。 */
    fun load(ctx: Context, uid: String): String? {
        val token = SafePrefs.string(prefs(ctx), KEY_PREFIX + uid)
        if (token.isEmpty()) return null
        return try {
            SecretBox.open(token).ifEmpty { null }
        } catch (e: Exception) {
            throw CredentialUnreadableException()
        }
    }

    fun clear(ctx: Context, uid: String) {
        prefs(ctx).edit { remove(KEY_PREFIX + uid) }
    }
}
