package com.wcm.checkin

import androidx.core.net.toUri
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.IOException

/**
 * 原图抓取引擎（v0.5）。口径对齐《抓取流程交接文档》：
 *
 * 1. 列表 /api/container/getIndex?containerid=107603<UID>&page=N（每页 10 条，card_type=9）；
 * 2. 详情 /statuses/show?id=<BID> 才有完整 pic_ids 与帖级 live_photo；
 * 3. pics 数组会截断到 9 且可能是数字键对象 —— 图清单以 pic_ids 为准，pics 只用于
 *    判定哪个 pid 是 Live（配对 videoSrc）；
 * 4. 原图 = wx{1..4}.sinaimg.cn/large/<PID>.jpg（接口给的 mw2000 是缩图）；
 * 5. Live 的 mov 内层直链 403，必须走 video.weibo.com/media/play?livephoto= 包装地址；
 * 6. 节流：下载间 600-900ms、详情间 ≥620ms、翻页间 ≥700ms；
 * 7. 单文件下载失败自动重试 3 次（间隔递增），重试与失败原因写入日志。
 *
 * v0.5 相对 v0.4 的三处根本性改动：
 * - **推进**：由 [CrawlPlanner] 驱动，用 `(anchorAt, anchorPostId)` 断点续跑，
 *   不再每轮从第 1 页重扫、也不再靠「连续 10 条已完成」猜终点；
 * - **去重**：三层（文件名规范化 → picId/postId 业务身份 → SHA-256 内容），
 *   索引落地在 [CrawlIndex]（追加式 NDJSON），换目录天然隔离；
 * - **完成语义**：只有全部槽位成功（或文件已存在）才 DONE，失败进重试队列，
 *   不再出现「3 张里 404 一张却记成 3 张全完成」的永久缺口。
 *
 * 下载阶段对 CDN 完全匿名（不带 Cookie）；所有出站 URL 过微博域名白名单。
 */
object Crawler {

    /** SAF 相册目录：DocumentsContract 单次查询缓存全部文件名与大小。 */
    class TreeStore(private val resolver: ContentResolver, treeUri: Uri) {
        private val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        private val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        private val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)

        /** 精确文件名 → 字节数。 */
        private val names = HashMap<String, Long>()

        /** 归一后的文件名（去掉 ` (1)` 撞名后缀）→ 字节数。 */
        private val canonicals = HashMap<String, Long>()

