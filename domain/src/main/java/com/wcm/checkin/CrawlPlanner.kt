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

    /**
     * 连续几页空才算「真的到底」。
     *
     * 取 2 而不是 1：微博列表接口会在风控/降级时返回空的 data 列表（HTTP 仍 200），
     * 单页空就置 `reachedBottom` 等于把「翻到底」永久写死，此后历史阶段与全量扫描
     * 都会直接返回 ALL_COVERED —— 缺口永远补不回来。多翻一页的成本只有一次请求。
     */
    private const val EMPTY_STREAK_TO_BOTTOM = 2

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

        /**
         * 参与「本页位于时间线的哪个位置」计算的帖。
         *
         * **必须排除置顶帖**：置顶帖是被置顶的**老帖**，它的时间不代表页面位置。
         * 早先把它算进 `minAt` / `low`，会让「本页最老时间」退化成一个很深的虚假值 ——
         * 覆盖下界被一路拉到置顶帖那天，配合起始日期就会让历史阶段永久停摆
         * （`shouldRunHistory` 认为「已推到起始日期以下」），中间那段图再也抓不到。
         * 整页都是置顶帖的极端情况下退回全量，避免算出 0。
         *
         * 构造时算一次并复用：原先写成 getter，每个 `minAt` / `maxAt` 访问都要重建
         * 两个 List（一页里被读 4-6 次），翻页高峰期纯是 GC 垃圾。
         */
        private val positionHint: List<PostRef> =
            posts.filter { it.at > 0L }.let { timed -> timed.filter { !it.pinned }.ifEmpty { timed } }

        val minAt: Long = positionHint.minOfOrNull { it.at } ?: 0L
        val maxAt: Long = positionHint.maxOfOrNull { it.at } ?: 0L
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
                // 置顶帖既不参与水线（新方向的判定），也不参与下界 —— 它的时间是被置顶
                // 那一刻的旧时间，用它算覆盖区间等于谎报「这一段已经抓过」。
                if (p.at > 0L && !p.pinned) {
                    if (p.at < low) low = p.at
                    if (p.at > high) high = p.at
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
        var emptyStreak = 0
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
                // 单页空**不等于**到底：接口偶发返回空列表（风控抖动、该页恰好被过滤）
                // 就把 reachedBottom 置上，历史阶段此后永久不再跑 —— 缺口再也不会被补。
                // 要求连续 [EMPTY_STREAK_TO_BOTTOM] 页为空才认定到底。
                emptyStreak++
                if (emptyStreak >= EMPTY_STREAK_TO_BOTTOM) {
                    reachedBottom = true
                    stop = StopReason.PAGE_EXHAUSTED
                    break
                }
                if (page == endPage) {
                    stop = if (endPage >= MAX_PAGES) StopReason.HARD_CAP else StopReason.BUDGET
                }
                continue
            }
            emptyStreak = 0
            lastPage = page
            if (slice.minAt > 0L && slice.minAt < low) low = slice.minAt
            // 锚点取「时间可解析」的最老帖：at == 0 的帖不能当锚点，否则锚点恒为 0；
            // 置顶帖同样要排除 —— 它是被置顶的老帖，时间不代表页面位置，
            // 一旦被选成锚点，下一轮 locateAnchor 永远找不到它，历史阶段就永久停摆
            val pageOldest = slice.posts.filter { it.at > 0L && !it.pinned }.minByOrNull { it.at }
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

    // ========================= 全量扫描（v0.9.12） =========================

    /**
     * 全量扫描：从最新一页开始，**连续**往最老方向翻，直到起始日期 / 到底 / 硬墙 / 本轮预算。
     *
     * 与「增量」的区别只在判据：
     * - 增量只补「水线以上」的缺口，并在回到已覆盖区间时停（`ALL_COVERED`）；
     * - 全量**不承认已有覆盖区间**，只信索引里的「这帖是否终态」—— 终态只花一次列表请求，
     *   不拉详情、不重复下载。所以它能发现任何历史上的空洞（旧版本按连续计数误判、
     *   置顶帖绕过判定、中途断过留下的缺口），代价是列表请求数随深度线性增长。
     *
     * 断点用 [CrawlCursor.fullScanPageHint]（页码）而不是时间锚点：全量是**单向连续下行**，
     * 页码即位置，中途插入/删除最多让一页重复、不会漏。增量则必须用时间锚点，
     * 因为它只补一段区间、容易被置顶与删帖带偏。
     */
    fun planScan(
        cursor: CrawlCursor,
        index: IndexView,
        sinceDate: String,
        fetch: (Int) -> PageOutcome,
        budget: Budget = Budget.FULL,
    ): Plan {
        val sinceSec = CrawlDate.dayStartSec(sinceDate)
        val startPage = cursor.fullScanPageHint.coerceIn(1, MAX_PAGES)
        val batch = ArrayList<PostRef>()
        val seen = HashSet<String>()
        var stop = StopReason.NONE
        var lastPage = 0
        var low = Long.MAX_VALUE
        var high = 0L
        var reachedBottom = false
        var emptyStreak = 0
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
                // 同 [planHistory]：单页空不能当成到底，否则中途一次空响应就让
                // 「全量扫描」永远停在那儿（断点 0 = 已完成），后面的历史再也补不到。
                emptyStreak++
                if (emptyStreak >= EMPTY_STREAK_TO_BOTTOM) {
                    reachedBottom = true
                    stop = StopReason.PAGE_EXHAUSTED
                    break
                }
                if (page == endPage) {
                    stop = if (endPage >= MAX_PAGES) StopReason.HARD_CAP else StopReason.BUDGET
                }
                continue
            }
            emptyStreak = 0
            lastPage = page
            if (slice.minAt > 0L && slice.minAt < low) low = slice.minAt
            if (slice.maxAt > high) high = slice.maxAt

            for (p in slice.posts) {
                if (!p.imageBearingOriginal) continue
                // 终态 = 这张帖的图已经完整落盘过：只记一次「跳过」，不去拉详情
                if (index.isTerminal(p.id)) {
                    if (seen.add(p.id)) skipped++
                    continue
                }
                // 起始日期是硬下界：全量也不越过用户设定的边界
                if (sinceSec != null && p.at > 0L && p.at < sinceSec) continue
                if (seen.add(p.id)) batch.add(p)
            }

            if (sinceSec != null && slice.minAt > 0L && slice.minAt < sinceSec) {
                stop = StopReason.SINCE_DATE
                break
            }
            if (page == endPage) {
                stop = if (endPage >= MAX_PAGES) StopReason.HARD_CAP else StopReason.BUDGET
            }
        }

        if (low == Long.MAX_VALUE) low = 0L
        return Plan(
            phase = Phase.SCAN,
            startPage = startPage,
            batch = batch.sortedBy { it.at },
            lastPage = lastPage,
            stop = stop,
            coverageLow = low,
            coverageHigh = high,
            reachedBottom = reachedBottom,
            skippedTerminal = skipped,
        )
    }

    /**
     * 全量扫描结束后的续跑页码：0 表示这一轮把方向走完了，下一轮从头再来。
     *
     * - 预算用完 → 从下一轮接着走（`lastPage + 1`）；
     * - 某页失败 → 同样从 `lastPage + 1` 开始，也就是**失败的这一页**（`lastPage` 是最后
     *   一个成功页，失败页 = `lastPage + 1`），跳过去才是真的漏；
     * - 到底 / 推到起始日期 / 触达 200 页硬墙 → 本轮全量扫描完成。
     */
    fun nextScanPage(plan: Plan): Int = when (plan.stop) {
        // lastPage 是「最后一个成功页」。首页就失败时它是 0，此时必须保留 startPage，
        // 否则断点从 41 掉回 1，下一轮白爬 40 页（稳定失败页还会在 1 与 41 之间往返）
        StopReason.BUDGET, StopReason.PAGE_ERROR ->
            if (plan.lastPage > 0) (plan.lastPage + 1).coerceAtMost(MAX_PAGES) else plan.startPage
        else -> 0
    }

    // ========================= 锚点定位 =========================

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
        // 置顶帖的时间是被置顶那一刻的旧时间，不能用来判定「页面跨过锚点」
        val hasNewer = slice.posts.any { !it.pinned && it.at >= cursor.anchorAt }
        val hasOlder = slice.posts.any { !it.pinned && it.at <= cursor.anchorAt }
        return hasNewer && hasOlder
    }

    // ========================= RETRY =========================

    /** 重试项：帖 id + 待补槽位。只对索引里 failed 非空的帖跑，不翻页。 */
    data class RetryItem(val postId: String, val at: Long, val slots: Set<Int>)

    fun planRetry(pending: List<CrawlIndex.PostRec>, limit: Int = 50): List<RetryItem> =
        pending.take(limit).map { RetryItem(it.postId, it.at, it.failed.toSet()) }
}
