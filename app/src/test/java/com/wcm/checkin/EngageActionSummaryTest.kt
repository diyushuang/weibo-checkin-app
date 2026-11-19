package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子页面摘要与独立日志的纯函数测试。
 *
 * 这里钉住两件容易回归的事：
 * 1. **独立日志必须包含全局流水**——只过滤 `action == X` 时，登录失败/免打扰这类
 *    整轮级原因会从子页面上消失，用户看到一片空白；
 * 2. 转发关闭文案后不该再去消耗转发库（否则会以「文案库为空」失败）。
 */
class EngageActionSummaryTest {

    private fun log(action: EngageAction?, text: String, at: Long) =
        EngageLogEntry(at = at, level = EngageLogLevel.INFO, action = action, seq = 1, text = text)

    @Test
    fun `独立日志包含全局流水`() {
        val state = EngageUiState(
            progress = EngageProgress(
                logs = listOf(
                    log(null, "本轮中止：登录态已失效", 10),
                    log(EngageAction.POST, "发帖成功", 20),
                    log(EngageAction.COMMENT, "评论成功", 30),
                ),
            ),
        )
        assertEquals(
            listOf("评论成功"),
            state.logsOf(EngageAction.COMMENT, includeGlobal = false).map { it.text },
        )
        assertEquals(
            listOf("本轮中止：登录态已失效", "评论成功"),
            state.logsOf(EngageAction.COMMENT).map { it.text },
        )
    }

    @Test
    fun `摘要按次数与当日计数算剩余`() {
        val state = EngageUiState(
            config = EngageConfig(commentCount = 4),
            counter = EngageDayCounter(done = mapOf(EngageAction.COMMENT to 3)),
        )
        val summary = state.summaryOf(EngageAction.COMMENT)
        assertEquals(4, summary.count)
        assertEquals(3, summary.done)
        assertEquals(1, summary.remaining)
        assertEquals("3/4", summary.quotaLabel)
    }

    @Test
    fun `发帖存在未收敛预占时剩余归零`() {
        val state = EngageUiState(
            config = EngageConfig(postCount = 1),
            counter = EngageDayCounter(pending = listOf(PendingSlot("POST#1", EngageAction.POST, 1L))),
        )
        val summary = state.summaryOf(EngageAction.POST)
        assertTrue(summary.pendingPost)
        assertEquals(0, summary.remaining)
    }

    @Test
    fun `关闭转发文案时不消耗文案库`() {
        val state = EngageUiState(config = EngageConfig(repostTextEnabled = false))
        assertEquals(null, state.summaryOf(EngageAction.REPOST).textKind)
        assertEquals(TextKind.COMMENT, state.summaryOf(EngageAction.COMMENT).textKind)
        assertEquals(null, state.summaryOf(EngageAction.LIKE).textKind)
    }

    @Test
    fun `次数上限与默认次数同一口径`() {
        // 「+」按钮与 setCount 都读 maxCountOf：两处写死不同数字时会出现「点了没反应」
        val cfg = EngageConfig()
        for (action in EngageAction.entries) {
            assertTrue("${action.label} 的上限应大于 0", cfg.maxCountOf(action) > 0)
            assertTrue(
                "${action.label} 默认 ${cfg.countOf(action)} 超过了上限 ${cfg.maxCountOf(action)}",
                cfg.countOf(action) <= cfg.maxCountOf(action),
            )
        }
    }

    @Test
    fun `不是今天的结果会带日期前缀`() {
        // 只显示「12:03 成功」会被读成「今天中午跑过」，实际可能是昨天的
        val at = 1_700_000_000_000L
        val summary = EngageActionSummary(
            action = EngageAction.COMMENT,
            mode = EngageActionMode.MANUAL,
            count = 4,
            done = 0,
            lastAt = at,
            lastState = ActionState.OK,
        )
        val crossDay = summary.lastLabel("2026-11-19")
        val sameDay = summary.lastLabel(EngageSchedule.dayKey(at))
        assertTrue(crossDay.length > sameDay.length)
        assertTrue(crossDay.startsWith(EngageSchedule.dateLabel(at)))
    }
}
