package com.wcm.checkin

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri

/**
 * 抓取引擎的共享壳：打开 SAF 目录 + 索引、身份选择、命名/计划/分页等公共能力。
 *
 * 从 `Crawler` 拆出。相册与时间线两条链路都通过 [withCrawlContext] 拿到同一份
 * [CrawlContext]，避免「目录/索引/label 锁」各写一套。
 */
class CrawlContext(
    val store: SafTreeStore,
    val index: CrawlIndex,
    val treeTag: String,
)

object CrawlEngine {

    /** 选**时间线模式**抓图用的 cookie（依次探活取第一个还活着的账号）。 */
    fun pickCrawlCookie(
        ctx: Context,
        accounts: List<AccountStore.Account>,
        log: (String) -> Unit,
    ): String? {
        if (accounts.isEmpty()) {
            log("✗ 没有登录账号，先在首页添加账号")
            return null
        }
        accounts.forEachIndexed { index, acc ->
            val label = acc.name.ifEmpty { acc.uid }
            try {
                val v = WeiboAuthApi.verify(acc.cookie)
                if (v.login) {
                    if (index > 0) log("抓图身份回退到「$label」")
                    // 探活已经证明这个 cookie 可用、也顺手拿到了轮换后的新 cookie：
                    // 不回写的话下一个阶段（相册 → 时间线回退）会再 verify 一遍，
                    // 白耗一次请求，也继续用着旧 cookie。
                    AccountStore.markVerified(
                        ctx, acc.uid, v.cookie, acc.name,
                        serverExpiresAt = v.serverExpiresAt,
                        loginAt = v.loginAt,
                    )
                    return v.cookie
                }
                log("账号「$label」登录态失效")
            } catch (e: Exception) {
                log("账号「$label」探活失败：${e.message}")
            }
        }
        log("✗ 所有账号的登录态均已失效，先在首页重新登录任一账号")
        return null
    }

    // ========================= 单条抓取 =========================

    /** 抓取一条微博的全部原图与 Live。幂等：三层去重，不占用增量进度。 */
    suspend fun crawlSinglePost(ctx: Context, bid: String, log: (String) -> Unit) {
        val cookie = pickCrawlCookie(ctx, AccountStore.load(ctx), log) ?: return
        val treeUriRaw = CrawlSettings.crawlTreeUri(ctx)
        if (treeUriRaw.isEmpty()) {
            log("✗ 请先选择相册目录")
            return
        }
        withCrawlContext(ctx, treeUriRaw, log) { state ->
            val detail = try {
                WeiboPostApi.fetchPostDetail(cookie, bid)
            } catch (e: Exception) {
                log("✗ 详情获取失败：${e.message}")
                return@withCrawlContext
            }
            val assets = PostAssetsParser.fromDetail(detail)
            if (assets.picIds.isEmpty()) {
                log("✗ 该微博没有图片（或为视频微博）")
                return@withCrawlContext
            }
            val postId = detail.optString("id", "").ifEmpty { bid }
            val at = epochOf(detail.optString("created_at", ""))
            val authorUid = detail.optJSONObject("user")?.optString("id", "") ?: ""
            val authorName = detail.optJSONObject("user")?.optString("screen_name", "") ?: ""
            val label = resolveLabel(state.index, authorUid, authorName.ifEmpty { "微博" })
            val prefix = prefixOf(at, label)

            val liveCount = assets.liveByIndex.size + assets.liveExtras.size
            log("── 单条抓取 $prefix（${assets.picIds.size} 图 / $liveCount Live）──")
            val outcome = CrawlSaver.savePost(ctx, state.store, state.index, prefix, postId, at, assets, null, log)
            log(
                if (outcome.saved > 0) "✓ 完成，新增 ${outcome.saved} 个文件（目录累计 ${state.store.count()}）"
                else "✓ 完成（文件均已存在，无新增）"
            )
        }
    }

    // ========================= 勾选抓取（v0.9.11） =========================

    /**
     * 一条勾选任务。[picIds] 为 null 表示**整帖全抓**（浏览页多选按帖勾选），
     * 非空时只落集合内的图（选图页逐张勾选）。picId 是图片业务身份，
     * 与增量抓取 / 单条抓取共用同一份索引与去重。
     */
    data class PickTask(val bid: String, val picIds: Set<String>? = null)

    /** 勾选抓取的结果汇总（选图页 / 浏览页的结果 toast 用）。 */
    data class PickedSummary(
        val posts: Int = 0,
        val saved: Int = 0,
        val failed: Int = 0,
        val stopped: Boolean = false,
    )

