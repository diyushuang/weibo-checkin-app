package com.wcm.checkin

import android.content.Context
import org.json.JSONObject

/**
 * 时间线抓取：`107603<uid>` 分页 + 详情，Phase RETRY / 补新 / 补历史 / 全量扫描。
 *
 * 从原 `Crawler` 拆出；相册链路见 [AlbumCrawler]。
 */
object TimelineCrawler {

    suspend fun runIncremental(
        ctx: Context,
        log: (String) -> Unit,
        autoMode: Boolean = false,
        retryOnly: Boolean = false,
        onEvent: (CrawlEvent) -> Unit = {},
        isStopped: () -> Boolean = { false },
        onlyUids: Set<String> = emptySet(),
        full: Boolean = false,
    ) {
        // 单博主抓取：先按 UID 收窄，再走完全相同的链路（顺序仍是用户看到的顺序）
        val targets = CrawlSettings.selectTargets(CrawlSettings.crawlTargets(ctx), onlyUids)
        val treeUriRaw = CrawlSettings.crawlTreeUri(ctx)
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) {
            log("✗ 没有登录账号，先在首页添加账号")
            return
        }
        if (targets.isEmpty() || treeUriRaw.isEmpty()) {
            if (!autoMode) {
                log(
                    if (onlyUids.isEmpty()) "✗ 请先添加抓取目标并选择相册目录"
                    else "✗ 该博主已不在抓取目标里，刷新目标列表后再试",
                )
            }
            return
        }
        val cookie = CrawlEngine.pickCrawlCookie(ctx, accounts, log) ?: return
        // 索引是按目录共享的：pendingRetry 没有 uid 维度，单博主跑重试会把别人的失败一起补掉。
        // 与其偷偷扩大范围，不如跳过并把入口指给「待重试」——用户点哪个范围就只跑哪个范围。
        val scoped = onlyUids.isNotEmpty()
        val allowRetry = !scoped && !full

        val what = when {
            full && scoped -> "单博主全量"
            full -> "全量抓取"
            retryOnly -> "补抓失败"
            scoped -> "单博主增量"
            else -> "增量抓取"
        }
        onEvent(CrawlEvent.Begin(what, targets.size))
        if (scoped) {
            log("范围：只抓 ${targets.joinToString("、") { it.name.ifEmpty { it.uid } }}")
            if (!allowRetry) log("提示：单博主 / 全量不跑共享的「待重试」队列，需要时用「待重试」入口")
        }
        CrawlEngine.withCrawlContext(ctx, treeUriRaw, log) { state ->
            var downloaded = 0
            for ((index, target) in targets.withIndex()) {
                if (isStopped()) break
                val label = target.name.ifEmpty { target.uid }
                onEvent(CrawlEvent.Target(label, index + 1, targets.size))
                log("── $label（${target.uid}${if (full) " · 全量" else ""}）──")
                try {
                    downloaded += crawlTarget(
                        ctx, cookie, target, state, log, retryOnly, onEvent, isStopped, full, allowRetry,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    state.index.flush()
                    throw e
                } catch (e: Exception) {
                    log("  ✗ 目标中断：${e.message}")
                }
                state.index.flush()
            }
            log("本轮共下载 $downloaded 个新文件，目录累计 ${state.store.count()}")
        }
        onEvent(CrawlEvent.Finish)
    }

