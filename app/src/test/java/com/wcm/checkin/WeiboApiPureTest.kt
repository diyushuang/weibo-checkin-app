package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** WeiboApi 纯函数单元测试（不触网）：时间解析 / 摘要 / bid 解析 / cookie 与 URL 白名单。 */
class WeiboApiPureTest {

    // ---------- 时间解析 ----------

    @Test
    fun parseWeiboTime_valid() {
        val t = WeiboApi.parseWeiboTime("Thu Oct 22 15:27:12 +0800 2026")
        assertNotNull(t)
        assertEquals("2026-10-22", t!!.first)
        assertEquals("15:27:12", t.second)
        assertEquals(2026, t.third)
    }

    @Test
    fun parseWeiboTime_invalid() {
        assertNull(WeiboApi.parseWeiboTime("2026-10-22 15:27:12"))
        assertNull(WeiboApi.parseWeiboTime("Thu Oct 22 2026"))
    }

    // ---------- 正文摘要 ----------

    @Test
    fun summarize_stripsHtmlAndEntities() {
        assertEquals("hello a&b", WeiboApi.summarize("hello&nbsp;<a>a&amp;b</a>"))
        assertEquals("纯 文本", WeiboApi.summarize("  纯\t文本  "))   // \t 折叠为单个空格
    }

    @Test
    fun summarize_truncatesTo90() {
        assertEquals(91, WeiboApi.summarize("x".repeat(200)).length)   // 90 字 + 省略号
    }

    // ---------- 单条微博标识解析 ----------

    @Test
    fun parseBidFromInput_variants() {
        assertEquals("RnQI2A5sU", WeiboApi.parseBidFromInput("https://m.weibo.cn/detail/RnQI2A5sU"))
        assertEquals("RnQI2A5sU", WeiboApi.parseBidFromInput("https://m.weibo.cn/status/RnQI2A5sU?f=1"))
        assertEquals("RnQI2A5sU", WeiboApi.parseBidFromInput("https://weibo.com/1234567/RnQI2A5sU"))
        assertEquals("AbCd12345", WeiboApi.parseBidFromInput("AbCd12345"))
        assertEquals("5356841628600820", WeiboApi.parseBidFromInput("5356841628600820"))
        assertEquals("", WeiboApi.parseBidFromInput("https://m.weibo.cn/u/123456"))
        assertEquals("", WeiboApi.parseBidFromInput("不是链接也不是ID"))
    }

    // ---------- cookie 工具 ----------

    @Test
    fun cookie_roundtrip() {
        val raw = "SUB=abc123; SUBP=def; SCF=x"
        assertEquals(raw, WeiboApi.cookieToString(WeiboApi.parseCookieString(raw)))
        assertEquals(emptyMap<String, String>(), WeiboApi.parseCookieString("垃圾串无等号"))
    }

    // ---------- 出站 URL 白名单（安全约束：仅微博域，拒绝其余 host 与私网 IP） ----------

    @Test
    fun weiboUrl_acceptsWeiboHosts() {
        assertEquals(
            "https://wx1.sinaimg.cn/large/a.jpg",
            WeiboApi.weiboUrl("https://wx1.sinaimg.cn/large/a.jpg"),
        )
        assertEquals(
            "https://video.weibo.com/media/play?livephoto=x",
            WeiboApi.weiboUrl("https://video.weibo.com/media/play?livephoto=x"),
        )
    }

    @Test
    fun weiboUrl_rejectsForeignAndPrivateHosts() {
        assertThrows(WeiboApi.WeiboException::class.java) {
            WeiboApi.weiboUrl("https://evil.example.com/steal.jpg")
        }
        assertThrows(WeiboApi.WeiboException::class.java) {
            WeiboApi.weiboUrl("http://192.168.1.1/internal")
        }
        assertThrows(WeiboApi.WeiboException::class.java) {
            WeiboApi.weiboUrl("http://127.0.0.1:8000/")
        }
        assertThrows(WeiboApi.WeiboException::class.java) {
            WeiboApi.weiboUrl("ftp://m.weibo.cn/a")
        }
    }

