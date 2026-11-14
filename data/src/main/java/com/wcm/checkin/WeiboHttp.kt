package com.wcm.checkin

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 微博 HTTP 基础设施：client、Cookie 工具、域名白名单与 URL 安全策略。
 *
 * 从原 `WeiboApi` 拆出：所有业务接口（身份 / 超话 / 抓图 / 图片地址）共用这一层，
 * 但不再把「怎么发请求」和「怎么解析业务」搅在一个 1100 行的对象里。
 */
object WeiboHttp {

    class WeiboException(
        message: String,
        /** HTTP 状态码；非 HTTP 层错误为 0。 */
        val httpCode: Int = 0,
        /**
         * 这次失败是否「像身份问题」（风控 432/403/429、`ok=-100`、返回的不是 JSON）。
         *
         * 只有这种失败才值得换一份游客 cookie 重试一次；接口结构变了、参数错了
         * 换身份也没用，重试只会多打一次请求。
         */
        val identitySuspect: Boolean = false,
    ) : Exception(message)

    const val BASE = "https://m.weibo.cn"

    /** Web 端（weibo.com 下的 ajax 接口族）的基址：游客身份只在这一侧成立。 */
    const val WEB_BASE = "https://weibo.com"

    /** m 站 UA。GuestSession 复用同一份，保持两处请求口径一致。 */
    const val UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

    /** Web 端 UA：ajax 接口按桌面浏览器口径下发数据，用移动 UA 会被当异常客户端。 */
    const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val WEIBO_HOSTS = listOf("weibo.cn", "weibo.com", "weibocdn.com", "sinaimg.cn")

    /** 登录页额外放行的新浪域（护照 / SSO / 跨域分发都在这些域下）。 */
    private val LOGIN_SINA_HOSTS = listOf("sina.com.cn", "sina.cn", "sina.com")

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * 拼进 URL 的 id 一律过一遍编码。
     *
     * uid / bid 目前都是上游校验过的数字或 base62，但抓取目标与「单帖浏览」的
     * bid 来自用户输入：一个带 & 或 # 的输入就能把 query 拆坏（参数注入到别的接口
     * 参数上）。这一层成本是零，收益是「永不因为脏输入构造出错误请求」。
     */
    internal fun q(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    internal fun request(url: String, cookie: String): Request =
        Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "$BASE/")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
            // 面板侧（app/weibo_client.py 的 HEADERS）长期带这个头：m 站按 PWA 口径
            // 下发卡片，`buttons` 才是「签到 / 已签」的按钮语义。少了它拿到的是另一套
            // 卡片结构，超话列表里的签到状态就会对不上。
            .header("MWeibo-Pwa", "1")
            .header("Cookie", cookie)
            .build()

    /**
     * JSON 解析失败统一标记：访客拦截页 / 风控页都是 HTML 或空串，正常接口不会走到这里。
     */
    internal fun parseJson(body: String, what: String): JSONObject =
        try {
            JSONObject(body)
        } catch (e: Exception) {
            throw WeiboException(
                "$what 响应不是 JSON（前 60 字：${body.trim().take(60)}）",
                identitySuspect = true,
            )
        }

    /** HTTP 层失败的统一出口：432/403/429 归为身份可疑（风控）。 */
    internal fun httpFailure(what: String, code: Int): WeiboException = WeiboException(
        "$what 请求失败：HTTP $code" + httpHint(code),
        httpCode = code,
        identitySuspect = code == 432 || code == 403 || code == 429,
    )

    internal fun httpHint(code: Int): String = when (code) {
        429, 432 -> "（微博风控拦截，稍等再试）"
        403 -> "（请求被拒绝）"
        else -> ""
    }

    /**
     * 是否是微博自家域名（含子域）。出站白名单与 WebView 导航白名单复用同一份口径。
     */
    fun isWeiboHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h.isNotEmpty() && WEIBO_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /**
     * 登录页导航白名单 = 微博自家域 + 新浪护照/SSO 域（`sina.com.cn` 与 `sina.cn` 全量）。
     *
     * 登录链路有两处必须放行的「非微博域」：
     * - `.cn` 护照：`passport.weibo.cn/signin/login` 302 到 `passport.weibo.com/sso/signin`，
     *   登录成功后前端再跳到服务端下发的 `location`（新浪 SSO 跨域分发）；
     * - **`passport.sina.cn`**：sinawap 通道的跨域桥，负责把会话写回 `.weibo.cn`。
     *   实测漏掉它会直接卡在「已拦截跳转：passport.sina.cn」。
     *
     * 为什么整域放行：新浪与微博同属一家，护照域散落在 `passport./login./sso./visitor.`
     * 等多个子域、且会随活动调整；本 WebView 不带 JS 桥、不开 file:// / content://，
     * 微博的会话 Cookie 又受域限制、不会发往 sina 域名，放行它们不扩大凭据暴露面。
     * 面板侧登录域白名单同样是「微博域 + sina.com.cn」（`微博签到/app/security.py`），
     * 这里按同一口径再加 `.cn`。
     *
     * 只给登录页用；所有出站接口请求仍走 [isWeiboHost] 的严格口径。
     */
    fun isLoginHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if (isWeiboHost(h)) return true
        return LOGIN_SINA_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    fun weiboUrl(raw: String): String {
        val url = raw.trim()
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: ""
        // 只放行 https：明文 http 可被中间人替换整张图片/整段接口响应。
        // 微博全站（含 sinaimg CDN）都支持 https，没有任何保留 http 的理由。
        if (scheme != "https") {
            throw WeiboException("仅允许 https 链接（收到：${scheme.ifEmpty { "无 scheme" }}）")
        }
        val host = (uri.host ?: "").lowercase().trimEnd('.')
        if (!isWeiboHost(host)) {
            throw WeiboException("链接域名不在微博白名单内：$host")
        }
        return url
    }

    // ---------- cookie 工具 ----------

    fun parseCookieString(raw: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        raw.split(";").forEach { part ->
            val kv = part.trim()
            if (kv.isEmpty()) return@forEach
            val key = kv.substringBefore("=").trim()
            val value = kv.substringAfter("=", "").trim()
            if (key.isNotEmpty() && value.isNotEmpty()) map[key] = value
        }
        return map
    }

    fun cookieToString(map: Map<String, String>): String =
        map.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** 合并响应里的 Set-Cookie（登录态轮换：换发的新 SUB 就在这里进来）。 */
    internal fun mergeSetCookies(base: LinkedHashMap<String, String>, response: Response) {
        for (header in response.headers("set-cookie")) {
            val kv = header.substringBefore(';')
            val key = kv.substringBefore('=').trim()
            val value = kv.substringAfter('=', "").trim()
            if (key.isNotEmpty() && value.isNotEmpty()) base[key] = value
        }
    }

    /** 登录凭据相关的 cookie：只有它们的到期时间能代表「服务端到期」。 */
    val LOGIN_EXPIRY_COOKIES = setOf("SUB", "SUBP", "SCF", "SSOLoginState", "ALF")

    /**
     * 从 Set-Cookie 的 Expires/Max-Age 里取服务端声明到期；没有再退回 cookie 的 ALF。
     * 取登录关键字段里**最早**的一个 —— 任何一个关键凭据先到期，登录态就撑不到更晚。
     */
    fun serverExpiryFromHeaders(expiries: Map<String, Long>, mergedCookie: String): Long =
        expiries.filterKeys { it in LOGIN_EXPIRY_COOKIES }.values.minOrNull()
            ?: CookieLife.serverExpiryOf(mergedCookie)
}
