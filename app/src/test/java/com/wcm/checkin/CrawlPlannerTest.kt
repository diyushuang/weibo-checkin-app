package com.wcm.checkin

import com.wcm.checkin.CrawlPlanner.IndexView
import com.wcm.checkin.CrawlPlanner.PageOutcome
import com.wcm.checkin.CrawlPlanner.PostRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规划器单测：覆盖判据、锚点续跑、置顶/乱序/失败/预算等边界。 */
class CrawlPlannerTest {

    // ---------- 工具 ----------

    private fun view(vararg terminal: String): IndexView = object : IndexView {
        private val set = terminal.toSet()
        override fun isTerminal(postId: String) = postId in set
        override fun failedSlots(postId: String) = emptySet<Int>()
    }

    private fun post(
        id: String,
        at: Long,
        pinned: Boolean = false,
        retweet: Boolean = false,
        pics: Int = 3,
    ) = PostRef(id = id, bid = "b$id", at = at, pinned = pinned, isRetweet = retweet, picNum = pics)

    private fun fetcher(pages: Map<Int, List<PostRef>>, failing: Set<Int> = emptySet()): (Int) -> PageOutcome =
        { page ->
            when {
                page in failing -> PageOutcome.fail(page, "HTTP 500")
                else -> PageOutcome.ok(page, pages[page] ?: emptyList())
            }
        }

    private fun cursor(newestAt: Long = 0L, oldest: Long = 0L, hint: Int = 1) = CrawlCursor(
        uid = "u", newestAt = newestAt, oldestCoveredAt = oldest, anchorPageHint = hint,
    )

    // ========================= Phase A =========================

    @Test
    fun new_firstRunCollectsAllPagesAscending() {
        val pages = mapOf(
            1 to listOf(post("a", 3000), post("b", 2000)),
            2 to listOf(post("c", 1000)),
            3 to emptyList<PostRef>(),
        )
        val plan = CrawlPlanner.planNew(cursor(), view(), "", fetcher(pages))
        assertEquals(Phase.NEW, plan.phase)
        assertEquals(listOf("c", "b", "a"), plan.batch.map { it.id })   // 升序
        assertEquals(StopReason.PAGE_EXHAUSTED, plan.stop)
        assertEquals(1000L, plan.coverageLow)
        assertEquals(3000L, plan.coverageHigh)
    }

    @Test
    fun new_stopsWhenPageFullyClassifiedAndReachedWatermark() {
        val pages = mapOf(
            1 to listOf(post("a", 3000), post("b", 2900)),
            2 to listOf(post("c", 1000)),
        )
        val plan = CrawlPlanner.planNew(cursor(newestAt = 3000), view("a", "b"), "", fetcher(pages))
        assertEquals(StopReason.ALL_COVERED, plan.stop)
        assertTrue(plan.batch.isEmpty())
        assertEquals(1, plan.lastPage)      // 没有白翻第 2 页
        assertEquals(2, plan.skippedTerminal)
    }

    /**
     * 旧实现的反例：第 1、2 页全是已完成（连续 10 条），第 3 页才有新帖。
     * 水线还在很下面（minAt > newestAt），必须继续翻，不能被「连续已完成」骗停。
     */
    @Test
    fun new_keepsPagingWhileAboveWatermark() {
        val pages = mapOf(
            1 to listOf(post("a", 3000), post("b", 2900)),
            2 to listOf(post("c", 2800), post("d", 2700)),
            3 to listOf(post("e", 2600)),
        )
        val plan = CrawlPlanner.planNew(cursor(newestAt = 1000), view("a", "b", "c", "d"), "", fetcher(pages))
        assertEquals(listOf("e"), plan.batch.map { it.id })
        assertEquals(3, plan.lastPage)
    }

    @Test
    fun new_pinnedPostDoesNotRaiseWatermark() {
        // 置顶帖时间很新，但它是老帖被置顶，不能把水线顶上去
        val pages = mapOf(
            1 to listOf(post("pin", 9000, pinned = true), post("a", 3000)),
            2 to emptyList<PostRef>(),
        )
        val plan = CrawlPlanner.planNew(cursor(), view("pin"), "", fetcher(pages))
        assertEquals(3000L, plan.coverageHigh)
    }

