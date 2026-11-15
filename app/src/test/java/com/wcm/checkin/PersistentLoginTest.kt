package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持久登录改造的纯逻辑回归：ALC 重签的解析、登录墙判定、会话换发回收、
 * 以及「cookie 加密落盘」的序列化契约。
 *
 * 这里刻意不碰 Android / Keystore：加密由 [AccountStore.serializeAccounts] 的
 * `sealCookie` 形参注入，测试用假实现即可验证"落盘形态"与"读取兼容"两件事。
 */
class PersistentLoginTest {

    // ---------- ALC 重签：纯解析 ----------

    @Test
    fun normalizeAlc_acceptsPastedForms() {
        assertEquals("AbC123", WeiboAlcLogin.normalizeAlc("AbC123"))
        assertEquals("AbC123", WeiboAlcLogin.normalizeAlc("  ALC=AbC123  "))
        assertEquals("AbC123", WeiboAlcLogin.normalizeAlc("alc=AbC123"))
        // 用户常整条粘过来：只取 ALC 那一段
        assertEquals("AbC123", WeiboAlcLogin.normalizeAlc("ALC=AbC123; SUBP=xxx; SUB=yyy"))
        assertEquals("", WeiboAlcLogin.normalizeAlc("   "))
    }

    @Test
    fun extractLocationReplace_readsBothQuoteStyles() {
        val html = """<html><script>location.replace("https://passport.weibo.com/sso/x?a=1")</script></html>"""
        assertEquals(
            "https://passport.weibo.com/sso/x?a=1",
            WeiboAlcLogin.extractLocationReplace(html),
        )
        val single = "<script>location.replace('https://passport.weibo.cn/y')</script>"
        assertEquals("https://passport.weibo.cn/y", WeiboAlcLogin.extractLocationReplace(single))
    }

    @Test
    fun extractLocationReplace_nullWhenAbsent() {
        assertNull(WeiboAlcLogin.extractLocationReplace("<html>nothing here</html>"))
        assertNull(WeiboAlcLogin.extractLocationReplace(""))
    }

    @Test
    fun extractCrossDomainUrls_readsArrUrl() {
        val html = """
            <script>
            parent.sinaSSOController.setCrossDomainUrlList(
              {"retcode":0,"arrURL":["https://passport.weibo.com/a?b=1","https://passport.weibo.cn/c"]});
            </script>
        """.trimIndent()
        assertEquals(
            listOf("https://passport.weibo.com/a?b=1", "https://passport.weibo.cn/c"),
            WeiboAlcLogin.extractCrossDomainUrls(html),
        )
    }

    @Test
    fun extractCrossDomainUrls_toleratesBrokenJson() {
        // 微博偶尔回 `retcode` 为字符串、或多包一层，解析必须"拿不到就返回空"而不是抛
        assertTrue(WeiboAlcLogin.extractCrossDomainUrls("setCrossDomainUrlList(not json);").isEmpty())
        assertTrue(WeiboAlcLogin.extractCrossDomainUrls("nothing").isEmpty())
        val withStringRetcode = """setCrossDomainUrlList({"retcode":"0","arrURL":["https://a/1"]});"""
        assertEquals(listOf("https://a/1"), WeiboAlcLogin.extractCrossDomainUrls(withStringRetcode))
    }

    @Test
    fun trustedTarget_blocksForeignHosts() {
        // 这一路请求携带 ALC，跳转目标由服务端响应决定，必须过白名单
        assertEquals(
            "https://passport.weibo.com/sso/v2/x?alt=1",
            WeiboAlcLogin.trustedTarget("https://passport.weibo.com/sso/v2/x?alt=1"),
        )
        assertEquals(
            "https://login.sina.com.cn/sso/login.php",
            WeiboAlcLogin.trustedTarget("https://login.sina.com.cn/sso/login.php"),
        )
        assertNull(WeiboAlcLogin.trustedTarget("https://evil.example.com/steal"))
        assertNull(WeiboAlcLogin.trustedTarget("http://passport.weibo.com/sso/x"))
        assertNull(WeiboAlcLogin.trustedTarget("javascript:alert(1)"))
        assertNull(WeiboAlcLogin.trustedTarget(""))
        assertNull(WeiboAlcLogin.trustedTarget(null))
    }

    // ---------- 登录墙判定（S11 信号） ----------

    @Test
    fun loginWall_detectsVisitorAndLoginPages() {
        assertTrue(
            WeiboHttp.looksLikeLoginWall("<html><title>Sina Visitor System</title></html>"),
        )
        assertTrue(
            WeiboHttp.looksLikeLoginWall(
                """<html><script>location.href="https://passport.weibo.com/sso/signin?entry=wapsso"</script></html>""",
            ),
        )
        assertTrue(WeiboHttp.looksLikeLoginWall("<html>请登录后使用</html>"))
    }

    @Test
    fun loginWall_ignoresJsonAndPlainText() {
        // 业务 JSON 哪怕带「登录」字样也不是登录墙
        assertFalse(WeiboHttp.looksLikeLoginWall("""{"ok":-100,"msg":"请先登录"}"""))
        assertFalse(WeiboHttp.looksLikeLoginWall("""{"ok":1,"data":{}}"""))
        assertFalse(WeiboHttp.looksLikeLoginWall(""))
        // 普通纯文本（非 HTML）不判
        assertFalse(WeiboHttp.looksLikeLoginWall("签到成功"))
    }