    // ---------- 签到响应解析（三种成功口径 + 失败 + 非 JSON） ----------

    @Test
    fun parseSignResponse_successWithRankAndExp() {
        val r = WeiboApi.parseSignResponse(
            """{"code":"100000","data":{"alert_title":"签到第12名","alert_subtitle":"经验+8"}}"""
        )
        assertTrue(r.ok)
        assertEquals("签到第12名", r.alertTitle)
        assertEquals("经验+8", r.alertSubtitle)
        assertEquals("签到第12名 · 经验+8", r.summary)
    }

    @Test
    fun parseSignResponse_acceptsOkAndResultFlags() {
        assertTrue(WeiboApi.parseSignResponse("""{"ok":1}""").ok)
        assertTrue(WeiboApi.parseSignResponse("""{"result":1}""").ok)
        assertTrue(WeiboApi.parseSignResponse("""{"result":"1"}""").ok)
    }

    @Test
    fun parseSignResponse_failureKeepsMessage() {
        val r = WeiboApi.parseSignResponse("""{"ok":0,"msg":"今天已经签到过了"}""")
        assertFalse(r.ok)
        assertEquals("今天已经签到过了", r.msg)
        assertEquals("", r.summary)
    }

    @Test
    fun parseSignResponse_nonJsonIsFailure() {
        val r = WeiboApi.parseSignResponse("<html>blocked</html>")
        assertFalse(r.ok)
        assertTrue(r.msg.contains("blocked"))
    }

    // ---------- 登录页导航白名单（v0.9.2：补新浪 SSO 域） ----------

    @Test
    fun isLoginHost_allowsWeiboAndSinaSso() {
        // 微博自家域
        assertTrue(WeiboApi.isLoginHost("passport.weibo.com"))
        assertTrue(WeiboApi.isLoginHost("m.weibo.cn"))
        assertTrue(WeiboApi.isLoginHost("weibo.com"))
        // 新浪 SSO：wapsso 把登录态写回 .weibo.cn 的必经一跳，拦掉会停在半登录态
        assertTrue(WeiboApi.isLoginHost("login.sina.com.cn"))
        assertTrue(WeiboApi.isLoginHost("passport.sina.com.cn"))
        assertTrue(WeiboApi.isLoginHost("sina.com.cn"))
        // .cn 护照域：实测漏掉它就会卡在「已拦截跳转：passport.sina.cn」
        assertTrue(WeiboApi.isLoginHost("passport.sina.cn"))
        assertTrue(WeiboApi.isLoginHost("login.sina.cn"))
        assertTrue(WeiboApi.isLoginHost("sina.cn"))
        // 大小写 / 结尾点号不影响判定
        assertTrue(WeiboApi.isLoginHost("Login.Sina.Com.CN."))
    }

    @Test
    fun isLoginHost_rejectsLookalikesAndOthers() {
        assertFalse(WeiboApi.isLoginHost(""))
        assertFalse(WeiboApi.isLoginHost("example.com"))
        assertFalse(WeiboApi.isLoginHost("evil-sina.com.cn"))     // 前缀伪装
        assertFalse(WeiboApi.isLoginHost("sina.com.cn.evil.com")) // 后缀伪装
        assertFalse(WeiboApi.isLoginHost("weibo.com.evil.com"))
        assertFalse(WeiboApi.isLoginHost("notweibo.cn"))
    }

    @Test
    fun isWeiboHost_staysStrictForOutboundApi() {
        // 出口白名单不能被登录白名单放宽：cookie 只允许发往微博自家人域名
        assertFalse(WeiboApi.isWeiboHost("login.sina.com.cn"))
        assertTrue(WeiboApi.isWeiboHost("m.weibo.cn"))
    }

