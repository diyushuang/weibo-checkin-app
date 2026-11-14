package com.wcm.checkin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface GalleryEvent {
    data class Toast(val text: String) : GalleryEvent
    data class ShowPostPicker(val posts: List<GalleryPostChoice>) : GalleryEvent
    data class ShowRetry(val items: List<String>) : GalleryEvent
}

/**
 * 图库抓取页状态与编排。
 *
 * 抓取任务仍挂在 AppScope：切 Tab / 转屏不中断；日志缓冲、进度快照与
 * 目标行文案都在这里，Fragment 只负责目录选择器/对话框与 ComposeView。
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val crawlService: CrawlService,
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GalleryEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<GalleryEvent> = _events.asSharedFlow()

    private var dirPath = ""
    private var dirStatus = ""
    private var galleryCount = ""
    private var targetsUi: List<GalleryTargetUi> = emptyList()
    private var targetsTitle = ""
    private var retryCount = 0
    private var retryText = ""
    private var progressUi: GalleryProgressUi? = null
    private var running = false
    private var modeAlbum = true
    private var autoAfterCheckin = false
    private var logExpanded = false
    private var busy = false
    private var lastProgress: CrawlProgress? = null

    private var crawlJob: Job? = null
    private var lastShownAt = 0L

    private val logLines = ArrayDeque<String>()
    private val timeFmt: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }

    private var countUri = ""
    private var countValue = -1
    private var countAt = 0L

    companion object {
        private const val LOG_MAX_LINES = 500
        private const val COUNT_TTL_MS = 5 * 60_000L
    }

    init {
        modeAlbum = CrawlSettings.crawlMode(ctx) == CrawlSettings.MODE_ALBUM
        autoAfterCheckin = CrawlSettings.crawlAuto(ctx)
        viewModelScope.launch {
            crawlService.state.collect { renderRunState(it) }
        }
    }

    private fun toast(text: String) {
        _events.tryEmit(GalleryEvent.Toast(text))
    }

    private fun stamp(line: String): String =
        "[${timeFmt.get()?.format(Date()) ?: ""}] $line"

    /** 追加一行日志（带时间戳）；可能来自 IO 线程。 */
    fun log(line: String) {
        val stamped = stamp(line)
        synchronized(logLines) {
            logLines.addLast(stamped)
            while (logLines.size > LOG_MAX_LINES) logLines.removeFirst()
        }
        updateLogState()
    }

    private fun updateLogState() {
        val lines: List<String>
        val summary: String
        synchronized(logLines) {
            lines = logLines.toList()
            summary = logLines.lastOrNull { it.isNotBlank() } ?: ctx.getString(R.string.log_empty)
        }
        _state.update {
            it.copy(log = LogCardUi(expanded = logExpanded, lines = lines, summary = summary))
        }
    }

    /** Fragment 显示时调用：重读目录/目标/计数/重试并刷新运行态。 */
    fun onShown() {
        lastShownAt = System.currentTimeMillis()
        renderDir()
        renderTargets()
        refreshGalleryCount()
        refreshRetryCount()
        renderRunState(crawlService.state.value)
        updateLogState()
    }

    // ========================= 目录 / 图库入口 =========================

    fun onDirPicked(uri: String) {
        CrawlSettings.setCrawlTreeUri(ctx, uri)
        renderDir()
        refreshGalleryCount(force = true)
        toast(ctx.getString(R.string.crawl_dir_saved))
    }

    fun treeUri(): String = CrawlSettings.crawlTreeUri(ctx)

    fun firstTargetUid(): String =
        CrawlSettings.crawlTargets(ctx).firstOrNull()?.uid.orEmpty()

    fun renderDir() {
        val raw = CrawlSettings.crawlTreeUri(ctx)
        val display = if (raw.isEmpty()) ctx.getString(R.string.crawl_dir_empty) else try {
            val segments = raw.toUri().pathSegments
            segments.lastOrNull { it.contains(':') }?.substringAfter(':')
                ?: segments.lastOrNull() ?: raw
        } catch (e: Exception) {
            raw
        }
        dirPath = display
        dirStatus = ""
        render()

        // 授权自检：被系统回收的授权要让用户当场看见，而不是等抓取全失败才发现。
        //
        // **必须放到后台线程**：check() 会走 ContentProvider 跨进程查询（目录可能是
        // 云盘 / U 盘），而本方法在冷启动时就会执行 —— 在主线程做，慢 provider 会把
        // 首屏直接卡住，严重的会被系统判 ANR。查完再回主线程点亮 pill。
        viewModelScope.launch(Dispatchers.IO) {
            val status = SafAccess.check(ctx, raw)
            val readOnly = status == SafAccess.Status.Ok && raw.isNotEmpty() &&
                runCatching { SafAccess.readOnly(ctx, raw.toUri()) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                dirStatus = when (status) {
                    SafAccess.Status.Ok ->
                        if (readOnly) ctx.getString(R.string.crawl_saf_readonly) else ""
                    SafAccess.Status.Missing -> ""
                    SafAccess.Status.NoPermission -> ctx.getString(R.string.crawl_saf_lost_action)
                    is SafAccess.Status.Unreadable -> status.message
                }
                render()
            }
        }
    }

    private fun countText(count: Int): String =
        if (count <= 0) ctx.getString(R.string.gallery_count_none)
        else ctx.getString(R.string.gallery_count_fmt, count)

    fun refreshGalleryCount(force: Boolean = false) {
        val raw = CrawlSettings.crawlTreeUri(ctx)
        if (raw.isEmpty()) {
            galleryCount = ctx.getString(R.string.gallery_count_none)
            render()
            return
        }
        if (!force && raw == countUri && countValue >= 0 &&
            System.currentTimeMillis() - countAt < COUNT_TTL_MS
        ) {
            galleryCount = countText(countValue)
            render()
            return
        }
        val resolver = ctx.contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            val count = try {
                GalleryFiles.listImages(resolver, raw.toUri()).size
            } catch (e: Exception) {
                -1
            }
            if (count >= 0) {
                countUri = raw
                countValue = count
                countAt = System.currentTimeMillis()
            }
            withContext(Dispatchers.Main) {
                galleryCount = countText(count)
                render()
            }
        }
    }

    // ========================= 目标 =========================

    fun renderTargets() {
        val targets = CrawlSettings.crawlTargets(ctx)
        targetsTitle = if (targets.isEmpty()) {
            ctx.getString(R.string.crawl_targets_title)
        } else {
            ctx.getString(R.string.crawl_targets_count_fmt, targets.size)
        }
        val treeTag = CrawlIndex.treeTagOf(CrawlSettings.crawlTreeUri(ctx))
        val cursors = CrawlSettings.allCursors(ctx, treeTag)
        targetsUi = targets.map { t ->
            GalleryTargetUi(
                uid = t.uid,
                name = t.name.ifEmpty { "UID ${t.uid}" },
                progress = describeProgress(t, cursors[t.uid], treeTag),
            )
        }
        render()
    }

    /** 进度行：已覆盖区间 + 起始日期 + 是否到底，让用户看得出「抓到哪天」。 */
    private fun describeProgress(
        target: CrawlTarget,
        cursor: CrawlCursor?,
        treeTag: String = "",
    ): String {
        val sb = StringBuilder()
        // 取非空的一份：`covers` 这种布尔中间量不会让 Kotlin 对 cursor 做智能转换
        val cov = cursor?.takeIf { it.oldestCoveredAt > 0L && it.newestAt > 0L }
        // 相册模式不走覆盖区间（它按相册墙游标推进），进度就看「上次抓到最后一条」：
        // 这条记忆就是下一轮补新的起点，写在行里用户不必翻日志（v0.9.17）
        val albumYm = if (treeTag.isNotEmpty() && CrawlSettings.crawlMode(ctx) == CrawlSettings.MODE_ALBUM) {
            AlbumMemo.ymOf(CrawlSettings.albumLastPost(ctx, treeTag, target.uid))
        } else {
            ""
        }
        if (cov != null) {
            sb.append(
                ctx.getString(
                    R.string.crawl_progress_fmt,
                    CrawlDate.dateOf(cov.oldestCoveredAt),
                    CrawlDate.dateOf(cov.newestAt),
                )
            )
            if (albumYm.isNotEmpty()) {
                sb.append(ctx.getString(R.string.crawl_progress_album_fmt, albumYm))
            }
        } else if (albumYm.isNotEmpty()) {
            // 相册模式没有覆盖区间这条事实，别写「尚未抓取」—— 那会和「上次抓到…」自相矛盾
            sb.append(ctx.getString(R.string.crawl_progress_album_lead_fmt, albumYm))
        } else {
            sb.append(ctx.getString(R.string.crawl_progress_none))
        }
        val since = CrawlSettings.crawlSince(ctx, target.uid)
        if (since.isNotEmpty()) sb.append(" · ").append(ctx.getString(R.string.crawl_progress_from, since))
        // 全量扫描有独立的页码断点：不显示的话用户会以为「全量没反应」
        if (cursor?.fullScanInProgress == true) {
            sb.append(ctx.getString(R.string.crawl_progress_scan_fmt, cursor.fullScanPageHint))
        }
        if (cursor?.reachedBottom == true) sb.append(ctx.getString(R.string.crawl_progress_bottom))
        return sb.toString()
    }

    fun addTarget(input: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val uid = WeiboAuthApi.resolveUid(input)
            if (uid.isEmpty()) {
                log("✗ 无法解析 UID：$input")
                return@launch
            }
            // 昵称走游客身份也能拿到：没有登录账号时依然能把博主名显示出来
            val accounts = AccountStore.load(ctx)
            val cookie = if (accounts.isEmpty()) null else CrawlEngine.pickCrawlCookie(ctx, accounts, ::log)
            val name = WeiboAuthApi.fetchUserName(cookie, uid)
            CrawlSettings.addCrawlTarget(ctx, CrawlTarget(uid, name))
            withContext(Dispatchers.Main) {
                renderTargets()
                log("✓ 已添加：${name.ifEmpty { uid }}（$uid）")
            }
        }
    }

    fun removeTarget(uid: String) {
        CrawlSettings.removeCrawlTarget(ctx, uid)
        renderTargets()
    }

    fun setSince(uid: String, picked: String) {
        val treeTag = CrawlIndex.treeTagOf(CrawlSettings.crawlTreeUri(ctx))
        val cursor = CrawlSettings.crawlCursor(ctx, treeTag, uid)
        CrawlSettings.setCrawlSince(ctx, uid, picked)
        CrawlSettings.saveCrawlCursor(ctx, treeTag, CursorLogic.onSinceDateChanged(cursor, picked))
        renderTargets()
        log(ctx.getString(R.string.crawl_since_saved, picked))
    }

    fun currentSince(uid: String): String = CrawlSettings.crawlSince(ctx, uid)

    // ========================= 运行 =========================

    fun setMode(album: Boolean) {
        modeAlbum = album
        CrawlSettings.setCrawlMode(
            ctx,
            if (album) CrawlSettings.MODE_ALBUM else CrawlSettings.MODE_TIMELINE,
        )
        render()
    }

    fun setAuto(checkin: Boolean) {
        autoAfterCheckin = checkin
        CrawlSettings.setCrawlAuto(ctx, checkin)
        render()
    }

    fun setLogExpanded(expanded: Boolean) {
        logExpanded = expanded
        render()
    }

    private fun phaseLabel(phase: CrawlPhase): String = when (phase) {
        CrawlPhase.IDLE, CrawlPhase.PREPARE -> ctx.getString(R.string.crawl_phase_prepare)
        CrawlPhase.RETRY -> ctx.getString(R.string.crawl_phase_retry)
        CrawlPhase.NEW -> ctx.getString(R.string.crawl_phase_new)
        CrawlPhase.HISTORY -> ctx.getString(R.string.crawl_phase_history)
        CrawlPhase.SCAN -> ctx.getString(R.string.crawl_phase_scan)
        CrawlPhase.PICK -> ctx.getString(R.string.crawl_phase_pick)
        CrawlPhase.DONE -> ctx.getString(R.string.crawl_phase_done)
    }

    /**
     * 把 [crawlService.state] 画到运行卡上。
     *
     * 空闲时不清空进度块 —— 用最后一份快照显示「这轮抓了多少」，
     * 否则点完“停止”数字会整块消失，用户反而不知道刚才发生了什么。
     */
    private fun renderRunState(state: CrawlService.RunState) {
        val progress = (state as? CrawlService.RunState.Running)?.progress
        running = progress != null

        val shown = progress ?: lastProgress
        if (shown == null) {
            progressUi = null
            render()
            return
        }
        if (progress != null) lastProgress = progress

        val step = if (shown.discovered > 0) {
            ctx.getString(
                R.string.crawl_stage_fmt, phaseLabel(shown.phase), shown.processed, shown.discovered,
            )
        } else {
            phaseLabel(shown.phase)
        }
        val counts = ctx.getString(
            R.string.crawl_counts_fmt, shown.downloaded, shown.skipped, shown.failedSlots,
        )
        val detail = if (shown.targetTotal > 0 && shown.targetLabel.isNotEmpty()) {
            ctx.getString(
                R.string.crawl_target_step_fmt,
                shown.targetIndex, shown.targetTotal, shown.targetLabel,
            ) + " · " + counts
        } else {
            counts
        }
        progressUi = GalleryProgressUi(
            ratio = if (progress == null) 1f else percent(shown) / 100f,
            step = step,
            detail = detail,
        )
        render()
    }

    fun startAll() = startCrawl(emptySet(), full = false)

    fun stop() {
        // 先置停止标志（首页自动触发的抓取没有本页协程可取消），再取消本页任务
        crawlService.requestStop()
        crawlJob?.cancel()
        log("⏹ 正在停止…（进度已保存）")
    }

    /**
     * 启动一轮抓取。
     *
     * @param uids 空 = 全部目标；非空 = 只跑这些博主（v0.9.12 单博主抓取）。
     * @param full 全量扫描；false 为增量。
     */
    fun startCrawl(uids: Set<String>, full: Boolean) {
        if (busy) {
            toast(ctx.getString(R.string.busy))
            return
        }
        val targets = CrawlSettings.selectTargets(CrawlSettings.crawlTargets(ctx), uids)
        if (targets.isEmpty()) {
            // 单博主入口点完后目标被删了：给一句准确的提示，而不是笼统的「请先添加目标」
            toast(ctx.getString(if (uids.isEmpty()) R.string.crawl_need_target else R.string.crawl_target_gone))
            return
        }
        if (CrawlSettings.crawlTreeUri(ctx).isEmpty()) {
            toast(ctx.getString(R.string.crawl_need_dir))
            return
        }
        busy = true
        render()
        setLogExpanded(true)            // 运行时自动展开日志
        crawlJob = AppScope.launch {
            try {
                val modeName = if (CrawlSettings.crawlMode(ctx) == CrawlSettings.MODE_ALBUM) "相册" else "时间线"
                val scope = when {
                    full && uids.isNotEmpty() -> "全量 · 单博主"
                    full -> "全量 · 全部目标"
                    uids.isNotEmpty() -> "增量 · 单博主"
                    else -> "增量"
                }
                log("==== 抓取开始（$scope · $modeName · Live→${if (CrawlSettings.crawlGif(ctx)) "GIF" else "MOV"}） ====")
                if (uids.isNotEmpty()) {
                    log(ctx.getString(R.string.crawl_scope_single, targets.joinToString("、") { it.name.ifEmpty { it.uid } }))
                }
                if (full) log(ctx.getString(R.string.crawl_full_running))
                // 与首页自动抓图共用一个闸门：拿不到锁立即返回，不排队、不并发写同一目录
                if (crawlService.runAll(ctx, ::log, onlyUids = uids, full = full) == CrawlService.RunResult.Busy) {
                    log(ctx.getString(R.string.crawl_busy))
                } else {
                    log("==== 抓取结束 ====")
                }
            } catch (e: CancellationException) {
                log("⏹ 已停止（进度已保存，可续跑）")
            } catch (e: Exception) {
                log("✗ 抓取异常：${e.message}")
            } finally {
                // 用 NonCancellable：「停止」按钮会取消本协程，若这里跟着被取消，
                // busy 就永远回不到 false，开始抓取的按钮会一直灰着。
                withContext(Dispatchers.Main + NonCancellable) {
                    busy = false
                    renderTargets()          // 单博主抓完，目标行的「已覆盖 / 全量页码」要刷新
                    refreshGalleryCount(force = true)
                    render()
                }
            }
        }
    }

    fun confirmFullName(uids: Set<String>): String = if (uids.isEmpty()) {
        ctx.getString(R.string.crawl_all_targets)
    } else {
        CrawlSettings.crawlTargets(ctx).firstOrNull { it.uid in uids }
            ?.name?.ifEmpty { null } ?: uids.first()
    }

    // ========================= 失败重试 =========================

    fun refreshRetryCount() {
        val raw = CrawlSettings.crawlTreeUri(ctx)
        if (raw.isEmpty()) {
            retryCount = 0
            retryText = ""
            render()
            return
        }
        val tag = CrawlIndex.treeTagOf(raw)
        viewModelScope.launch(Dispatchers.IO) {
            var count = CrawlIndex.retryHintOf(ctx.filesDir, tag)
            if (count < 0) {
                count = try {
                    val idx = CrawlIndex.open(ctx.filesDir, tag)
                    try {
                        idx.load()
                        idx.pendingRetryCount()
                    } finally {
                        idx.close()
                    }
                } catch (e: Exception) {
                    0
                }
            }
            withContext(Dispatchers.Main) {
                retryCount = count
                retryText = if (count > 0) ctx.getString(R.string.crawl_retry_title, count) else ""
                render()
            }
        }
    }

    /** 失败清单：列出「哪条帖子、缺几张」，确认后只跑重试阶段。 */
    fun loadRetryItems() {
        val raw = CrawlSettings.crawlTreeUri(ctx)
        if (raw.isEmpty()) {
            toast(ctx.getString(R.string.crawl_need_dir))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val items = try {
                val idx = CrawlIndex.open(ctx.filesDir, CrawlIndex.treeTagOf(raw))
                try {
                    idx.load()
                    describeFailures(idx.pendingRetries(System.currentTimeMillis(), 100))
                } finally {
                    idx.close()
                }
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (items.isEmpty()) {
                    toast(ctx.getString(R.string.crawl_retry_none))
                    refreshRetryCount()
                } else {
                    _events.tryEmit(GalleryEvent.ShowRetry(items))
                }
            }
        }
    }

    fun runRetryOnly() {
        if (busy) {
            toast(ctx.getString(R.string.busy))
            return
        }
        busy = true
        render()
        setLogExpanded(true)
        log("==== ${ctx.getString(R.string.crawl_retry_running)} ====")
        crawlJob = AppScope.launch {
            try {
                if (crawlService.runRetryOnly(ctx, ::log) == CrawlService.RunResult.Busy) {
                    log(ctx.getString(R.string.crawl_busy))
                }
            } catch (e: CancellationException) {
                log("⏹ 已停止")
            } catch (e: Exception) {
                log("✗ 重试异常：${e.message}")
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    busy = false
                    render()
                    refreshGalleryCount(force = true)
                    refreshRetryCount()
                }
            }
        }
    }

    // ========================= 单条抓取 =========================

    fun startSingleInput(input: String) {
        if (busy) {
            toast(ctx.getString(R.string.busy))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val bid = WeiboPostApi.parseBidFromInput(input)
            if (bid.isNotEmpty()) {
                startSingleBid(bid)
                return@launch
            }
            // 不是微博链接：当博主 UID 处理，拉最近微博供选择
            val uid = WeiboAuthApi.resolveUid(input)
            if (uid.isEmpty()) {
                log("✗ 无法识别输入：$input")
                return@launch
            }
            val cookie = CrawlEngine.pickCrawlCookie(ctx, AccountStore.load(ctx), ::log)
                ?: return@launch
            log("正在拉取博主 $uid 的最近微博…")
            val posts = ArrayList<WeiboPostApi.RawPost>()
            for (page in 1..2) {
                try {
                    val (_, list) = WeiboPostApi.fetchUserPosts(cookie, uid, page)
                    posts.addAll(list)
                    if (list.isEmpty()) break
                } catch (e: Exception) {
                    break
                }
            }
            if (posts.isEmpty()) {
                log("✗ 没有拉到微博（UID 是否正确？）")
                return@launch
            }
            withContext(Dispatchers.Main) {
                _events.tryEmit(
                    GalleryEvent.ShowPostPicker(
                        posts.map { p ->
                            GalleryPostChoice(p.bid, postPickerLine(p))
                        },
                    ),
                )
            }
        }
    }

    fun startSingleBid(bid: String) {
        if (busy) return
        busy = true
        setLogExpanded(true)
        render()
        AppScope.launch {
            try {
                if (crawlService.runSingle(ctx, bid, ::log) == CrawlService.RunResult.Busy) {
                    log(ctx.getString(R.string.crawl_busy))
                }
            } catch (e: CancellationException) {
                log("⏹ 单条抓取被取消")
            } catch (e: Exception) {
                log("✗ 单条抓取异常：${e.message}")
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    busy = false
                    render()
                    refreshGalleryCount(force = true)
                }
            }
        }
    }

    // ========================= 渲染 =========================

    private fun render() {
        val lines: List<String>
        val summary: String
        synchronized(logLines) {
            lines = logLines.toList()
            summary = logLines.lastOrNull { it.isNotBlank() } ?: ctx.getString(R.string.log_empty)
        }
        _state.value = GalleryUiState(
            galleryCount = galleryCount.ifEmpty { ctx.getString(R.string.gallery_count_none) },
            dirPath = dirPath.ifEmpty { ctx.getString(R.string.crawl_dir_empty) },
            dirStatus = dirStatus,
            targetsTitle = targetsTitle.ifEmpty { ctx.getString(R.string.crawl_targets_title) },
            targets = targetsUi,
            targetsEmptyHint = ctx.getString(R.string.crawl_target_empty),
            busy = busy,
            running = running,
            modeAlbum = modeAlbum,
            modeLabel = ctx.getString(
                if (modeAlbum) R.string.crawl_mode_album else R.string.crawl_mode_timeline,
            ),
            autoAfterCheckin = autoAfterCheckin,
            progress = progressUi,
            retryCount = retryCount,
            retryText = retryText,
            log = LogCardUi(expanded = logExpanded, lines = lines, summary = summary),
        )
    }

    fun targetOf(uid: String): CrawlTarget? =
        CrawlSettings.crawlTargets(ctx).firstOrNull { it.uid == uid }

    private fun postPickerLine(post: WeiboPostApi.RawPost): String {
        val t = WeiboPostApi.parseWeiboTime(post.createdAt)
        val whenStr = if (t != null) {
            "${t.first.substring(5)} ${t.second.substring(0, 5)}"
        } else {
            "未知时间"
        }
        val tags = buildString {
            if (post.pinned) append(" · 置顶")
            if (post.isRetweet) append(" · 转发")
            else if (post.picNum > 0) append(" · ${post.picNum}图")
            else append(" · 无图")
        }
        return "$whenStr$tags\n${post.text.ifEmpty { "（无文字）" }}"
    }
}