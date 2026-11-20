package com.wcm.checkin

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.math.BigInteger
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
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
    private val RISK_CODES = setOf(
        "2090", "50114001", "50114002", "50114003", "50114004",
        // 1117「抱歉！登录失败，请稍候再试」：服务端拒绝这次账密登录的通用回执。
        // 实测与账号是否存在无关（换任意 su 都是 1117），多为「新设备/异地要求二次验证」
        // 或「短时间登录过多」。归为风控类 = 可退避重试，但不硬停。
        "1117",
    )

    /**
     * 文案里的「要人工验证」特征词。
     *
     * 判定顺序放在码表**之前**：微博常把一个通用错误码配上「需要短信验证」这类文案，
     * 只认码表会把「要验证」误判成风控/未知，用户看到的就一直是「未知错误」。
     */
    private val VERIFY_MARKS = listOf(
        "验证码", "二次验证", "安全验证", "身份验证", "短信验证", "私信验证",
        "扫码", "验证后", "captcha", "verification",
    )

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
     *
     * 拆成三段（2026-11-20）：预热 + prelogin + RSA → 提交表单 → 探活组装。
     * 每段返回 `(产物, 失败终态)`——fail 非空时调用方直接 return，本函数只保留
     * 「阶段顺序」。此前 122 行直排（detekt LongMethod 门槛），逻辑没有任何变化。
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

        // ---- 预热 + prelogin + RSA ----
        val (parts, preFail) = preparePrelogin(client, user, password)
        preFail?.let { return it }
        val pre = parts!!

        // ---- 提交登录表单 ----
        val (payload, submitFail) = submitLogin(client, user, pre)
        submitFail?.let { return it }
        val body = payload!!

        // ---- 跟随新浪的跨域回跳（每个域各下一次 cookie）----
        for (url in callbackUrls(body).take(MAX_CALLBACKS)) {
            try {
                client.newCall(desktopRequest(url)).execute().close()
            } catch (e: Exception) {
                // 单条回跳失败不阻断整体登录，最终仍以探活结果为准
            }
        }

        // ---- 探活 + 组装结果 ----
        return verifyStage(jar, body)
    }

    /**
     * 预热 weibo.com（拿基础 cookie，失败不影响登录）→ prelogin 四件套 →
     * `showpin` 检查 → RSA 加密密码。
     */
    private fun preparePrelogin(
        client: OkHttpClient,
        user: String,
        password: String,
    ): Pair<PreloginParts?, Outcome?> {
        try {
            client.newCall(desktopRequest("https://weibo.com/")).execute().close()
        } catch (e: Exception) {
            // 预热失败不影响登录本身
        }

        val prelogin = try {
            fetchPrelogin(client, user)
        } catch (e: IOException) {
            return Pair(null, fail(ReloginPolicy.CODE_NETWORK, ""))
        } catch (e: Exception) {
            return Pair(null, fail(ReloginPolicy.CODE_UNKNOWN, ""))
        }
        val servertime = prelogin.optString("servertime", "").trim()
        val nonce = prelogin.optString("nonce", "").trim()
        val pubkey = prelogin.optString("pubkey", "").trim()
        val rsakv = prelogin.optString("rsakv", "").trim()
        if (servertime.isEmpty() || nonce.isEmpty() || pubkey.isEmpty() || rsakv.isEmpty()) {
            return Pair(null, fail(ReloginPolicy.CODE_UNKNOWN, prelogin.optString("retcode", "")))
        }
        // `showpin=1` = 服务端这次就要验证码。继续提交密码只会白撞一次风控，
        // 直接判 needs_manual（硬停），把「该去人工验证」这件事提前说清楚。
        if (prelogin.optInt("showpin", 0) == 1) {
            return Pair(null, fail(ReloginPolicy.CODE_NEEDS_MANUAL, prelogin.optString("retcode", "")))
        }

        val sp = try {
            rsaEncrypt(pubkey, servertime, nonce, password)
        } catch (e: Exception) {
            return Pair(null, fail(ReloginPolicy.CODE_UNKNOWN, ""))
        }
        return Pair(
            PreloginParts(servertime = servertime, nonce = nonce, pubkey = pubkey, rsakv = rsakv, sp = sp),
            null,
        )
    }

    /** 组表单并 POST；`retcode != 0` 与非 JSON 响应都在这里就地分类成失败终态。 */
    private fun submitLogin(
        client: OkHttpClient,
        user: String,
        pre: PreloginParts,
    ): Pair<JSONObject?, Outcome?> {
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
            .add("servertime", pre.servertime)
            .add("nonce", pre.nonce)
            .add("pwencode", "rsa2")
            .add("rsakv", pre.rsakv)
            .add("sp", pre.sp)
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
                    return Pair(null, fail(code, "", "登录接口 HTTP ${resp.code}"))
                }
                val body = resp.body?.string().orEmpty()
                // 不是 JSON 时不能一律判 unknown：微博要人工验证时会回一整页 HTML
                // （验证页 / 安全中心），把正文交给文案识别才能落到 needs_manual。
                parseJsonObject(body) ?: return Pair(null, fail(classifyRetcode("", body.take(4000)), ""))
            }
        } catch (e: IOException) {
            return Pair(null, fail(ReloginPolicy.CODE_NETWORK, ""))
        } catch (e: Exception) {
            return Pair(null, fail(ReloginPolicy.CODE_UNKNOWN, ""))
        }

        val retcode = payload.opt("retcode")?.toString()?.trim().orEmpty()
        if (retcode != "0" && retcode.isNotEmpty()) {
            val code = classifyRetcode(retcode, reasonTextOf(payload))
            return Pair(null, fail(code, retcode))
        }
        return Pair(payload, null)
    }

    /** 合并 cookie → 探活 → 按「HTTP 码 > 登录态 > 服务端到期」组装成功 Outcome。 */
    private fun verifyStage(jar: MemoryJar, payload: JSONObject): Outcome {
        val (merged, expiries) = jar.merged()
        val retcode = payload.opt("retcode")?.toString()?.trim().orEmpty()

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

    /** prelogin 四件套 + RSA 加密后的密码。submit 表单的原料。 */
    private class PreloginParts(
        val servertime: String,
        val nonce: String,
        val pubkey: String,
        val rsakv: String,
        val sp: String,
    )
    // ---------- 纯解析 / 分类（JVM 单测覆盖） ----------

    /**
     * `retcode` + 文案 → 失败分类。
     *
     * 文案只用于「同码不同因」的兜底识别（例如二次验证有时复用通用错误码），
     * 返回的固定文案不会回显原文，避免把表单内容带进日志。
     */
    fun classifyRetcode(retcode: String, text: String): String {
        val rc = retcode.trim()
        val t = text.lowercase()
        // 文案里明确提到「验证」→ 一定是要人工处理，优先于任何码表
        if (VERIFY_MARKS.any { t.contains(it) }) return ReloginPolicy.CODE_NEEDS_MANUAL

        if (rc in NEEDS_MANUAL_CODES) return ReloginPolicy.CODE_NEEDS_MANUAL
        if (rc in BAD_CREDENTIAL_CODES) return ReloginPolicy.CODE_BAD_CREDENTIALS
        if (rc in RISK_CODES) return ReloginPolicy.CODE_RISK_CONTROL

        return when {
            t.contains("密码") || t.contains("账号或密码") || t.contains("用户名或密码") ->
                ReloginPolicy.CODE_BAD_CREDENTIALS
            t.contains("频繁") || t.contains("风控") || t.contains("异常") || t.contains("限制") ->
                ReloginPolicy.CODE_RISK_CONTROL
            else -> ReloginPolicy.CODE_UNKNOWN
        }
    }

    /** 从跨域回跳 JSON 里收集可访问的回调 URL（只放行微博 / 新浪域，http 升级 https）。 */
    fun callbackUrls(payload: JSONObject): List<String> {
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
    fun parseJsonObject(body: String): JSONObject? {
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

    /**
     * RSA/PKCS#1 v1.5 加密登录口令。
     *
     * 公钥形态见 [parsePublicKey] —— 2026-11 起新浪下发的已经是 PEM，老实现只认
     * 16 进制 modulus，直接抛 NumberFormatException（表现为「登录返回未知错误」）。
     */
    fun rsaEncrypt(
        pubkey: String, servertime: String, nonce: String, password: String,
    ): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, parsePublicKey(pubkey))
        val plain = "$servertime\t$nonce\n$password".toByteArray(Charsets.UTF_8)
        return cipher.doFinal(plain).toByteString().base64()
    }

    /** PEM 头的起始标记：出现它才说明是 PEM 而不是裸 16 进制。 */
    private const val PEM_MARK = "-----BEGIN"

    /** 16 进制 modulus（无 PEM 头）。 */
    private val HEX_ONLY = Regex("^[0-9a-fA-F]+$")

    /**
     * 解析 `sso/prelogin.php` 下发的公钥，三种形态都认：
     *
     * 1. **PEM / X.509 SubjectPublicKeyInfo** —— `-----BEGIN PUBLIC KEY-----`，
     *    当前（2026-11 实测）线上就是这种，1024 位；
     * 2. **PEM / PKCS#1** —— `-----BEGIN RSA PUBLIC KEY-----`，兜底；
     * 3. **裸 16 进制 modulus** —— 2026-11 之前的口径，指数固定 65537。
     *
     * 为什么必须三种都认：这是整条账密重登链路的唯一入口。解析失败只会被上层归成
     * `unknown`，用户看到「微博登录返回未知错误」——密码再对也永远过不去，
     * 且现象和「密码错」「风控」全都长得不一样，极难从日志定位。
     */
    fun parsePublicKey(raw: String): PublicKey {
        val text = raw.trim()
        require(text.isNotEmpty()) { "公钥为空" }
        val body = if (text.contains(PEM_MARK)) pemBodyOf(text) else text
        if (HEX_ONLY.matches(body)) {
            return KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(BigInteger(body, 16), BigInteger("10001", 16)),
            )
        }
        val der = body.decodeBase64()?.toByteArray()
            ?: throw IllegalArgumentException("公钥不是合法的 PEM/base64")
        val factory = KeyFactory.getInstance("RSA")
        // 先按 X.509（带算法标识）；不成立再按 PKCS#1（裸 RSAPublicKey SEQUENCE）
        runCatching { factory.generatePublic(X509EncodedKeySpec(der)) }
            .getOrNull()
            ?.let { return it }
        val (modulus, exponent) = decodePkcs1(der)
        return factory.generatePublic(RSAPublicKeySpec(modulus, exponent))
    }

    /** 去掉 PEM 头尾与所有空白，只留 base64 主体。 */
    private fun pemBodyOf(pem: String): String =
        pem.lineSequence()
            .filterNot { it.contains("-----") }
            .joinToString("")
            .filterNot { it.isWhitespace() }

    /**
     * 极简 DER 读取，只服务 PKCS#1 这一种兜底：
     * `RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }`。
     *
     * 不追求通用性（不做 tag 树、不认其他类型），越界一律抛异常交给上层归类。
     */
    internal fun decodePkcs1(der: ByteArray): Pair<BigInteger, BigInteger> {
        var i = 0

        fun u8(): Int {
            require(i < der.size) { "DER 越界" }
            return der[i++].toInt() and 0xFF
        }

        fun expect(tag: Int) {
            require(u8() == tag) { "DER tag 不符" }
        }

        fun length(): Int {
            val first = u8()
            if (first and 0x80 == 0) return first
            val n = first and 0x7F
            require(n in 1..4 && i + n <= der.size) { "DER 长度越界" }
            var len = 0
            repeat(n) { len = (len shl 8) or u8() }
            return len
        }

        fun integer(): BigInteger {
            expect(0x02)
            val len = length()
            require(len > 0 && i + len <= der.size) { "DER 整数越界" }
            val bytes = der.copyOfRange(i, i + len)
            i += len
            // 正数：DER 会在最高位为 1 时补一个 0x00，这里按无符号解释
            return BigInteger(1, bytes)
        }

        expect(0x30)
        length()
        val modulus = integer()
        val exponent = integer()
        return modulus to exponent
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
