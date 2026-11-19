package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行节奏测试。
 *
 * 这一层最容易出的错不是崩溃，而是「界面显示均衡、实际跑的是稳健」这类**静默错配**：
 * [EngageConfig] 的默认值是一份字面量，而预设表里也有一份，两边一旦不同步，
 * 用户看到的档位和真实间隔就对不上。所以第一条用例就钉住这个等式。
 */
class EngagePacingTest {

    @Test
    fun `默认配置就是快速档`() {
        // 0.14.0 起默认就是最快那档：发帖 5–15 秒、评论 1–10 秒
        assertEquals(EngagePace.FAST, EngagePacing.presetOf(EngageConfig()))
        assertEquals(5_000L..15_000L, EngageConfig().gapRangeOf(EngageAction.POST))
        assertEquals(1_000L..10_000L, EngageConfig().gapRangeOf(EngageAction.COMMENT))
    }

    @Test
    fun `慢速档等于 0_12 的旧默认间隔`() {
        // 老默认值必须能一键找回：这是「我觉得太快了想切回去」的退路
        val g = EngagePacing.gapsOf(EngagePace.SLOW)!!
        assertEquals(5 * 60_000L..8 * 60_000L, g.post)
        assertEquals(25_000L..60_000L, g.comment)
        assertEquals(60_000L..180_000L, g.repost)
        assertEquals(15_000L..40_000L, g.like)
        assertEquals(5_000L..20_000L, g.actionGap)
    }

    @Test
    fun `五档都能被反向识别`() {
        for (pace in EngagePace.entries.filter { it != EngagePace.CUSTOM }) {
            val cfg = EngagePacing.apply(EngageConfig(), pace)
            assertEquals(pace.label, pace, EngagePacing.presetOf(cfg))
        }
    }

    @Test
    fun `档位按速度单调递增`() {
        val counts = mapOf(
            EngageAction.POST to 1,
            EngageAction.COMMENT to 4,
            EngageAction.REPOST to 2,
            EngageAction.LIKE to 3,
        )
        val order = listOf(
            EngagePace.TURBO, EngagePace.FAST, EngagePace.BALANCED, EngagePace.GENTLE, EngagePace.SLOW,
        )
        val estimates = order.map { EngagePacing.estimateRunMs(EngagePacing.apply(EngageConfig(), it), counts) }
        assertEquals(estimates.sorted(), estimates)
        assertTrue("slow=${estimates.last()}", estimates.last() > 5 * 60_000L)
    }

    @Test
    fun `间隔低于安全线时给出风险提示`() {
        // 默认档本身就低于社区经验线：提示必须存在，但不能被当成「配置错误」拦住执行
        assertNotNull(EngagePacing.riskHint(EngageConfig()))
        assertNull(EngagePacing.riskHint(EngagePacing.apply(EngageConfig(), EngagePace.GENTLE)))
    }

    @Test
    fun `自定义档没有预设值`() {
        assertNull(EngagePacing.gapsOf(EngagePace.CUSTOM))
        // CUSTOM 不是具体档位，套用它必须原样返回
        val cfg = EngageConfig()
        assertEquals(cfg, EngagePacing.apply(cfg, EngagePace.CUSTOM))
    }

    @Test
    fun `改一项间隔后就变成自定义`() {
        val cfg = EngagePacing.apply(EngageConfig(), EngagePace.BALANCED)
        val changed = EngagePacing.withGap(cfg, EngageAction.COMMENT, 1_000L..2_000L)
        assertEquals(EngagePace.CUSTOM, EngagePacing.presetOf(changed))
        // 其它项不受影响
        assertEquals(cfg.gapRangeOf(EngageAction.POST), changed.gapRangeOf(EngageAction.POST))
    }

    @Test
    fun `平移保留区间宽度`() {
        val shifted = EngagePacing.shift(10_000L..20_000L, 5_000L)
        assertEquals(15_000L..25_000L, shifted)
        assertEquals(10_000L, shifted.last - shifted.first)
    }

    @Test
    fun `平移不会越过下限与上限`() {
        val low = EngagePacing.shift(0L..5_000L, -60_000L)
        assertEquals(0L, low.first)
        assertTrue(low.last >= low.first)

        val high = EngagePacing.shift(
            (EngagePacing.MAX_GAP_MS - 60_000L)..EngagePacing.MAX_GAP_MS,
            600_000L,
        )
        assertEquals(EngagePacing.MAX_GAP_MS, high.last)
        assertTrue(high.first <= high.last)
    }

