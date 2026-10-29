package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 抓取进度模型测试：事件累加单调、阶段迁移、百分比边界、失败清单文案。 */
class CrawlProgressTest {

    private fun step(vararg events: CrawlEvent): CrawlProgress =
        events.fold(CrawlProgress()) { acc, e -> reduce(acc, e, now = 1_000L) }

    @Test
    fun begin_resetsAndStartsPrepare() {
        val p = step(CrawlEvent.Begin("增量抓取", targets = 3))
        assertEquals(CrawlPhase.PREPARE, p.phase)
        assertEquals("增量抓取", p.what)
        assertEquals(3, p.targetTotal)
        assertEquals(1_000L, p.since)
        assertEquals(0, p.discovered)
        assertTrue(p.running)
    }

    @Test
    fun target_updatesPosition() {
        val p = step(CrawlEvent.Begin("x", 5), CrawlEvent.Target("甲", 2, 5))
        assertEquals("甲", p.targetLabel)
        assertEquals(2, p.targetIndex)
        assertEquals(5, p.targetTotal)
    }

    @Test
    fun phase_accumulatesDiscoveredAcrossPhases() {
        val p = step(
            CrawlEvent.Begin("x", 1),
            CrawlEvent.Phase(CrawlPhase.RETRY, planned = 3, skipped = 0),
            CrawlEvent.Phase(CrawlPhase.NEW, planned = 10, skipped = 4),
            CrawlEvent.Phase(CrawlPhase.HISTORY, planned = 20, skipped = 6),
        )
        assertEquals(CrawlPhase.HISTORY, p.phase)
        assertEquals(33, p.discovered)
        assertEquals(10, p.skipped)
        assertEquals(0, p.processed)
    }

    @Test
    fun post_accumulatesMonotonically() {
        val p = step(
            CrawlEvent.Begin("x", 1),
            CrawlEvent.Phase(CrawlPhase.NEW, planned = 3, skipped = 0),
            CrawlEvent.Post(saved = 2, failed = 1),
            CrawlEvent.Post(saved = 0, failed = 0),
            CrawlEvent.Post(saved = 4, failed = 2),
        )
        assertEquals(3, p.processed)
        assertEquals(6, p.downloaded)
        assertEquals(3, p.failedSlots)
        assertEquals(100, percent(p))   // processed > discovered 也不能超过 100
    }

    @Test
    fun negativeNumbersNeverShrinkProgress() {
        // 接口层理论上不该给负数；真给了也不能让进度条倒退
        val p = step(
            CrawlEvent.Begin("x", 1),
            CrawlEvent.Phase(CrawlPhase.NEW, planned = -5, skipped = -1),
            CrawlEvent.Post(saved = -3, failed = -2),
        )
        assertEquals(0, p.discovered)
        assertEquals(0, p.skipped)
        assertEquals(0, p.downloaded)
        assertEquals(1, p.processed)
    }

    @Test
    fun finish_marksDoneAndNotRunning() {
        val p = step(CrawlEvent.Begin("x", 1), CrawlEvent.Finish)
        assertEquals(CrawlPhase.DONE, p.phase)
        assertFalse(p.running)
    }

    @Test
    fun percent_zeroWhenNothingDiscovered() {
        assertEquals(0, percent(CrawlProgress()))
        assertEquals(0, percent(CrawlProgress(phase = CrawlPhase.PREPARE)))
    }

    @Test
    fun percent_roundsDown() {
        assertEquals(50, percent(CrawlProgress(discovered = 4, processed = 2)))
        assertEquals(33, percent(CrawlProgress(discovered = 3, processed = 1)))
    }

    @Test
    fun describeFailures_showsMissingSlotCount() {
        val records = listOf(
            CrawlIndex.PostRec(
                postId = "5356841628600820",
                at = 0L,
                status = CrawlIndex.Status.PARTIAL,
                expected = 9,
                failed = linkedSetOf(2, 5),
            ),
            CrawlIndex.PostRec(
                postId = "5356841628600821",
                at = 0L,
                status = CrawlIndex.Status.PARTIAL,
                expected = 3,
                failed = linkedSetOf(),
            ),
        )
        assertEquals(
            listOf("5356841628600820 · 缺 2 张", "5356841628600821"),
            describeFailures(records),
        )
    }

    @Test
    fun describeFailures_empty() {
        assertTrue(describeFailures(emptyList()).isEmpty())
    }
}
