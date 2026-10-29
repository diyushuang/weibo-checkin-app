package com.wcm.checkin

/**
 * 抓取规划器（纯函数，零 Android / 零网络依赖，`fetch` 以 lambda 注入）。
 *
 * 旧实现在 [Crawler] 里用 `STOP_AFTER_DONE=10`（连续 10 条已完成就停）当终止条件，
 * 把「下载成功」错当成「区间已走完」的证据：转发/无图帖不断清零计数、置顶帖绕过判定、
 * 中途断过一次的历史缺口永远补不上。这里把两件事彻底拆开：
 *
 * - **任务维度**：这一页里哪些帖还没终态 → 需要下载；
 * - **证明维度**：这一页是否成功取回 → 是否允许推进覆盖边界。
 *
 * 覆盖判据（取代连续计数）：
 * 区间 `[T, newestAt]` 已完整覆盖，当且仅当从起始页到第 k 页**每一次列表请求都成功**、
 * 第 k 页最老时间 ≤ T、且第 1..k 页里每个「原创且有图」的帖都有终态记录。
 */
object CrawlPlanner {

    const val MAX_PAGES = 200          // 单账号硬墙：每页 10 条，最多 2000 帖
    private const val ANCHOR_HOPS = 3  // 锚点定位最多跳 3 页

    /** 列表页里的最小帖信息（`at` 已解析成 epoch 秒）。 */
    data class PostRef(
        val id: String,
        val bid: String,
        val at: Long,
        val pinned: Boolean = false,
        val isRetweet: Boolean = false,
        val picNum: Int = 0,
    ) {
        /** 只有原创且有图的帖才可能产生下载任务。转发帖的图不算博主原创。 */
        val imageBearingOriginal: Boolean get() = !isRetweet && picNum > 0
    }

    data class PageSlice(val page: Int, val posts: List<PostRef>) {
        /** at == 0 表示时间解析失败，不参与区间边界计算（否则会把边界拉到 0）。 */
        val minAt: Long get() = posts.mapNotNull { p -> p.at.takeIf { it > 0L } }.minOrNull() ?: 0L
        val maxAt: Long get() = posts.mapNotNull { p -> p.at.takeIf { it > 0L } }.maxOrNull() ?: 0L
    }

    /** `slice == null` 且 `error != null` 表示这一页没取回（网络/接口错误）。 */
    data class PageOutcome(val page: Int, val slice: PageSlice?, val error: String? = null) {
        companion object {
            fun ok(page: Int, posts: List<PostRef>) = PageOutcome(page, PageSlice(page, posts))
            fun fail(page: Int, why: String) = PageOutcome(page, null, why)
        }
    }

    /** 索引只读视图：单测用内存实现替换，生产用 [CrawlIndex]。 */
    interface IndexView {
        fun isTerminal(postId: String): Boolean
        fun failedSlots(postId: String): Set<Int>
    }

    data class Plan(
        val phase: Phase,
        val startPage: Int = 1,
        /** 已按 `at` 升序，可直接顺序下载（同轮内时间轴连续）。 */
        val batch: List<PostRef> = emptyList(),
        val lastPage: Int = 0,
        val stop: StopReason = StopReason.NONE,
        val coverageLow: Long = 0L,
        val coverageHigh: Long = 0L,
        val anchorPostId: String = "",
        val anchorAt: Long = 0L,
        val reachedBottom: Boolean = false,
        val parked: Boolean = false,
        /** 命中 L2 而无需拉详情的帖数（日志用，证明「没白扫」）。 */
        val skippedTerminal: Int = 0,
    )

    /** 历史方向是否还有活可干（已到底 / 已停靠 / 已推到起始日期以下 → 不用跑）。 */
    fun shouldRunHistory(c: CrawlCursor, sinceDate: String): Boolean {
        if (c.reachedBottom || c.historyParked) return false
        val sinceSec = CrawlDate.dayStartSec(sinceDate) ?: return true
        if (c.oldestCoveredAt > 0L && c.oldestCoveredAt <= sinceSec) return false
        return true
    }

    // ========================= Phase A：补新 =========================

