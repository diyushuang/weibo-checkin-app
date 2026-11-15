package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CookieLife 纯函数测试。
 *
 * 核心口径（与桌面端 `app/cookie_lifecycle.py` 一致）：
 * 寿命只从「真实登录 / 微博真换发关键字段」起算；同步与探活都只是观察，
 * 服务端声明到期与经验推算到期分开展示，最终判定取更早者。
 */
class CookieLifeTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L     // 固定"当前时刻"，断言可以精确到天

    // ---------- ALF / SSOLoginState 解析 ----------

    @Test
    fun parseAlf_secondsAndMillisAndGarbage() {
        assertEquals(1_900_000_000_000L, CookieLife.parseAlf("SUB=x; ALF=1900000000"))
        assertEquals(1_900_000_000_000L, CookieLife.parseAlf("ALF=1900000000000"))
        assertEquals(0L, CookieLife.parseAlf("SUB=x"))
        assertEquals(0L, CookieLife.parseAlf("ALF=abc"))
        assertEquals(0L, CookieLife.parseAlf("ALF=123456"))
        assertEquals(0L, CookieLife.parseAlf(""))
    }

    @Test
    fun loginTimeOf_readsSsoLoginState() {
        assertEquals(1_700_000_000_000L, CookieLife.loginTimeOf("SSOLoginState=1700000000"))
        assertEquals(0L, CookieLife.loginTimeOf("SUB=x"))
        assertEquals(0L, CookieLife.loginTimeOf("SSOLoginState=oops"))
        // 服务端声明到期与真实登录时间必须是两个独立来源
        assertEquals(1_900_000_000_000L, CookieLife.serverExpiryOf("ALF=1900000000"))
    }

    // ---------- 没有任何锚点 ----------

    @Test
    fun describe_withoutAnchors_isUnknown() {
        val v = CookieLife.describe(cookie = "SUB=x", now = now)
        assertEquals(CookieLife.State.UNKNOWN, v.state)
        assertFalse(v.known)
        assertFalse(v.alert)
        assertEquals(0L, v.deadlineAt)
        assertEquals(0L, v.loginAt)
        assertEquals(0L, v.serverExpiresAt)
    }

    // ---------- 旧数据兜底（只有同步时刻） ----------

    @Test
    fun describe_legacySyncOnlyStillEstimates() {
        val v = CookieLife.describe(cookie = "SUB=x", syncedAt = now, now = now)
        assertEquals(CookieLife.State.OK, v.state)
        assertTrue(v.fromPanel)
        assertEquals(now + 7 * day, v.deadlineAt)
        assertEquals(7.0, v.daysLeft!!, 0.001)
        assertFalse(v.alert)
    }

    @Test
    fun describe_legacySyncSixDaysAgo_isExpiring() {
        val v = CookieLife.describe(cookie = "SUB=x", syncedAt = now - 6 * day, now = now)
        assertEquals(CookieLife.State.EXPIRING, v.state)
        assertTrue(v.alert)
        assertEquals(1.0, v.daysLeft!!, 0.001)
    }

    /**
     * 有真实登录时间时，**同步不能续命**：30 天前登录、刚刚同步，仍然按登录时间判过期。
     * 这是旧实现最误导的地方（同步时刻被当成寿命起点）。
     */
    @Test
    fun describe_syncDoesNotExtendLife() {
        val v = CookieLife.describe(
            cookie = "SUB=x",
            loginAt = now - 30 * day,
            syncedAt = now,
            now = now,
        )
        assertEquals(CookieLife.State.EXPIRED, v.state)
        assertEquals(now - 30 * day + 7 * day, v.deadlineAt)
        assertFalse(v.fromPanel)
        assertEquals(now, v.syncedAt)
    }

    /** 探活成功同样不续命：只是「还活着」，不是换了新凭据。 */
    @Test
    fun describe_seenAtDoesNotExtendLife() {
        val v = CookieLife.describe(
            cookie = "SUB=x",
            issuedAt = now - 30 * day,
            seenAt = now,
            now = now,
        )
        assertEquals(CookieLife.State.EXPIRED, v.state)
        assertEquals(now - 30 * day + 7 * day, v.deadlineAt)
    }

    // ---------- 真实登录 / 换发锚点 ----------

    @Test
    fun describe_newLoginResetsLife() {
        val v = CookieLife.describe(
            cookie = "SUB=x",
            issuedAt = now - 30 * day,
            rotatedAt = now - 20 * day,
            loginAt = now - 1 * day,
            now = now,
        )
        assertEquals(CookieLife.State.OK, v.state)
        assertEquals(now - 1 * day + 7 * day, v.deadlineAt)
        assertEquals(now - 1 * day, v.anchorAt)
    }

    @Test
    fun describe_criticalRotationExtendsLife() {
        // 微博在第 1 天换发了新 SUB：这是真续期，寿命从换发时刻重新起算
        val v = CookieLife.describe(
            cookie = "SUB=x",
            issuedAt = now - 8 * day,
            rotatedAt = now - 1 * day,
            now = now,
        )
        assertEquals(CookieLife.State.OK, v.state)
        assertEquals(now - 1 * day + 7 * day, v.deadlineAt)
        assertEquals(6.0, v.daysLeft!!, 0.001)
    }

    @Test
    fun describe_oldIssuedAtAloneStillExpires() {
        val v = CookieLife.describe(
            cookie = "SUB=x",
            issuedAt = now - 30 * day,
            now = now,
        )
        assertEquals(CookieLife.State.EXPIRED, v.state)
    }

    // ---------- 服务端到期 vs 推算到期 ----------

    @Test
    fun describe_serverExpiryEarlierThanEstimateWins() {
        val v = CookieLife.describe(
            cookie = "SUB=x",
            loginAt = now - 1 * day,
            serverExpiresAt = now + 1 * day,
            now = now,
        )
        assertEquals(CookieLife.State.EXPIRING, v.state)
        assertEquals(now + 1 * day, v.deadlineAt)
        assertEquals(now + 6 * day, v.estimatedAt)
        assertTrue(v.serverAuthoritative)
    }

    @Test
    fun describe_estimatedEarlierThanServerExpiryWins() {
        val v = CookieLife.describe(
            cookie = "SUB=x",
            loginAt = now - 6 * day,
            serverExpiresAt = now + 30 * day,
            now = now,
        )
        assertEquals(CookieLife.State.EXPIRING, v.state)
        assertEquals(now + 1 * day, v.deadlineAt)
        assertFalse(v.serverAuthoritative)
    }

    @Test
    fun describe_serverExpiryAloneCanDecide() {
        val v = CookieLife.describe(
            cookie = "SUB=x",
            serverExpiresAt = now + 2 * day,
            now = now,
        )
        // 没有任何登录/同步锚点，但服务端声明了到期 → 仍可给出确定结论
        assertEquals(CookieLife.State.EXPIRING, v.state)
        assertEquals(now + 2 * day, v.deadlineAt)
    }

    // ---------- ALF ----------

    @Test
    fun describe_alfEarlierThanObservedWins() {
        val alfSeconds = (now + 2 * day) / 1000
        val v = CookieLife.describe(cookie = "ALF=$alfSeconds", syncedAt = now, now = now)
        assertEquals(CookieLife.State.EXPIRING, v.state)
        assertEquals(2.0, v.daysLeft!!, 0.01)
    }

    /**
     * ALF 早于基准 = 上一轮登录残留的旧值，必须忽略；否则刚登录成功的账号会立刻显示已过期。
     */
    @Test
    fun describe_staleAlfBeforeAnchorIsIgnored() {
        val staleAlf = (now - 100 * day) / 1000
        val v = CookieLife.describe(
            cookie = "SUB=x; ALF=$staleAlf",
            loginAt = now,
            now = now,
        )
        assertEquals(0L, v.alfAt)
        assertEquals(CookieLife.State.OK, v.state)
        assertEquals(now + 7 * day, v.deadlineAt)
    }

    @Test
    fun describe_customTtlAndWarnWindow() {
        val v = CookieLife.describe(
            cookie = "SUB=x",
            syncedAt = now,
            now = now,
            ttlDays = 3,
            warnDays = 5.0,
        )
        assertEquals(CookieLife.State.EXPIRING, v.state)
        assertEquals(now + 3 * day, v.deadlineAt)
    }

    /** 便捷重载：账号行字段直接喂进来，结果应与逐个传参一致。 */
    @Test
    fun describe_accountOverloadMatchesExplicit() {
        val acc = Account(
            uid = "42", name = "小号", cookie = "SUB=x",
            cookieIssuedAt = now - 2 * day,
            cookieSeenAt = now - 1 * day,
            cookieSyncedAt = now,          // 同步时刻不参与寿命
            cookieLoginAt = now - 2 * day,
        )
        val v = CookieLife.describe(acc, now = now)
        assertEquals(CookieLife.State.OK, v.state)
        assertEquals(now - 2 * day + 7 * day, v.deadlineAt)
        assertFalse(v.fromPanel)
        assertEquals(now, v.syncedAt)
        assertEquals(now - 2 * day, v.loginAt)
    }
}
