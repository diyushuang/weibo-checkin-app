package com.wcm.checkin

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * m.weibo.cn 客户端：与面板 app/weibo_client.py 同一套接口与判定逻辑的 Kotlin 移植。
 *
 * - verify: /api/config 探活并回收 Set-Cookie（登录态轮换的入口）
 * - listTopics: 关注超话列表（分页 + 已签检测）
 * - sign: 超话签到（执行按钮 scheme + st）
 * 所有出站 URL 只允许微博自家域名（对齐服务端 validate_weibo_url 语义）。
 */
object WeiboApi {

    class WeiboException(message: String) : Exception(message)

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
    ) {
        /** 「签到第 12 名 · 经验 +8」这样的单行摘要，无内容时为空串。 */
        val summary: String
            get() = listOf(alertTitle, alertSubtitle)
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
    }

    /** 超话列表接口的一页解析结果。 */
    data class TopicPage(
        val topics: List<Topic> = emptyList(),
        val sinceId: String = "",
        val ok: String = "",
        val msg: String = "",
    )

    data class Verify(val login: Boolean, val st: String?, val cookie: String)

    private const val BASE = "https://m.weibo.cn"
    private const val UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
    private val WEIBO_HOSTS = listOf("weibo.cn", "weibo.com", "weibocdn.com", "sinaimg.cn")
    private val DONE_BUTTONS = setOf("已签", "已簽", "已签到", "已簽到")

    /** 连续两次超话详情请求的最小间隔：详情只在用户点开时触发，仍要防连点打卡。 */
    private const val DETAIL_MIN_GAP_MS = 1200L
    private val detailGate = Any()
    private var lastDetailAt = 0L

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
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/plain, */*")
            .header("Cookie", cookie)
            .build()

    /** 出站白名单：只允许微博自家域，防 cookie 被发往任意外部主机。 */
    /** 是否是微博自家域名（含子域）。WebView 导航白名单复用同一份口径。 */
    fun isWeiboHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h.isNotEmpty() && WEIBO_HOSTS.any { h == it || h.endsWith(".$it") }
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

    // ---------- 接口 ----------

    /** 探活 + 轮换：返回是否登录、st 与合并轮换后的完整 cookie。 */
    fun verify(cookie: String): Verify {
        val jar = parseCookieString(cookie)
        client.newCall(request("$BASE/api/config", cookie)).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw WeiboException("探活请求失败：HTTP ${resp.code}")
            }
            mergeSetCookies(jar, resp)
            val payload = JSONObject(resp.body?.string() ?: "{}")
            val data = payload.optJSONObject("data") ?: JSONObject()
            val stRaw = data.optString("st", "")
            return Verify(
                login = data.optBoolean("login", false),
                st = stRaw.ifEmpty { null },
                cookie = cookieToString(jar),
            )
        }
    }

    /** 取当前账号 uid 与昵称（登录成功后调用）。 */
    fun fetchProfile(cookie: String): Pair<String, String> {
        try {
            client.newCall(request("$BASE/api/config", cookie)).execute().use { resp ->
                if (!resp.isSuccessful) return "" to ""
                val data = JSONObject(resp.body?.string() ?: "{}").optJSONObject("data")
                    ?: return "" to ""
                val uid = (data.optString("uid", "").ifEmpty { data.optString("user_id", "") }).trim()
                var name = data.optString("screen_name", "")
                if (uid.isNotEmpty() && name.isEmpty()) {
                    client.newCall(
                        request("$BASE/api/container/getIndex?type=uid&value=$uid", cookie)
                    ).execute().use { r2 ->
                        if (r2.isSuccessful) {
                            val p = JSONObject(r2.body?.string() ?: "{}")
                                .optJSONObject("data")?.optJSONObject("userinfo") ?: return "" to uid
                            name = p.optString("screen_name", "")
                        }
                    }
                }
                return name.trim() to uid
            }
        } catch (e: Exception) {
            return "" to ""
        }
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

    /** 签到响应：兼容 `ok=1` / `result=1` / `code=100000` 三种成功口径。 */
    fun parseSignResponse(body: String): SignResult {
        val payload = try {
            JSONObject(body)
        } catch (e: Exception) {
            // 非 JSON（例如接口改成返回 HTML）：判失败，把前 120 字留给日志
            return SignResult(false, msg = body.trim().take(120))
        }
        val code = payload.opt("code")?.toString() ?: ""
        val ok = payload.optInt("ok", 0) == 1 ||
            payload.optInt("result", 0) == 1 ||
            code == "100000"
        val data = payload.optJSONObject("data") ?: JSONObject()
        return SignResult(
            ok = ok,
            alertTitle = data.optString("alert_title", "").trim(),
            alertSubtitle = data.optString("alert_subtitle", "").trim(),
            msg = payload.optString("msg", "").trim(),
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
    fun listTopics(
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
            val parsed = client.newCall(request(url, cookie)).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw WeiboException("超话列表请求失败：HTTP ${resp.code}")
                }
                parseTopicPage(JSONObject(resp.body?.string() ?: "{}"))
            }
            if (parsed.ok != "1") {
                if (parsed.ok == "-100") {
                    throw WeiboException("Cookie 已失效（微博要求重新登录）")
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
            Thread.sleep(500)
        }
        return byKey.values.toList()
    }

    /**
     * 超话主页详情（简介 / 关注数 / 帖子数 / 阅读数 / oid）。
     *
     * 只在用户点开某条超话时调用，并做 [DETAIL_MIN_GAP_MS] 节流：
     * 连点多个超话不会打出并发请求。
     */
    fun fetchTopicDetail(cookie: String, topicId: String): TopicDetail {
        val url = "$BASE/api/container/getIndex?containerid=" + topicDetailContainerId(topicId)
        throttleDetail()
        client.newCall(request(url, cookie)).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw WeiboException("超话详情请求失败：HTTP ${resp.code}")
            }
            val payload = JSONObject(resp.body?.string() ?: "{}")
            val ok = payload.opt("ok")?.toString() ?: "?"
            if (ok != "1") {
                if (ok == "-100") throw WeiboException("Cookie 已失效（微博要求重新登录）")
                throw WeiboException("超话详情接口 ok=$ok ${payload.optString("msg", "")}".trim())
            }
            return parseTopicDetail(topicId, payload)
        }
    }

    private fun throttleDetail() {
        synchronized(detailGate) {
            val wait = DETAIL_MIN_GAP_MS - (System.currentTimeMillis() - lastDetailAt)
            if (wait > 0) Thread.sleep(wait)
            lastDetailAt = System.currentTimeMillis()
        }
    }

    /** 签到一个超话，返回结构化结果（成功时带「签到第 N 名 · 经验 +X」）。 */
    fun signDetailed(cookie: String, scheme: String, st: String?): SignResult {
        if (scheme.isEmpty()) throw WeiboException("缺少签到链接")
        var url = if (scheme.startsWith("http")) scheme else BASE + scheme
        url = weiboUrl(url)
        if (!st.isNullOrEmpty()) {
            url += (if (url.contains('?')) "&" else "?") + "st=" + st
        }
        client.newCall(request(url, cookie)).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw WeiboException("HTTP ${resp.code}")
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

    /** 用户微博列表（每页 10 条）。返回 (lifetime 总数, 本页帖列表)。 */
    fun fetchUserPosts(cookie: String, uid: String, page: Int): Pair<Int, List<RawPost>> {
        val url = "$BASE/api/container/getIndex?containerid=107603$uid&page=$page"
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "$BASE/u/$uid")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("MWeibo-Pwa", "1")
            .header("Cookie", cookie)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw WeiboException("列表请求失败：HTTP ${resp.code}")
            val payload = JSONObject(resp.body?.string() ?: "{}")
            if (payload.optInt("ok", 0) != 1) {
                throw WeiboException("列表接口 ok=${payload.opt("ok")}")
            }
            val data = payload.optJSONObject("data") ?: JSONObject()
            val total = data.optJSONObject("cardlistInfo")?.optInt("total", 0) ?: 0
            val posts = ArrayList<RawPost>()
            val cards = data.optJSONArray("cards") ?: return total to posts
            for (i in 0 until cards.length()) {
                val card = cards.optJSONObject(i) ?: continue
                if (card.optInt("card_type") != 9) continue   // 156 等运营位跳过
                val mb = card.optJSONObject("mblog") ?: continue
                posts.add(
                    RawPost(
                        id = mb.optString("id", ""),
                        bid = mb.optString("bid", ""),
                        createdAt = mb.optString("created_at", ""),
                        pinned = mb.optInt("mblogtype", 0) == 1,
                        isRetweet = mb.optJSONObject("retweeted_status") != null,
                        picNum = mb.optInt("pic_num", 0),
                        text = summarize(mb.optString("text", "")),
                    )
                )
            }
            return total to posts
        }
    }

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

    /** 微博详情（含完整 pic_ids 与帖级 live_photo，列表接口拿不到）。 */
    fun fetchPostDetail(cookie: String, bid: String): JSONObject {
        val url = "$BASE/statuses/show?id=$bid"
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "$BASE/detail/$bid")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("MWeibo-Pwa", "1")
            .header("Cookie", cookie)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw WeiboException("详情请求失败：HTTP ${resp.code}")
            val payload = JSONObject(resp.body?.string() ?: "{}")
            if (payload.optInt("ok", 0) != 1) {
                throw WeiboException("详情接口 ok=${payload.opt("ok")}")
            }
            return payload.optJSONObject("data") ?: JSONObject()
        }
    }

    /** 任意 uid 的昵称（抓图目标显示用）。 */
    fun fetchUserName(cookie: String, uid: String): String {
        return try {
            val url = "$BASE/api/container/getIndex?type=uid&value=$uid"
            client.newCall(request(url, cookie)).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                JSONObject(resp.body?.string() ?: "{}")
                    .optJSONObject("data")?.optJSONObject("userinfo")
                    ?.optString("screen_name", "") ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    /** 原图 URL：/large/ 才是原始分辨率（接口给的 mw2000 是缩图）。host 按 pid 轮转。 */
    fun originalImageUrl(pid: String): String {
        val crc = java.util.zip.CRC32().apply { update(pid.toByteArray()) }.value.toInt()
        val host = 1 + (Math.floorMod(crc, 4))
        return "https://wx$host.sinaimg.cn/large/$pid.jpg"
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
