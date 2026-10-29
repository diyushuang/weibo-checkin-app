package com.wcm.checkin

import androidx.core.graphics.scale
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Live Photo（mov）→ GIF 转换：帧提取用系统 MediaMetadataRetriever，
 * 编码用自研 GifEncoder。口径对齐交接文档：12fps、长边 640、256 色调色板。
 *
 * **内存有硬上限**：编码器需要全部帧才能统计出一张全局调色板，所以帧只能留在内存里。
 * 早先的实现无脑按 12fps 抽满整段视频：640 长边每帧约 0.9MB，10 秒视频就是 110MB，
 * 再叠加 mov 原始字节（上限 64MB）——Android 17 起系统会按设备 RAM 给应用设内存上限，
 * 这类峰值会直接把进程干掉。现在改成按**帧预算**反推采样步长：预算不够就降帧率，
 * 宁可变糙也不 OOM。
 *
 * mov 落在 cacheDir 临时文件（应用私有目录，随时可回收），成功转出 GIF 后即删，
 * 相册目录里只留 GIF；转换失败时由调用方回退保存原始 mov，内容不丢。
 */
object LivePhotoConverter {

    /** 帧数硬上限：12fps 下相当于 20 秒。 */
    private const val MAX_FRAMES = 240

    private const val MIN_FRAME_BUDGET = 16L * 1024 * 1024
    private const val MAX_FRAME_BUDGET = 48L * 1024 * 1024

    /**
     * @param frameBudgetBytes 帧缓存预算；默认取可用堆的 1/4（夹在 16–48MB 之间）。
     * @return GIF 字节；视频太短/解码失败返回 null（调用方回退存 mov）。
     */
    fun movBytesToGif(
        context: Context,
        mov: ByteArray,
        fps: Int = 12,
        maxEdge: Int = 640,
        frameBudgetBytes: Long = defaultFrameBudget(),
    ): ByteArray? {
        if (mov.isEmpty()) return null
        val tmp = File(context.cacheDir, "live_${System.currentTimeMillis()}.mov")
        val retriever = MediaMetadataRetriever()
        val frames = ArrayList<GifEncoder.GifFrame>()
        try {
            tmp.writeBytes(mov)
            retriever.setDataSource(tmp.absolutePath)
            val durMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            if (durMs < 300) return null

            // 第一帧用来确定实际输出尺寸，再据此算本轮能缓存多少帧
            val firstBmp = grabFrame(retriever, 0L) ?: return null
            val first = toGifFrame(scaleDown(firstBmp, maxEdge))
            val perFrameBytes = first.width.toLong() * first.height * 4L
            val maxFrames = (frameBudgetBytes / perFrameBytes.coerceAtLeast(1L))
                .coerceIn(2L, MAX_FRAMES.toLong()).toInt()

            var stepMs = 1000.0 / fps
            if (durMs / stepMs > maxFrames) {
                // 预算装不下全部帧 → 拉大采样间隔（等价于降帧率），保证总内存不超预算
                stepMs = durMs.toDouble() / maxFrames
            }

            frames.add(first)
            var t = stepMs
            while (t < durMs && frames.size < maxFrames) {
                grabFrame(retriever, (t * 1000).toLong())
                    ?.let { frames.add(toGifFrame(scaleDown(it, maxEdge))) }
                t += stepMs
            }
            if (frames.size < 2) return null

            // 帧延时按真实采样步长换算，这样降帧率后播放时长仍然对得上
            val delayCs = (stepMs / 10.0).roundToInt().coerceIn(2, 100)
            return GifEncoder.encode(frames, delayCs)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
            tmp.delete()
        }
    }

    /** 取某一时刻的帧；CLOSEST 拿不到时退化为最近的关键帧。 */
    private fun grabFrame(retriever: MediaMetadataRetriever, atUs: Long): Bitmap? {
        try {
            retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { return it }
        } catch (e: Exception) {
        }
        return try {
            retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        }
    }

    /** 帧缓存预算：可用堆的 1/4，夹在 16–48MB。 */
    private fun defaultFrameBudget(): Long =
        (Runtime.getRuntime().maxMemory() / 4).coerceIn(MIN_FRAME_BUDGET, MAX_FRAME_BUDGET)

    /** Bitmap → ARGB 像素帧，转换后立即回收位图控内存。 */
    private fun toGifFrame(b: Bitmap): GifEncoder.GifFrame {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        b.recycle()
        return GifEncoder.GifFrame(b.width, b.height, px)
    }

    /** 长边压到 maxEdge 以内（等比）；小图原样返回。原图立即回收省内存。 */
    private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longEdge
        val w = max(1, (src.width * ratio).roundToInt())
        val h = max(1, (src.height * ratio).roundToInt())
        val scaled = src.scale(w, h)
        if (scaled != src) src.recycle()
        return scaled
    }
}