    /**
     * 从第 1 页往下补新帖。终止条件是「本页全部是终态 且 本页最老已回到水位」，
     * 而不是数「连续几条已完成」。
     */
    fun planNew(
        cursor: CrawlCursor,
        index: IndexView,
        sinceDate: String,
        fetch: (Int) -> PageOutcome,
        budget: Budget = Budget.NEW,
    ): Plan {
        val batch = ArrayList<PostRef>()
        val seen = HashSet<String>()
        val sinceSec = CrawlDate.dayStartSec(sinceDate)
        var stop = StopReason.NONE
        var lastPage = 0
        var low = Long.MAX_VALUE
        var high = 0L
        var skipped = 0

        val maxPage = minOf(budget.maxPages, MAX_PAGES)
        for (page in 1..maxPage) {
            val out = fetch(page)
            val slice = out.slice
            if (slice == null) {
                stop = StopReason.PAGE_ERROR
                break
            }
            if (slice.posts.isEmpty()) {
                stop = StopReason.PAGE_EXHAUSTED
                break
            }
            lastPage = page
            for (p in slice.posts) {
                if (p.at > 0L) {
                    if (p.at < low) low = p.at
                    // 置顶帖永远出现在第 1 页，不能参与水位计算
                    if (!p.pinned && p.at > high) high = p.at
                }
            }

            var allClassified = true
            for (p in slice.posts) {
                if (!p.imageBearingOriginal) continue      // 转发/无图不构成任务，也不阻塞覆盖
                if (index.isTerminal(p.id)) {
                    if (seen.add(p.id)) skipped++
                    continue
                }
                allClassified = false
                // 起始日期是硬下界：早于它的帖不再抓（用户明确不要这段历史）
                if (sinceSec != null && p.at > 0L && p.at < sinceSec) continue
                if (seen.add(p.id)) batch.add(p)
            }

            // 停止：本页全部已处理，并且已经回到水线（或首次运行已走过第 1 页）
            val watermarkReached =
                cursor.newestAt > 0L && slice.minAt > 0L && slice.minAt <= cursor.newestAt
            val firstRunFloor = cursor.newestAt <= 0L && page > 1
            if (allClassified && (watermarkReached || firstRunFloor)) {
                stop = StopReason.ALL_COVERED
                break
            }
            if (sinceSec != null && slice.minAt > 0L && slice.minAt < sinceSec) {
                stop = StopReason.SINCE_DATE
                break
            }
            if (page == maxPage) {
                stop = if (maxPage >= MAX_PAGES) StopReason.HARD_CAP else StopReason.BUDGET
            }
        }

        if (low == Long.MAX_VALUE) low = 0L
        return Plan(
            phase = Phase.NEW,
            startPage = 1,
            batch = batch.sortedBy { it.at },
            lastPage = lastPage,
            stop = stop,
            coverageLow = low,
            coverageHigh = high,
            anchorPostId = "",
            anchorAt = 0L,
            skippedTerminal = skipped,
        )
    }

    // ========================= Phase B：补历史 =========================

