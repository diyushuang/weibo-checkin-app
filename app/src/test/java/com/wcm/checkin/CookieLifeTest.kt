package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CookieLife 纯函数测试：有效期估算与面板端 `describe_cookie_lifespan` 同口径。
 *
 * 时间全部用「当前时刻减去若干天」构造，不写死绝对时间戳，避免测试随时间腐烂。
 */
class CookieLifeTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L     // 固定"当前时刻"，断言可以精确到天

    // ---------- ALF 解析 ----------

    @Test
    fun parseAlf_secondsAndMillisAndGarbage() {
        // 秒级 → 毫秒
        assertEquals(1_900_000_000_000L, CookieLife.parseAlf("SUB=x; ALF=1900000000"))
        // 已经是毫秒 → 不重复乘 1000
        assertEquals(1_900_000_000_000L, CookieLife.parseAlf("ALF=1900000000000"))
        // 缺失 / 非数字 / 2001 年前的脏值一律判 0（无法估算）
        assertEquals(0L, CookieLife.parseAlf("SUB=x"))
        assertEquals(0L, CookieLife.parseAlf("ALF=abc"))
        assertEquals(0L, CookieLife.parseAlf("ALF=123456"))
        assertEquals(0L, CookieLife.parseAlf(""))
    }

    // ---------- 没有任何锚点 ----------

    @Test
    fun describe_withoutAnchors_isUnknown() {
        val v = CookieLife.describe(cookie = "SUB=x", now = now)
        assertEquals(CookieLife.State.UNKNOWN, v.state)
        assertFalse(v.known)
        assertFalse(v.alert)
        assertEquals(0L, v.deadlineAt)
    }

    // ---------- 面板基准（同步时刻 + 7 天） ----------

    @Test
    fun describe_justSynced_isOkWithSevenDays() {
        val v = CookieLife.describe(cookie = "SUB=x", syncedAt = now, now = now)
        assertEquals(CookieLife.State.OK, v.state)
        assertTrue(v.fromPanel)
        assertEquals(now + 7 * day, v.deadlineAt)
        assertEquals(7.0, v.daysLeft!!, 0.001)
        assertFalse(v.alert)
    }

    @Test
    fun describe_sixDaysAfterSync_isExpiring() {
        val v = CookieLife.describe(cookie = "SUB=x", syncedAt = now - 6 * day, now = now)
        assertEquals(CookieLife.State.EXPIRING, v.state)
        assertTrue(v.alert)
        assertEquals(1.0, v.daysLeft!!, 0.001)
    }

    @Test
    fun describe_pastDeadline_isExpired() {
        val v = CookieLife.describe(cookie = "SUB=x", syncedAt = now - 8 * day, now = now)
        assertEquals(CookieLife.State.EXPIRED, v.state)
        assertTrue(v.alert)
        assertTrue(v.daysLeft!! < 0)
    }

    /** 同步优先于本机锚点：面板拿到的是一份新 cookie，本机旧记录不该把它拖成「已过期」。 */
    @Test
    fun describe_panelAnchorWinsOverLocalAnchors() {
        val v = CookieLife.describe(
            cookie = "SUB=x",
            issuedAt = now - 30 * day,
            rotatedAt = now - 20 * day,
            seenAt = now - 10 * day,
            syncedAt = now - 1 * day,
            now = now,
        )
        assertEquals(CookieLife.State.OK, v.state)
        assertEquals(now - 1 * day + 7 * day, v.deadlineAt)
    }

    // ---------- 本机锚点（未同步） ----------

    @Test
    fun describe_localUsesEarliestAnchor() {
        // 面板端取 min(issued, rotated, seen)，App 侧未同步时同口径
        val v = CookieLife.describe(
            cookie = "SUB=x",
            issuedAt = now - 3 * day,
            rotatedAt = now - 1 * day,
            seenAt = now,
            now = now,
        )
        assertEquals(CookieLife.State.OK, v.state)
        assertEquals(now - 3 * day + 7 * day, v.deadlineAt)
        assertFalse(v.fromPanel)
    }

    // ---------- ALF 与经验寿命取最早 ----------

    @Test
    fun describe_alfEarlierThanObservedWins() {
        val alfSeconds = (now + 2 * day) / 1000
        val v = CookieLife.describe(
            cookie = "ALF=$alfSeconds",
            syncedAt = now,
            now = now,
        )
        assertEquals(CookieLife.State.EXPIRING, v.state)
        assertEquals(2.0, v.daysLeft!!, 0.01)
    }

    /**
     * ALF 早于基准 = 上一轮登录的旧 ALF（合并 cookie 时很常见）。
     * 如果照单全收，刚同步成功的账号会立刻显示「已过期」—— 这是要避免的误报。
     */
    @Test
    fun describe_staleAlfBeforeAnchorIsIgnored() {
        val staleAlf = (now - 100 * day) / 1000
        val v = CookieLife.describe(
            cookie = "SUB=x; ALF=$staleAlf",
            syncedAt = now,
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
        // 只剩 3 天，阈值 5 天 → 判临期
        assertEquals(CookieLife.State.EXPIRING, v.state)
        assertEquals(now + 3 * day, v.deadlineAt)
    }

    /** 便捷重载：账号行的四个时间戳直接喂进来，结果应与逐个传参一致。 */
    @Test
    fun describe_accountOverloadMatchesExplicit() {
        val acc = AccountStore.Account(
            uid = "42", name = "小号", cookie = "SUB=x",
            cookieIssuedAt = now - 2 * day,
            cookieSeenAt = now - 1 * day,
            cookieSyncedAt = now - 3 * day,
        )
        val v = CookieLife.describe(acc, now = now)
        assertEquals(CookieLife.State.OK, v.state)
        assertEquals(now - 3 * day + 7 * day, v.deadlineAt)
        assertTrue(v.fromPanel)
    }
}