    @Test
    fun loginLanding_detectsSigninPaths() {
        assertTrue(WeiboHttp.isLoginLanding("https://passport.weibo.com/sso/signin?entry=wapsso"))
        assertTrue(WeiboHttp.isLoginLanding("https://passport.weibo.cn/signin/login?r=x"))
        assertFalse(WeiboHttp.isLoginLanding("https://m.weibo.cn/api/config"))
    }

    // ---------- 会话：换发回收 ----------

    @Test
    fun session_mergeKeepsLatestValues() {
        val session = WeiboHttp.WeiboSession.of("SUB=old; SUBP=perm; SCF=1")
        assertEquals("SUB=old; SUBP=perm; SCF=1", session.cookie)
        assertTrue(session.isLoggedInShaped)

        // 探活/签到响应里换发的新 SUB 并进来：同名覆盖，其余保留
        session.merge("SUB=new; ALF=1700000000")
        val parsed = WeiboHttp.parseCookieString(session.cookie)
        assertEquals("new", parsed["SUB"])
        assertEquals("perm", parsed["SUBP"])
        assertEquals("1700000000", parsed["ALF"])
    }

    @Test
    fun session_domainHintsRoundTrip() {
        val hints = "SUB=weibo.cn;SUBP=weibo.cn"
        val session = WeiboHttp.WeiboSession.of("SUB=a", hints)
        assertEquals(hints, session.domainHints)
        // 空提示 / 脏数据不炸
        assertEquals("", WeiboHttp.WeiboSession.of("SUB=a", "").domainHints)
        assertEquals("SUB=weibo.cn", WeiboHttp.WeiboSession.of("SUB=a", ";;SUB=weibo.cn;=;").domainHints)
    }

    @Test
    fun digest_isStableAndNotTheCookie() {
        val cookie = "SUB=secret-value; SUBP=x"
        val d = WeiboHttp.digest(cookie)
        assertEquals(d, WeiboHttp.digest(cookie))
        assertFalse(d.contains("secret"))
        assertEquals(32, d.length)
        assertTrue(d != WeiboHttp.digest("SUB=another; SUBP=x"))
    }

    // ---------- 加密落盘的序列化契约 ----------

    private val sample = Account(
        uid = "1234567890",
        name = "测试账号",
        cookie = "SUB=plain-secret; SUBP=p; SCF=s",
        cookieIssuedAt = 1000L,
        cookieLoginAt = 2000L,
        cookieDomains = "SUB=weibo.cn",
        alcAt = 3000L,
    )

    @Test
    fun serialize_runsSealHookOnCookieOnly() {
        val raw = AccountStore.serializeAccounts(listOf(sample)) { "SEALED:$it" }
        assertTrue(raw.contains("SEALED:SUB=plain-secret"))
        // 昵称、uid 这类非敏感字段保持可读，便于排查
        assertTrue(raw.contains("测试账号"))
        assertTrue(raw.contains("1234567890"))
        // cookie 的明文整串不得出现（seal 之前不含前缀的那一份）
        assertFalse(raw.contains("\"cookie\":\"SUB=plain-secret"))
    }

    @Test
    fun serialize_deserialize_defaultIsIdentity() {
        // 默认 seal 是恒等：JVM 单测与老数据路径都靠这个前提
        val raw = AccountStore.serializeAccounts(listOf(sample))
        val back = AccountStore.deserializeAccounts(raw)
        assertEquals(1, back.size)
        assertEquals(sample.cookie, back[0].cookie)
        assertEquals(sample.cookieDomains, back[0].cookieDomains)
        assertEquals(sample.alcAt, back[0].alcAt)
    }

    @Test
    fun deserialize_acceptsSealedAndPlaintextTogether() {
        val raw = AccountStore.serializeAccounts(listOf(sample)) { "SEALED:$it" }
        val back = AccountStore.deserializeAccounts(raw) { it.removePrefix("SEALED:") }
        assertEquals(sample.cookie, back[0].cookie)

        // 老版本明文 + 新版本密文混在一张表里也要能读（升级中间态）
        val mixed = """
            [{"uid":"1","name":"老账号","cookie":"SUB=old-plain"},
             {"uid":"2","name":"新账号","cookie":"SEALED:SUB=new"}]
        """.trimIndent()
        val rows = AccountStore.deserializeAccounts(mixed) { it.removePrefix("SEALED:") }
        assertEquals(listOf("SUB=old-plain", "SUB=new"), rows.map { it.cookie })
    }

    @Test
    fun deserialize_survivesUnreadableCookie() {
        // 解密失败（换机恢复 / Keystore 异常）时该字段降级为空串，账号行本身不丢
        val raw = AccountStore.serializeAccounts(listOf(sample)) { "SEALED:$it" }
        val rows = AccountStore.deserializeAccounts(raw) { "" }
        assertEquals(1, rows.size)
        assertEquals("1234567890", rows[0].uid)
        assertEquals("", rows[0].cookie)
    }
}
