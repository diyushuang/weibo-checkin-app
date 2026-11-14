package com.wcm.checkin

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 浏览博主页（v0.9.11）：博主微博预览 + 单图 / 批量勾选抓取。
 *
 * - **列表**：`fetchUserPosts`（m 站 107603，必须账号身份）翻页加载，
 *   请求走 [WeiboThrottle]；卡片展示正文摘要 + 一排小图（56dp、整张缩进去）；
 * - **小图 → 大图**（v0.9.17）：点小图进 [RemotePreviewActivity] 全屏看大图，
 *   点卡片才是进选图页 —— 旧版把九宫格撑满整行并 CENTER_CROP 裁切，
 *   宽图只剩一条色带，完全看不出画面（用户实际反馈）；
 * - **筛选**：「只看原创」（滤掉 `retweeted_status`）与「只看有图」两个独立开关，
 *   默认全开 —— 对齐「批量勾选博主原创微博中的多张图」的需求口径；
 * - **多选**：长按卡片进入，点按勾选整帖；提交时 picIds 传 null = 整帖全抓，
 *   想在某帖里精选就退出多选点进它的选图页；
 * - **提交**：[AppScope] 启动 + [CrawlService.runPicked]，与增量抓取共用闸门/索引/命名；
 * - 浏览本身（列表 / 缩略图）只读、不占抓取闸门，可与后台增量任务并行。
 *
 * 本页刻意全列表 notifyDataSetChanged（lint: NotifyDataSetChanged）：
 * 筛选开关 / 多选切换 / 翻页插入都会改变「可见集合」本身，精确通知的复杂度
 * 配不上一页十来条卡片的量级 —— 与图库页（万级文件）的取舍不同。
 */
@Suppress("NotifyDataSetChanged")
@AndroidEntryPoint
class BrowsingActivity : AppCompatActivity() {

    @Inject lateinit var crawlService: CrawlService

    private val posts = ArrayList<WeiboPostApi.RawPost>()
    private val visible = ArrayList<WeiboPostApi.RawPost>()
    private val selectedBids = LinkedHashSet<String>()

    private val uiState = MutableStateFlow(BrowsingUiState())
    private var inputText = ""
    private var subtitleText = ""
    private var emptyText = ""
    private var loadingNow = false
    private var lastVisibleIndex = 0
    private var initialScrollIndex = 0

    private var uid = ""

    /**
     * 列表数据（posts / visible）**只归主线程**：IO 协程只负责网络，
     * 入库统一切 Dispatchers.Main。以前 IO 直接 posts.addAll 而 chip 点击在主线程
     * applyFilter() 迭代同一个 ArrayList → 必然偶发 ConcurrentModificationException。
     *
     * 下面这几个开关会被两侧读写，标 @Volatile 保证可见性（否则一次过期读会让
     * 翻页/筛选静默失效）。
     */
    @Volatile
    private var page = 0

    @Volatile
    private var hasMore = true

    @Volatile
    private var listBusy = false
    private var filterOriginal = true
    private var filterPic = true
    private var selectMode = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitSelectMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        emptyText = getString(R.string.browse_empty)