    // ---------- data.login 宽松解析（v0.9.2） ----------

    @Test
    fun parseLoginFlag_acceptsBooleanNumberAndString() {
        assertTrue(WeiboApi.parseLoginFlag(true))
        assertTrue(WeiboApi.parseLoginFlag(1))
        assertTrue(WeiboApi.parseLoginFlag(1L))
        assertTrue(WeiboApi.parseLoginFlag("1"))
        assertTrue(WeiboApi.parseLoginFlag("true"))
        assertTrue(WeiboApi.parseLoginFlag(" TRUE "))
    }

    @Test
    fun parseLoginFlag_treatsEverythingElseAsNotLoggedIn() {
        assertFalse(WeiboApi.parseLoginFlag(null))
        assertFalse(WeiboApi.parseLoginFlag(false))
        assertFalse(WeiboApi.parseLoginFlag(0))
        assertFalse(WeiboApi.parseLoginFlag("false"))
        assertFalse(WeiboApi.parseLoginFlag("0"))
        assertFalse(WeiboApi.parseLoginFlag(org.json.JSONObject()))
    }

    // ---------- 签到响应判定（v0.9.6：对齐面板口径） ----------

    @Test
    fun parseSignResponse_countsFlagSuccess() {
        assertTrue(WeiboApi.parseSignResponse("""{"ok":1}""").ok)
        assertTrue(WeiboApi.parseSignResponse("""{"ok":"1"}""").ok)
        assertTrue(WeiboApi.parseSignResponse("""{"result":1}""").ok)
        assertTrue(WeiboApi.parseSignResponse("""{"code":"100000"}""").ok)
    }

    @Test
    fun parseSignResponse_countsSuccessCopy() {
        // 微博对「今天已经签过」常回 ok=0 + 文案写着已签到：这是成功，不是失败
        assertTrue(WeiboApi.parseSignResponse("""{"ok":0,"msg":"今天已签到"}""").ok)
        assertTrue(WeiboApi.parseSignResponse("""{"ok":0,"msg":"签到成功，经验 +8"}""").ok)
        assertTrue(WeiboApi.parseSignResponse("""{"ok":0,"msg":"今天已簽到"}""").ok)
    }

    @Test
    fun parseSignResponse_plainFailureStaysFailure() {
        val r = WeiboApi.parseSignResponse("""{"ok":0,"msg":"系统繁忙，请稍后再试"}""")
        assertFalse(r.ok)
        assertEquals("系统繁忙，请稍后再试", r.msg)
        assertFalse(r.needsVerifyRetry)
    }

    @Test
    fun parseSignResponse_flagsVerifyRetry() {
        val r = WeiboApi.parseSignResponse("""{"ok":0,"errno":100015,"msg":"验签失败"}""")
        assertFalse(r.ok)
        assertEquals(100015, r.errno)
        assertTrue(r.needsVerifyRetry)

        // errno 可能是字符串，也可能嵌在 data 里
        val nested = WeiboApi.parseSignResponse("""{"ok":0,"data":{"errno":"100015"}}""")
        assertEquals(100015, nested.errno)
        assertTrue(nested.needsVerifyRetry)

        // 已成功 / 其它错误码都不该触发重试
        assertFalse(WeiboApi.parseSignResponse("""{"ok":1}""").needsVerifyRetry)
        assertFalse(
            WeiboApi.parseSignResponse("""{"ok":0,"errno":100001,"msg":"系统繁忙"}""").needsVerifyRetry
        )
    }

    @Test
    fun parseSignResponse_readsAlertCopy() {
        val r = WeiboApi.parseSignResponse(
            """{"ok":1,"data":{"alert_title":"签到第 12 名","alert_subtitle":"经验 +8"}}"""
        )
        assertTrue(r.ok)
        assertEquals("签到第 12 名 · 经验 +8", r.summary)
    }
}
