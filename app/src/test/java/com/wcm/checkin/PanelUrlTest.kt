package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 面板地址安全策略单测：公网禁明文、内网放行。 */
class PanelUrlTest {

    @Test
    fun publicHttps_isAllowed() {
        val p = PanelUrl.parse("https://panel.example.com")
        assertEquals("https://panel.example.com", p.base)
        assertEquals("https", p.scheme)
        assertFalse(p.isPrivateHost)
    }

    @Test
    fun publicHttp_isRejected() {
        val e = runCatching { PanelUrl.parse("http://panel.example.com") }.exceptionOrNull()
        assertTrue(e is WeiboHttp.WeiboException)
        assertTrue(e!!.message!!.contains("https"))
    }

    @Test
    fun privateHttp_isAllowed() {
        listOf(
            "http://192.168.1.10:8080",
            "http://10.0.0.5",
            "http://172.16.3.4",
            "http://172.31.255.254",
            "http://127.0.0.1",
            "http://localhost:3000",
            "http://nas.local",
        ).forEach { url ->
            val p = PanelUrl.parse(url)
            assertTrue("应判为内网：$url", p.isPrivateHost)
            assertEquals("http", p.scheme)
        }
    }

    @Test
    fun adjacentPublicRanges_areNotPrivate() {
        assertFalse(PanelUrl.isPrivateHost("172.15.0.1"))
        assertFalse(PanelUrl.isPrivateHost("172.32.0.1"))
        assertFalse(PanelUrl.isPrivateHost("192.169.0.1"))
        assertFalse(PanelUrl.isPrivateHost("11.0.0.1"))
        assertFalse(PanelUrl.isPrivateHost("8.8.8.8"))
        assertFalse(PanelUrl.isPrivateHost("www.baidu.com"))
    }

    @Test
    fun trailingSlashIsTrimmed() {
        assertEquals("https://a.example.com", PanelUrl.parse("https://a.example.com/").base)
    }

    @Test
    fun invalidInputs_areRejectedWithReadableMessage() {
        listOf("", "   ", "panel.example.com", "ftp://a.example.com", "https://").forEach { raw ->
            val e = runCatching { PanelUrl.parse(raw) }.exceptionOrNull()
            assertTrue("应拒绝：[$raw]", e is WeiboHttp.WeiboException)
            assertTrue("消息不应为空：[$raw]", !e!!.message.isNullOrBlank())
        }
    }

    @Test
    fun loopbackAndLinkLocal() {
        assertTrue(PanelUrl.isPrivateHost("127.0.0.1"))
        assertTrue(PanelUrl.isPrivateHost("169.254.1.1"))
        assertTrue(PanelUrl.isPrivateHost("::1"))
        assertTrue(PanelUrl.isPrivateHost("[::1]"))
    }

    /** Tailscale / ZeroTier 默认落在 100.64/10，漏判会让内网面板被强制 https 而不可用。 */
    @Test
    fun cgnatRange_isPrivate() {
        assertTrue(PanelUrl.isPrivateHost("100.64.0.1"))
        assertTrue(PanelUrl.isPrivateHost("100.101.102.103"))
        assertTrue(PanelUrl.isPrivateHost("100.127.255.254"))
        assertEquals("http", PanelUrl.parse("http://100.64.0.1:8080").scheme)
        // 边界外仍是公网
        assertFalse(PanelUrl.isPrivateHost("100.63.255.255"))
        assertFalse(PanelUrl.isPrivateHost("100.128.0.0"))
    }

    /** 纯 IPv6 内网部署：ULA fc00::/7 与链路本地 fe80::/10。 */
    @Test
    fun ipv6PrivateRanges() {
        assertTrue(PanelUrl.isPrivateHost("fc00::1"))
        assertTrue(PanelUrl.isPrivateHost("fd12:3456:789a::1"))
        assertTrue(PanelUrl.isPrivateHost("fe80::1"))
        assertTrue(PanelUrl.isPrivateHost("febf::1"))
        assertTrue(PanelUrl.isPrivateHost("[fd00::1234]"))
        assertTrue(PanelUrl.isPrivateHost("fe80::1%wlan0"))
        // IPv4-mapped 按内嵌地址判
        assertTrue(PanelUrl.isPrivateHost("::ffff:192.168.1.10"))

        // 公网 IPv6 不能放行
        assertFalse(PanelUrl.isPrivateHost("2001:4860:4860::8888"))
        assertFalse(PanelUrl.isPrivateHost("2606:4700::1111"))
    }

    @Test
    fun ipv6PrivateHost_allowsHttp() {
        val p = PanelUrl.parse("http://[fd00::1234]:8080")
        assertTrue(p.isPrivateHost)
        assertEquals("http", p.scheme)
    }

    @Test
    fun publicHttps_ipv6StillAllowed() {
        val p = PanelUrl.parse("https://[2606:4700::1111]")
        assertFalse(p.isPrivateHost)
        assertEquals("https", p.scheme)
    }
}
