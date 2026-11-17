package com.wcm.checkin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可观测性与时间预算口径的回归测试。
 *
 * 这两处对应线上真实踩到的坑，改动很容易被「顺手优化」掉，所以钉死：
 * 1. [WeiboThrottle.remaining] 对**从未请求过**的 key 必须返回 0。
 *    原来的预算判断用配置的理论间隔（发帖 5–8 分钟）直接比 deadline，
 *    把「当天第一条帖子」判成来不及，直接跳过 —— 表现是评论转发点赞都跑了、
 *    发帖一条没有，界面上还看不出原因。
 * 2. 摘要的分母必须包含「被提前终止而未执行」的动作，否则用户只看到
 *    「发帖 0/0」，无法判断是没计划发帖还是被砍掉了。
 */
class EngageDiagnosticsTest {

    // ---------- 节流：真实剩余等待 ----------

    @Test
    fun `从未请求过的 key 不需要等待`() {
        // 全新 key：真机上 await 会立刻放行，预算判断也必须认为「不用等」
        assertEquals(0L, WeiboThrottle.remaining("diag-fresh", 8 * 60_000L))
    }

    @Test
    fun `间隔为 0 时不需要等待`() {
        assertEquals(0L, WeiboThrottle.remaining("diag-zero", 0L))
    }

    @Test
    fun `刚请求过的 key 会报出剩余时间`() = runBlocking {
        WeiboThrottle.await("diag-used", 1L)
        val left = WeiboThrottle.remaining("diag-used", 60_000L)
        assertTrue("应剩余接近 60 秒，实际 $left", left in 55_000L..60_000L)
    }

    @Test
    fun `超过间隔后剩余归零`() = runBlocking {
        WeiboThrottle.await("diag-elapsed", 1L)
        Thread.sleep(20)
        // gap 已经过去 → 不该再算等待（否则预算会被虚报超时）
        assertEquals(0L, WeiboThrottle.remaining("diag-elapsed", 1L))
    }

    // ---------- 进度行 ----------

    @Test
    fun `进度行包含阶段动作与计数`() {
        val line = EngageProgress(
            stage = "执行动作",
            action = EngageAction.COMMENT,
            done = 3,
            total = 10,
            message = "成功",
        ).line()
        assertTrue(line, line.contains("执行动作"))
        assertTrue(line, line.contains("评论 3/10"))
        assertTrue(line, line.contains("成功"))
    }

    @Test
    fun `准备阶段没有动作时只显示阶段`() {
        assertEquals("校验登录态", EngageProgress(stage = "校验登录态").line())
    }

    // ---------- 摘要分母 ----------

    @Test
    fun `被截断的动作让分母保持真实`() {
        val run = EngageRun(
            runId = "r1",
            accountUid = "u1",
            topicId = "t1",
            topicName = "超话",
            startedAt = 0L,
            stopReason = "本轮已达时间预算",
            records = listOf(
                EngageActionRecord(EngageAction.COMMENT, 1, ActionState.OK),
                // 被预算砍掉的那条发帖
                EngageActionRecord(EngageAction.POST, 1, ActionState.SKIPPED),
            ),
        )
        assertTrue(run.summary(), run.summary().contains("评论 1/1"))
        assertTrue(run.summary(), run.summary().contains("发帖 0/1"))
    }

    // ---------- 状态文案 ----------

    @Test
    fun `每个动作状态都有中文标签`() {
        ActionState.entries.forEach { assertTrue(it.name, it.label.isNotEmpty()) }
    }
}