    private suspend fun crawlTarget(
        ctx: Context,
        cookie: String,
        target: CrawlTarget,
        state: CrawlContext,
        log: (String) -> Unit,
        retryOnly: Boolean = false,
        onEvent: (CrawlEvent) -> Unit = {},
        isStopped: () -> Boolean = { false },
        full: Boolean = false,
        allowRetry: Boolean = true,
    ): Int {
        val uid = target.uid
        val sinceDate = CrawlSettings.crawlSince(ctx, uid)
        var cursor = CrawlSettings.crawlCursor(ctx, state.treeTag, uid)
        var downloaded = 0
        val started = System.currentTimeMillis()
        var details = 0

        fun exhausted() = downloaded >= Budget.NEW.maxFiles || details >= Budget.NEW.maxDetails ||
            System.currentTimeMillis() - started > Budget.NEW.maxWallClockMs

        // ---- 全量扫描：一条连续下行通道，不跑「补新/补历史」两段式 ----
        if (full && !retryOnly) {
            return crawlFullScan(ctx, cookie, target, state, log, onEvent, isStopped)
        }

        // ---- Phase RETRY：先补上次失败的槽位（不翻页） ----
        // 单博主/全量时跳过：索引按目录共享，pendingRetry 没有 uid 维度，跑了就会越界。
        val pending = if (allowRetry) {
            state.index.pendingRetries(System.currentTimeMillis(), Budget.RETRY.maxDetails)
        } else {
            emptyList()
        }
        if (pending.isNotEmpty()) {
            onEvent(CrawlEvent.Phase(CrawlPhase.RETRY, pending.size, 0))
            log("  重试队列：${pending.size} 帖")
            for (rec in pending) {
                if (exhausted() || isStopped()) break
                details++
                downloaded += processPost(
                    ctx, cookie, rec.postId, uid, target, state, log,
                    // expected < 0 = 上次详情没取到、槽位未知：整帖重跑，不能按空集合跳过
                    onlySlots = if (rec.expected < 0) null else rec.failed.toSet(),
                    onEvent = onEvent,
                )
            }
        }
        // 「立即重试」只跑这一段：不翻页、不动游标
        if (retryOnly) return downloaded

        // ---- Phase A：补新帖 ----
        val fetch = CrawlEngine.pageFetcher(cookie, uid, log)
        val planA = CrawlPlanner.planNew(cursor, CrawlEngine.indexView(state.index), sinceDate, fetch, Budget.NEW)
        if (planA.batch.isNotEmpty()) log("  A 补新：${planA.batch.size} 帖（索引命中 ${planA.skippedTerminal}）")
        onEvent(CrawlEvent.Phase(CrawlPhase.NEW, planA.batch.size, planA.skippedTerminal))
        var processedA = 0
        for (p in planA.batch) {
            if (exhausted() || isStopped()) break
            details++
            downloaded += processPost(
                ctx, cookie, p.id, uid, target, state, log, onlySlots = null, onEvent = onEvent,
            )
            processedA++
        }
        val oldestBefore = cursor.oldestCoveredAt
        if (processedA >= planA.batch.size) {
            cursor = CursorLogic.afterNewPhase(cursor, planA.coverageHigh, planA.coverageLow)
            // Phase A 若把下边界往下推了（说明本轮是在追赶、还没回到水线），
            // 就把历史方向的锚点挪到它到达的最深处，Phase B 从这里接着往下，不重走 1..lastPage。
            if (planA.lastPage > 0 && planA.coverageLow > 0L &&
                (oldestBefore <= 0L || planA.coverageLow < oldestBefore)
            ) {
                cursor = cursor.copy(
                    anchorPageHint = planA.lastPage,
                    anchorAt = planA.coverageLow,
                    anchorPostId = "",
                )
            }
        } else {
            // 预算中途耗尽：后面的页虽然翻到了，但帖没下完，**不能**把边界推过去（否则谎报进度）
            log("  A 预算耗尽（${processedA}/${planA.batch.size}），覆盖边界本轮不推进")
        }
        if (planA.stop == StopReason.PAGE_EXHAUSTED) cursor = cursor.copy(reachedBottom = true)
        CrawlSettings.saveCrawlCursor(ctx, state.treeTag, cursor)
        CrawlEngine.logPlan("A", planA, cursor, log)

        // ---- Phase B：从断点锚点继续补历史 ----
        if (!exhausted() && CrawlPlanner.shouldRunHistory(cursor, sinceDate)) {
            val planB = CrawlPlanner.planHistory(cursor, CrawlEngine.indexView(state.index), sinceDate, fetch, Budget.HISTORY)
            if (planB.batch.isNotEmpty()) log("  B 补历史：${planB.batch.size} 帖（索引命中 ${planB.skippedTerminal}）")
            onEvent(CrawlEvent.Phase(CrawlPhase.HISTORY, planB.batch.size, planB.skippedTerminal))
            var processedB = 0
            for (p in planB.batch) {
                if (exhausted() || isStopped()) break
                details++
                downloaded += processPost(
                    ctx, cookie, p.id, uid, target, state, log, onlySlots = null, onEvent = onEvent,
                )
                processedB++
            }
            if (processedB >= planB.batch.size) {
                cursor = CursorLogic.afterHistoryPhase(
                    cursor, planB.coverageLow, planB.anchorPostId, planB.anchorAt,
                    planB.lastPage, planB.reachedBottom, planB.parked,
                )
            } else {
                log("  B 预算耗尽（${processedB}/${planB.batch.size}），覆盖边界本轮不推进")
                if (planB.reachedBottom) cursor = cursor.copy(reachedBottom = true)
            }
            state.index.flush()
            CrawlSettings.saveCrawlCursor(ctx, state.treeTag, cursor)
            CrawlEngine.logPlan("B", planB, cursor, log)
        }
        return downloaded
    }