        val composeView = ComposeView(this)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            val state by uiState.collectAsState()
            WcmTheme {
                BrowsingScreen(
                    state = state,
                    onBack = { finish() },
                    onInputChange = {
                        inputText = it
                        uiState.value = uiState.value.copy(input = it)
                    },
                    onLoad = { startBrowse() },
                    onTargetClick = { uid ->
                        inputText = uid
                        render()
                        startBrowse()
                    },
                    onToggleOriginal = {
                        filterOriginal = !filterOriginal
                        applyFilter()
                    },
                    onTogglePic = {
                        filterPic = !filterPic
                        applyFilter()
                    },
                    onPostClick = { bid ->
                        val post = postOf(bid) ?: return@BrowsingScreen
                        if (selectMode) {
                            if (!selectedBids.remove(bid)) selectedBids.add(bid)
                            if (selectedBids.isEmpty()) exitSelectMode() else render()
                        } else {
                            PostPickActivity.start(this, bid)
                        }
                    },
                    onPostLongClick = { bid ->
                        if (!selectMode) enterSelectMode(bid) else {
                            val post = postOf(bid) ?: return@BrowsingScreen
                            if (!selectedBids.remove(post.bid)) selectedBids.add(post.bid)
                            if (selectedBids.isEmpty()) exitSelectMode() else render()
                        }
                    },
                    onThumbClick = { bid, index -> postOf(bid)?.let { openPreview(it, index) } },
                    onMorePics = { bid -> PostPickActivity.start(this, bid) },
                    onExitSelect = { exitSelectMode() },
                    onDownload = { submitSelected() },
                    onLoadMore = { loadMore() },
                    onVisibleIndex = { lastVisibleIndex = it },
                )
            }
        }
        setContentView(composeView)

        onBackPressedDispatcher.addCallback(this, backCallback)

        // 「哪些图已经抓过」的本地快照（v0.9.18）：索引解析在后台，读完刷一遍列表。
        // 没读完之前一律不标 —— 宁可不标，也不能给没抓过的图打勾。
        CrawlMarks.ensure(this) {
            if (!isFinishing && !isDestroyed) render()
        }

        if (savedInstanceState == null) {
            // 全新进入：丢掉上一次遗留的进程级缓存，避免串页
            keptPosts = null
            keptUid = ""
            // 预填：从图片页点进来时若只有一个抓取目标，直接载入它
            val prefill = intent.getStringExtra(EXTRA_UID) ?: ""
            if (prefill.isNotEmpty()) {
                inputText = prefill
                startBrowse()
            } else {
                render()
            }
        } else {
            restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_UID, uid)
        outState.putInt(KEY_PAGE, page)
        outState.putBoolean(KEY_HAS_MORE, hasMore)
        outState.putBoolean(KEY_FILTER_ORIGINAL, filterOriginal)
        outState.putBoolean(KEY_FILTER_PIC, filterPic)
        outState.putBoolean(KEY_SELECT, selectMode)
        outState.putStringArrayList(KEY_SELECTED, ArrayList(selectedBids))
        outState.putString(KEY_INPUT, inputText)
        outState.putInt(KEY_SCROLL, lastVisibleIndex)
        // 列表本体：配置变更后仍是同一进程，直接留指针（拷贝一份防后续改动）
        keptPosts = ArrayList(posts)
        keptUid = uid
        keptPage = page
        keptHasMore = hasMore
    }

    /** 重建后恢复：先还原标量并重贴 chip，再决定是复用缓存还是重新翻页。 */
    private fun restoreState(saved: Bundle) {
        filterOriginal = saved.getBoolean(KEY_FILTER_ORIGINAL, true)
        filterPic = saved.getBoolean(KEY_FILTER_PIC, true)
        inputText = saved.getString(KEY_INPUT) ?: ""
        initialScrollIndex = saved.getInt(KEY_SCROLL, 0)

        val savedUid = saved.getString(KEY_UID) ?: ""
        val cached = keptPosts
        if (cached != null && savedUid.isNotEmpty() && keptUid == savedUid) {
            uid = savedUid
            page = keptPage
            hasMore = keptHasMore
            posts.clear()
            posts.addAll(cached)
            applyFilter()
            // 勾选态：只保留仍在列表里的 bid（缓存可能是上一轮的）
            saved.getStringArrayList(KEY_SELECTED)?.forEach { bid ->
                if (posts.any { it.bid == bid }) selectedBids.add(bid)
            }
            if (saved.getBoolean(KEY_SELECT, false) && selectedBids.isNotEmpty()) {
                selectMode = true
                backCallback.isEnabled = true
            }
            render()
        } else if (savedUid.isNotEmpty()) {
            // 进程被杀：列表没了，按恢复出的 uid 重跑一次首屏
            inputText = savedUid
            startBrowse()
        }
    }

    /**
     * 点小图 → 全屏看大图（v0.9.17）。
     *
     * 传的是同一帖的小图清单，翻页就能在整帖里来回看；尺寸段由预览页自己换成
     * `large`（这里的小图是 orj360，直接放大会糊）。
     */
    private fun openPreview(post: WeiboPostApi.RawPost, at: Int) {
        val urls = post.thumbs.take(MAX_TILES)
        if (urls.isEmpty()) return
        RemotePreviewActivity.start(this, urls, at)
    }

    // ---------- 载入 ----------

    private fun startBrowse() {
        val input = inputText.trim()
        if (input.isEmpty()) {
            toast(getString(R.string.browse_need_uid))
            return
        }
        // 提交中 / 多选态下不允许重新载入：startBrowse 会清空 posts 与 selectedBids，
        // 用户辛苦勾的一批帖（以及正在跑的任务列表）会被无声抹掉。
        if (listBusy || submitting || selectMode) {
            toast(getString(R.string.busy))
            return
        }
        val ctx = applicationContext
        listBusy = true
        loadingNow = true
        emptyText = ""
        posts.clear()
        visible.clear()
        selectedBids.clear()
        page = 0
        hasMore = true
        render()

        AppScope.launch {
            try {
                val resolved = WeiboAuthApi.resolveUid(input)
                if (resolved.isEmpty()) {
                    failList(getString(R.string.browse_resolve_fail, input))
                    return@launch
                }
                uid = resolved
                val accounts = AccountStore.load(ctx)
                if (accounts.isEmpty()) {
                    failList(getString(R.string.browse_need_cookie))
                    return@launch
                }
                val cookie = CrawlEngine.pickCrawlCookie(this@BrowsingActivity, accounts) { }
                    ?: run {
                        failList(getString(R.string.browse_need_cookie))
                        return@launch
                    }
                // 昵称：游客身份优先，取不到用 UID 兜底
                val name = WeiboAuthApi.fetchUserName(cookie, resolved).ifEmpty { resolved }
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    subtitleText = "$name · $resolved"
                    render()
                }
                // 首屏只拉 1 页（10 帖）：配合「卡片进可见区才加载缩略图」，
                // 进页面时不再一次把 3 页 × 最多 9 格（近 90 个请求）压给 CDN ——
                // 剩下的靠触底翻页，一屏一屏往下续。
                // 例外：筛完不足 3 条（前几条是置顶/纯转发）才补一页，最多 2 页。
                var fetched = 0
                while (fetched < 2) {
                    val needMore = withContext(Dispatchers.Main) { hasMore && visible.size < 3 }
                    if (fetched >= 1 && !needMore) break
                    if (!fetchOnePage(cookie)) break
                    fetched++
                }
                withContext(Dispatchers.Main) { onListArrived() }
            } catch (e: Exception) {
                failList(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * 拉一页并入库；返回是否拉到了内容。网络失败抛给上层。
     *
     * 网络部分留在 IO，**数据变更全部切主线程** —— 见 posts / visible 的线程归属说明。
     */
    private suspend fun fetchOnePage(cookie: String): Boolean {
        WeiboThrottle.await(KEY_BROWSE_PAGE, 700)
        val (_, list) = WeiboPostApi.fetchUserPosts(cookie, uid, page + 1)
        if (list.isEmpty()) {
            withContext(Dispatchers.Main) { hasMore = false }
            return false
        }
        withContext(Dispatchers.Main) {
            page++
            posts.addAll(list)
            applyFilter()
        }
        return true
    }

    private suspend fun failList(msg: String) {
        withContext(Dispatchers.Main) {
            listBusy = false
            loadingNow = false
            if (!isFinishing && !isDestroyed) {
                toast(getString(R.string.browse_fetch_fail, msg))
                if (posts.isEmpty()) emptyText = getString(R.string.browse_no_posts)
                render()
            }
        }
    }

    private fun onListArrived() {
        listBusy = false
        loadingNow = false
        if (isFinishing || isDestroyed) return
        // 空态要区分两种：还没载入过（引导文案）vs 载入了但筛完是 0 条（换 uid 再试）
        emptyText = if (posts.isEmpty()) {
            getString(R.string.browse_no_posts)
        } else {
            getString(R.string.browse_empty)
        }
        render()
    }

    private fun loadMore() {
        if (listBusy || !hasMore || uid.isEmpty()) return
        val ctx = applicationContext
        listBusy = true
        AppScope.launch {
            try {
                val accounts = AccountStore.load(ctx)
                val cookie = CrawlEngine.pickCrawlCookie(this@BrowsingActivity, accounts) { }
                if (cookie == null) {
                    failList(getString(R.string.browse_need_cookie))
                    return@launch
                }
                fetchOnePage(cookie)
                withContext(Dispatchers.Main) { onListArrived() }
            } catch (e: Exception) {
                // 一次 429/断网不等于「已经到底」：hasMore 一旦置 false，滚动监听
                // 首行就直接 return，底部永久不再加载。错误提示走 failList 即可。
                failList(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---------- 筛选与渲染 ----------

    /** 重建 visible 并刷新列表。**只能在主线程调用**（数据全部归主线程）。 */
    private fun applyFilter() {
        visible.clear()
        for (p in posts) {
            if (filterOriginal && p.isRetweet) continue
            if (filterPic && p.picNum <= 0 && p.thumbs.isEmpty()) continue
            visible.add(p)
        }
        if (isDestroyed || isFinishing) return
        if (selectMode) {
            val visibleBids = visible.mapTo(HashSet()) { it.bid }
            selectedBids.retainAll(visibleBids)
            if (selectedBids.isEmpty()) exitSelectMode()
        }
        render()
    }

    private fun countText(): String = when {
        posts.isEmpty() -> ""
        hasMore -> getString(R.string.browse_load_more)
        else -> getString(R.string.browse_no_more)
    }

    private fun postOf(bid: String): WeiboPostApi.RawPost? = posts.firstOrNull { it.bid == bid }

    private fun metaLine(post: WeiboPostApi.RawPost): String {
        val t = WeiboPostApi.parseWeiboTime(post.createdAt)
        val whenStr = t?.let { "${it.first.substring(5)} ${it.second.substring(0, 5)}" } ?: "未知时间"
        val doneCount = post.thumbs.count { CrawlMarks.hasPic(WeiboImageUrl.pidFromImageUrl(it)) }
        val tags = buildList {
            if (post.pinned) add(getString(R.string.browse_meta_pinned))
            if (post.isRetweet) {
                add(getString(R.string.browse_meta_retweet))
            } else if (post.picNum > 0) {
                add(getString(R.string.browse_meta_pics_fmt, post.picNum))
            } else {
                add(getString(R.string.browse_meta_no_pic))
            }
            if (doneCount > 0) add(getString(R.string.browse_meta_done_fmt, doneCount))
        }
        return "$whenStr · ${tags.joinToString(" · ")}"
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        val targets = CrawlSettings.crawlTargets(this).map {
            BrowseAuthorChipUi(it.uid, it.name.ifEmpty { "UID ${it.uid}" })
        }
        val postsUi = visible.map { post ->
            val tiles = post.thumbs.take(MAX_TILES).map { url ->
                BrowseThumbUi(
                    url = url,
                    done = CrawlMarks.hasPic(WeiboImageUrl.pidFromImageUrl(url)),
                )
            }
            val total = maxOf(post.thumbs.size, post.picNum)
            BrowsePostUi(
                bid = post.bid,
                meta = metaLine(post),
                text = post.text.ifEmpty { "（无文字）" },
                thumbs = tiles,
                moreCount = (total - tiles.size).coerceAtLeast(0),
                selected = post.bid in selectedBids,
            )
        }
        uiState.value = BrowsingUiState(
            title = getString(R.string.browse_title),
            subtitle = subtitleText,
            input = inputText,
            targets = targets,
            filterOriginal = filterOriginal,
            filterPic = filterPic,
            countText = countText(),
            hint = getString(
                if (selectMode) R.string.browse_select_mode_hint else R.string.browse_select_hint,
            ),
            posts = postsUi,
            selectMode = selectMode,
            selectedCount = selectedBids.size,
            submitting = submitting,
            loading = loadingNow,
            emptyVisible = !loadingNow && visible.isEmpty(),
            emptyText = emptyText.ifEmpty { getString(R.string.browse_empty) },
            initialScrollIndex = initialScrollIndex,
        )
        // 恢复用的初始滚动位置只该生效一次
        initialScrollIndex = 0
    }

    // ---------- 多选 ----------

    private fun enterSelectMode(firstBid: String) {
        selectMode = true
        selectedBids.clear()
        selectedBids.add(firstBid)
        backCallback.isEnabled = true
        render()
    }

    private fun exitSelectMode() {
        selectMode = false
        selectedBids.clear()
        backCallback.isEnabled = false
        render()
    }

    private fun submitSelected() {
        if (selectedBids.isEmpty()) {
            toast(getString(R.string.browse_none_selected))
            return
        }
        // 任务必须从 selectedBids 生成：visible 是「当前筛选结果」，
        // 勾选后被筛掉的帖若被无声丢弃，用户会看到「下载选中(5)」却只抓了 2 条。
        val tasks = posts.filter { it.bid in selectedBids }.map { CrawlEngine.PickTask(it.bid, null) }
        val missing = selectedBids.size - tasks.size
        val ctx = applicationContext
        setSubmitting(true)
        AppScope.launch {
            var busy = false
            var summary: CrawlEngine.PickedSummary? = null
            var err: String? = null
            try {
                val r = crawlService.runPicked(ctx, tasks) { }
                if (r == null) busy = true else summary = r
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            withContext(Dispatchers.Main) {
                setSubmitting(false)
                if (isFinishing || isDestroyed) return@withContext
                when {
                    busy -> toast(getString(R.string.crawl_busy))
                    err != null -> toast("✗ $err")
                    summary != null -> {
                        val suffix = buildString {
                            if (summary.failed > 0) {
                                append(getString(R.string.browse_fail_suffix_fmt, summary.failed))
                            }
                            if (summary.stopped) append(getString(R.string.browse_stopped))
                        }
                        toast(getString(R.string.browse_done_fmt, summary.saved, suffix))
                    }
                }
                if (missing > 0) {
                    // 重新载入后旧勾选会脱离列表，明确告知而不是静默少抓
                    toast("⚠️ " + missing + " " + getString(R.string.browse_missing_skipped))
                }
                // 刚落了新文件：索引变了，重读一次快照，让小勾立刻出现在刚抓的图上
                if (summary != null && summary.saved > 0) {
                    CrawlMarks.invalidate()
                    CrawlMarks.ensure(this@BrowsingActivity) { render() }
                }
                exitSelectMode()
            }
        }
    }

    private var submitting = false

    private fun setSubmitting(v: Boolean) {
        submitting = v
        render()
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_UID = "uid"
        private const val KEY_BROWSE_PAGE = "browse-page"

        /** 卡片里最多摆几张小图（与微博九宫格一致，再多的用「+n」表示，进选图页看全量）。 */
        private const val MAX_TILES = 9

        // ---- 旋转 / 深浅色切换的恢复现场 ----
        //
        // `WeiboPostApi.RawPost` 不是 Parcelable，塞不进 Bundle；而重建一次就重跑一遍
        // `resolveUid` + 最多 3 页请求（还覆盖用户输入、丢筛选与勾选）显然不可接受。
        // 所以列表本体放在进程级静态字段（配置变更时天然还在），Bundle 只存标量；
        // 进程被杀后静态字段为空，那时按恢复出来的 uid 重新翻页。
        private const val KEY_UID = "browse_uid"
        private const val KEY_PAGE = "browse_page"
        private const val KEY_HAS_MORE = "browse_has_more"
        private const val KEY_FILTER_ORIGINAL = "browse_filter_original"
        private const val KEY_FILTER_PIC = "browse_filter_pic"
        private const val KEY_SELECT = "browse_select"
        private const val KEY_SELECTED = "browse_selected"
        private const val KEY_INPUT = "browse_input"
        private const val KEY_SCROLL = "browse_scroll"

        @Volatile private var keptPosts: List<WeiboPostApi.RawPost>? = null
        @Volatile private var keptUid: String = ""
        @Volatile private var keptPage: Int = 0
        @Volatile private var keptHasMore: Boolean = true

        fun start(ctx: Context, uid: String = "") {
            ctx.startActivity(
                Intent(ctx, BrowsingActivity::class.java).putExtra(EXTRA_UID, uid),
            )
        }
    }
}