        fun loadExisting() {
            names.clear()
            canonicals.clear()
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val n = cursor.getString(nameIdx) ?: continue
                    remember(n, cursor.getLong(sizeIdx))
                }
            }
        }

        private fun remember(name: String, size: Long) {
            names[name] = size
            val c = AssetName.canonical(name)
            if (!canonicals.containsKey(c)) canonicals[c] = size
        }

        fun count(): Int = names.size

        /** 精确匹配（旧行为，保留给日志与兼容）。 */
        fun hasFile(name: String): Boolean = names.containsKey(name)

        /** 归一匹配：`x (1).jpg` 也算 `x.jpg` 已存在 —— SAF 撞名副本不会再被当成「没有」。 */
        fun hasCanonical(name: String): Boolean = canonicals.containsKey(AssetName.canonical(name))

        fun sizeOf(name: String): Long =
            names[name] ?: canonicals[AssetName.canonical(name)] ?: 0L

        /** 幂等写入：同名文件已存在视为成功（断点续跑不产生 "(1)" 副本）。 */
        fun save(name: String, mime: String, bytes: ByteArray): Boolean {
            if (hasCanonical(name)) return true
            if (bytes.isEmpty()) return false
            return try {
                val docUri = DocumentsContract.createDocument(resolver, rootUri, mime, name)
                    ?: return false
                resolver.openOutputStream(docUri)?.use { it.write(bytes) } ?: return false
                remember(name, bytes.size.toLong())
                true
            } catch (e: IOException) {
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    private const val DOWNLOAD_ATTEMPTS = 3    // 单文件下载尝试次数（CDN 偶发 403/超时自动重试）
    private val LIVE_WRAPPER = "https://video.weibo.com/media/play?livephoto="

    /** 大文件下载专用 client：Live mov 可达 64MB，放宽全局 client 的 25s callTimeout。 */
    private val downloadClient = WeiboApi.client.newBuilder()
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // ========================= 资产解析 =========================

    /** pics 归一化：数组或数字键对象 → 列表。 */
    private fun normPics(o: Any?): List<JSONObject> {
        return when (o) {
            is org.json.JSONArray ->
                (0 until o.length()).mapNotNull { o.optJSONObject(it) }
            is JSONObject -> o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.toList()
            else -> emptyList()
        }
    }

    /** 从 large.url 反推 pid（比 p.pid 稳：Live 条目两处 pid 不总一致）。 */
    private fun pidOf(pic: JSONObject): String {
        val url = pic.optJSONObject("large")?.optString("url", "")
            ?: pic.optString("url", "")
        if (url.isNotEmpty()) {
            return url.substringAfterLast('/')
                .replace(Regex("\\.(jpg|jpeg|png|gif|webp)$", RegexOption.IGNORE_CASE), "")
        }
        return pic.optString("pid", "")
    }

    /** 一条微博的全部图片资产（解析自详情接口）。 */
    class PostAssets(
        val picIds: List<String>,
        val liveByIndex: Map<Int, String>,
        val liveExtras: List<String>,
    ) {
        /**
         * 实际应落盘的文件数。
         *
         * Live 项只存动图（gif/mov），**不再额外存配对静图** —— 那张 jpg 只是动图的封面帧，
         * 存下来就是同一张图的两份（旧版 v0.4 的 bug）。所以一张配图恰好对应一个文件。
         */
        val expectedFiles: Int get() = picIds.size + liveExtras.size
    }

    /** 槽位编码：静图 i*2，配对 Live i*2+1，帖级 Live 1000+n。全局唯一且稳定。 */
    private fun stillSlot(i: Int) = i * 2
    private fun pairedLiveSlot(i: Int) = i * 2 + 1
    private fun extraLiveSlot(n: Int) = 1000 + n

    /** 详情 → 图清单（pic_ids 完整保序）+ Live 配对（pics 前 9 条内）+ 独立 Live。 */
    private fun assetsFromDetail(detail: JSONObject): PostAssets {
        val picIds = ArrayList<String>()
        val idsArr = detail.optJSONArray("pic_ids")
        if (idsArr != null) {
            for (i in 0 until idsArr.length()) picIds.add(idsArr.optString(i, ""))
        }
        val pics = normPics(detail.opt("pics"))
        if (picIds.isEmpty()) pics.forEach { picIds.add(pidOf(it)) }

        val liveByIndex = HashMap<Int, String>()
        val usedLiveUrls = HashSet<String>()
        for (pic in pics) {
            val videoSrc = pic.optString("videoSrc", "")
            if (pic.optString("type") == "livephoto" && videoSrc.isNotEmpty()) {
                val idx = picIds.indexOf(pidOf(pic))
                if (idx >= 0) {
                    liveByIndex[idx] = videoSrc
                    usedLiveUrls.add(videoSrc)
                }
            }
        }
        val liveExtras = ArrayList<String>()
        val postLive = detail.optJSONArray("live_photo")
        if (postLive != null) {
            for (i in 0 until postLive.length()) {
                val enc = postLive.optString(i, "")
                if (enc.isNotEmpty() && !usedLiveUrls.contains(LIVE_WRAPPER + enc)) {
                    liveExtras.add(LIVE_WRAPPER + enc)
                }
            }
        }
        return PostAssets(picIds, liveByIndex, liveExtras)
    }

    // ========================= 抓图身份选择（多账号回退） =========================

    /**
     * 选抓图用的 cookie：抓公开微博任一有效登录态都行，依次探活取第一个还活着的，
     * 不再固定赌第一个账号。全部失效返回 null（原因写日志，调用方直接 return）。
     */
    fun pickCrawlCookie(
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
                val v = WeiboApi.verify(acc.cookie)
                if (v.login) {
                    if (index > 0) log("抓图身份回退到「$label」")
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
        val cookie = pickCrawlCookie(AccountStore.load(ctx), log) ?: return
        val treeUriRaw = AccountStore.crawlTreeUri(ctx)
        if (treeUriRaw.isEmpty()) {
            log("✗ 请先选择相册目录")
            return
        }
        withCrawlContext(ctx, treeUriRaw, log) { state ->
            val detail = try {
                WeiboApi.fetchPostDetail(cookie, bid)
            } catch (e: Exception) {
                log("✗ 详情获取失败：${e.message}")
                return@withCrawlContext
            }
            val assets = assetsFromDetail(detail)
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
            val outcome = savePost(ctx, cookie, state, prefix, postId, at, assets, null, log)
            log(
                if (outcome.saved > 0) "✓ 完成，新增 ${outcome.saved} 个文件（目录累计 ${state.store.count()}）"
                else "✓ 完成（文件均已存在，无新增）"
            )
        }
    }

    // ========================= 全量增量抓取 =========================

    /**
     * 对全部抓取目标跑一轮增量（Phase RETRY → A 补新 → B 补历史）。
     * 进度落在 [CrawlCursor]，任意时刻中断都能从锚点接上。
     *
     * [retryOnly] 只跑 RETRY 阶段（补上次失败的槽位），不翻页。
     * [onEvent] 进度事件（只用于展示，不影响抓取语义）。
     * [isStopped] 停止信号：在每帖 / 每阶段之间检查，让自动触发的抓取也能停。
     */
    suspend fun runIncremental(
        ctx: Context,
        log: (String) -> Unit,
        autoMode: Boolean = false,
        retryOnly: Boolean = false,
        onEvent: (CrawlEvent) -> Unit = {},
        isStopped: () -> Boolean = { false },
    ) {
        val targets = AccountStore.crawlTargets(ctx)
        val treeUriRaw = AccountStore.crawlTreeUri(ctx)
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) {
            log("✗ 没有登录账号，先在首页添加账号")
            return
        }
        if (targets.isEmpty() || treeUriRaw.isEmpty()) {
            if (!autoMode) log("✗ 请先添加抓取目标并选择相册目录")
            return
        }
        val cookie = pickCrawlCookie(accounts, log) ?: return

        onEvent(CrawlEvent.Begin(if (retryOnly) "补抓失败" else "增量抓取", targets.size))
        withCrawlContext(ctx, treeUriRaw, log) { state ->
            var downloaded = 0
            for ((index, target) in targets.withIndex()) {
                if (isStopped()) break
                val label = target.name.ifEmpty { target.uid }
                onEvent(CrawlEvent.Target(label, index + 1, targets.size))
                log("── $label（${target.uid}）──")
                try {
                    downloaded += crawlTarget(
                        ctx, cookie, target, state, log, retryOnly, onEvent, isStopped,
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

    /** 一次抓取运行共享的上下文：目录快照 + 索引。 */
    private class RunState(val store: TreeStore, val index: CrawlIndex, val treeTag: String)

    private suspend fun withCrawlContext(
        ctx: Context,
        treeUriRaw: String,
        log: (String) -> Unit,
        block: suspend (RunState) -> Unit,
    ) {
        val treeTag = CrawlIndex.treeTagOf(treeUriRaw)
        val store = TreeStore(ctx.contentResolver, treeUriRaw.toUri())
        store.loadExisting()
        val index = CrawlIndex.open(ctx.filesDir, treeTag)
        val report = index.load()
        if (report.degraded) log("⚠ 索引有 ${report.skipped} 行损坏，已降级为「仅文件名去重」")

        // 旧版 crawl_done 一次性迁移（反向不可逆：失败槽位无法从旧数据还原）
        val legacy = AccountStore.crawlDoneLegacy(ctx)
        if (legacy.isNotEmpty()) {
            val n = index.migrateLegacy(legacy)
            AccountStore.clearCrawlDoneLegacy(ctx)
            if (n > 0) log("索引迁移：$n 帖从旧进度接管")
        }

        // 目录首次进入本索引：接管已有文件（按大小预筛，仅在撞车时算哈希）
        if (index.fileCount() == 0) {
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
                    index,
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
            log("相册目录已有 ${store.count()} 个文件（索引 ${index.fileCount()} 条）")
        }

        try {
            block(RunState(store, index, treeTag))
        } finally {
            index.maybeCompact()
            index.flush()
            index.close()
        }
    }

    private suspend fun crawlTarget(
        ctx: Context,
        cookie: String,
        target: AccountStore.CrawlTarget,
        state: RunState,
        log: (String) -> Unit,
        retryOnly: Boolean = false,
        onEvent: (CrawlEvent) -> Unit = {},
        isStopped: () -> Boolean = { false },
    ): Int {
        val uid = target.uid
        val sinceDate = AccountStore.crawlSince(ctx, uid)
        var cursor = AccountStore.crawlCursor(ctx, state.treeTag, uid)
        var downloaded = 0
        val started = System.currentTimeMillis()
        var details = 0

        fun exhausted() = downloaded >= Budget.NEW.maxFiles || details >= Budget.NEW.maxDetails ||
            System.currentTimeMillis() - started > Budget.NEW.maxWallClockMs

        // ---- Phase RETRY：先补上次失败的槽位（不翻页） ----
        val pending = state.index.pendingRetries(System.currentTimeMillis(), Budget.RETRY.maxDetails)
        if (pending.isNotEmpty()) {
            onEvent(CrawlEvent.Phase(CrawlPhase.RETRY, pending.size, 0))
            log("  重试队列：${pending.size} 帖")
            for (rec in pending) {
                if (exhausted() || isStopped()) break
                details++
                downloaded += processPost(
                    ctx, cookie, rec.postId, uid, target, state, log,
                    onlySlots = rec.failed.toSet(), onEvent = onEvent,
                )
            }
        }
        // 「立即重试」只跑这一段：不翻页、不动游标
        if (retryOnly) return downloaded

        // ---- Phase A：补新帖 ----
        val fetch = pageFetcher(cookie, uid, log)
        val planA = CrawlPlanner.planNew(cursor, indexView(state.index), sinceDate, fetch, Budget.NEW)
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
        AccountStore.saveCrawlCursor(ctx, state.treeTag, cursor)
        logPlan("A", planA, cursor, log)

        // ---- Phase B：从断点锚点继续补历史 ----
        if (!exhausted() && CrawlPlanner.shouldRunHistory(cursor, sinceDate)) {
            val planB = CrawlPlanner.planHistory(cursor, indexView(state.index), sinceDate, fetch, Budget.HISTORY)
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
            AccountStore.saveCrawlCursor(ctx, state.treeTag, cursor)
            logPlan("B", planB, cursor, log)
        }
        return downloaded
    }

    /** 把本阶段停下来的原因写进日志 —— 用户能直接看出「为什么这轮只抓到这儿」。 */
    private fun logPlan(
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

    private fun indexView(index: CrawlIndex) = object : CrawlPlanner.IndexView {
        override fun isTerminal(postId: String) = index.isTerminal(postId)
        override fun failedSlots(postId: String) = index.failedSlots(postId)
    }

    private fun pageFetcher(
        cookie: String,
        uid: String,
        log: (String) -> Unit,
    ): (Int) -> CrawlPlanner.PageOutcome = { page ->
        try {
            val (_, list) = WeiboApi.fetchUserPosts(cookie, uid, page)
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

    /** 抓单帖：先拉详情，再按槽位落盘。 */
    private suspend fun processPost(
        ctx: Context,
        cookie: String,
        postId: String,
        uid: String,
        target: AccountStore.CrawlTarget,
        state: RunState,
        log: (String) -> Unit,
        onlySlots: Set<Int>?,
        onEvent: (CrawlEvent) -> Unit = {},
    ): Int {
        val detail = try {
            WeiboApi.fetchPostDetail(cookie, postId)
        } catch (e: Exception) {
            log("  ✗ $postId 详情失败：${e.message}")
            onEvent(CrawlEvent.Post(saved = 0, failed = 0))
            return 0
        }
        val at = epochOf(detail.optString("created_at", ""))
        val assets = assetsFromDetail(detail)
        if (assets.picIds.isEmpty()) {
            state.index.onVoid(postId, at)
            onEvent(CrawlEvent.Post(saved = 0, failed = 0))
            return 0
        }
        val authorUid = detail.optJSONObject("user")?.optString("id", "").orEmpty().ifEmpty { uid }
        val label = resolveLabel(state.index, authorUid, target.name.ifEmpty { target.uid })
        val prefix = prefixOf(at, label)
        val outcome = savePost(ctx, cookie, state, prefix, postId, at, assets, onlySlots, log)
        if (outcome.saved > 0) log("  ✓ $prefix…（+${outcome.saved}${if (outcome.failed.isEmpty()) "" else "，缺 ${outcome.failed.size}"}）")
        onEvent(CrawlEvent.Post(saved = outcome.saved, failed = outcome.failed.size))
        return outcome.saved
    }

    private fun resolveLabel(index: CrawlIndex, uid: String, fallback: String): String {
        val locked = if (uid.isEmpty()) "" else index.labelOf(uid)
        if (locked.isNotEmpty()) return locked
        val label = AssetName.safeName(fallback)
        index.lockLabel(uid, label)
        return label
    }

    private fun epochOf(createdAt: String): Long {
        val t = WeiboApi.parseWeiboTime(createdAt) ?: return 0L
        return CrawlDate.epochSecOf(t.first, t.second) ?: 0L
    }

    private fun prefixOf(at: Long, label: String): String {
        if (at <= 0L) return AssetName.unknownPrefix(label)
        val d = CrawlDate.dateOf(at)
        val hhmmss = java.time.Instant.ofEpochSecond(at)
            .atZone(java.time.ZoneOffset.ofHours(8))
            .toLocalTime()
            .let { String.format(java.util.Locale.US, "%02d%02d%02d", it.hour, it.minute, it.second) }
        return AssetName.prefix(d, hhmmss, label)
    }

    // ========================= 逐槽位落盘（三层去重） =========================

    private data class SaveOutcome(val saved: Int = 0, val failed: Set<Int> = emptySet())

    /**
     * 按槽位下载一帖的全部资产。
     *
     * 每个槽位独立判定，**只有真正成功或确认已存在才算完成**：
     * L1 文件名（含撞名归一）→ L2 picId 业务身份 → 下载 → L3 内容哈希（撞了就不重复写盘）。
     */
    private suspend fun savePost(
        ctx: Context,
        cookie: String,
        state: RunState,
        prefix: String,
        postId: String,
        at: Long,
        assets: PostAssets,
        onlySlots: Set<Int>?,
        log: (String) -> Unit,
    ): SaveOutcome {
        var saved = 0
        val failed = LinkedHashSet<Int>()

        fun attempted(slot: Int) = onlySlots == null || slot in onlySlots
        // 重试只跑部分槽位：未成功的槽位要先继承下来，否则会把上次的失败记录抹掉
        if (onlySlots != null) failed.addAll(onlySlots)

        /** 落盘第 i 张配图的静图；返回是否新写了文件。 */
        suspend fun saveStill(i: Int): Boolean {
            val slot = stillSlot(i)
            if (!attempted(slot)) return false
            val picId = assets.picIds[i]
            val name = AssetName.stillName(prefix, i + 1)
            val r = saveOne(
                ctx, state, postId, slot, CrawlIndex.Kind.STILL,
                picId, name, "image/jpeg", log,
            ) { fetchBytes(WeiboApi.originalImageUrl(picId), name, log) }
            return when (r) {
                SlotResult.SAVED -> {
                    failed.remove(slot); true
                }
                SlotResult.EXISTS -> {
                    failed.remove(slot); false
                }
                SlotResult.FAILED -> {
                    failed.add(slot); false
                }
            }
        }

        for (i in assets.picIds.indices) {
            val picId = assets.picIds[i]
            val wrapper = assets.liveByIndex[i]

            // 普通静图
            if (wrapper == null) {
                if (saveStill(i)) saved++
                continue
            }

            // Live 项：动图（gif/mov）就是这张图的全部内容，**不再另存配对静图**。
            // 旧逻辑先存 _01.jpg 再存 _01_live.gif，同一张图在相册里出现两遍。
            val liveSlot = pairedLiveSlot(i)
            if (!attempted(liveSlot)) continue

            val existing = existingLiveName(state, prefix, i + 1, false, ctx)
            if (existing != null) {
                // 已存在（gif 或 mov 任一）：登记映射，避免后续重复判定
                state.index.onFileSaved(
                    postId, liveSlot,
                    if (existing.endsWith(".gif")) CrawlIndex.Kind.LIVE_PAIRED_GIF else CrawlIndex.Kind.LIVE_PAIRED_MOV,
                    picId, "", state.store.sizeOf(existing), existing,
                )
                failed.remove(liveSlot)
                continue
            }

            when (saveLive(ctx, state, postId, liveSlot, picId, prefix, i + 1, false, wrapper, log)) {
                LiveResult.GIF_SAVED -> {
                    saved++
                    failed.remove(liveSlot)
                }
                // 只存了 mov：图库不显示视频，补一张静图当封面，否则这张图等于没抓到
                LiveResult.MOV_SAVED -> {
                    saved++
                    failed.remove(liveSlot)
                    if (saveStill(i)) saved++
                }
                LiveResult.FAILED -> {
                    failed.add(liveSlot)
                    saveStill(i)          // 动图彻底失败，至少把静图留下来
                }
            }
        }
        assets.liveExtras.forEachIndexed { n, wrapper ->
            val slot = extraLiveSlot(n)
            if (!attempted(slot)) return@forEachIndexed
            val existing = existingLiveName(state, prefix, n + 1, true, ctx)
            if (existing != null) {
                state.index.onFileSaved(
                    postId, slot,
                    if (existing.endsWith(".gif")) CrawlIndex.Kind.LIVE_EXTRA_GIF else CrawlIndex.Kind.LIVE_EXTRA_MOV,
                    "", "", state.store.sizeOf(existing), existing,
                )
                failed.remove(slot)
            } else if (saveLive(ctx, state, postId, slot, "", prefix, n + 1, true, wrapper, log) != LiveResult.FAILED) {
                saved++
                failed.remove(slot)
            } else {
                failed.add(slot)
            }
        }

        state.index.onPostSealed(postId, at, assets.expectedFiles, saved > 0 || failed.isEmpty(), failed)
        return SaveOutcome(saved, failed)
    }

    /** Live 可能存成 gif（默认）或 mov（关闭转换/转换失败），两种都算「已存在」。 */
    private fun existingLiveName(
        state: RunState,
        prefix: String,
        seq: Int,
        isExtra: Boolean,
        ctx: Context,
    ): String? {
        val gifOn = AccountStore.crawlGif(ctx)
        val a = if (isExtra) AssetName.extraLiveName(prefix, seq - 1, gifOn)
        else AssetName.pairedLiveName(prefix, seq, gifOn)
        val b = if (isExtra) AssetName.extraLiveName(prefix, seq - 1, !gifOn)
        else AssetName.pairedLiveName(prefix, seq, !gifOn)
        return when {
            state.store.hasCanonical(a) -> a
            state.store.hasCanonical(b) -> b
            else -> null
        }
    }

    private enum class SlotResult { SAVED, EXISTS, FAILED }

    /** Live 的三种结局：转成 GIF / 只存了原始 mov / 彻底失败。 */
    private enum class LiveResult { GIF_SAVED, MOV_SAVED, FAILED }

    /** 单张静图：L2 → L1 → 下载 → L3。 */
    private suspend fun saveOne(
        ctx: Context,
        state: RunState,
        postId: String,
        slot: Int,
        kind: CrawlIndex.Kind,
        picId: String,
        name: String,
        mime: String,
        log: (String) -> Unit,
        fetch: suspend () -> ByteArray?,
    ): SlotResult {
        // L2：同一 picId 已经落过盘（可能是别的帖、别的日期文件名）。
        // 必须比对文件类型：Live 的 mov 和它的封面静图共用同一个 picId，
        // 若只看 picId 会把「mov 已存」误判成「静图已存」，导致回退静图永远下不来。
        state.index.picOwner(picId)?.let { owner ->
            if (owner.kind == kind.ordinal && state.store.hasCanonical(owner.name)) {
                return SlotResult.EXISTS
            }
        }
        // L1：文件名（含 SAF 撞名副本）已在目录里
        if (state.store.hasCanonical(name)) {
            state.index.onFileSaved(postId, slot, kind, picId, "", state.store.sizeOf(name), name)
            log("  ↷ 排除重复 $name（相册目录已存在）")
            return SlotResult.EXISTS
        }
        val bytes = fetch() ?: return SlotResult.FAILED
        val hash = Hashing.sha256Hex128(bytes)
        // L3：内容级去重 —— 同一张图换个帖/换个日期名再出现时不再重复写盘
        state.index.byHash(hash)?.let { other ->
            if (state.store.hasCanonical(other.name)) {
                state.index.onPicMapped(postId, slot, kind, picId, hash, bytes.size.toLong(), other.name)
                log("  ↷ 排除重复内容 $name（与 ${other.name} 相同）")
                return SlotResult.EXISTS
            }
        }
        if (!state.store.save(name, mime, bytes)) {
            log("  ✗ $name 写入相册目录失败")
            return SlotResult.FAILED
        }
        state.index.onFileSaved(postId, slot, kind, picId, hash, bytes.size.toLong(), name)
        kotlinx.coroutines.delay(kotlin.random.Random.nextLong(600, 900))
        return SlotResult.SAVED
    }

    /**
     * 保存一个 Live Photo：优先转 GIF（设置开启且转换成功），失败或关闭时保存原始 mov，
     * 内容不丢。转换用 cacheDir 临时文件，相册目录不留 mov 残留。
     */
    private suspend fun saveLive(
        ctx: Context,
        state: RunState,
        postId: String,
        slot: Int,
        picId: String,
        prefix: String,
        index1: Int,
        isExtra: Boolean,
        wrapperUrl: String,
        log: (String) -> Unit,
    ): LiveResult {
        val kind = when {
            isExtra && AccountStore.crawlGif(ctx) -> CrawlIndex.Kind.LIVE_EXTRA_GIF
            isExtra -> CrawlIndex.Kind.LIVE_EXTRA_MOV
            AccountStore.crawlGif(ctx) -> CrawlIndex.Kind.LIVE_PAIRED_GIF
            else -> CrawlIndex.Kind.LIVE_PAIRED_MOV
        }
        val movName = if (isExtra) AssetName.extraLiveName(prefix, index1 - 1, false)
        else AssetName.pairedLiveName(prefix, index1, false)
        val mov = fetchBytes(wrapperUrl, movName, log)
        if (mov == null) {
            kotlinx.coroutines.delay(kotlin.random.Random.nextLong(600, 900))
            return LiveResult.FAILED
        }
        val gifName = if (isExtra) AssetName.extraLiveName(prefix, index1 - 1, true)
        else AssetName.pairedLiveName(prefix, index1, true)
        if (AccountStore.crawlGif(ctx)) {
            val gif = LivePhotoConverter.movBytesToGif(ctx, mov)
            if (gif != null && state.store.save(gifName, "image/gif", gif)) {
                state.index.onFileSaved(postId, slot, kind, picId, Hashing.sha256Hex128(gif), gif.size.toLong(), gifName)
                kotlinx.coroutines.delay(kotlin.random.Random.nextLong(600, 900))
                return LiveResult.GIF_SAVED
            }
            if (gif == null) log("  ↷ $gifName GIF 转换失败，回退保存原始 mov")
        }
        val ok = state.store.save(movName, "video/quicktime", mov)
        if (ok) {
            state.index.onFileSaved(postId, slot, kind, picId, Hashing.sha256Hex128(mov), mov.size.toLong(), movName)
        } else {
            log("  ✗ $movName 写入相册目录失败")
        }
        kotlinx.coroutines.delay(kotlin.random.Random.nextLong(600, 900))
        return if (ok) LiveResult.MOV_SAVED else LiveResult.FAILED
    }

    // ========================= 下载 =========================

    /** 匿名下载（CDN 不需要登录态）。URL 必须过微博域名白名单；上限 64MB。
     *  失败自动重试 DOWNLOAD_ATTEMPTS 次（间隔递增），重试过程与原因都写入日志。 */
    private suspend fun fetchBytes(rawUrl: String, label: String, log: (String) -> Unit): ByteArray? {
        var reason = "未知错误"
        for (attempt in 1..DOWNLOAD_ATTEMPTS) {
            if (attempt > 1) {
                kotlinx.coroutines.delay(1200L * (attempt - 1))
            }
            val (bytes, why) = tryDownload(rawUrl)
            if (bytes != null) return bytes
            reason = why
            if (attempt < DOWNLOAD_ATTEMPTS) {
                log("  ↻ $label 下载失败（第 $attempt 次：$reason），重试…")
            }
        }
        log("  ✗ $label 下载失败（$reason，已尝试 $DOWNLOAD_ATTEMPTS 次）")
        return null
    }

    /** 单次下载尝试：返回 (字节, 失败原因)。成功时原因为空串。 */
    private fun tryDownload(rawUrl: String): Pair<ByteArray?, String> {
        return try {
            val url = WeiboApi.weiboUrl(rawUrl)
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "okhttp")
                .build()
            downloadClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null to "HTTP ${resp.code}"
                val body = resp.body ?: return null to "空响应"
                val bytes = body.bytes()
                when {
                    bytes.isEmpty() -> null to "空文件"
                    bytes.size > 64 * 1024 * 1024 -> null to "超过 64MB 上限"
                    else -> bytes to ""
                }
            }
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
    }
}
