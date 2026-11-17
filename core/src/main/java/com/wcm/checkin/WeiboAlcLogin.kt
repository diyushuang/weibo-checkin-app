package com.wcm.checkin

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.random.Random

/**
 * 用 `ALC` 做「重签」——即**不输入账号密码**地重新登录一次。
 *
 * 为什么不是"续期"：微博没有可主动调用的刷新接口。社区实测（findxyz，2026-07）已证明
 * `sso/updatetgt.php` + `sso/crossdomain.php` 那条旧"更新链"会把现有凭证直接作废
 * （调用后 5 秒同一接口返回 401，恢复原 cookie 也救不回），根因是扫码产出的 `SUBP`
 * 缺 `et` 字段。**本项目永不调用那条链。** 这里走的是 SSO 的**票据换会话**：
 * 用 `ALC`（登录中心下发的中长期凭证）重新走一遍 `sso/login.php`，换回一套全新凭据。
 *
 * 链路（对照 `happy888888/WeiboTask` 的 `loginByCookies.go`，2026-09 仍在用）：
 *
 * ```
 * GET login.sina.com.cn/sso/login.php
 *       ?url=<目标页>&gateway=1&service=miniblog&entry=miniblog
 *       &useticket=1&returntype=META&_client_version=0.6.36
 *   → HTML 里 location.replace("…") 拿跳转地址
 *   → 落地页返回 setCrossDomainUrlList({arrURL:[…]})
 *   → GET arrURL[0] + callback=sinaSSOController.doCrossDomainCallBack  ← 此时才落 SUB
 * ```
 *
 * 两臂各走一遍：`service=miniblog`（写 `.weibo.com`）与 `service=sinawap`
 * （写 `.weibo.cn`，签到链路要用）。两臂共用同一个 [WeiboHttp.WeiboSession]，
 * 所以谁先成功都不会把另一份踢掉。
 *
 * 失败一律归类成 `ReloginPolicy.CODE_*`，绝不抛异常——与 [WeiboPasswordLogin] 同口径。
 */
object WeiboAlcLogin {

    private const val LOGIN_URL = "https://login.sina.com.cn/sso/login.php"
    private const val CLIENT_VERSION = "0.6.36"

    /** 一臂的目标页：主站与 m 站各自关心的落地地址。 */
    private const val TARGET_WEB = "https://weibo.com/"
    private const val TARGET_M = "https://m.weibo.cn/profile/info"

    data class Outcome(
        val ok: Boolean,
        val code: String = "",
        val message: String = "",
        val cookie: String = "",
        val uid: String = "",
        val loginAt: Long = 0L,
        val serverExpiresAt: Long = 0L,
    )

    /**
     * 用 ALC 重签。
     *
     * @param alc `ALC` 的值（允许用户从浏览器整条粘贴，带不带 `ALC=` 前缀都认）
     */
    fun login(alc: String): Outcome {
        val value = normalizeAlc(alc)
        if (value.isEmpty()) {
            return fail(ReloginPolicy.CODE_BAD_CREDENTIALS, "ALC 为空")
        }

        val session = WeiboHttp.WeiboSession.of("ALC=$value")

        // 两臂独立成败：主站成功但 m 站失败时，签到仍拿不到 st，
        // 所以最终仍以探活结果为准，这里只负责把能落的 cookie 都落下来。
        try {
            arm(session, service = "miniblog", entry = "miniblog", target = TARGET_WEB)
        } catch (e: IOException) {
            return fail(ReloginPolicy.CODE_NETWORK, e.message ?: "网络异常")
        }
        try {
            arm(session, service = "sinawap", entry = "sinawap", target = TARGET_M)
        } catch (e: IOException) {
            return fail(ReloginPolicy.CODE_NETWORK, e.message ?: "网络异常")
        }

        if (!session.isLoggedInShaped) {
            // 「没换到 SUB」不等于「ALC 一定错了」：可能是微博这次抽风/风控。
            // 用软失败（verify_failed）而不是 bad_credentials，让它走退避重试，
            // 而不是一次就把该账号的自动重登永久硬停。
            return fail(ReloginPolicy.CODE_VERIFY_FAILED, "ALC 未被接受（未换到 SUB）")
        }

        val verify = try {
            WeiboAuthApi.verify(session.cookie, force = true)
        } catch (e: WeiboHttp.WeiboException) {
            val code = when {
                e.httpCode == 429 || e.httpCode == 432 || e.httpCode == 403 ->
                    ReloginPolicy.CODE_RISK_CONTROL
                e.httpCode > 0 -> ReloginPolicy.CODE_NETWORK
                else -> ReloginPolicy.CODE_VERIFY_FAILED
            }
            return fail(code, e.message ?: "")
        } catch (e: IOException) {
            return fail(ReloginPolicy.CODE_NETWORK, e.message ?: "")
        }
        if (!verify.login) {
            return fail(ReloginPolicy.CODE_VERIFY_FAILED, "ALC 重签后探活仍未登录")
        }

        return Outcome(
            ok = true,
            cookie = verify.cookie,
            uid = verify.uid,
            loginAt = verify.loginAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
            serverExpiresAt = verify.serverExpiresAt.takeIf { it > 0L }
                ?: CookieLife.serverExpiryOf(verify.cookie),
        )
    }

