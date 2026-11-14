package com.wcm.checkin

import org.json.JSONObject

/**
 * 微博图片地址的纯函数：原图/缩略图、尺寸段转换、pid 反查、卡片缩略图清单。
 *
 * 从原 `WeiboApi` 拆出；全部不触网（除域名白名单校验），可继续 JVM 单测。
 */
object WeiboImageUrl {

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
            WeiboHttp.weiboUrl(https)
            https
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 图片 URL → picId（路径最后一段去掉扩展名）。纯函数，JVM 可测。
     *
     * 索引里记的 key 就是详情 `pic_ids` 里的 pid，而微博图床的文件名恰好是同一个 pid ——
     * 预览页只有 URL（列表卡片只给 url，不给 pid），靠这个反推才能回答「这张抓过没有」。
     * 先剥 query / fragment 再剥扩展名（与 [Crawler] 的 pidOf 同口径）。
     */
    internal fun pidFromImageUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val path = t.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        if (name.isEmpty()) return ""
        return name.replace(Regex("\\.(jpg|jpeg|png|gif|webp)$", RegexOption.IGNORE_CASE), "")
    }

    /**
     * 缩略图地址 → 同一张图的大图地址：把尺寸段换成 `large`。纯函数（不触网），JVM 可测。
     *
     * 浏览页的小图手里只有 `orj360` 这类缩略图 URL，点开要看大图就得换尺寸段。
     * 不能写死 `replace("/orj360/", "/large/")` —— 卡片的 url 尺寸段并不总是 orj360
     * （GIF 帖、老帖、活动页会出现 `mw690` / `bmiddle` / `thumbnail` 等），
     * 所以按「`sinaimg.cn/<尺寸段>/<文件名>`」的结构整体替换，文件名与扩展名原样保留
     * （`.gif` 换成 `.jpg` 会直接 404）。
     *
     * 不是 sinaimg 地址、或结构里没有尺寸段时返回空串，由调用方回落到原缩略图。
     */
    internal fun fullFromThumb(raw: String): String {
        val url = sanitizeThumbUrl(raw) ?: return ""
        val marker = "sinaimg.cn/"
        val at = url.indexOf(marker)
        if (at < 0) return ""
        val head = url.substring(0, at + marker.length)
        val rest = url.substring(at + marker.length)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash >= rest.length - 1) return ""
        return head + "large/" + rest.substring(slash + 1)
    }
}
