package com.wcm.checkin

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 微博接口客户端：m 站（`m.weibo.cn`）为主，Web 端（`weibo.com/ajax`）为辅。
 *
 * 按用途分四块：
 * - **身份**：`verify`（/api/config 探活 + 回收轮换的 Set-Cookie）、`fetchProfile`；
 * - **超话**：`listTopics`（关注列表，分页 + 已签检测）、`fetchTopicDetail`、`signDetailed`；
 * - **抓图**：`fetchUserPosts` / `fetchPostDetail` / `originalImageUrl` / `resolveUid` / `fetchUserName`；
 * - **纯解析**（可随 CI 做 JVM 单测）：`parseTopicPage` / `parseTopicDetail` /
 *   `parseSignResponse` / `parseLoginFlag` / `parseWeiboTime` / `cardPicNum` / `summarize`。
 *
 * 所有出站 URL 只允许微博自家域名（对齐服务端 `validate_weibo_url` 语义）；
 * 登录 WebView 另有一套更宽的白名单，见 [isLoginHost]。
 */
object WeiboApi {

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

    data class Topic(
        val name: String,
        val scheme: String?,
        val done: Boolean,
        /** 超话 ID（scheme 里 `100808` 之后的 hash）。解析不到时为空串。 */
        val topicId: String = "",
        /** 超话等级；0 表示接口没给或解析失败（此时不展示徽章）。 */
        val level: Int = 0,
        /** 卡片次要描述（`desc2`）。 */
        val desc2: String = "",
        /** 该卡片原始 JSON（「查看原始 JSON」用，不落盘） */
        val raw: String = "",
    ) {
        val levelLabel: String get() = if (level > 0) "Lv.$level" else ""

        /** 列表内身份键：优先超话 ID，退化到名称。 */
        val key: String get() = if (topicId.isNotEmpty()) "id:$topicId" else "n:$name"
    }

    /** 超话主页详情（用户点开某条超话时才懒加载）。 */
    data class TopicDetail(
        val topicId: String,
        val title: String = "",
        val intro: String = "",
        val stats: List<String> = emptyList(),
        val portraitText: String = "",
        val oid: String = "",
        val fetchedAt: Long = 0L,
    )

    /** 签到响应：成功与否 + 排名/经验文案（接口给才有）。 */
    data class SignResult(
        val ok: Boolean,
        val alertTitle: String = "",
        val alertSubtitle: String = "",
        val msg: String = "",
        /** 微博的错误码：`100015` = 验签失败（st 过期），需要重新探活换新 st 再试一次。 */
        val errno: Int = 0,
        /** 是否是「重新探活后重试」得到的结果（日志/文案据此区分一次成功与二次成功）。 */
        val retried: Boolean = false,
    ) {
        /** 「签到第 12 名 · 经验 +8」这样的单行摘要，无内容时为空串。 */
        val summary: String
            get() = listOf(alertTitle, alertSubtitle)
                .filter { it.isNotEmpty() }
                .joinToString(" · ")

        /**
         * 是否值得「换一份 st 再试一次」。
         *
         * 面板侧（`weibo_client.run_account_checkin`）对这种情况的处理是：重新探活拿新 st，
         * 再原样请求一次。缺了这一步，st 一过期整轮签到就全 ✗，而列表里明明还是待签 ——
         * 表现就是「状态显示待签、点签到却失败」。
         */
        val needsVerifyRetry: Boolean
            get() = !ok && (errno == 100015 || msg.contains("验签") || msg.contains("驗簽"))
    }

    /** 超话列表接口的一页解析结果。 */
    data class TopicPage(
        val topics: List<Topic> = emptyList(),
        val sinceId: String = "",
        val ok: String = "",
        val msg: String = "",
    )

    data class Verify(
        val login: Boolean,
        val st: String?,
        val cookie: String,
        /** 配置接口顺带回的 UID（部分响应缺这个字段，由调用方兜底补拉）。 */
        val uid: String = "",
    )

    private const val BASE = "https://m.weibo.cn"