    /**
     * 勾选抓取：浏览页 / 选图页提交的一批任务，逐帖拉详情后按槽位落盘。
     *
     * 与 [crawlSinglePost] 的差别只在「哪些槽位要落」：
     * 命名（`prefixOf` + `AssetName`）、三层去重、索引、目录全部同一套 ——
     * 用户先勾选抓一张、以后增量抓全量，文件在同一个名字上相遇，L1 直接命中。
     *
     * 单帖失败记日志继续下一帖；[isStopped] 由 [CrawlService] 的停止信号驱动。
     */
    suspend fun crawlPicked(
        ctx: Context,
        tasks: List<PickTask>,
        log: (String) -> Unit,
        onEvent: (CrawlEvent) -> Unit = {},
        isStopped: () -> Boolean = { false },
    ): PickedSummary {
        val cookie = pickCrawlCookie(ctx, AccountStore.load(ctx), log) ?: return PickedSummary()
        val treeUriRaw = CrawlSettings.crawlTreeUri(ctx)
        if (treeUriRaw.isEmpty()) {
            log("✗ 请先选择相册目录")
            return PickedSummary()
        }
        var posts = 0
        var saved = 0
        var failed = 0
        var stopped = false
        withCrawlContext(ctx, treeUriRaw, log) { state ->
            if (tasks.isNotEmpty()) {
                onEvent(CrawlEvent.Phase(CrawlPhase.PICK, tasks.size, 0))
            }
            for ((taskIdx, task) in tasks.withIndex()) {
                if (isStopped()) {
                    log("⏹ 勾选抓取已停止（已处理 $taskIdx/${tasks.size}）")
                    stopped = true
                    break
                }
                val detail = try {
                    WeiboPostApi.fetchPostDetail(cookie, task.bid)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("✗ 帖 ${task.bid} 详情获取失败：${e.message}")
                    // 跳过也算「处理过一帖」，否则进度条会停在这里不动
                    onEvent(CrawlEvent.Post(0, 0))
                    continue
                }
                val assets = PostAssetsParser.fromDetail(detail)
                if (assets.picIds.isEmpty()) {
                    log("· 帖 ${task.bid} 没有图片（或为视频微博），跳过")
                    onEvent(CrawlEvent.Post(0, 0))
                    continue
                }
                val slots = CrawlSaver.slotsForPics(assets, task.picIds)
                if (slots != null && slots.isEmpty()) {
                    log("· 帖 ${task.bid} 选中的图片不在其中（可能已被删帖），跳过")
                    onEvent(CrawlEvent.Post(0, 0))
                    continue
                }
                val postId = detail.optString("id", "").ifEmpty { task.bid }
                val at = epochOf(detail.optString("created_at", ""))
                val authorUid = detail.optJSONObject("user")?.optString("id", "") ?: ""
                val authorName = detail.optJSONObject("user")?.optString("screen_name", "") ?: ""
                val label = resolveLabel(state.index, authorUid, authorName.ifEmpty { "微博" })
                val prefix = prefixOf(at, label)

                val want = if (slots == null) assets.picIds.size else slots.size
                log("── 勾选抓取 $prefix（${assets.picIds.size} 图，选 ${want} 张）──")
                // 这是唯一一次「只抓勾选的几张」：没勾的槽位要记成待补，
                // 否则 failed 为空会判 DONE，其余图再也补不回来。
                val outcome = CrawlSaver.savePost(
                    ctx, state.store, state.index, prefix, postId, at, assets, slots, log,
                    countNotPicked = true,
                )
                posts++
                saved += outcome.saved
                failed += outcome.failed.size
                onEvent(CrawlEvent.Post(outcome.saved, outcome.failed.size))
                log(
                    if (outcome.saved > 0) "✓ 完成，新增 ${outcome.saved} 个文件（目录累计 ${state.store.count()}）"
                    else "✓ 完成（文件均已存在，无新增）"
                )
            }
        }
        return PickedSummary(posts, saved, failed, stopped)
    }

    // ========================= 目录 / 索引生命周期 =========================

