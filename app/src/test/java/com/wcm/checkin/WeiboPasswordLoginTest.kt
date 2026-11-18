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
    fun classifyRetcode_1117IsRiskControlNotUnknown() {
        // 线上实测：换任意账号（不存在的用户名 / 手机号 / 邮箱）都是 1117
        // 「抱歉！登录失败，请稍候再试」—— 它是服务端对账密登录的通用拒绝，
        // 不是「密码错」，也不该落进 unknown 让用户看到「未知错误」。
        assertEquals(
            ReloginPolicy.CODE_RISK_CONTROL,
            WeiboPasswordLogin.classifyRetcode("1117", "抱歉！登录失败，请稍候再试"),
        )
    }

    @Test
    fun classifyRetcode_verificationHintBeatsCodeTable() {
        // 微博会把「要短信验证」配在一个通用错误码上：文案优先于码表，
        // 否则会被判成风控/未知，用户永远看不到该去验证的提示。
        assertEquals(
            ReloginPolicy.CODE_NEEDS_MANUAL,
            WeiboPasswordLogin.classifyRetcode("1117", "为保障账号安全，请完成短信验证"),
        )
        // 验证页 HTML 正文（不是 JSON 时也走这条）
        assertEquals(
            ReloginPolicy.CODE_NEEDS_MANUAL,
            WeiboPasswordLogin.classifyRetcode("", "<html>请输入手机验证码后继续</html>"),
        )
    }

    @Test
    fun rsaEncrypt_acceptsPemX509PublicKey() {
        // 2026-11 起 sso/prelogin.php 的 pubkey 变成 PEM（X.509 SubjectPublicKeyInfo）。
        // 老实现直接 BigInteger(pubkey, 16) → NumberFormatException → 被归成 unknown，
        // 用户看到的就是「微博登录返回未知错误」，密码再对也过不去。
        val pair = newPair()
        val pem = pemX509(pair.public)

        val sp = WeiboPasswordLogin.rsaEncrypt(pem, "1700000000", "abcdef", "密码123")

        assertEquals("1700000000\tabcdef\n密码123", decryptSp(sp, pair))
    }

    @Test
    fun rsaEncrypt_acceptsPemPkcs1PublicKey() {
        val pair = newPair()
        val pem = pemPkcs1(pair.public as RSAPublicKey)

        val sp = WeiboPasswordLogin.rsaEncrypt(pem, "s", "n", "p")

        assertEquals("s\tn\np", decryptSp(sp, pair))
    }

    @Test
    fun rsaEncrypt_acceptsLivePemShape() {
        // 线上抓到的真实公钥（2026-11-18 实测）。它固定住「真实形态能被解析」这条底线：
        // 只要 parsePublicKey 不认这种写法，账密重登就整体失效。
        val pub = WeiboPasswordLogin.parsePublicKey(LIVE_PUBKEY) as RSAPublicKey
        assertEquals(1024, pub.modulus.bitLength())
        assertEquals(java.math.BigInteger.valueOf(65537), pub.publicExponent)

        val sp = WeiboPasswordLogin.rsaEncrypt(LIVE_PUBKEY, "1795010629", "5KX1WV", "x")
        // PKCS#1 v1.5 + 1024 位密钥 = 固定 128 字节密文
        assertEquals(128, Base64.getDecoder().decode(sp).size)
    }

    @Test
    fun rsaEncrypt_stillAcceptsLegacyHexModulus() {
        // 老口径（裸 16 进制 modulus）必须继续可用：公钥格式是服务端说改就改的
        val pair = newPair()
        val hex = (pair.public as RSAPublicKey).modulus.toString(16)

        val sp = WeiboPasswordLogin.rsaEncrypt(hex, "s", "n", "p")

        assertEquals("s\tn\np", decryptSp(sp, pair))
    }

    // ---------- RSA 测试工具 ----------

    private fun newPair() = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(1024) }
        .generateKeyPair()

    private fun decryptSp(sp: String, pair: java.security.KeyPair): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.private)
        return String(cipher.doFinal(Base64.getDecoder().decode(sp)), Charsets.UTF_8)
    }

    /** 按线上口径包成 PEM/X.509（每行 64 字符）。 */
    private fun pemX509(pub: java.security.PublicKey): String = buildString {
        append("-----BEGIN PUBLIC KEY-----\n")
        append(Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(pub.encoded))
        append("\n-----END PUBLIC KEY-----")
    }

    /** 手工拼 PKCS#1 `RSAPublicKey` DER，再包成 `BEGIN RSA PUBLIC KEY`。 */
    private fun pemPkcs1(pub: RSAPublicKey): String {
        fun derInt(v: java.math.BigInteger): ByteArray {
            val raw = v.toByteArray() // 已含符号位，正是 DER 要的形态
            return byteArrayOf(0x02) + derLength(raw.size) + raw
        }
        val body = derInt(pub.modulus) + derInt(pub.publicExponent)
        val der = byteArrayOf(0x30) + derLength(body.size) + body
        return buildString {
            append("-----BEGIN RSA PUBLIC KEY-----\n")
            append(Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(der))
            append("\n-----END RSA PUBLIC KEY-----")
        }
    }

    private fun derLength(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n <= 0xFF -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private companion object {
        /** 2026-11-18 从 `sso/prelogin.php` 实际抓到的公钥原文。 */
        const val LIVE_PUBKEY = """-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDrKjhWhmGIf6GAvdtcq9XyHHv9
WcCQyy0kWoesJTBiiCcpKT5VBjUFCOf5qju3f0MzIxSQ+RX21jxV/i8IpJs1P0RK
05k8rMAtt4Sru45CqbG7//s4vhjXjoeg5Bubj3OpKO4MzuH2c5iEuXd+T+noihu+
SVknrEp5mzGB1kQkQwIDAQAB
-----END PUBLIC KEY-----"""
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