    @Test
    fun `写回间隔时会夹住非法区间`() {
        val cfg = EngagePacing.withGap(EngageConfig(), EngageAction.POST, 120_000L..60_000L)
        assertTrue(cfg.postGapMinMs <= cfg.postGapMaxMs)
        assertEquals(120_000L, cfg.postGapMinMs)
    }

    @Test
    fun `间隔上限不会超过 30 分钟`() {
        // 界面上的 + 按钮：写回时直接夹到上限，不会让用户点出一个两小时的间隔
        val cfg = EngagePacing.withGap(EngageConfig(), EngageAction.LIKE, 0L..(99 * 60_000L))
        assertEquals(EngagePacing.MAX_GAP_MS, cfg.likeGapMaxMs)

        // 手改 JSON / 老版本导入这类绕过界面的路径，必须被校验拦住：
        // 间隔比单轮预算还长时，每个动作都会被判「来不及」而静默跳过。
        val handEdited = EngageConfig(
            enabled = true,
            consentAt = 1L,
            consentVersion = EngageConfig.CONSENT_VERSION,
            accountUid = "1000000001",
            topicId = "1008088cf9880c8ec5f07f84c2f30e74d43574",
        ).copy(likeGapMinMs = 0L, likeGapMaxMs = 99 * 60_000L)
        assertTrue(handEdited.validate()!!.contains("间隔上限"))
    }

    @Test
    fun `步进按量级切换`() {
        assertEquals(5_000L, EngagePacing.stepOf(8_000L..15_000L))
        assertEquals(30_000L, EngagePacing.stepOf(60_000L..120_000L))
    }

    @Test
    fun `单次动作的估算不含等待`() {
        // 同一个节流键的首次请求不等待，所以 1 次动作只算一次请求开销
        val cfg = EngageConfig()
        assertEquals(1_500L, EngagePacing.estimateRunMs(cfg, mapOf(EngageAction.POST to 1)))
    }

    @Test
    fun `次数越多估算越长`() {
        val cfg = EngageConfig()
        val one = EngagePacing.estimateRunMs(cfg, mapOf(EngageAction.COMMENT to 1))
        val four = EngagePacing.estimateRunMs(cfg, mapOf(EngageAction.COMMENT to 4))
        assertTrue("$four 应大于 $one", four > one)
    }

    @Test
    fun `快速档的估算明显短于更慢的档位`() {
        val counts = mapOf(
            EngageAction.POST to 1,
            EngageAction.COMMENT to 4,
            EngageAction.REPOST to 2,
            EngageAction.LIKE to 3,
        )
        val fast = EngagePacing.estimateRunMs(EngagePacing.apply(EngageConfig(), EngagePace.FAST), counts)
        val balanced = EngagePacing.estimateRunMs(EngagePacing.apply(EngageConfig(), EngagePace.BALANCED), counts)
        val gentle = EngagePacing.estimateRunMs(EngagePacing.apply(EngageConfig(), EngagePace.GENTLE), counts)
        val slow = EngagePacing.estimateRunMs(EngagePacing.apply(EngageConfig(), EngagePace.SLOW), counts)
        assertTrue("fast=$fast balanced=$balanced", fast < balanced)
        assertTrue("balanced=$balanced gentle=$gentle", balanced < gentle)
        // 老默认档一轮六分钟左右，这正是用户抱怨「间隔太长」的来源
        assertTrue("slow=$slow", slow > 5 * 60_000L)
        assertNotEquals(0L, fast)
    }

    @Test
    fun `间隔描述是人话`() {
        assertEquals("立即", EngagePacing.describe(0L..0L))
        assertEquals("8 秒–15 秒", EngagePacing.describe(8_000L..15_000L))
        assertEquals("10 秒", EngagePacing.describe(10_000L..10_000L))
        assertEquals("1 分钟–2 分钟", EngagePacing.describe(60_000L..120_000L))
        assertEquals("1 分 30 秒", EngagePacing.describe(90_000L..90_000L))
    }

    @Test
    fun `耗时描述是人话`() {
        assertEquals("0 秒", EngagePacing.formatDuration(0L))
        assertEquals("2 秒", EngagePacing.formatDuration(1_200L))
        assertEquals("1 分钟", EngagePacing.formatDuration(60_000L))
        assertEquals("1 分 30 秒", EngagePacing.formatDuration(90_000L))
        assertEquals("10 分钟", EngagePacing.formatDuration(600_000L))
    }
}