    @Test
    fun new_pinnedPostStillDownloadedWhenNotTerminal() {
        val pages = mapOf(1 to listOf(post("pin", 9000, pinned = true)), 2 to emptyList<PostRef>())
        val plan = CrawlPlanner.planNew(cursor(), view(), "", fetcher(pages))
        assertEquals(listOf("pin"), plan.batch.map { it.id })
    }

    @Test
    fun new_duplicatePostAcrossPagesDownloadedOnce() {
        val pages = mapOf(
            1 to listOf(post("pin", 9000, pinned = true), post("a", 8000)),
            2 to listOf(post("pin", 9000, pinned = true), post("b", 7000)),
        )
        val plan = CrawlPlanner.planNew(cursor(), view(), "", fetcher(pages))
        assertEquals(listOf("b", "a", "pin"), plan.batch.map { it.id })
    }

    @Test
    fun new_backfilledOldPostIsCaught() {
        // 补发的老帖：时间远早于水线，但没抓过 → 必须进 batch
        val pages = mapOf(
            1 to listOf(post("a", 3000), post("old", 100)),
            2 to emptyList<PostRef>(),
        )
        val plan = CrawlPlanner.planNew(cursor(newestAt = 3000), view("a"), "", fetcher(pages))
        assertEquals(listOf("old"), plan.batch.map { it.id })
        assertEquals(100L, plan.coverageLow)
    }

    @Test
    fun new_retweetAndNoImageDoNotBlockCoverage() {
        val pages = mapOf(
            1 to listOf(post("rt", 3000, retweet = true), post("noimg", 2900, pics = 0)),
            2 to emptyList<PostRef>(),
        )
        val plan = CrawlPlanner.planNew(cursor(newestAt = 3000), view(), "", fetcher(pages))
        assertTrue(plan.batch.isEmpty())
        assertEquals(StopReason.ALL_COVERED, plan.stop)
    }

    @Test
    fun new_outOfOrderTimesTakeMinAndMax() {
        val pages = mapOf(
            1 to listOf(post("a", 2000), post("b", 5000), post("c", 1000)),
            2 to emptyList<PostRef>(),
        )
        val plan = CrawlPlanner.planNew(cursor(), view(), "", fetcher(pages))
        assertEquals(1000L, plan.coverageLow)
        assertEquals(5000L, plan.coverageHigh)
        assertEquals(listOf("c", "a", "b"), plan.batch.map { it.id })
    }

    @Test
    fun new_pageErrorStopsAndKeepsBoundaryAtPreviousPage() {
        val pages = mapOf(
            1 to listOf(post("a", 3000)),
            2 to listOf(post("b", 2000)),
            3 to listOf(post("c", 1000)),
        )
        val plan = CrawlPlanner.planNew(cursor(), view(), "", fetcher(pages, failing = setOf(2)))
        assertEquals(StopReason.PAGE_ERROR, plan.stop)
        assertEquals(1, plan.lastPage)
        assertEquals(3000L, plan.coverageHigh)   // 只认第 1 页
        assertEquals(3000L, plan.coverageLow)
    }

    @Test
    fun new_firstPageErrorMeansZeroProgress() {
        val plan = CrawlPlanner.planNew(cursor(newestAt = 500), view(), "", fetcher(emptyMap(), setOf(1)))
        assertEquals(StopReason.PAGE_ERROR, plan.stop)
        assertEquals(0L, plan.coverageLow)
        assertEquals(0L, plan.coverageHigh)
        assertEquals(0, plan.lastPage)
    }

    @Test
    fun new_emptyMiddlePageDoesNotExtendBoundary() {
        val pages = mapOf(1 to listOf(post("a", 3000)), 2 to emptyList<PostRef>())
        val plan = CrawlPlanner.planNew(cursor(), view(), "", fetcher(pages))
        assertEquals(StopReason.PAGE_EXHAUSTED, plan.stop)
        assertEquals(3000L, plan.coverageLow)
        assertEquals(1, plan.lastPage)
    }

