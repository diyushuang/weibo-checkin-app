package com.wcm.checkin

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动重登编排（[AutoRelogin]）的「不发请求」路径测试。
 *
 * 为什么值得先测这一块：这些判定就是**账号安全边界**——24 小时 3 次、失败退避、
 * 硬停后必须人工。越过一次上限，就多一次把账号撞进微博风控的机会，而这种错误在
 * 手工回归里几乎不可能稳定复现。
 *
 * 到 `attemptLogin()` 之后的路径需要真实 HTTP，不在单测范围（见真机清单 V9–V16）。
 */
class AutoReloginTest {

    private val ctx = mockk<Context>(relaxed = true)
    private val now = System.currentTimeMillis()

    private fun acc(uid: String = "1234567890") = Account(
        uid = uid,
        name = "测试账号",
        // 刚登录：CookieLife 会算出「剩余 7 天」= OK
        cookie = "SUB=test_sub",
        cookieIssuedAt = now,
        cookieSeenAt = now,
    )

    private fun service(
        port: FakeAccountPort,
        cred: FakeCredentialPort,
    ): AutoRelogin = AutoRelogin(port, cred)

    @Test
    fun globalSwitchOff_skipsWithoutRecordingAttempt() {
        val port = FakeAccountPort().apply {
            accounts = mutableListOf(acc())
            reloginEnabled = false
        }
        val out = service(port, FakeCredentialPort(password = "pwd")).tryAutoRelogin(ctx, acc(), "test")

        assertFalse(out.ok)
        assertFalse("没发起登录就不该记账", out.attempted)
        assertEquals("disabled", out.code)
        assertTrue("总开关关闭时不该写任何重登状态", port.states.isEmpty())
    }

    @Test
    fun accountSwitchOff_skips() {
        val a = acc().copy(autoReloginEnabled = false)
        val port = FakeAccountPort().apply {
            accounts = mutableListOf(a)
            reloginEnabled = true
        }
        val out = service(port, FakeCredentialPort(password = "pwd")).tryAutoRelogin(ctx, a, "test")

        assertEquals("disabled", out.code)
        assertFalse(out.attempted)
    }

    @Test
    fun noCredentialAtAll_skips() {
        val port = FakeAccountPort().apply {
            accounts = mutableListOf(acc())
            reloginEnabled = true
        }
        val out = service(port, FakeCredentialPort(password = null, alc = null))
            .tryAutoRelogin(ctx, acc(), "test")

        assertEquals("no_credentials", out.code)
        assertFalse(out.attempted)
    }

    /** 只有 ALC、没有账号密码也算「有凭证」——这正是 ALC 免密重签存在的意义。 */
    @Test
    fun alcOnly_countsAsCredential_andIsNotSkippedForMissingPassword() {
        val port = FakeAccountPort().apply {
            accounts = mutableListOf(acc())
            reloginEnabled = true
        }
        val out = service(port, FakeCredentialPort(password = null, alc = "alc_ticket"))
            .tryAutoRelogin(ctx, acc(), "test")

        // 走到「cookie 仍可用」这一条，说明凭证判定已经把 ALC 算进去了
        assertEquals("not_needed", out.code)
        assertFalse(out.attempted)
    }

    @Test
    fun cookieStillUsable_isNotRelogged() {
        val port = FakeAccountPort().apply {
            accounts = mutableListOf(acc())
            reloginEnabled = true
        }
        val out = service(port, FakeCredentialPort(password = "pwd")).tryAutoRelogin(ctx, acc(), "test")

        assertEquals("not_needed", out.code)
        assertFalse(out.attempted)
        assertTrue(port.states.isEmpty())
    }

    @Test
    fun backoffNotElapsed_skips() {
        val a = acc()
        val port = FakeAccountPort().apply {
            accounts = mutableListOf(a)
            reloginEnabled = true
            states[a.uid] = ReloginPolicy.State(nextAt = now + 3_600_000L)
        }
        val out = service(port, FakeCredentialPort(password = "pwd")).tryAutoRelogin(ctx, a, "test")

        assertEquals("backoff", out.code)
        assertFalse(out.attempted)
    }

    /**
     * 24 小时上限：已经试过 3 次就不再试，并把 `nextAt` 排到窗口内最早一次尝试之后，
     * 避免每轮保活都重复算一遍。
     */
    @Test
    fun dailyCap_reached_skipsAndReschedulesNextAt() {
        val a = acc()
        val attempts = listOf(now - 5 * 3_600_000L, now - 2 * 3_600_000L, now - 1 * 3_600_000L)
        val port = FakeAccountPort().apply {
            accounts = mutableListOf(a)
            reloginEnabled = true
            maxPerDay = 3
            states[a.uid] = ReloginPolicy.State(attempts = attempts)
        }
        val out = service(port, FakeCredentialPort(password = "pwd")).tryAutoRelogin(ctx, a, "test")

        assertEquals("daily_cap", out.code)
        assertFalse(out.attempted)
        val saved = port.states.getValue(a.uid)
        assertEquals("nextAt 应排到最早一次尝试 + 24h", attempts.min() + 86_400_000L, saved.nextAt)
    }

    @Test
    fun hardStopCode_blocksEvenWhenForced() {
        val a = acc()
        val port = FakeAccountPort().apply {
            accounts = mutableListOf(a)
            reloginEnabled = true
            states[a.uid] = ReloginPolicy.State(
                blocked = true,
                lastCode = ReloginPolicy.CODE_BAD_CREDENTIALS,
                lastReason = "账号或密码错误",
            )
        }
        val out = service(port, FakeCredentialPort(password = "pwd"))
            .tryAutoRelogin(ctx, a, "manual", force = true)

        assertEquals(ReloginPolicy.CODE_BAD_CREDENTIALS, out.code)
        assertFalse("硬停不允许被 force 绕过", out.attempted)
    }

    /**
     * 风控类暂停允许用户手动试一次，但 10 分钟内仍要挡住。
     *
     * 只断言「被挡住」这一半：允许的那一半会真的调用 `attemptLogin()` 发 HTTP，
     * 单测里不能跑（会变成不可靠的集成测试）。
     */
    @Test
    fun riskControlBlock_stillHasTenMinuteCooldown() {
        val a = acc()
        val base = ReloginPolicy.State(
            blocked = true,
            lastCode = ReloginPolicy.CODE_RISK_CONTROL,
            lastAttemptAt = now - 60_000L,
        )
        val port = FakeAccountPort().apply {
            accounts = mutableListOf(a)
            reloginEnabled = true
            states[a.uid] = base
        }

        val out = service(port, FakeCredentialPort(password = "pwd"))
            .tryAutoRelogin(ctx, a, "manual", force = true)

        assertEquals("cooldown", out.code)
        assertFalse(out.attempted)
    }
}
