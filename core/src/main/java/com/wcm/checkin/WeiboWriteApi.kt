package com.wcm.checkin

import org.json.JSONObject

/**
 * 超话互动的写接口客户端（超话发帖 / 评论 / 转发 / 点赞 / 删除）。
 *
 * 端点与参数全部来自 2026-11-16 的登录态实测，见
 * `docs/research/weibo-write-endpoints.md`。要点：
 *
 * - 超话归属靠 `topic_id=1022:<tid>`，参数名是 `content` 不是 `text`；
 * - 真实端点名是 `setLike` / `normal_repost`，公开资料里的 `like` / `repost` 是错的；
 * - `fp`（浏览器指纹）**可选**，实测省略可用——否则 Android 端根本做不了；
 * - `X-XSRF-TOKEN` 必需。
 *
 * 这里只做「构造请求 + 解析结果」，不重试、不节流、不记账——那些是
 * `:domain` 的职责。POST **绝不自动重试**：发帖重放会产生重复内容。
 */
object WeiboWriteApi {

    private const val BASE = "https://weibo.com"

    const val POST_STATUS = "$BASE/ajax/statuses/update"
    const val COMMENT = "$BASE/ajax/comments/create"
    const val REPOST = "$BASE/ajax/statuses/normal_repost"
    const val LIKE = "$BASE/ajax/statuses/setLike"
    const val DESTROY = "$BASE/ajax/statuses/destroy"

    /** 发帖时超话归属参数前缀。实测必须是 `1022:<tid>`。 */
    private const val TOPIC_PREFIX = "1022:"

    /** 一次写操作的结果。[kind] 为 [EngageErrorKind.NONE] 表示成功。 */
    data class WriteResult(
        val kind: EngageErrorKind = EngageErrorKind.NONE,
        /** 成功时返回的微博 mid（发帖/转发才有，评论/点赞为空）。 */
        val remoteId: String = "",
        /** 脱敏后的响应摘要，可直接进运行记录。 */
        val message: String = "",
    ) {
        /** 已经达成目的（含「本来就已经做过」）。 */
        val ok: Boolean get() = kind == EngageErrorKind.NONE || kind == EngageErrorKind.DUPLICATE_ACTION

        /**
         * 这次请求是否可能已经生效但客户端不知道。
         *
         * 发帖场景用它决定进不进 `UNCERTAIN`：网络失败/5xx 时帖子可能已经发出去了。
         */
        val outcomeUnknown: Boolean get() = kind.outcomeUnknown
    }

    /** 超话主页 Referer（发帖用）。 */
    fun topicReferer(topicId: String): String = "$BASE/page/$topicId?containerid=$topicId"

    // ---------- 五个动作 ----------

    /** 超话发帖的表单体（纯函数，便于单测钉死参数名与编码）。 */
    fun buildPostForm(topicId: String, content: String, syncToTimeline: Boolean): String = buildString {
        append("content=").append(WeiboHttp.q(content))
        append("&pic_id=")
        append("&visible=0")
        append("&share_id=")
        append("&media=")
        append("&vote=")
        append("&topic_id=").append(WeiboHttp.q(TOPIC_PREFIX + topicId))
        append("&sync_mblog=").append(if (syncToTimeline) "1" else "0")
    }

    /** 评论表单。 */
    fun buildCommentForm(mid: String, text: String): String =
        "id=${WeiboHttp.q(mid)}&comment=${WeiboHttp.q(text)}" +
            "&pic_id=&is_repost=0&comment_ori=0&is_comment=0"

    /** 转发表单。 */
    fun buildRepostForm(mid: String, text: String): String =
        "id=${WeiboHttp.q(mid)}&comment=${WeiboHttp.q(text)}" +
            "&pic_id=&is_repost=0&comment_ori=0&is_comment=0&visible=0&share_id="

    /** 在超话内发帖。 */
    fun publishTopicPost(
        session: WeiboHttp.WeiboSession,
        topicId: String,
        content: String,
        syncToTimeline: Boolean,
        referer: String = "",
    ): WriteResult {
        // 统一成完整 containerid：`topic_id` 必须是 `1022:100808…`，只给 hash 会发到错误对象
        val tid = EngageSchedule.topicContainerId(topicId)
        if (!EngageSchedule.isValidTopicId(tid)) return WriteResult(EngageErrorKind.CONFIG_INVALID, message = "超话 ID 非法")
        if (content.isBlank()) return WriteResult(EngageErrorKind.NO_TEXT, message = "正文为空")
        val ref = referer.ifEmpty { topicReferer(tid) }
        return execute(session, POST_STATUS, buildPostForm(tid, content, syncToTimeline), ref)
    }

    /** 评论一条微博。[mid] 用数字 mid（不是 base62 的 mblogid）。 */
    fun comment(
        session: WeiboHttp.WeiboSession,
        mid: String,
        text: String,
        referer: String = "$BASE/",
    ): WriteResult {
        if (!isValidMid(mid)) return WriteResult(EngageErrorKind.TARGET_GONE, message = "目标 mid 非法")
        if (text.isBlank()) return WriteResult(EngageErrorKind.NO_TEXT, message = "评论内容为空")
        return execute(session, COMMENT, buildCommentForm(mid, text), referer)
    }

    /** 转发一条微博。空转发也允许（`comment` 留空）。 */
    fun repost(
        session: WeiboHttp.WeiboSession,
        mid: String,
        text: String,
        referer: String = "$BASE/",
    ): WriteResult {
        if (!isValidMid(mid)) return WriteResult(EngageErrorKind.TARGET_GONE, message = "目标 mid 非法")
        return execute(session, REPOST, buildRepostForm(mid, text), referer)
    }

