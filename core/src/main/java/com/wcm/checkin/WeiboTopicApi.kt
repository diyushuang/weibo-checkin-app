package com.wcm.checkin

import org.json.JSONObject
import java.net.URLDecoder

/**
 * 超话接口与解析：关注列表、详情、签到（含验签失败重试）。
 *
 * 从原 `WeiboApi` 拆出；纯解析函数保持无 Android 依赖，可继续 JVM 单测。
 */
object WeiboTopicApi {

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
        if (!topicId.matches(TOPIC_ID_SAFE)) throw WeiboHttp.WeiboException("非法超话 ID：$topicId")
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
            WeiboAuthApi.parseLoginFlag(payload.opt("ok")) ||
                WeiboAuthApi.parseLoginFlag(payload.opt("result")) ||
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
    ): List<Topic> = listTopics(WeiboHttp.WeiboSession.of(cookie), maxPages, onPage)

    /**
     * 会话版：同一个 [WeiboHttp.WeiboSession] 贯穿整批请求，
     * 响应里的 `Set-Cookie` 会被逐条收下（旧实现只有探活会收，业务请求全丢）。
     *
     * 调用方在整批结束后把 `session.cookie` 写回账号即可完成"换发回写"。
     */
    suspend fun listTopics(
        session: WeiboHttp.WeiboSession,
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
            var url = "${WeiboHttp.BASE}/api/container/getIndex?containerid=100803_-_followsuper"
            if (sinceId.isNotEmpty()) url += "&since_id=$sinceId"
            // 页间间隔交给统一闸门：用 delay 而不是 Thread.sleep，切页/退出能立刻取消
            WeiboThrottle.await(WeiboThrottle.KEY_TOPIC_PAGE, TOPIC_PAGE_GAP_MS)
            val parsed = WeiboHttp.call(url, session).use { resp ->
                if (!resp.isSuccessful) {
                    throw WeiboHttp.httpFailure("超话列表", resp.code)
                }
                parseTopicPage(WeiboHttp.parseJson(resp.body?.string() ?: "{}", "超话列表"))
            }
            if (parsed.ok != "1") {
                if (parsed.ok == "-100") {
                    throw WeiboHttp.WeiboException("Cookie 已失效（微博要求重新登录）", identitySuspect = true)
                }
                if (byKey.isEmpty()) {
                    throw WeiboHttp.WeiboException("微博未返回超话列表：${parsed.msg}")
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
        val url = "${WeiboHttp.BASE}/api/container/getIndex?containerid=" +
            topicDetailContainerId(topicId)
        WeiboThrottle.await(WeiboThrottle.KEY_TOPIC_DETAIL, DETAIL_MIN_GAP_MS)
        WeiboHttp.client.newCall(WeiboHttp.request(url, cookie)).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw WeiboHttp.httpFailure("超话详情", resp.code)
            }
            val payload = WeiboHttp.parseJson(resp.body?.string() ?: "{}", "超话详情")
            val ok = payload.opt("ok")?.toString() ?: "?"
            if (ok != "1") {
                if (ok == "-100") {
                    throw WeiboHttp.WeiboException("Cookie 已失效（微博要求重新登录）", identitySuspect = true)
                }
                throw WeiboHttp.WeiboException("超话详情接口 ok=$ok ${payload.optString("msg", "")}".trim())
            }
            return parseTopicDetail(topicId, payload)
        }
    }

    /** 签到一个超话，返回结构化结果（成功时带「签到第 N 名 · 经验 +X」）。 */
    fun signDetailed(cookie: String, scheme: String, st: String?): SignResult =
        signDetailed(WeiboHttp.WeiboSession.of(cookie), scheme, st)

    /**
     * 会话版签到：签到响应也可能带 `Set-Cookie`，用会话接住能少丢一次换发。
     *
     * 验签失败（`errno=100015`）时的重试**必须 `force` 探活**：缓存里那份结果很可能
     * 正是过期 `st` 的来源。重试成功后把探活返回的合并串并回会话。
     */
    fun signDetailed(session: WeiboHttp.WeiboSession, scheme: String, st: String?): SignResult {
        val first = signOnce(session, scheme, st)
        if (!first.needsVerifyRetry) return first
        val fresh = try {
            WeiboAuthApi.verify(session.cookie, force = true)
        } catch (e: Exception) {
            return first
        }
        if (!fresh.login) return first
        session.merge(fresh.cookie)
        return signOnce(session, scheme, fresh.st).copy(retried = true)
    }

    /** 单次签到请求（不含验签重试）。 */
    private fun signOnce(session: WeiboHttp.WeiboSession, scheme: String, st: String?): SignResult {
        if (scheme.isEmpty()) throw WeiboHttp.WeiboException("缺少签到链接")
        var url = if (scheme.startsWith("http")) scheme else WeiboHttp.BASE + scheme
        url = WeiboHttp.weiboUrl(url)
        if (!st.isNullOrEmpty()) {
            url += (if (url.contains('?')) "&" else "?") + "st=" + st
        }
        WeiboHttp.call(url, session).use { resp ->
            if (!resp.isSuccessful) {
                throw WeiboHttp.WeiboException("HTTP ${resp.code}" + WeiboHttp.httpHint(resp.code))
            }
            return parseSignResponse(resp.body?.string() ?: "")
        }
    }

    // 兼容旧调用点：只关心成功与否时用这个。
    fun sign(cookie: String, scheme: String, st: String?): Boolean =
        signDetailed(cookie, scheme, st).ok

    /** 会话版便捷入口。 */
    fun sign(session: WeiboHttp.WeiboSession, scheme: String, st: String?): Boolean =
        signDetailed(session, scheme, st).ok
}
