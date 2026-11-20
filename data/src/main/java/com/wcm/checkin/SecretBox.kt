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
     *
     * 但它不再是**静默**的：传入 [ctx] 时会把降级事实写进 [SecretHealth]，
     * 首页据此提示「本机加密不可用，凭据以明文保存」。cookie（SUB）是微博的
     * 全权凭据，能发帖、能删帖、能看私信——这种状态不该只有开发者从 logcat 才知道。
     */
    fun sealOrPlain(ctx: Context?, plain: String): String =
        try {
            seal(plain)
        } catch (e: Exception) {
            Log.w(TAG, "加密失败，降级明文（长度 ${plain.length}）：${e.javaClass.simpleName}")
            if (ctx != null) SecretHealth.markDegraded(ctx, e.javaClass.simpleName)
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
 * 「本机加密不可用」的持久化标记。
 *
 * 为什么需要它：`SecretBox.sealOrPlain` 在 Keystore 出问题的 ROM 上会把 cookie
 * 以**明文**写进 `shared_prefs`，而这件事此前只在 logcat 留一条 warn——用户看不到，
 * 于是「凭据明文落盘」成了静默状态。这里把事实持久化，首页读它并显式提示。
 *
 * 三态语义：
 * - 标记存在 = 至少有一次降级发生了，磁盘上可能还有明文 cookie；
 * - 标记被清除 = 后续全量重写（[AccountStore.migrateToSealed]）已成功把明文加密；
 * - 标记不存在 = 从未降级。
 *
 * 用独立 prefs 文件，避免与账号表互相影响（账号表被误清也不会丢这条健康状态）。
 */
object SecretHealth {

    private const val TAG = "SecretHealth"
    private const val PREFS = "wcm_security"
    private const val KEY_DEGRADED = "sealed_degraded"
    private const val KEY_AT = "sealed_degraded_at"
    private const val KEY_REASON = "sealed_degraded_reason"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 记录一次降级。**不能抛**：它本身在异常路径上。 */
    fun markDegraded(ctx: Context, reason: String) {
        try {
            prefs(ctx).edit {
                putBoolean(KEY_DEGRADED, true)
                putLong(KEY_AT, System.currentTimeMillis())
                putString(KEY_REASON, reason.take(60))
            }
        } catch (e: Exception) {
            Log.w(TAG, "写降级标记失败：${e.javaClass.simpleName}")
        }
    }

    fun isDegraded(ctx: Context): Boolean =
        runCatching { SafePrefs.bool(prefs(ctx), KEY_DEGRADED, false) }.getOrDefault(false)

    /** 降级原因（异常类名），仅用于诊断展示。 */
    fun degradedReason(ctx: Context): String =
        runCatching { SafePrefs.string(prefs(ctx), KEY_REASON) }.getOrDefault("")

    /** 明文已全部加密回密文后调用。 */
    fun clear(ctx: Context) {
        runCatching {
            prefs(ctx).edit {
                remove(KEY_DEGRADED)
                remove(KEY_AT)
                remove(KEY_REASON)
            }
        }
    }
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
        val token = SecretBox.sealOrPlain(ctx, alc.trim())
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
