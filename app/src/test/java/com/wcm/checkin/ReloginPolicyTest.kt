package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自动重登策略纯逻辑测试：触发条件、退避、次数上限、硬停、成功复位。 */
class ReloginPolicyTest {

    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun state(
        lastAttemptAt: Long = 0L,
        nextAt: Long = 0L,
        streak: Int = 0,
        attempts: List<Long> = emptyList(),
        blocked: Boolean = false,
        code: String = "",
        reason: String = "",
    ) = ReloginPolicy.State(
        lastAttemptAt = lastAttemptAt,
        nextAt = nextAt,
        failStreak = streak,
        attempts = attempts,
        blocked = blocked,
        lastReason = reason,
        lastCode = code,
    )

    @Test
    fun shouldAttempt_respectsSwitchesAndCredential() {
        val s = state()
        assertFalse(
            ReloginPolicy.shouldAttempt(
                false, true, true, false, s, now, false, 3
            ).allowed
        )
        assertFalse(
            ReloginPolicy.shouldAttempt(
                true, false, true, false, s, now, false, 3
            ).allowed
        )
        assertFalse(
            ReloginPolicy.shouldAttempt(
                true, true, false, false, s, now, false, 3
            ).allowed
        )
        assertTrue(
            ReloginPolicy.shouldAttempt(
                true, true, true, false, s, now, false, 3
            ).allowed
        )
    }

    /** 重登只在 cookie 失效 / 临期时触发：仍可用的账号自动模式必须跳过。 */
    @Test
    fun shouldAttempt_skipsHealthyCookie() {
        val d = ReloginPolicy.shouldAttempt(true, true, true, true, state(), now, false, 3)
        assertFalse(d.allowed)
        assertEquals("not_needed", d.skipCode)
        // 用户手动点「立即重登」不受「仍可用」限制
        assertTrue(
            ReloginPolicy.shouldAttempt(true, true, true, true, state(), now, true, 3).allowed
        )
    }

    @Test
    fun shouldAttempt_blocksDuringBackoff() {
        val d = ReloginPolicy.shouldAttempt(
            true, true, true, false,
            state(nextAt = now + hour), now, false, 3,
        )
        assertFalse(d.allowed)
        assertEquals("backoff", d.skipCode)
        // force 越过退避
        assertTrue(
            ReloginPolicy.shouldAttempt(
                true, true, true, false,
                state(nextAt = now + hour), now, true, 3,
            ).allowed
        )
    }

    @Test
    fun shouldAttempt_blocksWhenDailyCapReached() {
        val attempts = listOf(now - 2 * hour, now - 1 * hour, now - 30 * 60_000L)
        val d = ReloginPolicy.shouldAttempt(
            true, true, true, false,
            state(attempts = attempts), now, false, 3,
        )
        assertFalse(d.allowed)
        assertEquals("daily_cap", d.skipCode)
    }

    @Test
    fun shouldAttempt_ignoresAttemptsOutside24h() {
        val d = ReloginPolicy.shouldAttempt(
            true, true, true, false,
            state(attempts = listOf(now - 25 * hour, now - 2 * hour)), now, false, 3,
        )
        assertTrue(d.allowed)
    }

    @Test
    fun shouldAttempt_hardStopRejectsEvenForce() {
        // 密码错误：自动与人手动都不该再硬试
        val bad = state(blocked = true, code = ReloginPolicy.CODE_BAD_CREDENTIALS)
        assertFalse(
            ReloginPolicy.shouldAttempt(true, true, true, false, bad, now, false, 3).allowed
        )
        assertFalse(
            ReloginPolicy.shouldAttempt(true, true, true, false, bad, now, true, 3).allowed
        )
        // 验证码 / 二次验证同理：停在「需人工扫码/手动处理」
        val manual = state(blocked = true, code = ReloginPolicy.CODE_NEEDS_MANUAL)
        assertFalse(
            ReloginPolicy.shouldAttempt(true, true, true, false, manual, now, true, 3).allowed
        )
    }

