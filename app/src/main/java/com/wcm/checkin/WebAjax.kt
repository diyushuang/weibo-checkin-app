package com.wcm.checkin

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Web 端 `weibo.com/ajax` 的只读接口（游客身份即可）。
 *
 * 这些接口是**免登录**的：用 [GuestSession] 拿到的访客 cookie 就能读，
 * 不需要任何微博账号。相册抓图模式完全建立在这组接口上。
 *
 * 实测边界（2026-10-30）：
 * - `profile/getImageWall`  ✅ 200，`data.list` 每页 20 条，带 `mid` / `timeline_year` / `timeline_month`
 * - `statuses/show`         ✅ 200，扁平 status（`pic_ids` + `pic_infos`，**没有**帖级 `live_photo`）
 * - `statuses/mymblog`      ❌ 403「请登录后使用」—— 时间线没有免登录替代
 */
object WebAjax {

    private const val WEB_BASE = "https://weibo.com"

    /** 相册墙每页条数由服务端决定，实测 20；这里只做上限保护。 */
    private const val WALL_SAFETY_MAX = 60

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * 命中就算「身份问题」的拒绝文案（HTTP 200 + ok=0 时检查）。
     *
     * 微博把登录墙 / 风控也包装成 200 + ok=0，只按 HTTP 码判会漏掉 ——
     * 而这正是「刷新游客身份就能恢复」的那一类错误。
     */
    private val IDENTITY_MSG = listOf(
        "请先登录", "请登录", "登录后", "未登录", "身份", "visitor", "风控", "异常",
    )

    /** 相册墙上的一张图（同一个 mid 会出现多次，说明该帖有多图）。 */
    data class WallItem(
        val mid: String,
        val year: String = "",
        val month: String = "",
    )

    data class WallPage(
        /** 去重后的 mid，按墙上的先后顺序（新的在前）。 */
        val mids: List<String>,
        /** 下一页游标；空串或 "0" 表示到底了。 */
        val nextSinceId: String = "",
        /** 本页最早一条的「年-月」，用于和起始日期做粗粒度比较。 */
        val oldestYm: String = "",
    )

    /**
     * 相册墙一页。
     *
     * [sinceId] 传空串表示第一页（最新的）。游标来自上一页返回的 `since_id`。
     * 失败抛 [WeiboApi.WeiboException]，由调用方决定是否回落到时间线模式。
     */
    fun imageWall(uid: String, sinceId: String): WallPage {
        val identity = GuestSession.cookie()
            ?: throw WeiboApi.WeiboException(
                "游客身份不可用" + (GuestSession.lastError?.let { "：$it" } ?: ""),
                identitySuspect = true,
            )
        var url = "$WEB_BASE/ajax/profile/getImageWall?uid=$uid&has_album=true"
        if (sinceId.isNotEmpty()) url += "&sinceid=$sinceId"
        val payload = get(url, referer = "$WEB_BASE/u/$uid", identity = identity, what = "相册墙")

        val data = payload.optJSONObject("data") ?: JSONObject()
        val list = data.optJSONArray("list") ?: return WallPage(emptyList(), "")
        val mids = LinkedHashMap<String, WallItem>()
        var oldestYm = ""
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val mid = item.optString("mid", "").trim()
            if (mid.isEmpty()) continue
            val year = item.optString("timeline_year", "")
            val month = item.optString("timeline_month", "")
            val ym = if (year.isNotEmpty() && month.isNotEmpty()) "$year-${month.padStart(2, '0')}" else ""
            if (ym.isNotEmpty() && (oldestYm.isEmpty() || ym < oldestYm)) oldestYm = ym
            if (!mids.containsKey(mid)) mids[mid] = WallItem(mid, year, month)
        }
        val next = data.optString("since_id", "").trim().let { if (it == "0") "" else it }
        return WallPage(mids.keys.toList().take(WALL_SAFETY_MAX), next, oldestYm)
    }

    /**
     * Web 端单条微博详情。
     *
     * 与 m 站 `/statuses/show` 的差别：返回**扁平 status 对象**（不是 `{ok,data}` 包装），
     * 且没有帖级 `live_photo`。`pic_ids` 是权威图清单，`pic_infos[pid].type == "livephoto"`
     * 时 `video` 字段是动图直链。
     */
    fun statusShow(mid: String): JSONObject {
        val identity = GuestSession.cookie()
            ?: throw WeiboApi.WeiboException(
                "游客身份不可用" + (GuestSession.lastError?.let { "：$it" } ?: ""),
                identitySuspect = true,
            )
        val url = "$WEB_BASE/ajax/statuses/show?id=$mid&isGetLongText=1"
        val payload = get(url, referer = "$WEB_BASE/detail/$mid", identity = identity, what = "微博详情")
        // 有的版本外面包一层 data，两种都认
        return payload.optJSONObject("data") ?: payload
    }

    private fun get(url: String, referer: String, identity: String, what: String): JSONObject {
        val req = Request.Builder().url(url)
            .header("User-Agent", WeiboApi.WEB_UA)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Cookie", identity)
            .build()
        return client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 403 + 「请登录后使用」是登录墙；432 是风控。两者都算身份问题
                throw WeiboApi.WeiboException(
                    "$what 请求失败：HTTP ${resp.code}",
                    httpCode = resp.code,
                    identitySuspect = true,
                )
            }
            val payload = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw WeiboApi.WeiboException(
                    "$what 响应不是 JSON（前 60 字：${body.trim().take(60)}）",
                    identitySuspect = true,
                )
            }
            if (payload.optInt("ok", 1) == 0) {
                val msg = payload.optString("message", payload.optString("msg", ""))
                // HTTP 200 + ok=0 是微博最常见的拒绝方式（"请先登录"、"身份异常"）。
                // 这里不置 identitySuspect 的话，Crawler 的「刷新游客身份再重试」
                // 永远不会触发 —— 一次登录墙就让整轮相册抓取退化成时间线模式。
                throw WeiboApi.WeiboException(
                    "$what 接口拒绝：$msg",
                    identitySuspect = IDENTITY_MSG.any { msg.contains(it) },
                )
            }
            payload
        }
    }
}