    @Test
    fun new_budgetExhaustedSavesCursor() {
        val pages = (1..10).associateWith { p -> listOf(post("p$p", 10_000L - p)) }
        val plan = CrawlPlanner.planNew(cursor(), view(), "", fetcher(pages), Budget(maxPages = 3))
        assertEquals(StopReason.BUDGET, plan.stop)
        assertEquals(3, plan.lastPage)
        assertEquals(3, plan.batch.size)
    }

    @Test
    fun new_stopsAtSinceDate() {
        val newer = CrawlDate.dayStartSec("2026-03-01")!!
        val older = CrawlDate.dayStartSec("2026-01-05")!!
        val pages = mapOf(
            1 to listOf(post("a", newer)),
            2 to listOf(post("b", older)),
        )
        val plan = CrawlPlanner.planNew(cursor(), view(), "2026-02-01", fetcher(pages))
        assertEquals(StopReason.SINCE_DATE, plan.stop)
        // 边界之上的新帖照抓，边界之下的一律不抓
        assertEquals(listOf("a"), plan.batch.map { it.id })
    }

    // ========================= Phase B =========================

    @Test
    fun history_locatesByAnchorId() {
        val pages = mapOf(
            1 to listOf(post("a", 3000)),
            2 to listOf(post("anchor", 2000)),
            3 to listOf(post("c", 1000)),
            4 to emptyList<PostRef>(),
        )
        val c = cursor(newestAt = 3000, oldest = 2000, hint = 2).copy(anchorPostId = "anchor", anchorAt = 2000)
        val plan = CrawlPlanner.planHistory(c, view("a", "anchor"), "", fetcher(pages))
        assertEquals(2, plan.startPage)
        assertEquals(listOf("c"), plan.batch.map { it.id })
    }

    @Test
    fun history_anchorDeletedFallsBackToTime() {
        // 锚点帖被删：id 命中失败，但该页时间跨越 anchorAt → 仍能定位
        val pages = mapOf(
            1 to listOf(post("a", 3000)),
            2 to listOf(post("x", 2100), post("y", 1900)),
            3 to listOf(post("c", 1000)),
        )
        val c = cursor(newestAt = 3000, oldest = 2000, hint = 2).copy(anchorPostId = "gone", anchorAt = 2000)
        val plan = CrawlPlanner.planHistory(c, view("a"), "", fetcher(pages))
        assertEquals(2, plan.startPage)
    }

    @Test
    fun history_hintPageTooNewWalksOlder() {
        val pages = mapOf(
            1 to listOf(post("a", 5000)),
            2 to listOf(post("b", 4000)),
            3 to listOf(post("c", 3000), post("d", 2000)),
            4 to emptyList<PostRef>(),
        )
        val c = cursor(newestAt = 5000, oldest = 2500, hint = 1).copy(anchorAt = 2500)
        val plan = CrawlPlanner.planHistory(c, view("a", "b"), "", fetcher(pages))
        assertEquals(3, plan.startPage)
    }

    @Test
    fun history_outOfWindowFallsBackToFirstPage() {
        val pages = mapOf(1 to listOf(post("a", 100)))
        val c = cursor(newestAt = 9000, oldest = 8000, hint = 50).copy(anchorAt = 8000)
        val plan = CrawlPlanner.planHistory(c, view("a"), "", fetcher(pages))
        assertEquals(1, plan.startPage)
    }

    @Test
    fun history_stopsAtSinceDateAndParks() {
        val newer = CrawlDate.dayStartSec("2026-03-01")!!
        val older = CrawlDate.dayStartSec("2026-01-05")!!
        val pages = mapOf(
            1 to listOf(post("a", newer)),
            2 to listOf(post("b", older)),
        )
        val c = cursor(newestAt = newer, oldest = newer, hint = 1)
            .copy(anchorAt = newer, anchorPostId = "a")
        val plan = CrawlPlanner.planHistory(c, view("a"), "2026-02-01", fetcher(pages))
        assertEquals(StopReason.SINCE_DATE, plan.stop)
        assertTrue(plan.parked)
        assertTrue(plan.batch.isEmpty())
    }