    /** 点赞。 */
    fun like(
        session: WeiboHttp.WeiboSession,
        mid: String,
        referer: String = "$BASE/",
    ): WriteResult {
        if (!isValidMid(mid)) return WriteResult(EngageErrorKind.TARGET_GONE, message = "目标 mid 非法")
        return execute(session, LIKE, "id=${WeiboHttp.q(mid)}", referer)
    }

    /** 删除自己的一条微博（发帖台账的撤回能力）。 */
    fun destroy(session: WeiboHttp.WeiboSession, mid: String): WriteResult {
        if (!isValidMid(mid)) return WriteResult(EngageErrorKind.TARGET_GONE, message = "目标 mid 非法")
        return execute(session, DESTROY, "id=${WeiboHttp.q(mid)}", "$BASE/")
    }

    // ---------- 执行与解析 ----------

    private fun execute(
        session: WeiboHttp.WeiboSession,
        url: String,
        form: String,
        referer: String,
    ): WriteResult {
        val xsrf = WeiboHttp.xsrfOf(session.cookie)
        if (xsrf.isEmpty()) {
            // 没有 XSRF 必然 403，先在本地说清楚，不发这个注定失败的请求
            return WriteResult(EngageErrorKind.LOGIN_WALL, message = "缺少 XSRF-TOKEN，需要重新登录")
        }
        return try {
            WeiboHttp.postForm(url, session, form, referer, xsrf).use { resp ->
                val body = resp.body?.string() ?: ""
                parseWriteResult(resp.code, body)
            }
        } catch (e: Exception) {
            WriteResult(EngageErrorKind.NETWORK, message = WeiboHttp.safeBodyExcerpt(e.message ?: "网络异常"))
        }
    }

    /** 解析写接口响应。[httpCode] 与 [body] 一起判，缺一不可。 */
    fun parseWriteResult(httpCode: Int, body: String): WriteResult {
        val kind = classify(httpCode, body)
        if (kind != EngageErrorKind.NONE) {
            return WriteResult(kind, message = WeiboHttp.safeBodyExcerpt(body))
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
        val data = json?.optJSONObject("data")
        val remoteId = data?.let {
            it.optString("mid", "").ifEmpty {
                it.optString("id", "").ifEmpty { it.optString("idstr", "") }
            }
        }.orEmpty()
        return WriteResult(EngageErrorKind.NONE, remoteId, "")
    }

    /**
     * 失败分类。顺序即优先级——**具体文案先于笼统的 ok 判断**，
     * 否则「操作频繁」这类带着 `ok:0` 的响应会被当成普通参数错误。
     */
    fun classify(httpCode: Int, body: String): EngageErrorKind {
        if (httpCode == 429 || httpCode == 432) return EngageErrorKind.RATE_LIMITED
        if (httpCode >= 500) return EngageErrorKind.SERVER_ERROR
        if (httpCode == 400) return EngageErrorKind.PARAM_INVALID

        val text = body.trim()
        if (text.isEmpty()) return EngageErrorKind.SERVER_ERROR
        // 实测：「请先验证身份」是以纯文本返回的（不是 JSON，也不是登录页 HTML），
        // 必须在 looksLikeLoginWall 与 JSON 解析之前认出来，否则会被误判成网络错误。
        if (text.contains("验证身份") || text.contains("安全验证")) return EngageErrorKind.NEED_VERIFY
        if (WeiboHttp.looksLikeLoginWall(text)) return EngageErrorKind.LOGIN_WALL

        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return EngageErrorKind.NETWORK

        val ok = json.opt("ok")?.toString() ?: ""
        val code = json.opt("code")?.toString() ?: ""
        val msg = json.optString("msg", "")
        val errno = json.optInt("errno", 0)

        if (ok == "-100") return EngageErrorKind.NOT_LOGGED_IN
        if (errno == 404 && msg.contains("无效")) return EngageErrorKind.ENDPOINT_INVALID
        if (errno == 10022 || errno == 10023) return EngageErrorKind.RATE_LIMITED
        if (msg.contains("频繁") || msg.contains("太快")) return EngageErrorKind.RATE_LIMITED

        if (msg.contains("验证") || msg.contains("captcha", ignoreCase = true)) return EngageErrorKind.NEED_VERIFY
        if (msg.contains("审核") || msg.contains("违规") || msg.contains("敏感")) return EngageErrorKind.CONTENT_REJECTED
        if (msg.contains("已转发") || msg.contains("已点赞") || msg.contains("赞过") || msg.contains("重复")) {
            return EngageErrorKind.DUPLICATE_ACTION
        }
        if (msg.contains("删除") || msg.contains("不存在")) return EngageErrorKind.TARGET_GONE
        if (msg.contains("未关注") || msg.contains("请先关注")) return EngageErrorKind.TOPIC_NOT_FOLLOWED
        if (msg.contains("屏蔽") || msg.contains("禁言")) return EngageErrorKind.TOPIC_BLOCKED
        if (msg.contains("等级")) return EngageErrorKind.LEVEL_TOO_LOW

        if (ok == "1" || code == "100000") return EngageErrorKind.NONE
        // 有 data 但既没有 ok:1 也没有明确错误码：按成功处理（发帖成功响应就是这个形态）
        if (json.optJSONObject("data") != null) return EngageErrorKind.NONE

        return EngageErrorKind.PARAM_INVALID
    }

    /** mid 必须是纯数字（base62 的 mblogid 不能用在写接口上）。 */
    fun isValidMid(mid: String): Boolean = mid.isNotEmpty() && mid.all { it.isDigit() } && mid.length >= 10
}
