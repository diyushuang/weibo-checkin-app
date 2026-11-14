package com.wcm.checkin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BrowsingEvent {
    data class Toast(val text: String) : BrowsingEvent
    data class OpenPostPick(val bid: String) : BrowsingEvent
    data class OpenRemotePreview(val urls: List<String>, val index: Int) : BrowsingEvent
}

/** 浏览博主页状态与编排（route 版）。 */
@HiltViewModel
class BrowsingViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val crawlService: CrawlService,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowsingUiState())
    val state: StateFlow<BrowsingUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BrowsingEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<BrowsingEvent> = _events.asSharedFlow()

    private val posts = ArrayList<WeiboPostApi.RawPost>()
    private val visible = ArrayList<WeiboPostApi.RawPost>()
    private val selectedBids = LinkedHashSet<String>()

    private var inputText = ""
    private var subtitleText = ""
    private var emptyText = ""
    private var loadingNow = false
    private var initialScrollIndex = 0
    private var started = false

    private var uid = ""
    private var page = 0
    private var hasMore = true
    private var listBusy = false
    private var filterOriginal = true
    private var filterPic = true
    private var selectMode = false
    private var submitting = false

    companion object {
        private const val KEY_BROWSE_PAGE = "browse-page"
        private const val MAX_TILES = 9
    }

    init {
        emptyText = ctx.getString(R.string.browse_empty)
        CrawlMarks.ensure(ctx) { render() }
    }

    fun start(initialUid: String) {
        if (started) {
            render()
            return
        }
        started = true
        if (initialUid.isNotEmpty()) {
            inputText = initialUid
            startBrowse()
        } else {
            render()
        }
    }

    fun onResume() {
        if (started) render()
    }

    fun updateInput(text: String) {
        inputText = text
        _state.value = _state.value.copy(input = text)
    }

    fun reload() {
        startBrowse()
    }

    fun selectTarget(uid: String) {
        inputText = uid
        _state.value = _state.value.copy(input = uid)
        startBrowse()
    }

    fun toggleOriginal() {
        filterOriginal = !filterOriginal
        applyFilter()
    }

    fun togglePic() {
        filterPic = !filterPic
        applyFilter()
    }

    fun onPostClick(bid: String) {
        if (selectMode) {
            if (!selectedBids.remove(bid)) selectedBids.add(bid)
            if (selectedBids.isEmpty()) exitSelectMode() else render()
        } else {
            _events.tryEmit(BrowsingEvent.OpenPostPick(bid))
        }
    }

    fun onPostLongClick(bid: String) {
        if (!selectMode) {
            enterSelectMode(bid)
        } else {
            if (!selectedBids.remove(bid)) selectedBids.add(bid)
            if (selectedBids.isEmpty()) exitSelectMode() else render()
        }
    }

    fun onThumbClick(bid: String, index: Int) {
        val post = posts.firstOrNull { it.bid == bid } ?: return
        val urls = post.thumbs.take(MAX_TILES)
        if (urls.isEmpty()) return
        _events.tryEmit(BrowsingEvent.OpenRemotePreview(urls, index))
    }

    fun openPostPick(bid: String) {
        _events.tryEmit(BrowsingEvent.OpenPostPick(bid))
    }

    fun exitSelectMode() {
        selectMode = false
        selectedBids.clear()
        render()
    }

    fun submitSelected() {
        if (selectedBids.isEmpty()) {
            toast(ctx.getString(R.string.browse_none_selected))
            return
        }
        // 任务必须从 selectedBids 生成：visible 是「当前筛选结果」，
        // 勾选后被筛掉的帖若被无声丢弃，用户会看到「下载选中(5)」却只抓了 2 条。
        val tasks = posts.filter { it.bid in selectedBids }.map { CrawlEngine.PickTask(it.bid, null) }
        val missing = selectedBids.size - tasks.size
        submitting = true
        render()
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
                submitting = false
                when {
                    busy -> toast(ctx.getString(R.string.crawl_busy))
                    err != null -> toast("✗ $err")
                    summary != null -> {
                        val suffix = buildString {
                            if (summary.failed > 0) {
                                append(ctx.getString(R.string.browse_fail_suffix_fmt, summary.failed))
                            }
                            if (summary.stopped) append(ctx.getString(R.string.browse_stopped))
                        }
                        toast(ctx.getString(R.string.browse_done_fmt, summary.saved, suffix))
                    }
                }
                if (missing > 0) {
                    toast("⚠️ " + missing + " " + ctx.getString(R.string.browse_missing_skipped))
                }
                if (summary != null && summary.saved > 0) {
                    CrawlMarks.invalidate()
                    CrawlMarks.ensure(ctx) { render() }
                }
                exitSelectMode()
            }
        }
    }

    fun loadMore() {
        if (listBusy || !hasMore || uid.isEmpty()) return
        listBusy = true
        viewModelScope.launch {
            try {
                val cookie = CrawlEngine.pickCrawlCookie(ctx, AccountStore.load(ctx)) { }
                if (cookie == null) {
                    failList(ctx.getString(R.string.browse_need_cookie))
                    return@launch
                }
                fetchOnePage(cookie)
                withContext(Dispatchers.Main) { onListArrived() }
            } catch (e: Exception) {
                failList(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun startBrowse() {
        val input = inputText.trim()
        if (input.isEmpty()) {
            toast(ctx.getString(R.string.browse_need_uid))
            return
        }
        if (listBusy || submitting || selectMode) {
            toast(ctx.getString(R.string.busy))
            return
        }
        listBusy = true
        loadingNow = true
        emptyText = ""
        posts.clear()
        visible.clear()
        selectedBids.clear()
        page = 0
        hasMore = true
        render()

        viewModelScope.launch {
            try {
                val resolved = WeiboAuthApi.resolveUid(input)
                if (resolved.isEmpty()) {
                    failList(ctx.getString(R.string.browse_resolve_fail, input))
                    return@launch
                }
                uid = resolved
                val accounts = AccountStore.load(ctx)
                if (accounts.isEmpty()) {
                    failList(ctx.getString(R.string.browse_need_cookie))
                    return@launch
                }
                val cookie = CrawlEngine.pickCrawlCookie(ctx, accounts) { }
                    ?: run {
                        failList(ctx.getString(R.string.browse_need_cookie))
                        return@launch
                    }
                val name = WeiboAuthApi.fetchUserName(cookie, resolved).ifEmpty { resolved }
                withContext(Dispatchers.Main) {
                    subtitleText = "$name · $resolved"
                    render()
                }
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
            toast(ctx.getString(R.string.browse_fetch_fail, msg))
            if (posts.isEmpty()) emptyText = ctx.getString(R.string.browse_no_posts)
            render()
        }
    }

    private fun onListArrived() {
        listBusy = false
        loadingNow = false
        emptyText = if (posts.isEmpty()) {
            ctx.getString(R.string.browse_no_posts)
        } else {
            ctx.getString(R.string.browse_empty)
        }
        render()
    }

    private fun applyFilter() {
        visible.clear()
        for (p in posts) {
            if (filterOriginal && p.isRetweet) continue
            if (filterPic && p.picNum <= 0 && p.thumbs.isEmpty()) continue
            visible.add(p)
        }
        if (selectMode) {
            val visibleBids = visible.mapTo(HashSet()) { it.bid }
            selectedBids.retainAll(visibleBids)
            if (selectedBids.isEmpty()) exitSelectMode()
        }
        render()
    }

    private fun enterSelectMode(firstBid: String) {
        selectMode = true
        selectedBids.clear()
        selectedBids.add(firstBid)
        render()
    }

    private fun countText(): String = when {
        posts.isEmpty() -> ""
        hasMore -> ctx.getString(R.string.browse_load_more)
        else -> ctx.getString(R.string.browse_no_more)
    }

    private fun metaLine(post: WeiboPostApi.RawPost): String {
        val t = WeiboPostApi.parseWeiboTime(post.createdAt)
        val whenStr = t?.let { "${it.first.substring(5)} ${it.second.substring(0, 5)}" } ?: "未知时间"
        val doneCount = post.thumbs.count { CrawlMarks.hasPic(WeiboImageUrl.pidFromImageUrl(it)) }
        val tags = buildList {
            if (post.pinned) add(ctx.getString(R.string.browse_meta_pinned))
            if (post.isRetweet) {
                add(ctx.getString(R.string.browse_meta_retweet))
            } else if (post.picNum > 0) {
                add(ctx.getString(R.string.browse_meta_pics_fmt, post.picNum))
            } else {
                add(ctx.getString(R.string.browse_meta_no_pic))
            }
            if (doneCount > 0) add(ctx.getString(R.string.browse_meta_done_fmt, doneCount))
        }
        return "$whenStr · ${tags.joinToString(" · ")}"
    }

    private fun render() {
        val targets = CrawlSettings.crawlTargets(ctx).map {
            BrowseAuthorChipUi(it.uid, it.name.ifEmpty { "UID ${it.uid}" })
        }
        val postsUi = visible.map { post ->
            val tiles = post.thumbs.take(MAX_TILES).map { url ->
                BrowseThumbUi(url, CrawlMarks.hasPic(WeiboImageUrl.pidFromImageUrl(url)))
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
        _state.value = BrowsingUiState(
            title = ctx.getString(R.string.browse_title),
            subtitle = subtitleText,
            input = inputText,
            targets = targets,
            filterOriginal = filterOriginal,
            filterPic = filterPic,
            countText = countText(),
            hint = ctx.getString(
                if (selectMode) R.string.browse_select_mode_hint else R.string.browse_select_hint,
            ),
            posts = postsUi,
            selectMode = selectMode,
            selectedCount = selectedBids.size,
            submitting = submitting,
            loading = loadingNow,
            emptyVisible = !loadingNow && visible.isEmpty(),
            emptyText = emptyText.ifEmpty { ctx.getString(R.string.browse_empty) },
            initialScrollIndex = initialScrollIndex,
        )
        initialScrollIndex = 0
    }

    private fun toast(text: String) {
        _events.tryEmit(BrowsingEvent.Toast(text))
    }
}