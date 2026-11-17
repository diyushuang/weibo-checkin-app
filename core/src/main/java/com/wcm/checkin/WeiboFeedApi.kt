package com.wcm.checkin

import org.json.JSONObject

/**
 * 超话帖子流：取「互动目标」（评论/转发/点赞要作用在别人发的帖子上）。
 *
 * 端点与字段来自实测：`GET https://weibo.com/ajax_proxy/chaohua/page?flowId=<tid>`，
 * **必须带 `X-Requested-With`**，否则返回业务错误而不是数据。
 *
 * 实测该接口单次返回 8–10 条，其中相当比例 `comments_count == 0`——
 * 这正是「0 互动帖首评 +2 分」的目标，所以 [pickTargets] 默认优先挑它们。
 */
object WeiboFeedApi {

    const val CHAOHUA_PAGE = "https://weibo.com/ajax_proxy/chaohua/page"

    /** 一条可作为互动目标的帖子。 */
    data class FeedPost(
        val mid: String,
        val uid: String = "",
        val text: String = "",
        val comments: Int = 0,
        val reposts: Int = 0,
        val attitudes: Int = 0,
        /** 纯转发帖（原帖是别人的转发）——互动收益低，默认跳过。 */
        val isRetweet: Boolean = false,
    ) {
        /** 完全无互动。首评这种帖子额外 +2 分。 */
        val isCold: Boolean get() = comments == 0 && reposts == 0 && attitudes == 0
    }

    private val TAG_RE = Regex("<[^>]+>")

    /** 解析帖子流响应。字段缺失的条目直接丢弃，不让脏数据打断整轮。 */
    fun parseFeed(body: String): List<FeedPost> {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val items = json.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<FeedPost>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("category") != "feed") continue
            val data = item.optJSONObject("data") ?: continue
            val mid = data.optString("mid", "").ifEmpty { data.optString("id", "") }
            if (mid.isEmpty()) continue
            out.add(
                FeedPost(
                    mid = mid,
                    uid = data.optJSONObject("user")?.optString("id", "") ?: "",
                    text = TAG_RE.replace(data.optString("text", ""), "").trim(),
                    comments = data.optInt("comments_count", 0),
                    reposts = data.optInt("reposts_count", 0),
                    attitudes = data.optInt("attitudes_count", 0),
                    isRetweet = data.optJSONObject("retweeted_status") != null,
                )
            )
        }
        return out
    }

    /**
     * 从候选帖里挑出这一轮要互动的目标。
     *
     * 排除：自己的帖子（评论/转发自己的不得分）、纯转发帖、本轮已用过的 mid。
     * 排序：[preferFirstComment] 为真时把「完全无互动」的帖子排前面。
     */
    fun pickTargets(
        posts: List<FeedPost>,
        selfUid: String,
        usedMids: Set<String> = emptySet(),
        preferFirstComment: Boolean = true,
        limit: Int = 20,
    ): List<FeedPost> {
        if (limit <= 0) return emptyList()
        val filtered = posts.filter { p ->
            p.mid !in usedMids &&
                !p.isRetweet &&
                (selfUid.isEmpty() || p.uid != selfUid)
        }
        val ordered = if (!preferFirstComment) {
            filtered
        } else {
            // 稳定分区：无互动的保持原顺序排前面，其余保持原顺序跟在后面
            filtered.partition { it.isCold }.let { (cold, rest) -> cold + rest }
        }
        return ordered.take(limit)
    }

    /**
     * 拉一次超话帖子流。
     *
     * 只读接口，失败直接抛 [WeiboHttp.WeiboException] 交给上层分类；
     * `pages > 1` 目前不生效（实测分页参数未确认，宁可少拉也不猜参数）。
     */
    suspend fun listTopicFeed(
        session: WeiboHttp.WeiboSession,
        topicId: String,
    ): List<FeedPost> {
        // flowId 要完整 containerid（`100808…`），只给 hash 会拿到空列表
        val tid = EngageSchedule.topicContainerId(topicId)
        if (!EngageSchedule.isValidTopicId(tid)) {
            throw WeiboHttp.WeiboException("超话 ID 非法：$topicId")
        }
        val url = "$CHAOHUA_PAGE?flowId=$tid"
        val xsrf = WeiboHttp.xsrfOf(session.cookie)

        // Referer 先用超话页：浏览器里这个请求就是从超话页发出的，最贴近真实调用。
        // 真机实测缺 Origin/Referer/XSRF 三者时该接口直接 403，所以再留一次主页 Referer
        // 的回退——不同账号的权限/灰度不同，多试一次比让整轮互动全灭划算。
        var referer = "https://weibo.com/p/$tid"
        var resp = WeiboHttp.client
            .newCall(WeiboHttp.ajaxRequest(url, session.cookie, referer, xsrf))
            .execute()
        if (resp.code == 403) {
            resp.close()
            referer = "https://weibo.com/"
            resp = WeiboHttp.client
                .newCall(WeiboHttp.ajaxRequest(url, session.cookie, referer, xsrf))
                .execute()
        }

        resp.use { r ->
            session.absorb(r)
            if (!r.isSuccessful) {
                // 432/429 是风控，不是身份问题；由上层按 identitySuspect 处理
                throw WeiboHttp.httpFailure("超话帖子流（referer=$referer, xsrf=${xsrf.isNotEmpty()}）", r.code)
            }
            val body = r.body?.string() ?: ""
            val parsed = parseFeed(body)
            if (parsed.isEmpty() && !body.trimStart().startsWith("{")) {
                throw WeiboHttp.WeiboException(
                    "超话帖子流响应非 JSON（前 60 字：${WeiboHttp.safeBodyExcerpt(body, 60)}）",
                    identitySuspect = true,
                    loginWall = WeiboHttp.looksLikeLoginWall(body),
                )
            }
            return parsed
        }
    }
}
