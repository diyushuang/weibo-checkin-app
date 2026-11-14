package com.wcm.checkin

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.math.BigInteger
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/**
 * 微博账号密码登录（SSO），桌面端 `app/relogin.py` 的 Kotlin 版本。
 *
 * 流程：
 * 1. `sso/prelogin.php` 拿 `servertime / nonce / pubkey / rsakv`；
 * 2. 用新浪公钥做 RSA/PKCS#1 v1.5 加密 `servertime \t nonce \n password`；
 * 3. POST `sso/login.php`，按 `retcode` 分类失败（密码错 / 验证码 / 风控 / 网络）；
 * 4. 跟随跨域回跳，收集 Set-Cookie；
 * 5. 用新 cookie 探活 `m.weibo.cn/api/config`，确认真实登录并回传真实登录时间、
 *    服务端声明到期与 uid。
 *
 * 安全约束：账号密码只在内存里参与 RSA 加密；日志与错误信息只带固定文案 + retcode，
 * 绝不回显密码、表单或响应原文。
 */
object WeiboPasswordLogin {

    private const val PRELOGIN_URL = "https://login.sina.com.cn/sso/prelogin.php"
    private const val LOGIN_URL = "https://login.sina.com.cn/sso/login.php"
    private const val LOGIN_CLIENT = "ssologin.js(v1.4.19)"
    private const val LOGIN_REDIRECT =
        "https://weibo.com/ajaxlogin.php?framelogin=1&callback=parent.sinaSSOController.feedBackUrlCallBack"
    private const val MAX_CALLBACKS = 6

    private const val UA = WeiboHttp.WEB_UA

    /** 需要人工处理（验证码 / 二次验证 / 扫码）。 */
    private val NEEDS_MANUAL_CODES = setOf(
        "107",
        "2070", "2071", "2072", "2073", "2074", "2075",
    )

    /** 账号或密码错误（重试无意义）。 */
    private val BAD_CREDENTIAL_CODES = buildSet {
        addAll(listOf("101", "102", "103", "104", "105", "106"))
        for (i in 108..112) add(i.toString())
    }

    /** 微博风控 / 频率限制。 */
    private val RISK_CODES = setOf("2090", "50114001", "50114002", "50114003", "50114004")

    data class Outcome(
        val ok: Boolean,
        val code: String = "",
        val message: String = "",
        val retcode: String = "",
        val cookie: String = "",
        val loginAt: Long = 0L,
        val serverExpiresAt: Long = 0L,
        val uid: String = "",
    )

    private class MemoryJar : CookieJar {
        private val byName = LinkedHashMap<String, Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                val old = byName[c.name]
                val newIsCn = c.domain.contains("weibo.cn")
                val oldIsCn = old?.domain?.contains("weibo.cn") == true
                // 同名 cookie 优先保留 .weibo.cn 域（m 站探活认它），与桌面端口径一致
                if (old == null || newIsCn || !oldIsCn) byName[c.name] = c
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            byName.values.filter { it.matches(url) }

        /** 合并成 Cookie 头 + 各 cookie 的到期时间。 */
        fun merged(): Pair<String, Map<String, Long>> {
            val expiries = LinkedHashMap<String, Long>()
            for (c in byName.values) if (c.expiresAt > 0L) expiries[c.name] = c.expiresAt
            val text = byName.values
                .filter { it.value.isNotEmpty() }
                .joinToString("; ") { "${it.name}=${it.value}" }
            return text to expiries
        }
    }

    /**
     * 尝试账密登录。不抛异常：所有失败都归类成 [Outcome.code]，交给策略层决定退避 / 硬停。
     */
    fun login(username: String, password: String): Outcome {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            return fail(ReloginPolicy.CODE_BAD_CREDENTIALS, "")
        }

        val jar = MemoryJar()
        val client = WeiboHttp.client.newBuilder()
            .cookieJar(jar)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        // 预热 weibo.com：拿一份基础 cookie，避免登录表单在空会话下被判异常
        try {
            client.newCall(desktopRequest("https://weibo.com/")).execute().close()
        } catch (e: Exception) {
            // 预热失败不影响登录本身
        }

