package com.wcm.checkin

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 远程图片字节读取器（v0.9.14）。
 *
 * 从 OkHttp 的响应流里**边读边卡上限**，不信任 `Content-Length`：CDN 可能不给长度
 * （chunked / gzip），也可能给一个和真实体积不符的长度。以前的写法是先看
 * `contentLength()` 再 `bytes()`，一旦服务端没给长度就直接无上限读，是 OOM 入口。
 *
 * 超限选择 **整张失败** 而不是截断 —— 截断出来的半张 JPEG 解码结果是一张灰图，
 * 显示出来比占位色更像 bug。
 */
internal object RemoteBytes {

    /** 分块大小：16KB 与 OkHttp 内部的 segment 对齐，读 2MB 图只有百来次循环。 */
    private const val CHUNK = 16 * 1024

    /** 读完整条流；累计超过 [maxBytes] 立即返回 null。空流也返回 null。 */
    fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
        if (maxBytes <= 0) return null
        val out = ByteArrayOutputStream(CHUNK)
        val buf = ByteArray(CHUNK)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            if (out.size() + n > maxBytes) return null
            out.write(buf, 0, n)
        }
        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else bytes
    }
}
