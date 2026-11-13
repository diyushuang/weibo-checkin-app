package com.wcm.checkin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

/**
 * 账密登录里可以脱离网络验证的部分：RSA 加密往返、`retcode` 分类、
 * JSONP 解析、跨域回跳白名单，以及「失败文案绝不带密码」这条安全底线。
 */
class WeiboPasswordLoginTest {

    @Test
    fun rsaEncrypt_roundTripsWithJcaPrivateKey() {
        val pair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(1024) }
            .generateKeyPair()
        val modulusHex = (pair.public as RSAPublicKey).modulus.toString(16)

        val sp = WeiboPasswordLogin.rsaEncrypt(modulusHex, "1700000000", "abcdef", "密码123")

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.private)
        val plain = String(cipher.doFinal(Base64.getDecoder().decode(sp)), Charsets.UTF_8)
        assertEquals("1700000000\tabcdef\n密码123", plain)
    }

    @Test
    fun rsaEncrypt_usesTabAndNewlineLayout() {
        // 新浪要求 `servertime \t nonce \n password`，顺序错了服务端只会回「密码错误」，
        // 这里用固定串把格式钉死，避免以后被顺手改成拼接顺序。
        val pair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(1024) }
            .generateKeyPair()
        val modulusHex = (pair.public as RSAPublicKey).modulus.toString(16)

        val sp = WeiboPasswordLogin.rsaEncrypt(modulusHex, "s", "n", "p")

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.private)
        val plain = String(cipher.doFinal(Base64.getDecoder().decode(sp)), Charsets.UTF_8)
        assertEquals("s\tn\np", plain)
    }

    @Test
    fun classifyRetcode_mapsSsoCodes() {
        assertEquals(
            ReloginPolicy.CODE_NEEDS_MANUAL,
            WeiboPasswordLogin.classifyRetcode("107", ""),
        )
        assertEquals(
            ReloginPolicy.CODE_NEEDS_MANUAL,
            WeiboPasswordLogin.classifyRetcode("2070", ""),
        )
        assertEquals(
            ReloginPolicy.CODE_BAD_CREDENTIALS,
            WeiboPasswordLogin.classifyRetcode("101", ""),
        )
        assertEquals(
            ReloginPolicy.CODE_BAD_CREDENTIALS,
            WeiboPasswordLogin.classifyRetcode("112", ""),
        )
        assertEquals(
            ReloginPolicy.CODE_RISK_CONTROL,
            WeiboPasswordLogin.classifyRetcode("2090", ""),
        )
        assertEquals(
            ReloginPolicy.CODE_UNKNOWN,
            WeiboPasswordLogin.classifyRetcode("9999", ""),
        )
        assertEquals(
            ReloginPolicy.CODE_UNKNOWN,
            WeiboPasswordLogin.classifyRetcode("", ""),
        )
    }

    @Test
    fun classifyRetcode_fallsBackToFixedPhrases() {
        // 同码不同因时靠文案兜底；只认固定关键词，不把原文透出去
        assertEquals(
            ReloginPolicy.CODE_NEEDS_MANUAL,
            WeiboPasswordLogin.classifyRetcode("", "需要输入验证码"),
        )
        assertEquals(
            ReloginPolicy.CODE_NEEDS_MANUAL,
            WeiboPasswordLogin.classifyRetcode("", "请完成二次验证"),
        )
        assertEquals(
            ReloginPolicy.CODE_BAD_CREDENTIALS,
            WeiboPasswordLogin.classifyRetcode("", "用户名或密码错误"),
        )
        assertEquals(
            ReloginPolicy.CODE_RISK_CONTROL,
            WeiboPasswordLogin.classifyRetcode("", "操作过于频繁"),
        )
    }

    @Test
    fun parseJsonObject_handlesJsonpAndBareJson() {
        val jsonp = WeiboPasswordLogin.parseJsonObject(
            """sinaSSOController.preloginCallBack({"retcode":0,"servertime":"1700000000"})""",
        )
        assertNotNull(jsonp)
        assertEquals("0", jsonp!!.optString("retcode"))

        assertNotNull(WeiboPasswordLogin.parseJsonObject("""{"retcode":0}"""))
        assertNull(WeiboPasswordLogin.parseJsonObject("not json at all"))
        assertNull(WeiboPasswordLogin.parseJsonObject("""{"broken":}""" + "}"))
    }

    @Test
    fun callbackUrls_onlyAllowsWeiboAndSinaHosts() {
        val payload = JSONObject()
            .put("url", "http://passport.weibo.cn/x?y=1")
            .put(
                "arr",
                JSONArray()
                    .put("https://evil.example.com/cb")
                    .put("https://passport.sina.cn/cb"),
            )
            .put("nested", JSONObject().put("location", "https://login.sina.com.cn/sso/x"))

        val urls = WeiboPasswordLogin.callbackUrls(payload)

        assertTrue(urls.contains("https://passport.weibo.cn/x?y=1"))
        assertTrue(urls.contains("https://passport.sina.cn/cb"))
        assertTrue(urls.contains("https://login.sina.com.cn/sso/x"))
        assertFalse(urls.any { it.contains("evil.example.com") })
        // 只升级到 https，不接受任何明文回跳
        assertFalse(urls.any { it.startsWith("http://") })
    }

    @Test
    fun callbackUrls_dedupesRepeatedLinks() {
        val payload = JSONObject().put(
            "a",
            JSONArray().put("https://passport.weibo.cn/cb").put("https://passport.weibo.cn/cb"),
        )
        assertEquals(1, WeiboPasswordLogin.callbackUrls(payload).size)
    }

    @Test
    fun login_withEmptyCredentialsFailsFastWithoutLeakingPassword() {
        val outcome = WeiboPasswordLogin.login("   ", "s3cret-pw")

        assertFalse(outcome.ok)
        assertEquals(ReloginPolicy.CODE_BAD_CREDENTIALS, outcome.code)
        assertFalse(outcome.message.contains("s3cret-pw"))
        assertEquals("账号或密码错误", outcome.message)
    }
}