    /**
     * 全量扫描（v0.9.12）：从最新一页开始**连续**往最老翻，一段一段走到底。
     *
     * 和增量的本质区别在判据（见 [CrawlPlanner.planScan]）：这里不相信「已覆盖区间」，
     * 只相信索引里「这帖是否终态」。所以它适合用来补历史空洞 —— 旧版本按连续计数
     * 误判、置顶帖绕过判定、中途断掉都会留下「游标以为抓过、其实没抓」的缺口，
     * 增量永远不会回头，全量会。已抓过的帖只花一次列表请求，不会重复下载。
     *
     * 断点存在 [CrawlCursor.fullScanPageHint]：预算用完就从下一页接着走，
     * 翻页失败就留在本页下一轮重试。断点只在**这一轮把整批帖都处理完**时才推进，
     * 否则宁可重做一轮，也不能把没下的帖当成已扫过。
     */
    private suspend fun crawlFullScan(
        ctx: Context,
        cookie: String,
        target: CrawlTarget,
        state: CrawlContext,
        log: (String) -> Unit,
        onEvent: (CrawlEvent) -> Unit,
        isStopped: () -> Boolean,
    ): Int {
        val uid = target.uid
        val sinceDate = CrawlSettings.crawlSince(ctx, uid)
        var cursor = CrawlSettings.crawlCursor(ctx, state.treeTag, uid)
        var downloaded = 0
        var details = 0
        val started = System.currentTimeMillis()

        fun exhausted() = downloaded >= Budget.FULL.maxFiles || details >= Budget.FULL.maxDetails ||
            System.currentTimeMillis() - started > Budget.FULL.maxWallClockMs

        val fetch = CrawlEngine.pageFetcher(cookie, uid, log)
        val plan = CrawlPlanner.planScan(cursor, CrawlEngine.indexView(state.index), sinceDate, fetch, Budget.FULL)
        log("  全量扫描：第 ${plan.startPage} 页起，${plan.batch.size} 帖（索引命中 ${plan.skippedTerminal}）")
        onEvent(CrawlEvent.Phase(CrawlPhase.SCAN, plan.batch.size, plan.skippedTerminal))

        var processed = 0
        for (p in plan.batch) {
            if (exhausted() || isStopped()) break
            details++
            // 上次抓到一半的帖（PARTIAL）只补缺的槽位，已落盘的图不再重下
            val slots = state.index.failedSlots(p.id).ifEmpty { null }
            downloaded += processPost(
                ctx, cookie, p.id, uid, target, state, log, onlySlots = slots, onEvent = onEvent,
            )
            processed++
        }

        if (processed >= plan.batch.size) {
            cursor = CursorLogic.afterNewPhase(cursor, plan.coverageHigh, plan.coverageLow)
            val next = CrawlPlanner.nextScanPage(plan)
            cursor = cursor.copy(fullScanPageHint = next)
            if (plan.reachedBottom) cursor = cursor.copy(reachedBottom = true)
            if (next == 0) {
                log("  ✓ 全量扫描完成（第 ${plan.startPage} ~ ${plan.lastPage.ifAtLeast(1)} 页）")
            } else {
                log("  全量已扫到第 ${plan.lastPage.ifAtLeast(1)} 页，下次从第 $next 页接着走")
            }
        } else {
            // 预算中途耗尽：本页的帖没下完，断点必须留在本轮起点重做，
            // 下一轮已终态的帖会被索引跳过，重复代价只有一次列表请求。
            log("  全量预算耗尽（$processed/${plan.batch.size}），断点留在第 ${plan.startPage} 页，下轮重试")
        }
        state.index.flush()
        CrawlSettings.saveCrawlCursor(ctx, state.treeTag, cursor)
        CrawlEngine.logPlan("全量", plan, cursor, log)
        return downloaded
    }


    /** 抓单帖：先拉详情，再按槽位落盘。 */
    private suspend fun processPost(
        ctx: Context,
        cookie: String,
        postId: String,
        uid: String,
        target: CrawlTarget,
        state: CrawlContext,
        log: (String) -> Unit,
        onlySlots: Set<Int>?,
        onEvent: (CrawlEvent) -> Unit = {},
    ): Int {
        val detail = try {
            WeiboPostApi.fetchPostDetail(cookie, postId)
        } catch (e: Exception) {
            // 什么都不写会让索引根本不知道这帖存在，而游标仍按「页」推进 →
            // 它落在覆盖区间内部，planNew/planHistory 都判「已覆盖」跳过，永不补抓。
            // 落一条 OPEN（expected = -1 表示语义未知），RETRY 队列会反复捞起它。
            state.index.onPostUnknown(postId)
            log("  ✗ $postId 详情失败：${e.message}（已记入待重试）")
            onEvent(CrawlEvent.Post(saved = 0, failed = 0))
            return 0
        }
        val at = CrawlEngine.epochOf(detail.optString("created_at", ""))
        val assets = PostAssetsParser.fromDetail(detail)
        if (assets.picIds.isEmpty()) {
            state.index.onVoid(postId, at)
            onEvent(CrawlEvent.Post(saved = 0, failed = 0))
            return 0
        }
        val authorUid = detail.optJSONObject("user")?.optString("id", "").orEmpty().ifEmpty { uid }
        val label = CrawlEngine.resolveLabel(state.index, authorUid, target.name.ifEmpty { target.uid })
        val prefix = CrawlEngine.prefixOf(at, label)
        val outcome = CrawlSaver.savePost(ctx, state.store, state.index, prefix, postId, at, assets, onlySlots, log)
        if (outcome.saved > 0) log("  ✓ $prefix…（+${outcome.saved}${if (outcome.failed.isEmpty()) "" else "，缺 ${outcome.failed.size}"}）")
        onEvent(CrawlEvent.Post(saved = outcome.saved, failed = outcome.failed.size))
        return outcome.saved
    }
}