    suspend fun withCrawlContext(
        ctx: Context,
        treeUriRaw: String,
        log: (String) -> Unit,
        block: suspend (CrawlContext) -> Unit,
    ) {
        val treeTag = CrawlIndex.treeTagOf(treeUriRaw)
        val store = SafTreeStore(ctx.contentResolver, treeUriRaw.toUri())
        // 打开索引**之后**的所有准备动作（load / migrateLegacy / Takeover.claimAll）
        // 都可能抛异常（SAF 目录被删、磁盘满、迁移写盘失败…）。此前这些操作在 try
        // 之外，一旦抛出 index.close() 就永远不会执行 —— 索引句柄与临时文件泄漏，
        // 同一目录反复重试会不断累积。所以整个生命周期都塞进 try/finally。
        var index: CrawlIndex? = null
        try {
            store.loadExisting()
            val idx = CrawlIndex.open(ctx.filesDir, treeTag)
            index = idx
            val report = idx.load()
            if (report.degraded) log("⚠ 索引有 ${report.skipped} 行损坏，已降级为「仅文件名去重」")

            // 旧版 crawl_done 一次性迁移（反向不可逆：失败槽位无法从旧数据还原）
            val legacy = CrawlSettings.crawlDoneLegacy(ctx)
            if (legacy.isNotEmpty()) {
                val n = idx.migrateLegacy(legacy)
                CrawlSettings.clearCrawlDoneLegacy(ctx)
                if (n > 0) log("索引迁移：$n 帖从旧进度接管")
            }

            // 目录首次进入本索引：接管已有文件（按大小预筛，仅在撞车时算哈希）
            if (idx.fileCount() == 0) {
                val all = try {
                    GalleryFiles.listAll(ctx.contentResolver, treeUriRaw.toUri())
                } catch (e: Exception) {
                    emptyList()
                }
                if (all.isNotEmpty()) {
                    val resolver = ctx.contentResolver
                    val byName = HashMap<String, Uri>(all.size)
                    all.forEach { byName[AssetName.canonical(it.name)] = it.uri }
                    val rep = Takeover.claimAll(
                        all.map { Takeover.Candidate(AssetName.canonical(it.name), it.size) },
                        idx,
                    ) { c ->
                        val uri = byName[c.name] ?: return@claimAll null
                        try {
                            resolver.openInputStream(uri)?.use { Hashing.sha256Hex128(it) }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    log("相册目录已有 ${all.size} 个文件，${rep.summary}")
                } else {
                    log("相册目录已有 ${store.count()} 个文件")
                }
            } else {
                log("相册目录已有 ${store.count()} 个文件（索引 ${idx.fileCount()} 条）")
            }

            block(CrawlContext(store, idx, treeTag))
        } finally {
            index?.let { idx ->
                idx.maybeCompact()
                idx.flush()
                // 索引写失败此前是完全静默的：用户只会看到「进度回退 / 图片重复下载」
                idx.lastWriteError?.let {
                    log("⚠ 抓取索引写盘失败（$it）：进度可能没保存下来，已下载的文件不受影响")
                }
                idx.close()
            }
        }
    }

    // ========================= 命名 / 计划 / 分页 =========================

    fun resolveLabel(index: CrawlIndex, uid: String, fallback: String): String {
        val locked = if (uid.isEmpty()) "" else index.labelOf(uid)
        if (locked.isNotEmpty()) return locked
        val label = AssetName.safeName(fallback)
        index.lockLabel(uid, label)
        return label
    }

    fun epochOf(createdAt: String): Long {
        val t = WeiboPostApi.parseWeiboTime(createdAt) ?: return 0L
        return CrawlDate.epochSecOf(t.first, t.second) ?: 0L
    }

    fun prefixOf(at: Long, label: String): String {
        if (at <= 0L) return AssetName.unknownPrefix(label)
        val d = CrawlDate.dateOf(at)
        val hhmmss = java.time.Instant.ofEpochSecond(at)
            .atZone(java.time.ZoneOffset.ofHours(8))
            .toLocalTime()
            .let { String.format(java.util.Locale.US, "%02d%02d%02d", it.hour, it.minute, it.second) }
        return AssetName.prefix(d, hhmmss, label)
    }

    /** 把本阶段停下来的原因写进日志 —— 用户能直接看出「为什么这轮只抓到这儿」。 */
    fun logPlan(
        phaseName: String,
        plan: CrawlPlanner.Plan,
        cursor: CrawlCursor,
        log: (String) -> Unit,
    ) {
        if (plan.stop == StopReason.NONE) return
        val range = if (cursor.oldestCoveredAt > 0L && cursor.newestAt > 0L) {
            "，覆盖 ${CrawlDate.dateOf(cursor.oldestCoveredAt)} ~ ${CrawlDate.dateOf(cursor.newestAt)}"
        } else {
            ""
        }
        val detail = when (plan.stop) {
            StopReason.PAGE_EXHAUSTED -> "已翻到底"
            StopReason.SINCE_DATE -> "已推到起始日期"
            StopReason.BUDGET -> "本轮预算用完，下轮从断点续"
            StopReason.PAGE_ERROR -> "第 ${plan.lastPage + 1} 页失败，边界停在上一页"
            StopReason.ALL_COVERED -> "已回到已覆盖区间"
            StopReason.HARD_CAP -> "触达 200 页硬墙，建议把起始日期往后调"
            StopReason.NONE -> ""
        }
        log("  $phaseName 阶段结束：$detail$range")
    }

    fun indexView(index: CrawlIndex) = object : CrawlPlanner.IndexView {
        override fun isTerminal(postId: String) = index.isTerminal(postId)
        override fun failedSlots(postId: String) = index.failedSlots(postId)
    }

    fun pageFetcher(
        cookie: String,
        uid: String,
        log: (String) -> Unit,
    ): (Int) -> CrawlPlanner.PageOutcome = { page ->
        try {
            val (_, list) = WeiboPostApi.fetchUserPosts(cookie, uid, page)
            CrawlPlanner.PageOutcome.ok(
                page,
                list.map { rp ->
                    CrawlPlanner.PostRef(
                        id = rp.id,
                        bid = rp.bid,
                        at = epochOf(rp.createdAt),
                        pinned = rp.pinned,
                        isRetweet = rp.isRetweet,
                        picNum = rp.picNum,
                    )
                },
            )
        } catch (e: Exception) {
            log("  列表第 $page 页失败：${e.message}")
            CrawlPlanner.PageOutcome.fail(page, e.message ?: "列表请求失败")
        }
    }
}

/** 页码至少从 1 开始展示（全量扫描的 lastPage 可能是 0）。 */
internal fun Int.ifAtLeast(min: Int): Int = if (this < min) min else this