    /**
     * 拼进 URL 的 id 一律过一遍编码。
     *
     * uid / bid 目前都是上游校验过的数字或 base62，但抓取目标与「单帖浏览」的
     * bid 来自用户输入：一个带 & 或 # 的输入就能把 query 拆坏（参数注入到别的接口
     * 参数上）。这一层成本是零，收益是「永不因为脏输入构造出错误请求」。
     */
    private fun q(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** Web 端（weibo.com 下的 ajax 接口族）的基址：游客身份只在这一侧成立。 */
    private const val WEB_BASE = "https://weibo.com"

    /** m 站 UA。GuestSession 复用同一份，保持两处请求口径一致。 */
    internal const val UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

    /** Web 端 UA：ajax 接口按桌面浏览器口径下发数据，用移动 UA 会被当异常客户端。 */
    internal const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    private val WEIBO_HOSTS = listOf("weibo.cn", "weibo.com", "weibocdn.com", "sinaimg.cn")

    /** 登录页额外放行的新浪域（护照 / SSO / 跨域分发都在这些域下）。 */
    private val LOGIN_SINA_HOSTS = listOf("sina.com.cn", "sina.cn", "sina.com")
    private val DONE_BUTTONS = setOf("已签", "已簽", "已签到", "已簽到")

    /** 签到成功文案（接口偶发 `ok=0` 但文案写着成功，与面板同口径）。 */
    private val SUCCESS_WORDS = listOf("成功", "已签到", "已簽到")

/**
 * 否定语：出现任意一个就判失败，**优先级高于 [SUCCESS_WORDS]**。
 *
 * 微博的失败文案常写成「签到未成功」「领取失败」—— 它们同样包含「成功」，
 * 只按 contains 判成功会让核心结果判定完全失效。
 */
private val NEGATIVE_WORDS = listOf(
    "未成功", "不成功", "没有成功", "失败", "未签到", "未簽到", "无法签到", "不能签到",
)

    /** 连续两次超话详情请求的最小间隔：详情只在用户点开时触发，仍要防连点打卡。 */
    private const val DETAIL_MIN_GAP_MS = 1200L

    /** 超话列表翻页间隔（每页 10 条）。与面板口径一致：500ms。 */
    private const val TOPIC_PAGE_GAP_MS = 500L

    /**
     * 探活结果的缓存时长。
     *
     * 只有 `login=true` 的结果才会被缓存：登录页轮询依赖「未登录」的实时反馈，
     * 一旦把 `false` 缓存 90 秒，用户登录成功后还会卡在「等待微博确认」。
     * 而登录态的重复探活（刷新/签到/详情前各一次）正是请求量翻倍的主因，缓存它能直接减半。
     */
    private const val VERIFY_TTL_MS = 90_000L

    @Volatile private var verifyCache: CachedVerify? = null

    private data class CachedVerify(val cookie: String, val at: Long, val value: Verify)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

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
    private fun mergeSetCookies(base: LinkedHashMap<String, String>, response: Response) {
        for (header in response.headers("set-cookie")) {
            val kv = header.substringBefore(';')
            val key = kv.substringBefore('=').trim()
            val value = kv.substringAfter('=', "").trim()
            if (key.isNotEmpty() && value.isNotEmpty()) base[key] = value
        }
    }

    private fun request(url: String, cookie: String): Request =
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

    // ---------- 身份选择 ----------

    /**
     * JSON 解析失败统一标记：访客拦截页 / 风控页都是 HTML 或空串，正常接口不会走到这里。
     */
    private fun parseJson(body: String, what: String): JSONObject =
        try {
            JSONObject(body)
        } catch (e: Exception) {
            throw WeiboException(
                "$what 响应不是 JSON（前 60 字：${body.trim().take(60)}）",
                identitySuspect = true,
            )
        }

    /** HTTP 层失败的统一出口：432/403/429 归为身份可疑（风控）。 */
    private fun httpFailure(what: String, code: Int): WeiboException = WeiboException(
        "$what 请求失败：HTTP $code" + httpHint(code),
        httpCode = code,
        identitySuspect = code == 432 || code == 403 || code == 429,
    )

    // ---------- 接口 ----------

    /**
     * 探活 + 轮换：返回是否登录、st、uid 与合并轮换后的完整 cookie。
     *
     * 登录态结果缓存 [VERIFY_TTL_MS]：[force] 可跳过缓存。签到验签重试、登录轮询这类
     * 必须看到实时状态的场景要显式传 `force = true`。
     */
    fun verify(cookie: String, force: Boolean = false): Verify {
        if (!force) {
            val c = verifyCache
            if (c != null && c.cookie == cookie &&
                System.currentTimeMillis() - c.at < VERIFY_TTL_MS
            ) {
                return c.value
            }
        }
        val jar = parseCookieString(cookie)
        val result = client.newCall(request("$BASE/api/config", cookie)).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw httpFailure("探活", resp.code)
            }
            mergeSetCookies(jar, resp)
            val body = resp.body?.string().orEmpty()
            val payload = try {
                JSONObject(body)
            } catch (e: Exception) {
                // 风控页 / HTML 不是「未登录」：抛出去让调用方显示原因，别静默当成没登录
                throw WeiboException(
                    "探活响应不是 JSON（前 60 字：${body.trim().take(60)}）",
                    identitySuspect = true,
                )
            }
            val data = payload.optJSONObject("data") ?: JSONObject()
            val stRaw = data.optString("st", "")
            Verify(
                login = parseLoginFlag(data.opt("login")),
                st = stRaw.ifEmpty { null },
                cookie = cookieToString(jar),
                uid = (data.optString("uid", "").ifEmpty { data.optString("user_id", "") }).trim(),
            )
        }
        // 只缓存「已登录」的结果：把 false 缓存住会让登录页卡在「等待微博确认」。
        if (result.login) {
            verifyCache = CachedVerify(cookie, System.currentTimeMillis(), result)
        }
        return result
    }

    /**
     * `data.login` 的宽松解析：布尔 / 数字 / 字符串都认。
     *
     * 微博把 `true` 写成 `1`（或被序列化成 `"true"`）时，`optBoolean` 只会返回
     * false —— 登录页就会永久停在「等待微博确认登录态」，所以这里显式放宽。
     */
    fun parseLoginFlag(raw: Any?): Boolean = when (raw) {
        null -> false
        is Boolean -> raw
        is Number -> raw.toInt() == 1
        is String -> raw.trim().let { it == "1" || it.equals("true", ignoreCase = true) }
        else -> false
    }

    private fun httpHint(code: Int): String = when (code) {
        429, 432 -> "（微博风控拦截，稍等再试）"
        403 -> "（请求被拒绝）"
        else -> ""
    }

    /** 账号资料：昵称 / UID / 头像（任一字段都可能为空，调用方按需兜底）。 */
    data class Profile(val name: String = "", val uid: String = "", val avatarUrl: String = "")

    /** 昵称所在的键。微博各端两种写法都出现过，统一大小写口径后逐个试。 */
    private val NAME_KEYS = listOf("screen_name", "screenName", "nickname")

    /** 泛用键：只允许从「看起来像用户」的对象里取，否则会把卡片的 `name:"签到"` 当昵称。 */
    private val GENERIC_NAME_KEYS = listOf("name")

    /** 判断一个 dict 像不像「用户对象」：要有 id 类字段，且带一处用户专有字段。 */
    private val USER_ID_KEYS = listOf("idstr", "id", "uid", "user_id")
    private val USER_MARK_KEYS = listOf(
        "profile_image_url", "avatar_hd", "avatar_large", "avatar_url",
        "followers_count", "followers_count_str", "verified", "description",
    )

    /**
     * 从任意微博响应里挖出昵称 / UID / 头像（对齐面板 `_extract_weibo_profile`）。
     *
     * 为什么不能直接读某个固定字段：昵称散落在 `data.screen_name`（旧版 m 站）、
     * `data.userInfo.screen_name`（m 站容器，键名大小写随版本变）、
     * `data.user.screen_name`（Web 端 `ajax/profile/info`）三处，写死任一个都会在
     * 另两个端点上拿到空串 —— App 的「（未获取到昵称）」就是这么来的。
     *
     * 但**不能无脑递归取第一个 name**：超话卡片里 `buttons[i].name` 就是「签到 / 已签」，
     * 无差别取 `name` 会把昵称显示成「签到」。所以只认两类对象：
     * 1. 带 `screen_name` / `screenName` / `nickname` 的对象（键名本身就是用户专有）；
     * 2. 带 id 类字段**且**带头像/粉丝数/认证等用户标记的对象，才允许取泛用的 `name`。
     */
    fun extractProfile(payload: JSONObject): Profile {
        var name = ""
        var uid = ""
        var avatar = ""

        fun harvest(any: Any?) {
            when (any) {
                is JSONObject -> {
                    val keys = any.keys().asSequence().toSet()
                    val userLike = USER_MARK_KEYS.any { it in keys }
                    if (name.isEmpty()) {
                        val strict = NAME_KEYS.firstNotNullOfOrNull { k ->
                            any.optString(k, "").trim().ifEmpty { null }
                        }
                        val loose = if (userLike) {
                            GENERIC_NAME_KEYS.firstNotNullOfOrNull { k ->
                                any.optString(k, "").trim().ifEmpty { null }
                            }
                        } else {
                            null
                        }
                        name = strict ?: loose ?: ""
                    }
                    if (uid.isEmpty() && (userLike || NAME_KEYS.any { it in keys })) {
                        uid = USER_ID_KEYS.firstNotNullOfOrNull { k ->
                            any.opt(k)?.toString()?.trim()?.ifEmpty { null }
                        } ?: ""
                    }
                    if (avatar.isEmpty()) {
                        avatar = listOf("avatar_hd", "avatar_large", "profile_image_url", "avatar_url")
                            .firstNotNullOfOrNull { k ->
                                any.optString(k, "").trim()
                                    .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                            } ?: ""
                    }
                    any.keys().forEach { k -> harvest(any.opt(k)) }
                }

                is org.json.JSONArray -> for (i in 0 until any.length()) harvest(any.opt(i))
            }
        }
        harvest(payload)
        return Profile(name = name, uid = uid, avatarUrl = avatar)
    }

    /**
     * 取当前账号 uid 与昵称（登录成功后、昵称补拉时调用）。
     *
     * 三条链路依次尝试，与面板 `_fetch_weibo_profile` 同口径但顺序按「实测可用性」重排：
     * 1. **Web 端 `ajax/profile/info`** —— 实测游客 cookie 就能返回
     *    `data.user.screen_name`（`m.weibo.cn/api/config` 根本不带昵称字段，
     *    见 2026-10 实测：data 里只有 login/st/loginUrl）；
     * 2. m 站 `getIndex?type=uid` —— 键名大小写随版本变，用 [extractProfile] 容错解析；
     * 3. 都失败就返回空昵称，交给调用方用占位名，不阻断登录。
     *
     * [knownUid]：登录/探活已经拿到的 uid（/api/config 的 `data.uid`）。传进来可以省掉
     * 一次配置请求，也能在配置接口没回 uid 时仍然完成昵称补拉。
     */
    fun fetchProfile(cookie: String, knownUid: String = ""): Pair<String, String> {
        var uid = knownUid.trim()
        var name = ""

        // 1) 配置接口：主要为了 uid（以及拿不到 knownUid 时的兜底）
        if (uid.isEmpty()) {
            try {
                client.newCall(request("$BASE/api/config", cookie)).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val payload = JSONObject(resp.body?.string() ?: "{}")
                        val data = payload.optJSONObject("data") ?: JSONObject()
                        uid = (data.optString("uid", "").ifEmpty { data.optString("user_id", "") }).trim()
                        val parsed = extractProfile(payload)
                        if (uid.isEmpty()) uid = parsed.uid
                        name = parsed.name
                    }
                }
            } catch (e: Exception) {
                // 网络/风控：继续走下面两条链路，昵称拿不到不该让登录流程失败
            }
        }
        if (name.isNotEmpty()) return name to uid
        if (uid.isEmpty()) return "" to ""

        // 2) Web 端用户资料：游客身份可用，是当前唯一稳定的昵称来源
        webProfileName(cookie, uid)?.let { return it to uid }
        // 3) m 站用户主页兜底（风控严格，常为空体；能拿到就用）
        mStationName(cookie, uid)?.let { return it to uid }
        return "" to uid
    }

    /** Web 端 `ajax/profile/info`：先账号身份，再游客身份。拿不到返回 null。 */
    private fun webProfileName(identity: String, uid: String): String? {
        if (identity.isNotEmpty()) {
            webUserName(identity, uid)?.let { return it }
        }
        // 账号 cookie 的域是 `.weibo.cn`，Web 端不一定认；游客 cookie 实测可用。
        GuestSession.cookie()?.let { guest ->
            webUserName(guest, uid)?.let { return it }
        }
        return null
    }

    /** m 站用户主页：`getIndex?type=uid`，昵称键名不固定，交给 [extractProfile] 容错。 */
    private fun mStationName(cookie: String, uid: String): String? = try {
        val url = "$BASE/api/container/getIndex?type=uid&value=${q(uid)}"
        client.newCall(request(url, cookie)).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            extractProfile(JSONObject(body)).name.ifEmpty { null }
        }
    } catch (e: Exception) {
        null
    }

    // ---------- 超话字段解析（纯函数，不触网，随 CI 做 JVM 单测） ----------

    private val LEVEL_DIGITS = Regex("\\d+")
    private val TOPIC_ID_RE = Regex("100808([0-9A-Za-z]+)")
    private val TOPIC_ID_SAFE = Regex("^[0-9A-Za-z]{1,64}$")

    /** `desc1`（形如 "Lv.7"、"LV 12"、"等级7"）→ 等级数字；无数字 → 0。 */
    fun parseTopicLevel(desc1: String): Int =
        LEVEL_DIGITS.find(desc1)?.value?.toIntOrNull()?.coerceIn(0, 999) ?: 0

    /** 等级分档，驱动徽章配色：0=未知(不展示) / 1=1-3 / 2=4-6 / 3=7-9 / 4=10+。 */
    fun topicLevelTier(level: Int): Int = when {
        level <= 0 -> 0
        level <= 3 -> 1
        level <= 6 -> 2
        level <= 9 -> 3
        else -> 4
    }

    /**
     * 从超话 scheme 里提超话 ID（`100808` 之后的 hash）。
     *
     * scheme 可能是 `sinaweibo://...?containerid=100808abc`，也可能是
     * `https://m.weibo.cn/p/100808abc/super_index`；百分号编码先解码再匹配。
     */
    fun extractTopicId(scheme: String): String {
        if (scheme.isEmpty()) return ""
        val decoded = try {
            URLDecoder.decode(scheme, "UTF-8")
        } catch (e: Exception) {
            scheme
        }
        return TOPIC_ID_RE.find(decoded)?.groupValues?.get(1) ?: ""
    }

    /** 详情接口的 containerid：`100808` + hash。hash 只允许字母数字，杜绝查询串注入。 */
    fun topicDetailContainerId(topicId: String): String {
        if (!topicId.matches(TOPIC_ID_SAFE)) throw WeiboException("非法超话 ID：$topicId")
        return "100808$topicId"
    }

    /** 列表接口一页 → 结构化超话。解析口径与旧实现一致（card_type=8 + buttons[0]）。 */
    fun parseTopicPage(payload: JSONObject): TopicPage {
        val ok = payload.opt("ok")?.toString() ?: "?"
        val msg = payload.optString("msg", ok)
        if (ok != "1") return TopicPage(ok = ok, msg = msg)
        val data = payload.optJSONObject("data") ?: return TopicPage(ok = ok, msg = msg)
        val sinceId = data.optJSONObject("cardlistInfo")?.optString("since_id", "") ?: ""
        val cards = data.optJSONArray("cards") ?: return TopicPage(sinceId = sinceId, ok = ok)
        val topics = ArrayList<Topic>()
        for (i in 0 until cards.length()) {
            val card = cards.optJSONObject(i) ?: continue
            val groups = card.optJSONArray("card_group")
            val items = if (groups != null) {
                (0 until groups.length()).mapNotNull { groups.optJSONObject(it) }
            } else {
                listOf(card)
            }
            for (item in items) {
                if (item.optString("card_type") != "8") continue
                val buttons = item.optJSONArray("buttons") ?: continue
                val name = item.optString("title_sub", "").trim()
                if (name.isEmpty()) continue
                val button = buttons.optJSONObject(0) ?: continue
                val buttonName = button.optString("name", "")
                val buttonScheme = button.optString("scheme", "")
                val done = buttonName in DONE_BUTTONS || buttonScheme.isEmpty()
                topics.add(
                    Topic(
                        name = name,
                        scheme = if (done) null else buttonScheme,
                        done = done,
                        topicId = extractTopicId(item.optString("scheme", "")),
                        level = parseTopicLevel(item.optString("desc1", "")),
                        desc2 = item.optString("desc2", "").trim(),
                        raw = item.toString(),
                    )
                )
            }
        }
        return TopicPage(topics, sinceId, ok, msg)
    }

    /** 超话主页接口 → 详情。字段全部容错，缺失只影响对应文案。 */
    fun parseTopicDetail(
        topicId: String,
        payload: JSONObject,
        now: Long = System.currentTimeMillis(),
    ): TopicDetail {
        val data = payload.optJSONObject("data") ?: JSONObject()
        val page = data.optJSONObject("pageInfo") ?: JSONObject()
        val stats = ArrayList<String>()
        val more = page.optJSONArray("desc_more")
        if (more != null) {
            for (i in 0 until more.length()) {
                val text = when (val el = more.opt(i)) {
                    null -> ""
                    // 少数版本是 {"name":"关注","value":"1.2万"}，多数是纯字符串
                    is JSONObject -> listOf(el.optString("name", ""), el.optString("value", ""))
                        .filter { it.isNotEmpty() }.joinToString(" ")
                    else -> el.toString().trim()
                }
                if (text.isNotEmpty()) stats.add(text)
            }
        }
        return TopicDetail(
            topicId = topicId,
            title = page.optString("title_top", "").trim(),
            intro = page.optString("desc", "").trim(),
            stats = stats,
            portraitText = page.optString("portrait_sub_text", "").trim(),
            oid = page.optString("oid", "").trim(),
            fetchedAt = now,
        )
    }

    /**
     * 签到响应：成功判定对齐面板（`weibo_client.run_account_checkin`）。
     *
     * `ok=1` / `result=1` / `code=100000` 之外，还要认「文案里写着成功」：
     * 微博对「今天已经签过」的场景常回 `ok=0` + `msg="今天已签到"`，
     * 只按 ok 判定会把它报成**失败** —— 用户看到的就是「明明签了却说签到失败」。
     */
    fun parseSignResponse(body: String): SignResult {
        val payload = try {
            JSONObject(body)
        } catch (e: Exception) {
            // 非 JSON（例如接口改成返回 HTML）：判失败，把前 120 字留给日志
            return SignResult(false, msg = body.trim().take(120))
        }
        val code = payload.opt("code")?.toString() ?: ""
        val data = payload.optJSONObject("data") ?: JSONObject()
        val msg = payload.optString("msg", "").trim()
        // 必须用 optInt 而不是 opt(...)?.toString()：opt 对值为 JSON null 的键返回
        // JSONObject.NULL（非 Kotlin null），前者的 elvis 链就断在那儿了 —— 外层 errno
        // 为 null、真实错误码在 data.errno 里（例如 100015）时会被读成 0，于是把
        // 「需要验证」判成签到成功。optInt 既能吃数字也能吃 "100015" 这种数字字符串。
        val errno = payload.optInt("errno", data.optInt("errno", 0))
        // 否定语要先看：「签到未成功」「领取失败」这类文案里同样含「成功」二字，
        // 单靠 SUCCESS_WORDS 的 contains 会把明确的失败判成成功 ——
        // 用户于是永远收不到失败提示，连续漏签也无感。
        val negative = NEGATIVE_WORDS.any { msg.contains(it) }
        val ok = !negative && errno == 0 && (
            parseLoginFlag(payload.opt("ok")) ||
                parseLoginFlag(payload.opt("result")) ||
                code == "100000" ||
                SUCCESS_WORDS.any { msg.contains(it) }
            )
        return SignResult(
            ok = ok,
            alertTitle = data.optString("alert_title", "").trim(),
            alertSubtitle = data.optString("alert_subtitle", "").trim(),
            msg = msg,
            errno = errno,
        )
    }

    // ---------- 超话接口 ----------

    /**
     * 关注超话列表（对齐 weibo_client.get_followed_topics 的解析与翻页）。
     *
     * [maxPages] 默认放宽到 20 页：超话页每页 10 条，旧的 6 页上限会把关注 60+
     * 超话的账号截断，导致「列表不全 / 状态漏报」。
     * [onPage] 每页解析完后回调已累计条数（页面上显示「已加载 N 个」）。
     */
    suspend fun listTopics(
        cookie: String,
        maxPages: Int = 20,
        onPage: (Int) -> Unit = {},
    ): List<Topic> {
        // 以超话 ID（缺失时退化为名称）为键：跨页去重，后页覆盖前页（签到状态以最新为准）
        val byKey = LinkedHashMap<String, Topic>()
        var sinceId = ""
        val seenPages = HashSet<String>()
        var page = 0
        while (page < maxPages) {
            page++
            var url = "$BASE/api/container/getIndex?containerid=100803_-_followsuper"
            if (sinceId.isNotEmpty()) url += "&since_id=$sinceId"
            // 页间间隔交给统一闸门：用 delay 而不是 Thread.sleep，切页/退出能立刻取消
            WeiboThrottle.await(WeiboThrottle.KEY_TOPIC_PAGE, TOPIC_PAGE_GAP_MS)
            val parsed = client.newCall(request(url, cookie)).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw httpFailure("超话列表", resp.code)
                }
                parseTopicPage(parseJson(resp.body?.string() ?: "{}", "超话列表"))
            }
            if (parsed.ok != "1") {
                if (parsed.ok == "-100") {
                    throw WeiboException("Cookie 已失效（微博要求重新登录）", identitySuspect = true)
                }
                if (byKey.isEmpty()) {
                    throw WeiboException("微博未返回超话列表：${parsed.msg}")
                }
                return byKey.values.toList()
            }
            parsed.topics.forEach { byKey[it.key] = it }
            onPage(byKey.size)
            sinceId = parsed.sinceId
            if (sinceId.isEmpty() || !seenPages.add(sinceId)) break
        }
        return byKey.values.toList()
    }

    /**
     * 超话主页详情（简介 / 关注数 / 帖子数 / 阅读数 / oid）。
     *
     * 只在用户点开某条超话时调用，并做 [DETAIL_MIN_GAP_MS] 节流：
     * 连点多个超话不会打出并发请求。
     *
     * 必须账号身份：实测 m 站 `100808` 在游客 cookie 下同样返回 `ok:-100`。
     */
    suspend fun fetchTopicDetail(cookie: String, topicId: String): TopicDetail {
        val url = "$BASE/api/container/getIndex?containerid=" + topicDetailContainerId(topicId)
        WeiboThrottle.await(WeiboThrottle.KEY_TOPIC_DETAIL, DETAIL_MIN_GAP_MS)
        client.newCall(request(url, cookie)).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw httpFailure("超话详情", resp.code)
            }
            val payload = parseJson(resp.body?.string() ?: "{}", "超话详情")
            val ok = payload.opt("ok")?.toString() ?: "?"
            if (ok != "1") {
                if (ok == "-100") {
                    throw WeiboException("Cookie 已失效（微博要求重新登录）", identitySuspect = true)
                }
                throw WeiboException("超话详情接口 ok=$ok ${payload.optString("msg", "")}".trim())
            }
            return parseTopicDetail(topicId, payload)
        }
    }

    /** 签到一个超话，返回结构化结果（成功时带「签到第 N 名 · 经验 +X」）。 */
    fun signDetailed(cookie: String, scheme: String, st: String?): SignResult {
        val first = signOnce(cookie, scheme, st)
        if (!first.needsVerifyRetry) return first
        // 验签失败（errno=100015）：st 过期了。重新探活拿一份新的 st（顺带收下换发的
        // Cookie）再原样请求一次 —— 与面板 run_account_checkin 的处理一致。
        // 这里必须 force：缓存里的那份探活结果很可能正是过期 st 的来源。
        // 探测失败就直接返回首次结果，把真实原因留给调用方展示。
        val fresh = try {
            verify(cookie, force = true)
        } catch (e: Exception) {
            return first
        }
        if (!fresh.login) return first
        return signOnce(fresh.cookie, scheme, fresh.st).copy(retried = true)
    }

    /** 单次签到请求（不含验签重试）。 */
    private fun signOnce(cookie: String, scheme: String, st: String?): SignResult {
        if (scheme.isEmpty()) throw WeiboException("缺少签到链接")
        var url = if (scheme.startsWith("http")) scheme else BASE + scheme
        url = weiboUrl(url)
        if (!st.isNullOrEmpty()) {
            url += (if (url.contains('?')) "&" else "?") + "st=" + st
        }
        client.newCall(request(url, cookie)).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw WeiboException("HTTP ${resp.code}" + httpHint(resp.code))
            }
            return parseSignResponse(resp.body?.string() ?: "")
        }
    }

    // 兼容旧调用点：只关心成功与否时用这个。
    fun sign(cookie: String, scheme: String, st: String?): Boolean =
        signDetailed(cookie, scheme, st).ok

    // ========================= 原图抓取（v0.2.0，口径对齐抓取流程交接文档） =========================

    /** 列表接口的一页微博（只保留抓图需要的字段）。 */
    data class RawPost(
        val id: String,
        val bid: String,
        val createdAt: String,      // 原始串，如 "Thu Oct 22 15:27:12 +0800 2026"
        val pinned: Boolean,
        val isRetweet: Boolean,
        val picNum: Int,            // 不可信：Live 会重复计数，仅当粗筛用
        val text: String = "",      // 去 HTML 的正文摘要（列表选择器展示用）
        /**
         * 列表卡片自带的缩略图 URL（orj360 尺寸，已 https 化并过白名单）。
         * 超 9 图帖列表会截断到 9 张，这里的清单只做**预览**；真正的张数与
         * 图身份以详情里的 pic_ids 为准（选图页进入后再拉）。
         */
        val thumbs: List<String> = emptyList(),
    )

    private val MONTHS = mapOf(
        "Jan" to "01", "Feb" to "02", "Mar" to "03", "Apr" to "04",
        "May" to "05", "Jun" to "06", "Jul" to "07", "Aug" to "08",
        "Sep" to "09", "Oct" to "10", "Nov" to "11", "Dec" to "12",
    )

    private val TIME_RE = Regex(
        "^([A-Za-z]{3})\\s+([A-Za-z]{3})\\s+(\\d{1,2})\\s+([\\d:]{8})\\s+([+-]\\d{4})\\s+(\\d{4})$"
    )

    /** 微博时间串 → (yyyy-MM-dd, HH:mm:ss, 年)。解析失败返回 null（老帖边界判定依赖年份）。 */
    fun parseWeiboTime(raw: String): Triple<String, String, Int>? {
        val m = TIME_RE.find(raw.trim()) ?: return null
        val month = MONTHS[m.groupValues[2]] ?: return null
        val day = m.groupValues[3].padStart(2, '0')
        return Triple(
            "${m.groupValues[6]}-$month-$day",
            m.groupValues[4],
            m.groupValues[6].toInt(),
        )
    }

    /**
     * 用户微博列表（每页 10 条）。返回 (lifetime 总数, 本页帖列表)。
     *
     * **必须账号身份**：实测游客 cookie 调 m 站 `107603` 与 Web 端 `ajax/statuses/mymblog`
     * 分别返回 `ok:-100` 与 403「请登录后使用」，这条链路没有免登录替代。抓图必须登录，
     * 与签到共用同一份账号凭据 —— 这是现状，不是可以省掉的依赖。
     */
    fun fetchUserPosts(cookie: String, uid: String, page: Int): Pair<Int, List<RawPost>> {
        val url = "$BASE/api/container/getIndex?containerid=107603${q(uid)}&page=$page"
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "$BASE/u/$uid")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("MWeibo-Pwa", "1")
            .header("Cookie", cookie)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw httpFailure("列表", resp.code)
            val payload = parseJson(resp.body?.string() ?: "{}", "列表")
            val ok = payload.optInt("ok", 0)
            if (ok != 1) {
                throw WeiboException("列表接口 ok=${payload.opt("ok")}", identitySuspect = ok == -100)
            }
            val data = payload.optJSONObject("data") ?: JSONObject()
            val total = data.optJSONObject("cardlistInfo")?.optInt("total", 0) ?: 0
            val posts = ArrayList<RawPost>()
            val cards = data.optJSONArray("cards") ?: return total to posts
            for (i in 0 until cards.length()) {
                val card = cards.optJSONObject(i) ?: continue
                if (card.optInt("card_type") != 9) continue   // 156 等运营位跳过
                val mb = card.optJSONObject("mblog") ?: continue
                // 超 9 图帖的时间线卡片会把 pics / pic_num 截断（真机见过 9/18 与 0/18 两种形态）。
                // 取三处最大值当粗筛信号：只要有一处透出「有图」，CrawlPlanner 就不会把这条
                // 当成纯文字帖跳过 —— 真正的张数以详情里的 pic_ids 为准。
                posts.add(
                    RawPost(
                        id = mb.optString("id", ""),
                        bid = mb.optString("bid", ""),
                        createdAt = mb.optString("created_at", ""),
                        pinned = mb.optInt("mblogtype", 0) == 1,
                        isRetweet = mb.optJSONObject("retweeted_status") != null,
                        picNum = cardPicNum(mb),
                        text = summarize(mb.optString("text", "")),
                        thumbs = parseThumbUrls(mb),
                    )
                )
            }
            return total to posts
        }
    }

    /**
     * 列表卡片 `mblog.pics[]` → 缩略图 URL 清单（浏览页九宫格用）。
     *
     * - url 通常是 `https://wx{n}.sinaimg.cn/orj360/<pid>.jpg`（GIF 帖保留 `.gif` 后缀）；
     * - 个别卡片只有 pid 没有 url，按 pid 拼一份兜底；
     * - http 的强制升级 https，域名不在白名单的整张丢弃 —— 宁可少显示一张，
     *   不让一张图把「明文流量」或「任意域」的口子撕开。
     */
    internal fun parseThumbUrls(mblog: JSONObject): List<String> {
        val out = ArrayList<String>()
        val arr = mblog.optJSONArray("pics") ?: return out
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val raw = p.optString("url", "").trim()
                .ifEmpty { thumbnailUrl(p.optString("pid", "")) }
            sanitizeThumbUrl(raw)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 缩略图 URL 规范化（纯函数，JVM 可测）：
     * `http://` 升级 `https://`，过微博域名白名单；不合格返回 null（调用方丢弃）。
     */
    internal fun sanitizeThumbUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val https = if (t.startsWith("http://", ignoreCase = true)) {
            "https://" + t.substring("http://".length)
        } else {
            t
        }
        return try {
            weiboUrl(https)
            https
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 时间线卡片上的「这条帖有几张图」粗筛值。
     *
     * 超 9 图帖的卡片会截断：`pic_num`、`pics`、`pic_ids` 三处都可能只给一部分
     * （真机见过 9/18 与 0/18 两种形态）。取三者最大值，只要任一处透出「有图」，
     * 抓取规划就不会把这条当纯文字帖跳过；真正的张数以详情里的 `pic_ids` 为准。
     */
    internal fun cardPicNum(mblog: JSONObject): Int = maxOf(
        mblog.optInt("pic_num", 0),
        mblog.optJSONArray("pics")?.length() ?: 0,
        mblog.optJSONArray("pic_ids")?.length() ?: 0,
    )

    /** 正文去 HTML 标签与常见实体，截断到 90 字（列表选择器展示口径）。 */
    fun summarize(raw: String): String {
        var text = raw.replace(Regex("<[^>]+>"), "")
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
        text = text.replace(Regex("\\s+"), " ").trim()
        return if (text.length > 90) text.substring(0, 90) + "…" else text
    }

    /** 从用户输入解析单条微博标识（bid/mid）。支持链接与纯 ID 两种。 */
    fun parseBidFromInput(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        Regex("/detail/([A-Za-z0-9]+)").find(t)?.let { return it.groupValues[1] }
        Regex("/status/([A-Za-z0-9]+)").find(t)?.let { return it.groupValues[1] }
        Regex("weibo\\.com/\\d+/([A-Za-z0-9]+)").find(t)?.let { return it.groupValues[1] }
        if (t.matches(Regex("^[A-Za-z0-9]{9}$"))) return t       // 9 位 base62 bid
        if (t.matches(Regex("^\\d{9,}$"))) return t              // 数字 mid 直接可用
        return ""
    }

    /**
     * 微博详情（含完整 pic_ids 与帖级 live_photo，列表接口拿不到）。
     *
     * **必须账号身份**。Web 端 `ajax/statuses/show` 游客可用，但返回的是扁平 status 对象：
     * 没有 `pics` 的 Live 配对信息、也没有帖级 `live_photo`，切过去会让 Live 动图退化成静图，
     * 所以这里坚持 m 站口径。
     */
    fun fetchPostDetail(cookie: String, bid: String): JSONObject {
        val url = "$BASE/statuses/show?id=${q(bid)}"
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "$BASE/detail/$bid")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("MWeibo-Pwa", "1")
            .header("Cookie", cookie)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw httpFailure("详情", resp.code)
            val payload = parseJson(resp.body?.string() ?: "{}", "详情")
            val ok = payload.optInt("ok", 0)
            if (ok != 1) {
                throw WeiboException("详情接口 ok=${payload.opt("ok")}", identitySuspect = ok == -100)
            }
            return payload.optJSONObject("data") ?: JSONObject()
        }
    }

    /**
     * 任意 uid 的昵称（抓图目标显示用）。
     *
     * 两级：
     * 1. **游客身份走 Web 端 `ajax/profile/info`** —— 实测免登录可用，不消耗账号频次；
     *    添加抓图目标时即使一个账号都没有，也能把博主名显示出来。
     * 2. 游客不可用时回落 m 站 `type=uid`（要账号）。
     *
     * 失败一律返回空串：调用方只用来做展示兜底，不该因为它中断抓取。
     */
    fun fetchUserName(cookie: String?, uid: String): String {
        // 顺序与 fetchProfile 一致：Web 端（游客即可）稳定，m 站只作兜底
        val acc = cookie?.trim().orEmpty()
        if (acc.isNotEmpty()) webUserName(acc, uid)?.let { return it }
        GuestSession.cookie()?.let { guest ->
            webUserName(guest, uid)?.let { return it }
        }
        if (acc.isEmpty()) return ""
        return mStationName(acc, uid) ?: ""
    }

    /** Web 端用户信息（游客可用）。返回 null 表示「这条路拿不到」，交给调用方回落。 */
    private fun webUserName(identity: String, uid: String): String? = try {
        val req = Request.Builder().url("$WEB_BASE/ajax/profile/info?uid=${q(uid)}")
            .header("User-Agent", WEB_UA)
            .header("Referer", "$WEB_BASE/u/$uid")
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Cookie", identity)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val payload = parseJson(resp.body?.string() ?: "{}", "用户信息")
            if (payload.optInt("ok", 0) != 1) return null
            // 正常是 data.user.screen_name；个别版本会包成 data.userInfo，交给容错解析
            extractProfile(payload).name.ifEmpty { null }
        }
    } catch (e: Exception) {
        null
    }

    /** 原图 URL：/large/ 才是原始分辨率（接口给的 mw2000 是缩图）。host 按 pid 轮转。 */
    fun originalImageUrl(pid: String): String {
        val crc = java.util.zip.CRC32().apply { update(pid.toByteArray()) }.value.toInt()
        val host = 1 + (Math.floorMod(crc, 4))
        return "https://wx$host.sinaimg.cn/large/$pid.jpg"
    }

    /**
     * 缩略图 URL：与 [originalImageUrl] 同一套 host 轮转，尺寸段可换
     * （`orj360` 网格预览 / `mw690` 更清晰 / `large` 原图）。仅在列表卡片
     * 只给 pid 不给 url 时兜底使用 —— 有 url 时永远用接口给的（后缀才对得上 GIF）。
     */
    fun thumbnailUrl(pid: String, size: String = "orj360"): String {
        if (pid.isEmpty()) return ""
        val crc = java.util.zip.CRC32().apply { update(pid.toByteArray()) }.value.toInt()
        val host = 1 + (Math.floorMod(crc, 4))
        return "https://wx$host.sinaimg.cn/$size/$pid.jpg"
    }

    /** 从用户输入解析 UID：纯数字 / 含 /u/ 或 profile/ 的链接 / 个性域名（302 跳转）。 */
    fun resolveUid(input: String): String {
        val text = input.trim()
        if (text.matches(Regex("^\\d+$"))) return text
        Regex("/u/(\\d+)").find(text)?.let { return it.groupValues[1] }
        Regex("profile/(\\d+)").find(text)?.let { return it.groupValues[1] }
        Regex("containerid=107603(\\d+)").find(text)?.let { return it.groupValues[1] }
        // 个性域名：m.weibo.cn/n/<name> 会 302 到 /u/<uid>
        val domain = Regex("([A-Za-z][A-Za-z0-9_-]*)/?$").find(text.trimEnd('/'))
            ?.groupValues?.get(1) ?: return ""
        return try {
            client.newCall(
                Request.Builder().url("$BASE/n/$domain")
                    .header("User-Agent", UA).build()
            ).execute().use { resp ->
                resp.request.url.toString().let { finalUrl ->
                    Regex("/u/(\\d+)").find(finalUrl)?.groupValues?.get(1) ?: ""
                }
            }
        } catch (e: Exception) {
            ""
        }
    }
}