    /** 风控 / 网络类型暂停允许用户手动试一次，但要有 10 分钟最短间隔。 */
    @Test
    fun shouldAttempt_riskBlockAllowsManualAfterCooldown() {
        val risk = state(
            blocked = true,
            code = ReloginPolicy.CODE_RISK_CONTROL,
            lastAttemptAt = now - 2 * 60_000L,
        )
        val d = ReloginPolicy.shouldAttempt(true, true, true, false, risk, now, true, 3)
        assertFalse(d.allowed)
        assertEquals("cooldown", d.skipCode)
        val later = ReloginPolicy.shouldAttempt(
            true, true, true, false, risk, now + 10 * 60_000L, true, 3,
        )
        assertTrue(later.allowed)
    }

    // ---------- 失败后的退避 ----------

    @Test
    fun onFailure_backoffDoublesAndCaps() {
        var s = state()
        // 第 1 次：6h
        var f = ReloginPolicy.onFailure(s, ReloginPolicy.CODE_NETWORK, "", now, 10, 6.0)
        assertEquals(now + 6 * hour, f.state.nextAt)
        assertEquals(1, f.state.failStreak)
        assertFalse(f.state.blocked)
        // 第 2 次：12h
        f = ReloginPolicy.onFailure(f.state, ReloginPolicy.CODE_NETWORK, "", now + hour, 10, 6.0)
        assertEquals(now + hour + 12 * hour, f.state.nextAt)
        // 第 4 次：24h 封顶
        f = ReloginPolicy.onFailure(f.state, ReloginPolicy.CODE_NETWORK, "", now + 2 * hour, 10, 6.0)
        f = ReloginPolicy.onFailure(f.state, ReloginPolicy.CODE_NETWORK, "", now + 3 * hour, 10, 6.0)
        assertEquals(now + 3 * hour + 24 * hour, f.state.nextAt)
    }

    @Test
    fun onFailure_badCredentialsBlocksImmediately() {
        val f = ReloginPolicy.onFailure(
            state(), ReloginPolicy.CODE_BAD_CREDENTIALS, "101", now, 3, 6.0,
        )
        assertTrue(f.state.blocked)
        assertEquals(0L, f.state.nextAt)
        assertTrue(f.message.contains("账号或密码错误"))
        assertTrue(f.message.contains("已暂停"))
        assertFalse(f.message.contains("密码是"))     // 只允许固定文案
    }

    @Test
    fun onFailure_needsManualBlocksImmediately() {
        val f = ReloginPolicy.onFailure(
            state(), ReloginPolicy.CODE_NEEDS_MANUAL, "2070", now, 3, 6.0,
        )
        assertTrue(f.state.blocked)
        assertTrue(f.message.contains("验证码"))
        assertTrue(f.message.contains("人工"))
    }

    @Test
    fun onFailure_streakReachingCapBlocks() {
        var s = state(streak = 2)
        val f = ReloginPolicy.onFailure(s, ReloginPolicy.CODE_NETWORK, "", now, 3, 6.0)
        assertTrue(f.state.blocked)
        assertEquals(3, f.state.failStreak)
        assertEquals(0L, f.state.nextAt)
    }

    @Test
    fun onSuccess_resetsEverything() {
        state(
            streak = 4, blocked = true, code = ReloginPolicy.CODE_RISK_CONTROL,
            attempts = listOf(now - day), reason = "已暂停",
        )
        val out = ReloginPolicy.onSuccess(now)
        assertEquals(0, out.failStreak)
        assertFalse(out.blocked)
        assertEquals(0L, out.nextAt)
        assertTrue(out.attempts.isEmpty())
        assertEquals("", out.lastReason)
        assertEquals(now, out.lastAttemptAt)
    }

    @Test
    fun recordAttempt_countsBeforeNetworkCall() {
        val out = ReloginPolicy.recordAttempt(state(attempts = listOf(now - day)), now)
        assertEquals(listOf(now - day, now), out.attempts)
        assertEquals(now, out.lastAttemptAt)
    }

    @Test
    fun reasonText_isClassifiedAndCarriesRetcode() {
        assertEquals("账号或密码错误", ReloginPolicy.reasonText(ReloginPolicy.CODE_BAD_CREDENTIALS))
        assertTrue(
            ReloginPolicy.reasonText(ReloginPolicy.CODE_RISK_CONTROL, "2090").contains("retcode 2090")
        )
        assertEquals(
            "本地凭证无法解密，请重新保存账号密码",
            ReloginPolicy.reasonText(ReloginPolicy.CODE_CREDENTIAL_UNREADABLE),
        )
    }
}