    /**
     * 走一臂：`sso/login.php` → `location.replace` → `setCrossDomainUrlList` → 回跳。
     *
     * **所有由响应决定的跳转地址都要过白名单**：这一路带着 `ALC` 走，响应体是微博给的，
     * 一旦被开放重定向带到第三方域，等于把票据发出去。口径与 WebView 导航白名单一致
     * （[WeiboHttp.isLoginHost]：微博域 + 新浪护照域，且必须 https）。
     *
     * @return 这一臂是否换到了 SUB（两臂都失败也可能靠另一臂成功，由调用方统一判断）
     */
    private fun arm(session: WeiboHttp.WeiboSession, service: String, entry: String, target: String): Boolean {
        val url = buildString {
            append(LOGIN_URL)
            append("?url=").append(URLEncoder.encode(target, "UTF-8"))
            append("&_rand=").append(rand())
            append("&gateway=1&service=").append(service)
            append("&entry=").append(entry)
            // useticket=1 是关键：凭票据（ALC）换会话，而不是走账号密码表单
            append("&useticket=1&returntype=META&sudaref=&_client_version=").append(CLIENT_VERSION)
        }
        val html = get(session, url) ?: return false
        val landing = trustedTarget(extractLocationReplace(html)) ?: return false
        val page = get(session, landing) ?: return false
        val first = extractCrossDomainUrls(page).firstOrNull() ?: return false
        val callback = trustedTarget(
            first + (if (first.contains('?')) "&" else "?") +
                "callback=sinaSSOController.doCrossDomainCallBack" +
                "&scriptId=ssoscript0&client=ssologin.js(v1.4.2)",
        ) ?: return false
        get(session, callback)
        return session.isLoggedInShaped
    }

    /**
     * 只放行「https + 微博/新浪护照域」的跳转目标，其余一律拒绝（返回 null）。
     *
     * 为什么必须做：`location.replace` 与 `arrURL` 的内容完全由服务端响应决定，
     * 而这一路请求携带 `ALC`。不设白名单就等于允许远端把票据指向任意主机。
     */
    fun trustedTarget(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            val uri = java.net.URI(text)
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            if (scheme == "https" && host.isNotEmpty() && WeiboHttp.isLoginHost(host)) text else null
        } catch (e: Exception) {
            null
        }
    }

    /** 登录域用桌面 UA：SSO 对移动 UA 会改走另一套（sinawap）编排。 */
    private fun get(session: WeiboHttp.WeiboSession, url: String): String? =
        WeiboHttp.call(url, session, desktop = true).use { resp ->
            if (resp.isSuccessful) resp.body?.string().orEmpty() else null
        }

    // ---------- 纯解析（JVM 单测覆盖） ----------

    /** 允许用户整条粘贴：`ALC=xxx` / `xxx` / 带空格的都认。 */
    fun normalizeAlc(raw: String): String =
        raw.trim()
            .removePrefix("ALC=")
            .removePrefix("alc=")
            .substringBefore(';')
            .trim()

    /**
     * 从**整串 cookie** 里取出 `ALC` 的值；没有返回空串。
     *
     * 用途：登录或探活拿到的 cookie 里如果自带 `ALC`，就自动收进票据库，
     * 用户不必再去浏览器开发者工具里手动复制一遍。
     * 与 [normalizeAlc] 的区别是后者只认「单条 `ALC=xxx`」，整串 cookie 会取错字段。
     */
    fun alcFromCookie(cookie: String): String {
        if (cookie.isBlank()) return ""
        val raw = WeiboHttp.parseCookieString(cookie)["ALC"] ?: return ""
        return normalizeAlc(raw)
    }

    /** 从 SSO 的 META 返回页里取 `location.replace("…")` 的目标地址。 */
    fun extractLocationReplace(html: String): String? {
        val pattern = Regex(
            """location\.replace\(\s*["']([^"']+)["']\s*\)""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.find(html)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }

    /**
     * 从落地页里取 `setCrossDomainUrlList({"retcode":…,"arrURL":[…]})` 的 URL 列表。
     *
     * 微博这里返回的是**非标准 JSON 的参数片段**，而且历史上出现过 `retcode` 为字符串、
     * `arrURL` 为嵌套数组两种形态，所以全部走 `opt*` 宽松读取。
     */
    fun extractCrossDomainUrls(html: String): List<String> {
        val pattern = Regex("""setCrossDomainUrlList\((.*?)\)\s*;?""", RegexOption.DOT_MATCHES_ALL)
        val raw = pattern.find(html)?.groupValues?.get(1)?.trim() ?: return emptyList()
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("arrURL") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i, "").trim().ifEmpty { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 毫秒级时间戳 + 随机尾数，`_rand` 用。必须 `Locale.ROOT`：某些区域的小数点是逗号。 */
    private fun rand(): String = String.format(
        java.util.Locale.ROOT, "%.4f",
        System.currentTimeMillis() / 1000.0 + Random.nextDouble(),
    )

    private fun fail(code: String, message: String): Outcome =
        Outcome(false, code = code, message = message)
}
