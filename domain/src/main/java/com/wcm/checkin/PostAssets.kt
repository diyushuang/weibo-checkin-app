package com.wcm.checkin

import org.json.JSONObject

/** 一条微博的全部图片资产（解析自详情接口）。 */
class PostAssets(
    val picIds: List<String>,
    val liveByIndex: Map<Int, String>,
    val liveExtras: List<String>,
) {
    /**
     * 实际应落盘的文件数。
     *
     * Live 项只存动图（gif/mov），**不再额外存配对静图** —— 那张 jpg 只是动图的封面帧，
     * 存下来就是同一张图的两份（旧版 v0.4 的 bug）。所以一张配图恰好对应一个文件。
     */
    val expectedFiles: Int get() = picIds.size + liveExtras.size
}

/** 槽位编码：静图 i*2，配对 Live i*2+1，帖级 Live 1000+n。全局唯一且稳定。 */
object AssetSlots {
    fun still(i: Int) = i * 2
    fun pairedLive(i: Int) = i * 2 + 1
    fun extraLive(n: Int) = 1000 + n
}

/**
 * 微博详情 / Web 端扁平 status → 图清单。
 *
 * 纯解析（不触网），从 `Crawler` 拆出；18 图补全与 Live 配对的判定口径不变。
 */
object PostAssetsParser {

    private const val LIVE_WRAPPER = "https://video.weibo.com/media/play?livephoto="

    /** pics 归一化：数组或数字键对象 → 列表。 */
    private fun normPics(o: Any?): List<JSONObject> {
        return when (o) {
            is org.json.JSONArray ->
                (0 until o.length()).mapNotNull { o.optJSONObject(it) }
            is JSONObject -> o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.toList()
            else -> emptyList()
        }
    }

    /** 从 large.url 反推 pid（比 p.pid 稳：Live 条目两处 pid 不总一致）。 */
    private fun pidOf(pic: JSONObject): String {
        val url = pic.optJSONObject("large")?.optString("url", "")
            ?: pic.optString("url", "")
        if (url.isNotEmpty()) {
            return url.substringAfterLast('/')
                .replace(Regex("\\.(jpg|jpeg|png|gif|webp)$", RegexOption.IGNORE_CASE), "")
        }
        return pic.optString("pid", "")
    }

    /** 详情 → 图清单（pic_ids 完整保序）+ Live 配对（pics 前 9 条内）+ 独立 Live。 */
    fun fromDetail(detail: JSONObject): PostAssets {
        val picIds = ArrayList<String>()
        val idsArr = detail.optJSONArray("pic_ids")
        if (idsArr != null) {
            // 空串 / 空白 pid 必须剔掉：它们会占掉一个槽位却永远下不下来，
            // expectedFiles 因此永远大于实际落盘数 —— 这条帖永远判不成完成，
            // 每轮抓取都重试一次，日志里无限重复同一帖。
            for (i in 0 until idsArr.length()) {
                val id = idsArr.optString(i, "").trim()
                if (id.isNotEmpty()) picIds.add(id)
            }
        }
        val pics = normPics(detail.opt("pics"))
        if (picIds.isEmpty()) {
            pics.forEach { p ->
                val id = pidOf(p).trim()
                if (id.isNotEmpty()) picIds.add(id)
            }
        }

        val liveByIndex = HashMap<Int, String>()
        val usedLiveUrls = HashSet<String>()
        for (pic in pics) {
            val videoSrc = pic.optString("videoSrc", "")
            if (pic.optString("type") == "livephoto" && videoSrc.isNotEmpty()) {
                val idx = picIds.indexOf(pidOf(pic))
                if (idx >= 0) {
                    liveByIndex[idx] = videoSrc
                    usedLiveUrls.add(videoSrc)
                }
            }
        }
        val liveExtras = ArrayList<String>()
        val postLive = detail.optJSONArray("live_photo")
        if (postLive != null) {
            for (i in 0 until postLive.length()) {
                val enc = postLive.optString(i, "")
                if (enc.isNotEmpty() && !usedLiveUrls.contains(LIVE_WRAPPER + enc)) {
                    liveExtras.add(LIVE_WRAPPER + enc)
                }
            }
        }
        return PostAssets(picIds, liveByIndex, liveExtras)
    }

    /**
     * Web 端扁平 status → 图清单。
     *
     * `pic_ids` 是权威顺序；Live 判定看 `pic_infos[pid].type == "livephoto"`，其 `video`
     * 就是动图直链。Web 版没有帖级 `live_photo`，所以 `liveExtras` 恒为空。
     */
    fun fromWebStatus(status: JSONObject): PostAssets {
        val picIds = ArrayList<String>()
        status.optJSONArray("pic_ids")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pid = arr.optString(i, "").trim()
                if (pid.isNotEmpty()) picIds.add(pid)
            }
        }
        val liveByIndex = HashMap<Int, String>()
        val infos = status.optJSONObject("pic_infos")
        if (infos != null && picIds.isNotEmpty()) {
            picIds.forEachIndexed { idx, pid ->
                val info = infos.optJSONObject(pid) ?: return@forEachIndexed
                if (info.optString("type") != "livephoto") return@forEachIndexed
                val video = info.optString("video", "").ifEmpty {
                    info.optJSONObject("video")?.optString("url", "").orEmpty()
                }
                // 只认能过白名单的直链：否则干脆当静图存，别让整张图因为「域名不在白名单」失败
                if (video.isNotEmpty() && videoAllowed(video)) liveByIndex[idx] = video
            }
        }
        return PostAssets(picIds, liveByIndex, emptyList())
    }

    /** 动图直链是否落在微博域名白名单内。 */
    private fun videoAllowed(url: String): Boolean = try {
        WeiboHttp.weiboUrl(url)
        true
    } catch (e: Exception) {
        false
    }
}