    @Test
    fun history_emptyPageMeansBottom() {
        val pages = mapOf(1 to listOf(post("a", 3000)), 2 to emptyList<PostRef>())
        val c = cursor(newestAt = 3000, oldest = 3000).copy(anchorAt = 3000)
        val plan = CrawlPlanner.planHistory(c, view("a"), "", fetcher(pages))
        assertTrue(plan.reachedBottom)
        assertEquals(StopReason.PAGE_EXHAUSTED, plan.stop)
    }

    @Test
    fun history_alreadyParkedDoesNothing() {
        val c = cursor(newestAt = 3000, oldest = 3000).copy(historyParked = true)
        val plan = CrawlPlanner.planHistory(c, view(), "", fetcher(emptyMap()))
        assertEquals(StopReason.ALL_COVERED, plan.stop)
        assertTrue(plan.batch.isEmpty())
    }

    @Test
    fun history_pageErrorKeepsBoundaryBeforeFailure() {
        val pages = mapOf(
            1 to listOf(post("a", 4000), post("b", 3000)),
            2 to listOf(post("c", 2000)),
        )
        val c = cursor(newestAt = 4000, oldest = 3500, hint = 1).copy(anchorAt = 3500)
        val plan = CrawlPlanner.planHistory(c, view("a"), "", fetcher(pages, failing = setOf(2)))
        assertEquals(StopReason.PAGE_ERROR, plan.stop)
        assertEquals(1, plan.lastPage)
    }

    @Test
    fun history_anchorFieldsPointToOldestWalked() {
        val pages = mapOf(
            1 to listOf(post("a", 4000)),
            2 to listOf(post("b", 3000), post("c", 2500)),
            3 to emptyList<PostRef>(),
        )
        val c = cursor(newestAt = 4000, oldest = 4000, hint = 1).copy(anchorAt = 4000)
        val plan = CrawlPlanner.planHistory(c, view("a"), "", fetcher(pages))
        assertEquals("c", plan.anchorPostId)
        assertEquals(2500L, plan.anchorAt)
        assertEquals(2, plan.lastPage)
    }

    // ========================= 其它 =========================

    @Test
    fun shouldRunHistory_respectsBottomParkAndSince() {
        assertTrue(CrawlPlanner.shouldRunHistory(cursor(newestAt = 100, oldest = 100), ""))
        assertFalse(CrawlPlanner.shouldRunHistory(cursor(newestAt = 100, oldest = 100).copy(reachedBottom = true), ""))
        assertFalse(CrawlPlanner.shouldRunHistory(cursor(newestAt = 100, oldest = 100).copy(historyParked = true), ""))
        // 已覆盖下端比起始日期还老 → 历史方向无事可做
        val since = CrawlDate.dateOf(CrawlDate.dayStartSec("2026-01-01")!!)
        assertFalse(CrawlPlanner.shouldRunHistory(cursor(newestAt = 0, oldest = CrawlDate.dayStartSec(since)!!), since))
    }

    @Test
    fun planRetry_mapsPendingSlots() {
        val recs = listOf(
            CrawlIndex.PostRec("p1", 10L, CrawlIndex.Status.PARTIAL, 3, linkedSetOf(1, 2)),
            CrawlIndex.PostRec("p2", 20L, CrawlIndex.Status.OPEN, 1, linkedSetOf(0)),
        )
        val items = CrawlPlanner.planRetry(recs)
        assertEquals(listOf("p1", "p2"), items.map { it.postId })
        assertEquals(setOf(1, 2), items[0].slots)
    }

    @Test
    fun budgetDefaultsAreSane() {
        assertTrue(Budget.NEW.maxPages < Budget.HISTORY.maxPages)
        assertTrue(Budget.NEW.maxFiles > 0)
        assertTrue(Budget.NEW.maxWallClockMs > 0)
    }
}
