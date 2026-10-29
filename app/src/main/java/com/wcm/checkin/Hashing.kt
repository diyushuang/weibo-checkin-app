package com.wcm.checkin

import java.io.InputStream
import java.security.MessageDigest

/**
 * 内容哈希（去重第三层）。
 *
 * 只取 SHA-256 的**前 128 bit（32 hex）**：10 万张图下碰撞概率约 1e-27，
 * 工程上等于零，而每条记录省下 32 字节（10 万条即 3 MB）。
 *
 * 采样哈希（首尾块）只适合做远端预筛，不能当最终判据 —— 微博 CDN 会轻微重压缩。
 */
object Hashing {

    private const val HEX = "0123456789abcdef"
    private const val KEEP_BYTES = 16      // 128 bit
    private const val BUF = 64 * 1024

    /** 下载路径：字节已在内存，直接算，无额外拷贝。 */
    fun sha256Hex128(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return toHex128(md.digest())
    }

    /** 已存在文件的路径：流式计算，绝不整文件入内存。IO 出错返回 null（调用方跳过即可）。 */
    fun sha256Hex128(input: InputStream): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUF)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
            toHex128(md.digest())
        } catch (e: Exception) {
            null
        }
    }

    fun toHex128(digest: ByteArray): String {
        val sb = StringBuilder(KEEP_BYTES * 2)
        for (i in 0 until minOf(KEEP_BYTES, digest.size)) {
            val v = digest[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}
