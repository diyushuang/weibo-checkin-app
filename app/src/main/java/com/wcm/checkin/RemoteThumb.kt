package com.wcm.checkin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 远程图片加载（v0.9.11 引入，v0.9.14 修复）。
 *
 * 与 [ThumbLoader]（本地 SAF 文件）的分工：这里的输入是微博 CDN 的 URL。
 * 内存 LruCache（上限对齐 ThumbLoader 的 maxMemory/8），**不做磁盘缓存** ——
 * 预览图是 orj360 量级的小图，浏览行为一次性很强，磁盘缓存的收益配不上
 * 「再占一块存储」的复杂度；重复浏览命中内存缓存即可。
 *
 * ## 为什么 v0.9.13 之前是一屏空白
 *
 * sinaimg 有热链保护：**浏览器型 UA + 没有 Referer 一律 403**（实测响应体约 150 字节
 * `text/html`）。本类以前只发了 `User-Agent`（[WeiboHttp.UA]，iPhone Safari 口径），
 * 于是九宫格缩略图与长按大图 **100% 拿不到字节**：解码器拿到的是 HTML，采样解码
 * 直接返回 null，界面停在 `@color/image_placeholder` 的占位色上，长按弹「加载失败」。
 * 现在补 `Referer: https://m.weibo.cn/`（实测 200 image/jpeg；换成非浏览器 UA 也行，
 * 但 UA 保持浏览器口径更不容易被风控）。抓取内核 [Crawler] 那边一直用
 * `User-Agent: okhttp`，所以同样的图它能下、预览这边下不了 —— 差异就在 Referer 上。
 *
 * ## 分档字节上限
 *
 * 缩略图与「大图」共用一份 256KB 上限也是 v0.9.13 的 bug：CDN `large` 实测单张
 * **2MB+**（2000×3000 级别的图），修好 403 之后仍会被自己的体积闸门拦成「加载失败」。
 * 现在按请求尺寸分档：缩略图 1.5MB、大图 12MB，且用 [RemoteBytes.readBounded] 边读边卡。
 *
 * ## 并发与顺序
 *
 * [io] 用 `limitedParallelism(4)` 保持「先到先服务」的少量并发：调用方（浏览页/选图页）
 * 只在卡片进入可见区时才发起、离屏即取消，卡片内的格子按顺序逐张来 —— 滚动时是
 * 「一格一格出现」，而不是一屏几十个请求一起挤 CDN。
 *
 * 所有 URL 必须过 [WeiboHttp.weiboUrl] 白名单 —— 缩略图接口虽然在 L0 档，
 * 安全口径与原图下载完全一致（https + 微博域）。
 */
object RemoteThumb {

    private const val TAG = "wcm"

    private const val THUMB_SIZE = 360   // orj360 的尺寸段对齐，避免过度解码
    private const val FULL_SIZE = 2048

    /** 单张缩略图字节上限：orj360 实测 20~30KB，orj960 约 130KB，1.5MB 已很宽容。 */
    private const val THUMB_MAX_BYTES = 1_500_000

    /** 单张大图字节上限：CDN `large` 实测 2MB+，留足余量又挡住「重定向到原图」。 */
    private const val FULL_MAX_BYTES = 12_000_000

    /** 热链保护要求的 Referer（见类注释）。 */
    private const val REFERER = "https://m.weibo.cn/"

    /** 顺序 + 少量：固定 4 路并发，先到先服务。 */
    private val io = Dispatchers.IO.limitedParallelism(4)

    /** 大图可能是 2MB+，用比接口更宽的 callTimeout（接口那套 25s 对 2MB 偏紧）。 */
    private val client = WeiboHttp.client.newBuilder()
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val cache: LruCache<String, Bitmap> by lazy {
        object : LruCache<String, Bitmap>(cacheKb()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    private fun cacheKb(): Int =
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceIn(8 * 1024, 64 * 1024)

    /** 网格缩略图（带内存缓存）。失败返回 null，由调用方占位。 */
    suspend fun load(url: String): Bitmap? = load(url, THUMB_SIZE, THUMB_MAX_BYTES)

    /** 全屏预览大图（长边 2048 采样，仍走同一份缓存，但字节上限放宽到 12MB）。 */
    suspend fun loadFull(url: String): Bitmap? = load(url, FULL_SIZE, FULL_MAX_BYTES)

    private suspend fun load(url: String, reqSize: Int, maxBytes: Int): Bitmap? {
        // key 必须带请求尺寸：同一个 URL 既可能按 360 加载（网格），也可能按 2048
        // 加载（全屏预览）。只按 URL 做 key 会让后到的 360 小图直接把 2048 大图
        // 顶掉，全屏打开就是一张糊图。
        val key = "$reqSize|$url"
        cache.get(key)?.let { return it }
        val bytes = withContext(io) { fetch(url, maxBytes) } ?: return null
        val bmp = decodeSampled(bytes, reqSize) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /**
     * 下载字节：白名单校验 → GET（带 Referer）→ 边读边卡上限。单次尝试，
     * 失败就交给调用方占位/重试（网格图不值得 3 次重试）。
     */
    private fun fetch(rawUrl: String, maxBytes: Int): ByteArray? {
        return try {
            val req = okhttp3.Request.Builder()
                .url(WeiboHttp.weiboUrl(rawUrl))
                .header("User-Agent", WeiboHttp.UA)
                .header("Referer", REFERER)
                .header("Accept", "image/*,*/*;q=0.8")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 403 基本就是热链保护（Referer/UA 变了），留一行日志便于下次定位
                    Log.w(TAG, "图片 HTTP ${resp.code}：$rawUrl")
                    return null
                }
                val body = resp.body ?: return null
                if (body.contentLength() > maxBytes) {
                    Log.w(TAG, "图片过大 ${body.contentLength()}B：$rawUrl")
                    return null
                }
                RemoteBytes.readBounded(body.byteStream(), maxBytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "图片加载异常 ${e.javaClass.simpleName}: ${e.message}：$rawUrl")
            null
        }
    }

    /** 字节 → 采样解码（两次解码：先边界再采样）。失败（含 OOM）返回 null。 */
    private fun decodeSampled(bytes: ByteArray, reqSize: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ThumbLoader.computeInSampleSize(bounds.outWidth, bounds.outHeight, reqSize)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}