        val prelogin = try {
            fetchPrelogin(client, user)
        } catch (e: IOException) {
            return fail(ReloginPolicy.CODE_NETWORK, "")
        } catch (e: Exception) {
            return fail(ReloginPolicy.CODE_UNKNOWN, "")
        }
        val servertime = prelogin.optString("servertime", "").trim()
        val nonce = prelogin.optString("nonce", "").trim()
        val pubkey = prelogin.optString("pubkey", "").trim()
        val rsakv = prelogin.optString("rsakv", "").trim()
        if (servertime.isEmpty() || nonce.isEmpty() || pubkey.isEmpty() || rsakv.isEmpty()) {
            return fail(ReloginPolicy.CODE_UNKNOWN, prelogin.optString("retcode", ""))
        }

        val sp = try {
            rsaEncrypt(pubkey, servertime, nonce, password)
        } catch (e: Exception) {
            return fail(ReloginPolicy.CODE_UNKNOWN, "")
        }

        val su = user.encodeUtf8().base64()
        val form = FormBody.Builder()
            .add("entry", "weibo")
            .add("gateway", "1")
            .add("from", "")
            .add("savestate", "30")
            .add("useticket", "1")
            .add("pagerefer", "")
            .add("vsnf", "1")
            .add("su", su)
            .add("service", "miniblog")
            .add("servertime", servertime)
            .add("nonce", nonce)
            .add("pwencode", "rsa2")
            .add("rsakv", rsakv)
            .add("sp", sp)
            .add("sr", "1920*1080")
            .add("encoding", "UTF-8")
            .add("prelt", "115")
            .add("returntype", "TEXT")
            .add("url", LOGIN_REDIRECT)
            .build()

