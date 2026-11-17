package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 熔断器测试。
 *
 * 核心要钉住的是**优先级**：硬停类（需人工 / 环境不对）必须永远先于普通失败计数，
 * 否则「2 次网络失败后遇到验证码」会被动作级放弃吞掉，用户看不到"要你去验证"的提示。
 */
class EngageGuardTest {

    @Test
    fun `成功会清零两级计数`() {
        val g = EngageGuard(5)
        g.onResult(EngageErrorKind.NETWORK)
        g.onResult(EngageErrorKind.NETWORK)
        assertEquals(2, g.currentActionStreak)

        assertEquals(Disposition.CONTINUE, g.onResult(EngageErrorKind.NONE))
        assertEquals(0, g.currentActionStreak)
    }

    @Test
    fun `连续同动作网络失败达阈值只放弃该动作`() {
        val g = EngageGuard(3)
        assertEquals(Disposition.CONTINUE, g.onResult(EngageErrorKind.NETWORK))
        assertEquals(Disposition.CONTINUE, g.onResult(EngageErrorKind.NETWORK))
        assertEquals(Disposition.ABORT_ACTION, g.onResult(EngageErrorKind.NETWORK))
        assertFalse(g.isStopped)
    }

    @Test
    fun `换动作后仍失败则整轮熔断`() {
        val g = EngageGuard(3)
        repeat(3) { g.onResult(EngageErrorKind.NETWORK) }
        g.nextAction()
        assertEquals(Disposition.ABORT_RUN, g.onResult(EngageErrorKind.NETWORK))
        assertTrue(g.isStopped)
    }

    @Test
    fun `跳过类不计失败`() {
        val g = EngageGuard(2)
        assertEquals(Disposition.CONTINUE, g.onResult(EngageErrorKind.TARGET_GONE))
        assertEquals(Disposition.CONTINUE, g.onResult(EngageErrorKind.DUPLICATE_ACTION))
        assertEquals(0, g.currentActionStreak)
        assertFalse(g.isStopped)
    }

    @Test
    fun `需要验证时要求人工并停止`() {
        val g = EngageGuard(3)
        assertEquals(Disposition.REQUIRE_HUMAN, g.onResult(EngageErrorKind.NEED_VERIFY))
        assertTrue(g.isStopped)
        assertTrue(g.currentStopReason.contains("验证"))
    }

    @Test
    fun `登录墙要求人工`() {
        val g = EngageGuard(3)
        assertEquals(Disposition.REQUIRE_HUMAN, g.onResult(EngageErrorKind.LOGIN_WALL))
        assertTrue(g.isStopped)
    }

    @Test
    fun `等级不足只放弃当前动作且不停整轮`() {
        val g = EngageGuard(3)
        assertEquals(Disposition.ABORT_ACTION, g.onResult(EngageErrorKind.LEVEL_TOO_LOW))
        assertFalse(g.isStopped)
    }

    @Test
    fun `限流要求退避`() {
        val g = EngageGuard(3)
        assertEquals(Disposition.RETRY_LATER, g.onResult(EngageErrorKind.RATE_LIMITED))
        assertTrue(g.isStopped)
        assertTrue(g.currentStopReason.contains("频繁"))
    }

    @Test
    fun `未关注超话终止整轮`() {
        val g = EngageGuard(3)
        assertEquals(Disposition.ABORT_RUN, g.onResult(EngageErrorKind.TOPIC_NOT_FOLLOWED))
        assertTrue(g.isStopped)
    }

    @Test
    fun `接口改版终止整轮并提示升级`() {
        val g = EngageGuard(3)
        assertEquals(Disposition.ABORT_RUN, g.onResult(EngageErrorKind.ENDPOINT_INVALID))
        assertTrue(g.currentStopReason.contains("升级"))
    }

    @Test
    fun `停止之后任何结果都返回整轮终止`() {
        val g = EngageGuard(3)
        g.onResult(EngageErrorKind.NEED_VERIFY)
        assertEquals(Disposition.ABORT_RUN, g.onResult(EngageErrorKind.NONE))
        assertEquals(Disposition.ABORT_RUN, g.onResult(EngageErrorKind.NETWORK))
    }

    @Test
    fun `内容被审核只放弃该条`() {
        val g = EngageGuard(3)
        assertEquals(Disposition.ABORT_ACTION, g.onResult(EngageErrorKind.CONTENT_REJECTED))
        assertFalse(g.isStopped)
    }

    @Test
    fun `显式熔断记录原因且只记第一次`() {
        val g = EngageGuard(3)
        g.trip("时间预算耗尽")
        g.trip("第二个原因")
        assertTrue(g.currentStopReason.contains("预算"))
    }
}
