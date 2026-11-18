package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度模型测试。
 *
 * 这一层是「用户能不能看出任务在干什么」的全部依据，所以钉住三件事：
 * 1. 步骤状态必须能走到终态——否则界面永远显示「进行中」，用户以为卡死；
 * 2. 流水必须有上限——一轮跑几十条，不裁剪会一直涨；
 * 3. 进度与剩余时间不能出现负数或除零。
 */
class EngageProgressTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `步骤从进行中走到完成`() {
        val p = EngageProgress(startedAt = now)
            .stepStart("auth", "校验登录态", now)
            .stepFinish("auth", EngageStepState.OK, now + 1_200, "有效")
        assertEquals(1, p.steps.size)
        assertEquals(EngageStepState.OK, p.steps[0].state)
        assertEquals(1_200L, p.steps[0].elapsedMs(now + 10_000))
        assertEquals("有效", p.steps[0].detail)
    }

    @Test
    fun `重复开始同一步不会追加新行`() {
        val p = EngageProgress(startedAt = now)
            .stepStart("auth", "校验登录态", now)
            .stepStart("auth", "校验登录态", now + 500)
        assertEquals(1, p.steps.size)
        assertEquals(now + 500, p.steps[0].startedAt)
    }

    @Test
    fun `结束不存在的步骤被忽略`() {
        val p = EngageProgress(startedAt = now).stepFinish("nope", EngageStepState.OK, now)
        assertTrue(p.steps.isEmpty())
    }

    @Test
    fun `收尾会把没结束的步骤标成终态`() {
        // 异常/熔断中止时若不停掉「进行中」，界面会一直转圈
        val p = EngageProgress(startedAt = now)
            .stepStart("a", "第一步", now)
            .stepStart("b", "第二步", now)
            .closeOpenSteps(EngageStepState.FAILED, now + 5_000, "已中止")
        assertTrue(p.steps.all { it.isDone })
        assertTrue(p.steps.all { it.endedAt == now + 5_000 })
    }

    @Test
    fun `流水超过上限时丢最旧的`() {
        var p = EngageProgress(startedAt = now)
        repeat(EngageProgress.MAX_LOGS + 30) { i ->
            p = p.log(now + i, EngageLogLevel.INFO, "第 $i 条")
        }
        assertEquals(EngageProgress.MAX_LOGS, p.logs.size)
        // 保留的是最新的那一批
        assertTrue(p.logs.last().text.contains("${EngageProgress.MAX_LOGS + 29}"))
    }

    @Test
    fun `流水行包含时间动作与正文`() {
        val entry = EngageLogEntry(now, EngageLogLevel.OK, EngageAction.COMMENT, 2, "成功")
        val line = entry.line()
        assertEquals(EngageSchedule.clockLabel(now), entry.clock())
        assertTrue(line, line.contains("评论#2"))
        assertTrue(line, line.contains("成功"))
    }

    @Test
    fun `进度比例与百分比`() {
        assertEquals(0.5f, EngageProgress(done = 2, total = 4).fraction, 0.001f)
        assertEquals(50, EngageProgress(done = 2, total = 4).percent)
        // 总数为 0 时不能除零
        assertEquals(0f, EngageProgress().fraction, 0.001f)
        assertEquals(100, EngageProgress(done = 9, total = 4).percent)
    }

    @Test
    fun `等待状态可设置与清除`() {
        val p = EngageProgress(startedAt = now).waiting(now + 12_000)
        assertTrue(p.isWaiting)
        assertEquals(now + 12_000, p.waitUntilMs)
        assertFalse(p.clearWait().isWaiting)
    }

    @Test
    fun `剩余时间按已完成条数推算`() {
        val start = System.currentTimeMillis() - 60_000
        val p = EngageProgress(startedAt = start, done = 2, total = 6)
        val label = p.etaLabel()
        assertTrue(label, label.startsWith("约剩"))
        // 6 条里做完 2 条用了 60 秒 → 剩 4 条约 120 秒
        assertTrue(label, label.contains("2 分钟"))
    }

    @Test
    fun `还没开始动作时用计划估算`() {
        val p = EngageProgress(startedAt = System.currentTimeMillis(), done = 0, total = 5, etaMs = 180_000)
        assertTrue(p.etaLabel().contains("3 分钟"))
    }

    @Test
    fun `跑完后不再显示剩余时间`() {
        val p = EngageProgress(startedAt = now, done = 1, total = 5).finish(now + 1_000)
        assertEquals("", p.etaLabel())
        assertTrue(p.isFinished)
        assertFalse(p.isWaiting)
    }

    @Test
    fun `当前步骤优先取进行中的那一步`() {
        val p = EngageProgress(startedAt = now)
            .stepStart("a", "第一步", now)
            .stepFinish("a", EngageStepState.OK, now + 10)
            .stepStart("b", "第二步", now + 20)
        assertEquals("b", p.currentStep()?.id)
    }

    @Test
    fun `单行进度保留原有口径`() {
        // 通知/Toast 这类没有排版的位置还在用 line()，格式不能变
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
        assertEquals("校验登录态", EngageProgress(stage = "校验登录态").line())
    }

    @Test
    fun `没有步骤时当前步骤为空`() {
        assertNotNull(EngageProgress())
        assertEquals(null, EngageProgress().currentStep())
    }
}
