package com.wcm.checkin

import okhttp3.Request
import org.json.JSONObject

/**
 * 微博帖子接口：用户时间线、单帖详情、时间解析、正文摘要与 bid 输入解析。
 *
 * 从原 `WeiboApi` 拆出；图片 URL 相关逻辑见 [WeiboImageUrl]。
 */
object WeiboPostApi {

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
        val url = "${WeiboHttp.BASE}/api/container/getIndex?" +
            "containerid=107603${WeiboHttp.q(uid)}&page=$page"
        val req = Request.Builder().url(url)
            .header("User-Agent", WeiboHttp.UA)
            .header("Referer", "${WeiboHttp.BASE}/u/$uid")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("MWeibo-Pwa", "1")
            .header("Cookie", cookie)
            .build()
        WeiboHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw WeiboHttp.httpFailure("列表", resp.code)
            val payload = WeiboHttp.parseJson(resp.body?.string() ?: "{}", "列表")
            val ok = payload.optInt("ok", 0)
            if (ok != 1) {
                throw WeiboHttp.WeiboException(
                    "列表接口 ok=${payload.opt("ok")}",
                    identitySuspect = ok == -100,
                )
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
                        thumbs = WeiboImageUrl.parseThumbUrls(mb),
                    )
                )
            }
            return total to posts
        }
    }

    /**
     * 时间线卡片上的「这条帖有几张图」粗筛值。
     *
     * 超 9 图帖的卡片会截断：`pic_num`、`pics`、`pic_ids` 三处都可能只给一部分
     * （真机见过 9/18 与 0/18 两种形态）。取三者最大值，只要任一处透出「有图」，
     * 抓取规划就不会把这条当纯文字帖跳过；真正的张数以详情里的 `pic_ids` 为准。
     */
    fun cardPicNum(mblog: JSONObject): Int = maxOf(
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
        val url = "${WeiboHttp.BASE}/statuses/show?id=${WeiboHttp.q(bid)}"
        val req = Request.Builder().url(url)
            .header("User-Agent", WeiboHttp.UA)
            .header("Referer", "${WeiboHttp.BASE}/detail/$bid")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("MWeibo-Pwa", "1")
            .header("Cookie", cookie)
            .build()
        WeiboHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw WeiboHttp.httpFailure("详情", resp.code)
            val payload = WeiboHttp.parseJson(resp.body?.string() ?: "{}", "详情")
            val ok = payload.optInt("ok", 0)
            if (ok != 1) {
                throw WeiboHttp.WeiboException(
                    "详情接口 ok=${payload.opt("ok")}",
                    identitySuspect = ok == -100,
                )
            }
            return payload.optJSONObject("data") ?: JSONObject()
        }
    }
}
