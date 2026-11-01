package com.wcm.checkin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 远程缩略图加载（v0.9.11，浏览页 / 选图页用）。
 *
 * 与 [ThumbLoader]（本地 SAF 文件）的分工：这里的输入是微博 CDN 的 URL。
 * 内存 LruCache（上限对齐 ThumbLoader 的 maxMemory/8），**不做磁盘缓存** ——
 * 预览图是 orj360 量级的小图，浏览行为一次性很强，磁盘缓存的收益配不上
 * 「再占一块存储」的复杂度；重复浏览命中内存缓存即可。
 *
 * 并发闸：[Semaphore]（4）限制同时在飞的 CDN 请求，快速滚动时不堆积连接；
 * 协程取消时 OkHttp 的 execute 会被 interrupt 打断，队列自然排空。
 *
 * 所有 URL 必须过 [WeiboApi.weiboUrl] 白名单 —— 缩略图接口虽然在 L0 档，
 * 安全口径与原图下载完全一致（https + 微博域）。
 */
object RemoteThumb {

    private const val THUMB_SIZE = 360   // orj360 的尺寸段对齐，避免过度解码
    private const val FULL_SIZE = 2048

    /** 单张缩略图字节上限：网格图正常 20-80KB，超过这个量级说明拿到的不是缩略图。 */
    private const val MAX_BYTES = 256 * 1024

    private val cache: LruCache<String, Bitmap> by lazy {
        object : LruCache<String, Bitmap>(cacheKb()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    private fun cacheKb(): Int =
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceIn(8 * 1024, 64 * 1024)

    private val gate = Semaphore(4)

    /** 网格缩略图（带内存缓存）。失败返回 null，由调用方占位。 */
    suspend fun load(url: String): Bitmap? = load(url, THUMB_SIZE)

    /** 全屏预览大图（长边 2048 采样，仍走同一份缓存与闸门）。 */
    suspend fun loadFull(url: String): Bitmap? = load(url, FULL_SIZE)

    private suspend fun load(url: String, reqSize: Int): Bitmap? {
        // key 必须带请求尺寸：同一个 URL 既可能按 360 加载（网格），也可能按 2048
        // 加载（全屏预览）。只按 URL 做 key 会让后到的 360 小图直接把 2048 大图
        // 顶掉，全屏打开就是一张糊图。
        val key = "$reqSize|$url"
        cache.get(key)?.let { return it }
        val bytes = withContext(Dispatchers.IO) {
            gate.withPermit { fetch(url) }
        } ?: return null
        val bmp = decodeSampled(bytes, reqSize) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** 下载字节：白名单校验 → GET，单次尝试（网格图不值得 3 次重试，失败就占位）。 */
    private fun fetch(rawUrl: String): ByteArray? {
        return try {
            val url = WeiboApi.weiboUrl(rawUrl)
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", WeiboApi.UA)
                .build()
            WeiboApi.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                // 无上限的 bytes() 是 OOM 入口：这个 URL 一旦被重定向到原图
                // （CDN 常见行为），单张就是几 MB，网格滚动时叠着 4 个并发直接爆内存。
                // 与 Crawler 的 64MB 闸门口径一致，但缩略图应该更严。
                if (body.contentLength() > MAX_BYTES) return null
                val bytes = body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
                bytes
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 字节 → 采样解码（两次解码：先边界再采样）。失败返回 null。 */
    private fun decodeSampled(bytes: ByteArray, reqSize: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = ThumbLoader.computeInSampleSize(bounds.outWidth, bounds.outHeight, reqSize)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
    }
}
