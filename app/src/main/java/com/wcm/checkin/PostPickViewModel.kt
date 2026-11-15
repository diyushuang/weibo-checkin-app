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

sealed interface PostPickEvent {
    data class Toast(val text: String) : PostPickEvent
    data class ShowFullPreview(val fullUrl: String, val picId: String) : PostPickEvent
}

/** 选图页状态与编排（route 版）。 */
@HiltViewModel
class PostPickViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val crawlService: CrawlService,
    /** 勾选/单帖抓取要按当前模式挑身份（游客 or 账号）。 */
    private val crawlEngine: CrawlEngine,
) : ViewModel() {

    private data class PicItem(
        val picId: String,
        val thumbUrl: String,
        val fullUrl: String,
        val isLive: Boolean,
        val isGif: Boolean,
    )

    private val items = ArrayList<PicItem>()
    private val selected = LinkedHashSet<String>()

    private val _state = MutableStateFlow(PickUiState())
    val state: StateFlow<PickUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PostPickEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PostPickEvent> = _events.asSharedFlow()

    private var bid = ""
    private var started = false
    private var loadingNow = false
    private var emptyText = ""
    private var loaded = false
    private var submitting = false

    /** 上一次离开本页时列表的首可见项下标；重新进入组合时用它恢复位置。 */
    private var initialScrollIndex = 0

    init {
        emptyText = ctx.getString(R.string.pick_no_pics)
        CrawlMarks.ensure(ctx) { if (loaded) render() }
    }

    fun start(initialBid: String) {
        if (started) return
        started = true
        bid = initialBid
        loadDetail()
    }

    private fun loadDetail() {
        loadingNow = true
        render()
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val cookie = crawlEngine.pickCrawlCookie(ctx, AccountStore.load(ctx)) { }
                    ?: throw IllegalStateException(ctx.getString(R.string.browse_need_cookie))
                WeiboPostApi.fetchPostDetail(cookie, bid)
            }
            withContext(Dispatchers.Main) {
                loadingNow = false
                result.fold(
                    onSuccess = { detail ->
                        loaded = true
                        parseItems(detail)
                        emptyText = if (items.isEmpty()) ctx.getString(R.string.pick_no_pics) else ""
                        render()
                    },
                    onFailure = { e ->
                        emptyText = ctx.getString(R.string.pick_load_fail, e.message ?: "")
                        render()
                    },
                )
            }
        }
    }

    /** 详情 → 图清单：pic_ids 权威保序，pics 只补 URL 与类型（可能截断到 9）。 */
    private fun parseItems(detail: org.json.JSONObject) {
        items.clear()
        val picIds = ArrayList<String>()
        detail.optJSONArray("pic_ids")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pid = arr.optString(i, "").trim()
                if (pid.isNotEmpty()) picIds.add(pid)
            }
        }
        val infos = HashMap<String, org.json.JSONObject>()
        detail.optJSONArray("pics")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                infos[pidOf(p)] = p
            }
        }
        picIds.forEach { pid ->
            val p = infos[pid]
            val type = p?.optString("type", "") ?: ""
            val thumbUrl = WeiboImageUrl.sanitizeThumbUrl(p?.optString("url", "").orEmpty())
                ?: WeiboImageUrl.thumbnailUrl(pid)
            val fullUrl = WeiboImageUrl.sanitizeThumbUrl(
                p?.optJSONObject("large")?.optString("url", "").orEmpty(),
            ) ?: WeiboImageUrl.originalImageUrl(pid)
            items.add(
                PicItem(
                    picId = pid,
                    thumbUrl = thumbUrl,
                    fullUrl = fullUrl,
                    isLive = type == "livephoto",
                    isGif = type == "gif",
                ),
            )
        }
    }

    private fun pidOf(pic: org.json.JSONObject): String {
        val url = pic.optJSONObject("large")?.optString("url", "")
            ?: pic.optString("url", "")
        if (url.isNotEmpty()) {
            val bare = url.substringBefore('?').substringBefore('#')
            return bare.substringAfterLast('/')
                .replace(Regex("\\.(jpg|jpeg|png|gif|webp)$", RegexOption.IGNORE_CASE), "")
        }
        return pic.optString("pid", "")
    }

    fun toggle(picId: String) {
        if (!selected.remove(picId)) selected.add(picId)
        render()
    }

    fun toggleAll() {
        if (items.isEmpty()) return
        if (selected.size == items.size) {
            selected.clear()
        } else {
            selected.clear()
            items.forEach { selected.add(it.picId) }
        }
        render()
    }

    fun requestFullPreview(index: Int) {
        val item = items.getOrNull(index) ?: return
        _events.tryEmit(PostPickEvent.ShowFullPreview(item.fullUrl, item.picId))
    }

    fun submitSelected() {
        submit(selected.toSet())
    }

    fun submit(picIds: Set<String>) {
        if (submitting) return
        if (picIds.isEmpty()) {
            toast(ctx.getString(R.string.browse_none_selected))
            return
        }
        submitting = true
        render()
        AppScope.launch {
            var busy = false
            var summary: CrawlEngine.PickedSummary? = null
            var err: String? = null
            try {
                val r = crawlService.runPicked(ctx, listOf(CrawlEngine.PickTask(bid, picIds))) { }
                if (r == null) busy = true else summary = r
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            withContext(Dispatchers.Main + NonCancellable) {
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
                if ((summary?.saved ?: 0) > 0) {
                    CrawlMarks.invalidate()
                    CrawlMarks.ensure(ctx) { render() }
                }
                render()
            }
        }
    }

    /**
     * 记录选图网格当前首可见项下标（只写字段，不重发 UiState —— 见
     * `BrowsingViewModel.onVisibleIndex` 的同类说明）。
     */
    fun onVisibleIndex(index: Int) {
        initialScrollIndex = index
    }

    private fun render() {
        val itemsUi = items.map { item ->
            PickItemUi(
                picId = item.picId,
                thumbUrl = item.thumbUrl,
                isLive = item.isLive,
                isGif = item.isGif,
                done = CrawlMarks.hasPic(item.picId),
                selected = item.picId in selected,
            )
        }
        _state.value = PickUiState(
            items = itemsUi,
            loading = loadingNow,
            emptyVisible = !loadingNow && items.isEmpty(),
            emptyText = emptyText.ifEmpty { ctx.getString(R.string.pick_no_pics) },
            selectedCount = selected.size,
            allSelected = items.isNotEmpty() && selected.size == items.size,
            submitting = submitting,
            initialScrollIndex = initialScrollIndex,
        )
    }

    private fun toast(text: String) {
        _events.tryEmit(PostPickEvent.Toast(text))
    }
}