    /**
     * 从断点锚点继续往旧补。锚点是 `(anchorAt, anchorPostId)`，**不是页码** ——
     * 新帖插入会让页码整体下移、删帖会让页码上移，页码只能当搜索起点。
     *
     * 定位失败（锚点帖被删且时间也对不上）时退化为从第 1 页重走，正确性不受影响，只是多花几次请求。
     */
    fun planHistory(
        cursor: CrawlCursor,
        index: IndexView,
        sinceDate: String,
        fetch: (Int) -> PageOutcome,
        budget: Budget = Budget.HISTORY,
    ): Plan {
        if (cursor.reachedBottom || cursor.historyParked) {
            return Plan(phase = Phase.HISTORY, stop = StopReason.ALL_COVERED)
        }
        val sinceSec = CrawlDate.dayStartSec(sinceDate)
        val startPage = locateAnchor(cursor, fetch)

        val batch = ArrayList<PostRef>()
        val seen = HashSet<String>()
        var stop = StopReason.NONE
        var lastPage = 0
        var low = Long.MAX_VALUE
        var reachedBottom = false
        var parked = false
        var oldestPost: PostRef? = null
        var skipped = 0

        val endPage = minOf(startPage + budget.maxPages - 1, MAX_PAGES)
        for (page in startPage..endPage) {
            val out = fetch(page)
            val slice = out.slice
            if (slice == null) {
                stop = StopReason.PAGE_ERROR
                break
            }
            if (slice.posts.isEmpty()) {
                reachedBottom = true
                stop = StopReason.PAGE_EXHAUSTED
                break
            }
            lastPage = page
            if (slice.minAt > 0L && slice.minAt < low) low = slice.minAt
            // 锚点取「时间可解析」的最老帖：at == 0 的帖不能当锚点，否则锚点恒为 0
            val pageOldest = slice.posts.filter { it.at > 0L }.minByOrNull { it.at }
            val curOldest = oldestPost
            if (pageOldest != null && (curOldest == null || pageOldest.at < curOldest.at)) {
                oldestPost = pageOldest
            }

            var allClassified = true
            for (p in slice.posts) {
                if (!p.imageBearingOriginal) continue
                if (index.isTerminal(p.id)) {
                    if (seen.add(p.id)) skipped++
                    continue
                }
                allClassified = false
                if (sinceSec != null && p.at > 0L && p.at < sinceSec) continue
                if (seen.add(p.id)) batch.add(p)
            }

            if (sinceSec != null && slice.minAt > 0L && slice.minAt < sinceSec) {
                stop = StopReason.SINCE_DATE
                parked = true
                break
            }
            // 整页都落在已经覆盖过的区间里、且都处理过 → 空洞已补齐。
            // 只在起始页之后判断：起始页本身就是边界页，否则会刚进门就退出。
            if (page > startPage && allClassified &&
                cursor.oldestCoveredAt > 0L && slice.maxAt <= cursor.oldestCoveredAt
            ) {
                stop = StopReason.ALL_COVERED
                break
            }
            if (page == endPage) {
                stop = if (endPage >= MAX_PAGES) StopReason.HARD_CAP else StopReason.BUDGET
            }
        }

        if (low == Long.MAX_VALUE) low = 0L
        return Plan(
            phase = Phase.HISTORY,
            startPage = startPage,
            batch = batch.sortedBy { it.at },
            lastPage = lastPage,
            stop = stop,
            coverageLow = low,
            coverageHigh = 0L,
            anchorPostId = oldestPost?.id ?: "",
            anchorAt = oldestPost?.at ?: 0L,
            reachedBottom = reachedBottom,
            parked = parked,
            skippedTerminal = skipped,
        )
    }

    /**
     * 用 hint 页缩小搜索；只信 id 与时间，不信页码本身。
     * 顺序按 |偏移| 递增：hint → hint+1 → hint-1 → hint+2 → hint-2 → hint+3 → hint-3。
     */
    private fun locateAnchor(cursor: CrawlCursor, fetch: (Int) -> PageOutcome): Int {
        if (cursor.anchorAt <= 0L && cursor.anchorPostId.isEmpty()) return 1
        val hint = cursor.anchorPageHint.coerceIn(1, MAX_PAGES)
        if (!cursor.hasCoverage) return 1
        for (dist in 0..ANCHOR_HOPS) {
            val candidates = if (dist == 0) listOf(hint) else listOf(hint + dist, hint - dist)
            for (p in candidates) {
                if (p < 1 || p > MAX_PAGES) continue
                val slice = fetch(p).slice ?: continue
                if (slice.posts.isEmpty()) continue
                if (crossesAnchor(slice, cursor)) return p
            }
        }
        return 1     // 漂移超出窗口：退化重走，正确性不变
    }

    /** 页内是否含锚点：id 命中，或时间跨越 anchorAt（防锚点帖被删）。 */
    private fun crossesAnchor(slice: PageSlice, cursor: CrawlCursor): Boolean {
        if (cursor.anchorPostId.isNotEmpty() && slice.posts.any { it.id == cursor.anchorPostId }) return true
        if (cursor.anchorAt <= 0L) return false
        val hasNewer = slice.posts.any { it.at >= cursor.anchorAt }
        val hasOlder = slice.posts.any { it.at <= cursor.anchorAt }
        return hasNewer && hasOlder
    }

    // ========================= RETRY =========================

    /** 重试项：帖 id + 待补槽位。只对索引里 failed 非空的帖跑，不翻页。 */
    data class RetryItem(val postId: String, val at: Long, val slots: Set<Int>)

    fun planRetry(pending: List<CrawlIndex.PostRec>, limit: Int = 50): List<RetryItem> =
        pending.take(limit).map { RetryItem(it.postId, it.at, it.failed.toSet()) }
}
