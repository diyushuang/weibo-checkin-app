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
 * 6. 节流：下载间 600-900ms（唯一有固定间隔的阶段）；列表翻页与详情**没有**固定延时，
 *    靠预算约束（`CrawlCursor.Budget`：补新 6 页 / 补历史 20 页 / 200 页硬墙 / 单轮 25 分钟）；
 * 7. 单文件下载失败自动重试 3 次（间隔递增），重试与失败原因写入日志。
 * 8. 身份：**两条链路身份不同**
 *    - 时间线模式（`runIncremental`）：列表与详情都必须账号登录（游客 cookie 在 m 站
 *      `107603` 返回 `ok:-100`、Web 端 `mymblog` 返回 403）；
 *    - 相册模式（`runAlbum`，v0.9.8 起）：枚举相册墙与取单帖详情都走游客身份
 *      （`GuestSession` + `WebAjax`），游客失效或缺图时才回落账号。
 *    下载阶段对 CDN 完全匿名。
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
            val docUri = try {
                DocumentsContract.createDocument(resolver, rootUri, mime, name)
            } catch (e: Exception) {
                null
            } ?: return false
            // createDocument 已经把文档建出来了；写入失败若就这样返回 false，
            // 相册里会留下一个 0 字节 / 截断的坏图，而下次 loadExisting() 会把它收进
            // canonicals → 判 EXISTS → 永远不再重下。所以失败必须回滚（删掉空文档）。
            val wrote = try {
                resolver.openOutputStream(docUri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                    true
                } ?: false
            } catch (e: IOException) {
                false
            } catch (e: Exception) {
                false
            }
            if (!wrote) {
                runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
                return false
            }
            remember(name, bytes.size.toLong())
            return true
        }
    }

    private const val DOWNLOAD_ATTEMPTS = 3    // 单文件下载尝试次数（CDN 偶发 403/超时自动重试）

    /** 单文件大小上限：Live mov 正常在 64MB 以内，超过直接放弃而不是撑爆内存。 */
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
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
    internal fun stillSlot(i: Int) = i * 2
    internal fun pairedLiveSlot(i: Int) = i * 2 + 1
    private fun extraLiveSlot(n: Int) = 1000 + n

    /** 详情 → 图清单（pic_ids 完整保序）+ Live 配对（pics 前 9 条内）+ 独立 Live。 */
    private fun assetsFromDetail(detail: JSONObject): PostAssets {
        val picIds = ArrayList<String>()
        val idsArr = detail.optJSONArray("pic_ids")
        if (idsArr != null) {
            // 空串 / 空白 pid 必须剔掉：它们会占掉一个槽位却永远下不下来，
            // expectedFiles 因此永远大于实际落盘数 —— 这条帖永远判不成完成，
            // 每轮抓取都重试一次，日志里无限重复同一帖。
            for (i in 0 until idsArr.length()) {
                val id = idsArr.optString(i, "").trim()
                if (id.isNotEmpty()) picIds.add(id)
            }
        }
        val pics = normPics(detail.opt("pics"))
        if (picIds.isEmpty()) {
            pics.forEach { p ->
                val id = pidOf(p).trim()
                if (id.isNotEmpty()) picIds.add(id)
            }
        }

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
     * 选**时间线模式**抓图用的 cookie。
     *
     * 时间线链路实测必须账号登录 —— 游客 cookie 在 m 站 `107603` 返回 `ok:-100`、
     * Web 端 `ajax/statuses/mymblog` 返回 403「请登录后使用」，这条链路没有免登录替代。
     * 所以这里是「依次探活取第一个还活着的账号」。
     *
     * 相册模式（`runAlbum`）**不调本方法**：它优先走游客身份（`GuestSession`），
     * 只有在游客失效或缺图时才用这里的结果做兜底。
     */
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
                val v = WeiboApi.verify(acc.cookie)
                if (v.login) {
                    if (index > 0) log("抓图身份回退到「$label」")
                    // 探活已经证明这个 cookie 可用、也顺手拿到了轮换后的新 cookie：
                    // 不回写的话下一个阶段（相册 → 时间线回退）会再 verify 一遍，
                    // 白耗一次请求，也继续用着旧 cookie。
                    AccountStore.markVerified(ctx, acc.uid, v.cookie, acc.name)
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
            val outcome = savePost(ctx, state, prefix, postId, at, assets, null, log)
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
     * 用户勾选的 picId → 落盘槽位（internal 供 JVM 单测钉住口径）。
     *
     * - 未选中的图不产生槽位；
     * - 选中的图若是配对 Live（`liveByIndex` 有值），槽位是 `pairedLiveSlot(i)`：
     *   落动图不落封面静图，与增量抓取「Live 只存一份」的口径一致；
     * - picked 为 null（整帖）直接返回 null = `savePost(onlySlots=null)` 全槽位。
     */
    internal fun slotsForPics(assets: PostAssets, picked: Set<String>?): Set<Int>? {
        if (picked == null) return null
        if (picked.isEmpty()) return emptySet()
        val slots = LinkedHashSet<Int>()
        assets.picIds.forEachIndexed { i, pid ->
            if (pid in picked) {
                slots.add(if (assets.liveByIndex.containsKey(i)) pairedLiveSlot(i) else stillSlot(i))
            }
        }
        return slots
    }

    /**
     * 勾选抓取：浏览页 / 选图页提交的一批任务，逐帖拉详情后按槽位落盘。
     *
     * 与 [crawlSinglePost] 的差别只在「哪些槽位要落」：
     * 命名（`prefixOf` + `AssetName`）、三层去重、索引、目录全部同一套 ——
     * 用户先勾选抓一张、以后增量抓全量，文件在同一个名字上相遇，L1 直接命中。
     *
     * 单帖失败记日志继续下一帖；[isStopped] 由 [CrawlRunner] 的停止信号驱动。
     */
    suspend fun crawlPicked(
        ctx: Context,
        tasks: List<PickTask>,
        log: (String) -> Unit,
        onEvent: (CrawlEvent) -> Unit = {},
        isStopped: () -> Boolean = { false },
    ): PickedSummary {
        val cookie = pickCrawlCookie(ctx, AccountStore.load(ctx), log) ?: return PickedSummary()
        val treeUriRaw = AccountStore.crawlTreeUri(ctx)
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
                    WeiboApi.fetchPostDetail(cookie, task.bid)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("✗ 帖 ${task.bid} 详情获取失败：${e.message}")
                    // 跳过也算「处理过一帖」，否则进度条会停在这里不动
                    onEvent(CrawlEvent.Post(0, 0))
                    continue
                }
                val assets = assetsFromDetail(detail)
                if (assets.picIds.isEmpty()) {
                    log("· 帖 ${task.bid} 没有图片（或为视频微博），跳过")
                    onEvent(CrawlEvent.Post(0, 0))
                    continue
                }
                val slots = slotsForPics(assets, task.picIds)
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
                val outcome = savePost(ctx, state, prefix, postId, at, assets, slots, log)
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

    // ========================= 全量增量抓取 =========================

    /**
     * 对全部抓取目标跑一轮增量（Phase RETRY → A 补新 → B 补历史）。
     * 进度落在 [CrawlCursor]，任意时刻中断都能从锚点接上。
     *
     * [retryOnly] 只跑 RETRY 阶段（补上次失败的槽位），不翻页。
     * [onlyUids] 只抓这些 UID（v0.9.12 单博主抓取）；空集合 = 全部目标。
     * [full] 全量扫描：从头连续下行、不信已有覆盖区间（详见 [CrawlPlanner.planScan]）。
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
        onlyUids: Set<String> = emptySet(),
        full: Boolean = false,
    ) {
        // 单博主抓取：先按 UID 收窄，再走完全相同的链路（顺序仍是用户看到的顺序）
        val targets = AccountStore.selectTargets(AccountStore.crawlTargets(ctx), onlyUids)
        val treeUriRaw = AccountStore.crawlTreeUri(ctx)
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
        val cookie = pickCrawlCookie(ctx, accounts, log) ?: return
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
        withCrawlContext(ctx, treeUriRaw, log) { state ->
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

    // ========================= 相册抓图模式（v0.9.8） =========================

    /** 每轮「补新」最多翻几页相册墙（每页约 20 张图）。 */
    private const val ALBUM_NEW_PAGES = 2

    /** 每轮「补历史」最多翻几页相册墙。 */
    private const val ALBUM_HISTORY_PAGES = 8

    /**
     * 全量（v0.9.12）每轮最多翻几页相册墙。
     *
     * 相册墙的游标本身就是「从头往下的位置」，所以相册模式没有时间线那种
     * 「覆盖区间」概念：全量与增量的唯一区别就是每轮走多深。已抓过的帖在索引里
     * 是终态，只花列表请求、不拉详情，20 页很便宜。
     */
    private const val ALBUM_FULL_PAGES = 20

    /** 每轮每目标最多处理几帖（一帖可能带 18 张图，按帖计数比按图更可控）。 */
    private const val ALBUM_MAX_POSTS = 40

    /** 单轮时间上限：一帖最多 18 张图，没有时间墙的话自动抓图会跑很久。 */
    private const val ALBUM_MAX_MILLIS = 20 * 60 * 1000L

    /** 相册墙一轮的结果。 */
    private class AlbumPass(
        val enumerated: Boolean,
        val saved: Int,
        val nextCursor: String,
        val reachedBottom: Boolean,
    )

    /**
     * 相册模式：用**游客身份**从相册墙枚举 `mid`，再逐帖取图。
     *
     * 为什么值得单独做一条链路：
     * 1. 相册墙只列**有图**的微博，跳过大量纯文字帖 —— 时间线要翻 10 帖才碰到几张图，
     *    相册墙一页 20 张图（约 5–8 帖）；
     * 2. 全程游客身份，不消耗账号频次与风控评分；
     * 3. 天然避开「时间线卡片对超 9 图帖只带部分图」的截断：逐帖取详情、以 `pic_ids` 为准。
     *
     * 逐帖取详情优先 `weibo.com/ajax/statuses/show`（游客），拿不到或张数不够时
     * **回退到账号 + m 站详情**（那里有帖级 `live_photo`，Live 动图不会退化）。
     *
     * @return 是否至少有一个目标成功枚举到相册墙。false 时上层回退到时间线模式。
     */
    suspend fun runAlbum(
        ctx: Context,
        log: (String) -> Unit,
        autoMode: Boolean = false,
        onEvent: (CrawlEvent) -> Unit = {},
        isStopped: () -> Boolean = { false },
        onlyUids: Set<String> = emptySet(),
        full: Boolean = false,
    ): Boolean {
        val targets = AccountStore.selectTargets(AccountStore.crawlTargets(ctx), onlyUids)
        val treeUriRaw = AccountStore.crawlTreeUri(ctx)
        if (targets.isEmpty() || treeUriRaw.isEmpty()) {
            if (!autoMode) {
                log(
                    if (onlyUids.isEmpty()) "✗ 请先添加抓取目标并选择相册目录"
                    else "✗ 该博主已不在抓取目标里，刷新目标列表后再试",
                )
            }
            // 没有目标不算「相册模式失败」，不需要上层回退到时间线
            return true
        }
        val accounts = AccountStore.load(ctx)
        var anyEnumerated = false
        var downloaded = 0
        val startedAt = System.currentTimeMillis()

        onEvent(CrawlEvent.Begin(if (full) "相册全量" else "相册抓取", targets.size))
        if (onlyUids.isNotEmpty()) {
            log("范围：只抓 ${targets.joinToString("、") { it.name.ifEmpty { it.uid } }}")
        }
        withCrawlContext(ctx, treeUriRaw, log) { state ->
            for ((index, target) in targets.withIndex()) {
                if (isStopped()) break
                if (System.currentTimeMillis() - startedAt > ALBUM_MAX_MILLIS) {
                    log("已达单轮时间上限（${ALBUM_MAX_MILLIS / 60000} 分钟），本轮先到这里")
                    break
                }
                val label = target.name.ifEmpty { target.uid }
                onEvent(CrawlEvent.Target(label, index + 1, targets.size))
                log("── $label（${target.uid} · 相册${if (full) " · 全量" else ""}）──")
                try {
                    val result = crawlAlbumTarget(
                        ctx, target, accounts, state, log, onEvent, startedAt, isStopped, full,
                    )
                    if (result.enumerated) anyEnumerated = true
                    downloaded += result.saved
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
        return anyEnumerated
    }

    private class AlbumTargetResult(val enumerated: Boolean, val saved: Int)

    /**
     * 游客身份是否已被判定不可用。
     *
     * 一次运行内只要撞上一次「身份类」失败（403/432/非 JSON），后续帖子就直接走账号，
     * 不再每帖白打一次必然失败的请求，也不会把日志刷满。
     */
    private class GuestGate {
        var blocked = false

        /**
         * 本轮是否已经因「身份可疑」刷新过一次游客身份。
         *
         * 只刷一次：真被风控时反复握手除了刷日志没有任何收益，还会放大请求量。
         * 但完全不刷也不行 —— 游客 cookie 有一年有效期，换发前失效是常态，
         * 一次 403 就让整轮退化到时间线模式，等于白跑。
         */
        var refreshed = false
    }

    private suspend fun crawlAlbumTarget(
        ctx: Context,
        target: AccountStore.CrawlTarget,
        accounts: List<AccountStore.Account>,
        state: CrawlContext,
        log: (String) -> Unit,
        onEvent: (CrawlEvent) -> Unit,
        startedAt: Long,
        isStopped: () -> Boolean,
        full: Boolean = false,
    ): AlbumTargetResult {
        val uid = target.uid
        val sinceDate = AccountStore.crawlSince(ctx, uid)
        val savedCursor = AccountStore.albumSince(ctx, state.treeTag, uid)
        // 全量只是「每轮走得更深」，不需要新的断点字段：相册墙游标本身就是位置
        val historyPages = if (full) ALBUM_FULL_PAGES else ALBUM_HISTORY_PAGES

        // 账号只在需要回退时解析一次（探活有成本，不能每帖都做）
        var accountResolved = false
        var accountCookie: String? = null
        fun account(): String? {
            if (!accountResolved) {
                accountResolved = true
                accountCookie = pickCrawlCookie(ctx, accounts, log)
            }
            return accountCookie
        }
        val guest = GuestGate()

        suspend fun pass(
            startCursor: String,
            maxPages: Int,
            maxPosts: Int,
        ): AlbumPass {
            var cursor = startCursor
            var pages = 0
            var handled = 0
            var enumerated = false
            var saved = 0
            val seen = HashSet<String>()
            val floorYm = if (sinceDate.length >= 7) sinceDate.substring(0, 7) else ""

            /** 取一页相册墙；遇到「身份可疑」失败时刷新一次游客身份再重试。 */
            suspend fun wall(atCursor: String): WebAjax.WallPage? {
                repeat(2) { attempt ->
                    try {
                        return WebAjax.imageWall(uid, atCursor)
                    } catch (e: Exception) {
                        val reason = e.message ?: e.javaClass.simpleName
                        val suspect = e is WeiboApi.WeiboException && e.identitySuspect
                        if (attempt == 0 && suspect && !guest.refreshed) {
                            guest.refreshed = true
                            log("  · 游客身份可能已失效（$reason），刷新后重试")
                            GuestSession.refresh()
                        } else {
                            log("  ✗ 相册墙第 ${pages + 1} 页失败：$reason")
                            return null
                        }
                    }
                }
                return null
            }

            while (pages < maxPages && handled < maxPosts && !isStopped() &&
                System.currentTimeMillis() - startedAt < ALBUM_MAX_MILLIS
            ) {
                val page = wall(cursor) ?: return AlbumPass(enumerated, saved, cursor, false)
                enumerated = true
                // 墙上只有「年-月」，只能做粗粒度下界判定
                if (floorYm.isNotEmpty() && page.oldestYm.isNotEmpty() && page.oldestYm < floorYm) {
                    log("  已到起始日期（$sinceDate）之前，停止翻页")
                    return AlbumPass(enumerated, saved, "", true)
                }
                var pageDone = true
                for (mid in page.mids) {
                    if (isStopped() || handled >= maxPosts ||
                        System.currentTimeMillis() - startedAt > ALBUM_MAX_MILLIS
                    ) {
                        pageDone = false
                        break
                    }
                    if (state.index.isTerminal(mid) && state.index.failedSlots(mid).isEmpty()) continue
                    handled++
                    saved += processAlbumPost(ctx, uid, mid, target, state, log, onEvent, guest) { account() }
                }
                pages++
                // 本页没走完就中断：游标必须停在**页首**。相册墙的 nextSinceId 是
                // 「本页最后一条」的位置，提前推进等于把本页剩下的 mid 直接跳过，
                // 而它们既不在 failed 槽位里、也不会进 RETRY —— 那些图永久缺失。
                if (!pageDone) {
                    log("  · 本轮在第 $pages 页中断（未处理完），游标留在页首，下轮从这条继续")
                    return AlbumPass(enumerated, saved, cursor, false)
                }
                if (page.nextSinceId.isEmpty() || !seen.add(page.nextSinceId)) {
                    return AlbumPass(enumerated, saved, "", true)
                }
                cursor = page.nextSinceId
            }
            return AlbumPass(enumerated, saved, cursor, false)
        }

        // 1) 补新：固定从第一页扫，保证刚发的图能进来（不依赖历史游标走到哪）
        val newPass = pass("", ALBUM_NEW_PAGES, ALBUM_MAX_POSTS)
        if (!newPass.enumerated) return AlbumTargetResult(false, 0)

        var saved = newPass.saved
        var nextCursor = newPass.nextCursor
        var reachedBottom = newPass.reachedBottom

        // 2) 补历史：从存档游标继续。游标为空说明「补新」已经覆盖了开头，不必重复。
        if (newPass.nextCursor.isNotEmpty() && savedCursor.isNotEmpty()) {
            val historyPass = pass(savedCursor, historyPages, ALBUM_MAX_POSTS)
            saved += historyPass.saved
            nextCursor = historyPass.nextCursor
            reachedBottom = reachedBottom || historyPass.reachedBottom
        }
        if (reachedBottom) nextCursor = ""

        AccountStore.setAlbumSince(ctx, state.treeTag, uid, nextCursor)
        return AlbumTargetResult(true, saved)
    }

    /**
     * 相册模式下的单帖：取详情 → 组图清单 → 落盘。
     *
     * 两层兜底，专治「18 图只拿到 9 张」：
     * 1. 游客 Web 详情（不占账号）拿 `pic_ids`；
     * 2. 若 `pic_ids` 少于帖子自述的 `pic_num`（或游客这条路直接失败），
     *    回退账号 + m 站详情再取一次，取张数更多的那个结果；
     * 3. 仍然不足则告警（不阻塞），与参考实现 `_ensure_full_pics` 同口径。
     */
    private suspend fun processAlbumPost(
        ctx: Context,
        uid: String,
        mid: String,
        target: AccountStore.CrawlTarget,
        state: CrawlContext,
        log: (String) -> Unit,
        onEvent: (CrawlEvent) -> Unit,
        guest: GuestGate,
        account: () -> String?,
    ): Int {
        var detail: JSONObject? = null
        var assets: PostAssets? = null

        if (!guest.blocked) {
            // 两轮：第一轮失败且属于「身份可疑」时，刷新游客身份再试一次
            for (attempt in 0..1) {
                try {
                    val web = WebAjax.statusShow(mid)
                    detail = web
                    assets = assetsFromWebStatus(web)
                    break
                } catch (e: Exception) {
                    val reason = e.message ?: e.javaClass.simpleName
                    val suspect = e is WeiboApi.WeiboException && e.identitySuspect
                    if (attempt == 0 && suspect && !guest.refreshed) {
                        guest.refreshed = true
                        log("  · 游客身份可能已失效（$reason），刷新后重试 $mid")
                        GuestSession.refresh()
                        continue
                    }
                    log("  · $mid 游客取详情失败（$reason）")
                    if (suspect) {
                        guest.blocked = true
                        log("  · 游客身份不可用，本轮后续直接走账号详情")
                    }
                    break
                }
            }
        }

        val declared = detail?.optInt("pic_num", 0) ?: 0
        val short = assets == null || assets.picIds.isEmpty() || (declared > 0 && assets.picIds.size < declared)
        if (short) {
            val cookie = account()
            if (cookie != null) {
                try {
                    val mDetail = WeiboApi.fetchPostDetail(cookie, mid)
                    val mAssets = assetsFromDetail(mDetail)
                    if (assets == null || mAssets.picIds.size > assets.picIds.size) {
                        detail = mDetail
                        assets = mAssets
                    }
                } catch (e: Exception) {
                    log("  ✗ $mid 账号详情也失败：${e.message}")
                }
            }
        }

        val d = detail
        if (d == null) {
            // 同 processPost：不落索引就等于「这帖从未来过」，相册游标会把整页翻过去
            state.index.onPostUnknown(mid)
            log("  ✗ $mid 取不到详情（已记入待重试）")
            onEvent(CrawlEvent.Post(saved = 0, failed = 0))
            return 0
        }
        val at = epochOf(d.optString("created_at", ""))
        val a = assets
        if (a == null || a.picIds.isEmpty()) {
            state.index.onVoid(mid, at)
            onEvent(CrawlEvent.Post(saved = 0, failed = 0))
            return 0
        }
        if (declared > 0 && a.picIds.size < declared) {
            log("  ⚠ $mid 只识别到 ${a.picIds.size}/$declared 张（接口截断），已按现有图片继续")
        }

        val authorUid = d.optJSONObject("user")?.optString("id", "").orEmpty().ifEmpty { uid }
        val authorName = d.optJSONObject("user")?.optString("screen_name", "").orEmpty()
        val label = resolveLabel(state.index, authorUid, authorName.ifEmpty { target.name.ifEmpty { target.uid } })
        val prefix = prefixOf(at, label)
        val outcome = savePost(ctx, state, prefix, mid, at, a, null, log)
        if (outcome.saved > 0) {
            log("  ✓ $prefix…（+${outcome.saved}${if (outcome.failed.isEmpty()) "" else "，缺 ${outcome.failed.size}"}）")
        }
        onEvent(CrawlEvent.Post(saved = outcome.saved, failed = outcome.failed.size))
        return outcome.saved
    }

    /**
     * Web 端扁平 status → 图清单。
     *
     * `pic_ids` 是权威顺序；Live 判定看 `pic_infos[pid].type == "livephoto"`，其 `video`
     * 就是动图直链。Web 版没有帖级 `live_photo`，所以 `liveExtras` 恒为空。
     */
    internal fun assetsFromWebStatus(status: JSONObject): PostAssets {
        val picIds = ArrayList<String>()
        status.optJSONArray("pic_ids")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pid = arr.optString(i, "").trim()
                if (pid.isNotEmpty()) picIds.add(pid)
            }
        }
        val liveByIndex = HashMap<Int, String>()
        val infos = status.optJSONObject("pic_infos")
        if (infos != null && picIds.isNotEmpty()) {
            picIds.forEachIndexed { idx, pid ->
                val info = infos.optJSONObject(pid) ?: return@forEachIndexed
                if (info.optString("type") != "livephoto") return@forEachIndexed
                val video = info.optString("video", "").ifEmpty {
                    info.optJSONObject("video")?.optString("url", "").orEmpty()
                }
                // 只认能过白名单的直链：否则干脆当静图存，别让整张图因为「域名不在白名单」失败
                if (video.isNotEmpty() && videoAllowed(video)) liveByIndex[idx] = video
            }
        }
        return PostAssets(picIds, liveByIndex, emptyList())
    }

    /** 动图直链是否落在微博域名白名单内。 */
    private fun videoAllowed(url: String): Boolean = try {
        WeiboApi.weiboUrl(url)
        true
    } catch (e: Exception) {
        false
    }


    private class CrawlContext(val store: TreeStore, val index: CrawlIndex, val treeTag: String)

    private suspend fun withCrawlContext(
        ctx: Context,
        treeUriRaw: String,
        log: (String) -> Unit,
        block: suspend (CrawlContext) -> Unit,
    ) {
        val treeTag = CrawlIndex.treeTagOf(treeUriRaw)
        val store = TreeStore(ctx.contentResolver, treeUriRaw.toUri())
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
            val legacy = AccountStore.crawlDoneLegacy(ctx)
            if (legacy.isNotEmpty()) {
                val n = idx.migrateLegacy(legacy)
                AccountStore.clearCrawlDoneLegacy(ctx)
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

    private suspend fun crawlTarget(
        ctx: Context,
        cookie: String,
        target: AccountStore.CrawlTarget,
        state: CrawlContext,
        log: (String) -> Unit,
        retryOnly: Boolean = false,
        onEvent: (CrawlEvent) -> Unit = {},
        isStopped: () -> Boolean = { false },
        full: Boolean = false,
        allowRetry: Boolean = true,
    ): Int {
        val uid = target.uid
        val sinceDate = AccountStore.crawlSince(ctx, uid)
        var cursor = AccountStore.crawlCursor(ctx, state.treeTag, uid)
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
        target: AccountStore.CrawlTarget,
        state: CrawlContext,
        log: (String) -> Unit,
        onEvent: (CrawlEvent) -> Unit,
        isStopped: () -> Boolean,
    ): Int {
        val uid = target.uid
        val sinceDate = AccountStore.crawlSince(ctx, uid)
        var cursor = AccountStore.crawlCursor(ctx, state.treeTag, uid)
        var downloaded = 0
        var details = 0
        val started = System.currentTimeMillis()

        fun exhausted() = downloaded >= Budget.FULL.maxFiles || details >= Budget.FULL.maxDetails ||
            System.currentTimeMillis() - started > Budget.FULL.maxWallClockMs

        val fetch = pageFetcher(cookie, uid, log)
        val plan = CrawlPlanner.planScan(cursor, indexView(state.index), sinceDate, fetch, Budget.FULL)
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
        AccountStore.saveCrawlCursor(ctx, state.treeTag, cursor)
        logPlan("全量", plan, cursor, log)
        return downloaded
    }

    private fun Int.ifAtLeast(min: Int): Int = if (this < min) min else this

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
        state: CrawlContext,
        log: (String) -> Unit,
        onlySlots: Set<Int>?,
        onEvent: (CrawlEvent) -> Unit = {},
    ): Int {
        val detail = try {
            WeiboApi.fetchPostDetail(cookie, postId)
        } catch (e: Exception) {
            // 什么都不写会让索引根本不知道这帖存在，而游标仍按「页」推进 →
            // 它落在覆盖区间内部，planNew/planHistory 都判「已覆盖」跳过，永不补抓。
            // 落一条 OPEN（expected = -1 表示语义未知），RETRY 队列会反复捞起它。
            state.index.onPostUnknown(postId)
            log("  ✗ $postId 详情失败：${e.message}（已记入待重试）")
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
        val outcome = savePost(ctx, state, prefix, postId, at, assets, onlySlots, log)
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
        state: CrawlContext,
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

        // 本贴理论上应有的全部槽位。勾选抓取时，**没勾选**的那些槽位既没下载、
        // 也不会进 failed —— 若不开除，failed 为空就会被判 DONE，永久毒化该帖：
        // 之后增量/历史/全量都用 isTerminal 跳过它，那几张图再也补不回来。
        val allSlots = HashSet<Int>()
        for (i in assets.picIds.indices) {
            allSlots.add(if (assets.liveByIndex[i] == null) stillSlot(i) else pairedLiveSlot(i))
        }
        for (n in assets.liveExtras.indices) allSlots.add(extraLiveSlot(n))
        val notPicked: Set<Int> =
            if (onlySlots == null) emptySet() else allSlots.filter { it !in onlySlots }.toSet()

        /** 落盘第 i 张配图的静图；返回是否新写了文件。 */
        suspend fun saveStill(i: Int): Boolean {
            val slot = stillSlot(i)
            if (!attempted(slot)) return false
            val picId = assets.picIds[i]
            val name = AssetName.stillName(prefix, i + 1)
            val r = saveOne(
                state, postId, slot, CrawlIndex.Kind.STILL,
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

        state.index.onPostSealed(
            postId, at, assets.expectedFiles,
            saved > 0 || (failed.isEmpty() && notPicked.isEmpty()),
            failed + notPicked,
        )
        return SaveOutcome(saved, failed)
    }

    /** Live 可能存成 gif（默认）或 mov（关闭转换/转换失败），两种都算「已存在」。 */
    private fun existingLiveName(
        state: CrawlContext,
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
        state: CrawlContext,
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
        state: CrawlContext,
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
                // 先看响应声明的长度：旧实现是「先 body.bytes() 全读进内存，再判 64MB」——
                // 一个异常的超大响应会在上限生效之前就把进程 OOM 掉。
                val declared = body.contentLength()
                if (declared > MAX_FILE_BYTES) {
                    return null to "超过 64MB 上限（声明 ${declared / 1024 / 1024}MB）"
                }
                val bytes = body.bytes()
                when {
                    bytes.isEmpty() -> null to "空文件"
                    bytes.size > MAX_FILE_BYTES -> null to "超过 64MB 上限"
                    else -> bytes to ""
                }
            }
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
    }
}