        val payload = try {
            client.newCall(
                desktopRequest(LOGIN_URL).newBuilder().post(form).build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val code = if (resp.code == 429 || resp.code == 432 || resp.code == 403) {
                        ReloginPolicy.CODE_RISK_CONTROL
                    } else {
                        ReloginPolicy.CODE_NETWORK
                    }
                    return fail(code, "", "登录接口 HTTP ${resp.code}")
                }
                parseJsonObject(resp.body?.string().orEmpty())
                    ?: return fail(ReloginPolicy.CODE_UNKNOWN, "")
            }
        } catch (e: IOException) {
            return fail(ReloginPolicy.CODE_NETWORK, "")
        } catch (e: Exception) {
            return fail(ReloginPolicy.CODE_UNKNOWN, "")
        }

        val retcode = payload.opt("retcode")?.toString()?.trim().orEmpty()
        if (retcode != "0" && retcode.isNotEmpty()) {
            val code = classifyRetcode(retcode, reasonTextOf(payload))
            return fail(code, retcode)
        }

        // 跟随新浪的跨域回跳（每个域各下一次 cookie），最多 6 次
        for (url in callbackUrls(payload).take(MAX_CALLBACKS)) {
            try {
                client.newCall(desktopRequest(url)).execute().close()
            } catch (e: Exception) {
                // 单条回跳失败不阻断整体登录，最终仍以探活结果为准
            }
        }

        val (merged, expiries) = jar.merged()
        if (merged.isEmpty()) return fail(ReloginPolicy.CODE_VERIFY_FAILED, retcode)

        val verify = try {
            WeiboAuthApi.verify(merged, force = true)
        } catch (e: WeiboHttp.WeiboException) {
            val code = if (e.httpCode == 429 || e.httpCode == 432 || e.httpCode == 403) {
                ReloginPolicy.CODE_RISK_CONTROL
            } else if (e.httpCode > 0) {
                ReloginPolicy.CODE_NETWORK
            } else {
                ReloginPolicy.CODE_VERIFY_FAILED
            }
            return fail(code, retcode)
        } catch (e: IOException) {
            return fail(ReloginPolicy.CODE_NETWORK, retcode)
        } catch (e: Exception) {
            return fail(ReloginPolicy.CODE_VERIFY_FAILED, retcode)
        }
        if (!verify.login) return fail(ReloginPolicy.CODE_VERIFY_FAILED, retcode)

        val serverExpires = listOf(
            WeiboHttp.serverExpiryFromHeaders(expiries, verify.cookie),
            verify.serverExpiresAt,
        ).filter { it > 0L }.minOrNull() ?: 0L
        val loginAt = verify.loginAt.takeIf { it > 0L } ?: System.currentTimeMillis()

        return Outcome(
            ok = true,
            cookie = verify.cookie,
            loginAt = loginAt,
            serverExpiresAt = serverExpires,
            uid = verify.uid,
        )
    }

    // ---------- 纯解析 / 分类（JVM 单测覆盖） ----------

    /**
     * `retcode` + 文案 → 失败分类。
     *
     * 文案只用于「同码不同因」的兜底识别（例如二次验证有时复用通用错误码），
     * 返回的固定文案不会回显原文，避免把表单内容带进日志。
     */
    internal fun classifyRetcode(retcode: String, text: String): String {
        val rc = retcode.trim()
        if (rc in NEEDS_MANUAL_CODES) return ReloginPolicy.CODE_NEEDS_MANUAL
        if (rc in BAD_CREDENTIAL_CODES) return ReloginPolicy.CODE_BAD_CREDENTIALS
        if (rc in RISK_CODES) return ReloginPolicy.CODE_RISK_CONTROL

        val t = text.lowercase()
        return when {
            t.contains("验证码") || t.contains("二次验证") || t.contains("安全验证") ||
                t.contains("扫码") || t.contains("captcha") -> ReloginPolicy.CODE_NEEDS_MANUAL
            t.contains("密码") || t.contains("账号或密码") || t.contains("用户名或密码") ->
                ReloginPolicy.CODE_BAD_CREDENTIALS
            t.contains("频繁") || t.contains("风控") || t.contains("异常") || t.contains("限制") ->
                ReloginPolicy.CODE_RISK_CONTROL
            else -> ReloginPolicy.CODE_UNKNOWN
        }
    }

    /** 从跨域回跳 JSON 里收集可访问的回调 URL（只放行微博 / 新浪域，http 升级 https）。 */
    internal fun callbackUrls(payload: JSONObject): List<String> {
        val out = LinkedHashSet<String>()

        fun push(any: Any?) {
            when (any) {
                is String -> Regex("https?://[^\\s\"'<>\\\\]+").findAll(any).forEach { m ->
                    val raw = m.value.trim()
                    val https = if (raw.startsWith("http://", ignoreCase = true)) {
                        "https://" + raw.substring(7)
                    } else {
                        raw
                    }
                    val host = try {
                        java.net.URI(https).host?.lowercase().orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                    if (host.isNotEmpty() && WeiboHttp.isLoginHost(host)) out.add(https)
                }

                is JSONObject -> any.keys().forEach { push(any.opt(it)) }
                is JSONArray -> for (i in 0 until any.length()) push(any.opt(i))
            }
        }
        push(payload)
        return out.toList()
    }

    /** 解析 JSONP（`callback({...})`）或裸 JSON。 */
    internal fun parseJsonObject(body: String): JSONObject? {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(body.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 内部实现 ----------

    private fun fetchPrelogin(client: OkHttpClient, username: String): JSONObject {
        val su = username.encodeUtf8().base64()
        val url = buildString {
            append(PRELOGIN_URL)
            append("?entry=weibo&callback=sinaSSOController.preloginCallBack&rsa=1&client=")
            append(URLEncoder.encode(LOGIN_CLIENT, "UTF-8"))
            append("&su=")
            append(URLEncoder.encode(su, "UTF-8"))
            append("&_=")
            append(System.currentTimeMillis())
        }
        client.newCall(desktopRequest(url)).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return parseJsonObject(body) ?: JSONObject()
        }
    }

    private fun desktopRequest(url: String): Request =
        Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://weibo.com/")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

    /** RSA/PKCS#1 v1.5 加密登录口令（新浪公钥为 1024 位，指数固定 65537）。 */
    internal fun rsaEncrypt(
        pubkeyHex: String, servertime: String, nonce: String, password: String,
    ): String {
        val modulus = BigInteger(pubkeyHex.trim(), 16)
        val exponent = BigInteger("10001", 16)
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val plain = "$servertime\t$nonce\n$password".toByteArray(Charsets.UTF_8)
        return cipher.doFinal(plain).toByteString().base64()
    }

    private fun reasonTextOf(payload: JSONObject): String {
        var text = ""
        fun take(any: Any?) {
            when (any) {
                is JSONObject -> {
                    for (k in listOf("reason", "msg", "message", "error", "errmsg")) {
                        val v = any.optString(k, "").trim()
                        if (text.isEmpty() && v.isNotEmpty()) text = v
                    }
                    any.keys().forEach { take(any.opt(it)) }
                }

                is JSONArray -> for (i in 0 until any.length()) take(any.opt(i))
            }
        }
        take(payload)
        return text.take(80)
    }

    private fun fail(code: String, retcode: String, message: String = ""): Outcome {
        val text = message.ifEmpty { ReloginPolicy.reasonText(code, retcode) }
        return Outcome(ok = false, code = code, message = text, retcode = retcode)
    }
}
