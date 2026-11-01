package com.wcm.checkin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 远程图片字节读取器单元测试（v0.9.14）。
 *
 * 回归点：v0.9.13 的 [RemoteThumb] 用固定的 256KB 闸门同时管缩略图与「大图」，
 * 而 CDN `large` 实测单张 2,049,418 字节 —— 修好 403 之后仍会被自己的体积闸门
 * 拦成「加载失败」。所以这里既验证「超限整张失败」，也验证「2MB 这种真实大图
 * 在放宽后的上限下能读完」。
 */
class RemoteBytesTest {

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun readsWholeStreamUnderLimit() {
        val data = bytes(40_000)
        assertArrayEquals(data, RemoteBytes.readBounded(ByteArrayInputStream(data), 64 * 1024))
    }

    @Test
    fun allowsExactLimit() {
        val data = bytes(1024)
        assertArrayEquals(data, RemoteBytes.readBounded(ByteArrayInputStream(data), 1024))
    }

    @Test
    fun overLimitFailsWholeImage() {
        // 截断解码出来是一张灰图，比占位色更像 bug —— 超限必须整张作废
        assertNull(RemoteBytes.readBounded(ByteArrayInputStream(bytes(200_000)), 100_000))
    }

    @Test
    fun realLargeImageFitsRelaxedCap() {
        // CDN large 实测 2,049,418 字节，旧 256KB 上限会拦掉它
        val data = bytes(2_049_418)
        val out = RemoteBytes.readBounded(ByteArrayInputStream(data), 12_000_000)
        assertEquals(data.size, out?.size)
    }

    @Test
    fun emptyStreamIsNull() {
        assertNull(RemoteBytes.readBounded(ByteArrayInputStream(ByteArray(0)), 1024))
    }

    @Test
    fun nonPositiveLimitIsNull() {
        assertNull(RemoteBytes.readBounded(ByteArrayInputStream(bytes(10)), 0))
        assertNull(RemoteBytes.readBounded(ByteArrayInputStream(bytes(10)), -1))
    }

    @Test
    fun thumbnailSizedImageFitsThumbCap() {
        // orj360 实测 21,983 字节；1.5MB 上限下必须能读
        assertEquals(21_983, RemoteBytes.readBounded(ByteArrayInputStream(bytes(21_983)), 1_500_000)?.size)
    }
}
