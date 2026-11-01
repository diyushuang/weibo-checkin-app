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

    /** 同 [scan_singleEmptyPageIsNotBottom]：历史阶段也不能被一页空响应永久停掉。 */
    @Test
    fun history_singleEmptyPageIsNotBottom() {
        val pages = mapOf(
            1 to listOf(post("a", 3000)),
            2 to emptyList<PostRef>(),
            3 to listOf(post("b", 1000)),
        )
        val c = cursor(newestAt = 3000, oldest = 3000).copy(anchorAt = 3000)
        // 预算卡在第 3 页：否则第 4、5 页也空，够两连空就会被判到底
        val plan = CrawlPlanner.planHistory(c, view("a"), "", fetcher(pages), Budget(maxPages = 3))
        assertFalse(plan.reachedBottom)
        assertEquals(listOf("b"), plan.batch.map { it.id })
        assertEquals(3, plan.lastPage)
        assertEquals(StopReason.BUDGET, plan.stop)
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

    // ========================= 置顶老帖的边界回归（v0.9.9） =========================

    @Test
    fun new_oldPinnedPostDoesNotPullCoverageLowDown() {
        // 置顶帖是 2020 年的老帖：它的时间不能当成「本页最老」，
        // 否则覆盖下界会被一路拉到那天，谎报「这段已抓过」。
        val pages = mapOf(
            1 to listOf(post("pin", 100, pinned = true), post("a", 3000), post("b", 2000)),
            2 to emptyList<PostRef>(),
        )
        val plan = CrawlPlanner.planNew(cursor(), view("pin"), "", fetcher(pages))
        assertEquals(2000L, plan.coverageLow)
        assertEquals(3000L, plan.coverageHigh)
    }

    @Test
    fun new_oldPinnedPostDoesNotTriggerSinceDateStop() {
        // 置顶帖早于起始日期，但本页真实内容都在起始日期之后 → 不该立刻判「已推到起始日期」
        val sinceSec = CrawlDate.dayStartSec("2026-01-01")!!
        val pages = mapOf(
            1 to listOf(
                post("pin", 100, pinned = true),
                post("a", sinceSec + 1000),
                post("b", sinceSec + 500),
            ),
            2 to emptyList<PostRef>(),
        )
        val plan = CrawlPlanner.planNew(cursor(), view("pin", "a", "b"), "2026-01-01", fetcher(pages))
        assertTrue(plan.stop != StopReason.SINCE_DATE)
        assertTrue(plan.coverageLow >= sinceSec)
    }

    @Test
    fun new_oldPinnedPostKeepsHistoryRunnable() {
        // 老 bug 的直接后果：覆盖下界被写成置顶帖那天 → shouldRunHistory 永久 false。
        // 修正后下界来自真实内容，历史方向仍然可跑。
        val sinceSec = CrawlDate.dayStartSec("2026-01-01")!!
        val pages = mapOf(
            1 to listOf(post("pin", 100, pinned = true), post("a", sinceSec + 1000)),
            2 to emptyList<PostRef>(),
        )
        val plan = CrawlPlanner.planNew(cursor(), view("pin", "a"), "2026-01-01", fetcher(pages))
        val next = CursorLogic.afterNewPhase(CrawlCursor(uid = "u"), plan.coverageHigh, plan.coverageLow)
        assertTrue(next.oldestCoveredAt > sinceSec)
        assertTrue(CrawlPlanner.shouldRunHistory(next, "2026-01-01"))
    }

    @Test
    fun history_pageWithOldPinnedPostKeepsSliceMinAt() {
        // 历史阶段同样：第 1 页的置顶老帖不能把 minAt 拉到那天
        val pages = mapOf(
            1 to listOf(post("pin", 10, pinned = true), post("a", 3000), post("b", 2500)),
            2 to emptyList<PostRef>(),
        )
        // 起点：已有覆盖到 3000，锚点也指向 3000
        val c = CrawlCursor(
            uid = "u", newestAt = 3000L, oldestCoveredAt = 3000L,
            anchorAt = 3000L, anchorPageHint = 1,
        )
        val plan = CrawlPlanner.planHistory(c, view("pin"), "", fetcher(pages))
        assertEquals(2500L, plan.coverageLow)
    }

    // ========================= 全量扫描（v0.9.12） =========================

    private fun scanCursor(scanPage: Int = 0, newestAt: Long = 0L, oldest: Long = 0L) = CrawlCursor(
        uid = "u", newestAt = newestAt, oldestCoveredAt = oldest, fullScanPageHint = scanPage,
    )

    /**
     * 全量的核心语义：**不承认已覆盖区间**。
     * 增量在同一份数据上会立刻 ALL_COVERED 停在第 1 页，全量必须继续往下翻。
     */
    @Test
    fun scan_doesNotStopAtAlreadyCovered() {
        val pages = mapOf(
            1 to listOf(post("a", 3000), post("b", 2900)),
            2 to listOf(post("c", 2000)),
            3 to emptyList<PostRef>(),
        )
        // 覆盖区间已经很宽，索引里第 1 页也全是终态
        val c = scanCursor(newestAt = 3000, oldest = 1000)
        val plan = CrawlPlanner.planScan(c, view("a", "b", "c"), "", fetcher(pages))
        assertEquals(Phase.SCAN, plan.phase)
        assertEquals(StopReason.PAGE_EXHAUSTED, plan.stop)
        assertEquals(2, plan.lastPage)              // lastPage 是最后一个「有内容」的页
        assertEquals(3, plan.skippedTerminal)       // 终态帖只记跳过，不进批次
        assertTrue(plan.batch.isEmpty())
        assertEquals(0, CrawlPlanner.nextScanPage(plan))   // 到底 = 本轮全量完成
    }

    @Test
    fun scan_collectsPendingPostsAscendingAndRecordsCoverage() {
        val pages = mapOf(
            1 to listOf(post("a", 3000), post("b", 2500), post("done", 2400)),
            2 to listOf(post("c", 1000)),
        )
        val plan = CrawlPlanner.planScan(scanCursor(), view("done"), "", fetcher(pages), Budget(maxPages = 2))
        assertEquals(listOf("c", "b", "a"), plan.batch.map { it.id })
        assertEquals(1000L, plan.coverageLow)
        assertEquals(3000L, plan.coverageHigh)
        assertEquals(StopReason.BUDGET, plan.stop)  // 页数用满
        assertEquals(2, plan.lastPage)
        assertEquals(3, CrawlPlanner.nextScanPage(plan))   // 下轮从第 3 页接着走
    }

    /** 断点续跑：有 scanPage 就从那一页开始，不重走前面的页。 */
    @Test
    fun scan_resumesFromScanPageHint() {
        val fetched = ArrayList<Int>()
        val pages = mapOf(
            3 to listOf(post("x", 1000)),
            4 to emptyList<PostRef>(),
            5 to listOf(post("y", 500)),
        )
        val fetch: (Int) -> PageOutcome = { p ->
            fetched.add(p)
            PageOutcome.ok(p, pages[p] ?: emptyList())
        }
        val plan = CrawlPlanner.planScan(scanCursor(scanPage = 3), view(), "", fetch, Budget(maxPages = 3))
        assertEquals(3, plan.startPage)
        // 第 4 页空只是「疑似到底」，必须再确认一页才收工（见 EMPTY_STREAK_TO_BOTTOM）
        assertEquals(listOf(3, 4, 5), fetched)
        assertFalse(plan.reachedBottom)
        assertEquals(StopReason.BUDGET, plan.stop)
        assertEquals(5, plan.lastPage)
        assertEquals(listOf("y", "x"), plan.batch.map { it.id })
        assertEquals(6, CrawlPlanner.nextScanPage(plan))
    }

    /**
     * 单页空**不能**当成到底：接口风控时会返回空列表，
     * 那时若置 reachedBottom，历史/全量就永久停摆，缺口再也补不回来。
     */
    @Test
    fun scan_singleEmptyPageIsNotBottom() {
        val pages = mapOf(
            1 to listOf(post("a", 3000)),
            2 to emptyList<PostRef>(),
            3 to listOf(post("b", 1000)),
        )
        // 预算卡在第 3 页，否则第 4、5 页也空，两连空会被判到底（那条判据另有用例覆盖）
        val plan = CrawlPlanner.planScan(scanCursor(), view("a"), "", fetcher(pages), Budget(maxPages = 3))
        assertFalse(plan.reachedBottom)
        assertEquals(listOf("b"), plan.batch.map { it.id })
        assertEquals(3, plan.lastPage)
        assertEquals(StopReason.BUDGET, plan.stop)
    }

    /** 起始日期是硬下界：全量也不越过用户设定的边界。 */
    @Test
    fun scan_stopsAtSinceDate() {
        val since = CrawlDate.dayStartSec("2025-06-01")!!
        val pages = mapOf(
            1 to listOf(post("new", since + 86400)),
            2 to listOf(post("old", since - 86400)),
        )
        val plan = CrawlPlanner.planScan(scanCursor(), view(), "2025-06-01", fetcher(pages))
        assertEquals(StopReason.SINCE_DATE, plan.stop)
        assertEquals(listOf("new"), plan.batch.map { it.id })
        assertEquals(0, CrawlPlanner.nextScanPage(plan))
    }

    /** 翻页失败不能跳过：断点停回失败页（lastPage 是最后成功页）。 */
    @Test
    fun scan_pageErrorKeepsHintAtFailedPage() {
        val pages = mapOf(1 to listOf(post("a", 3000)))
        val plan = CrawlPlanner.planScan(scanCursor(), view("a"), "", fetcher(pages, failing = setOf(2)))
        assertEquals(StopReason.PAGE_ERROR, plan.stop)
        assertEquals(1, plan.lastPage)
        assertEquals(2, CrawlPlanner.nextScanPage(plan))
    }

    @Test
    fun scan_firstPageEmptyMeansReachedBottom() {
        val plan = CrawlPlanner.planScan(scanCursor(), view(), "", fetcher(emptyMap()))
        assertTrue(plan.reachedBottom)
        assertEquals(StopReason.PAGE_EXHAUSTED, plan.stop)
        assertEquals(0, CrawlPlanner.nextScanPage(plan))
    }

    /** 纯文字帖不进批次（与增量同口径）：没有原图可下，进批次只会白拉详情。 */
    @Test
    fun scan_skipsTextOnlyPosts() {
        val pages = mapOf(1 to listOf(post("t", 3000, pics = 0), post("g", 2500)))
        val plan = CrawlPlanner.planScan(scanCursor(), view(), "", fetcher(pages), Budget(maxPages = 1))
        assertEquals(listOf("g"), plan.batch.map { it.id })
    }

    // ========================= 回归：置顶锚点 / 起始页失败 =========================

    /**
     * BUG-003 回归：置顶老帖不能当历史锚点。
     *
     * 置顶帖是被顶起来的**老帖**，时间不代表页面位置。一旦选中它，
     * 下一轮 locateAnchor 按 id+时间都找不到（它已不在那个位置），
     * 历史阶段永久停摆，后面的图再也抓不到。
     */
    @Test
    fun history_anchorSkipsPinnedPost() {
        val pages = mapOf(
            1 to listOf(
                post("pin", 100, pinned = true),   // 最老，但是置顶
                post("n1", 3000),
                post("n2", 2500),
            ),
        )
        val plan = CrawlPlanner.planHistory(cursor(newestAt = 3000), view(), "", fetcher(pages))
        assertEquals("n2", plan.anchorPostId)
        assertEquals(2500L, plan.anchorAt)
    }

    /**
     * BUG-015 回归：起始页（scanPage hint）本身就是失败页时，断点必须留在该页。
     *
     * lastPage 是「最后一个成功页」，首页就失败时它是 0；旧实现返回 lastPage + 1 = 1，
     * 断点从 41 掉回 1，下一轮白爬 40 页，稳定失败页还会在 1 与 41 之间往返。
     */
    @Test
    fun scan_firstPageErrorAtHintKeepsStartPage() {
        val plan = CrawlPlanner.planScan(
            scanCursor(scanPage = 7), view(), "", fetcher(emptyMap(), failing = setOf(7)),
        )
        assertEquals(StopReason.PAGE_ERROR, plan.stop)
        assertEquals(0, plan.lastPage)
        assertEquals(7, plan.startPage)
        assertEquals(7, CrawlPlanner.nextScanPage(plan))
    }
}
