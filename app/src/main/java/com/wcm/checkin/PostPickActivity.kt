package com.wcm.checkin

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 选图页（v0.9.11）：一条微博的完整图片清单，支持勾选下载。
 *
 * - 数据：进入时拉一次 m 站详情，以 `pic_ids` 为权威清单（列表 9 图截断在此终结）；
 * - 交互：单击 = 勾选/取消；长按 = 全屏大图（黑底 Dialog + [ZoomableImageView]），
 *   大图页里有「只下这张」—— 单图抓取的最短路径；
 * - 徽标：`pics[].type == "livephoto"` 标「实况」（勾选后落 GIF/mov），
 *   `type == "gif"` 标 GIF。注意详情的 pics 数组在超 9 图帖里同样会截断，
 *   第 10 张起没有类型信息，按静图处理 —— 与增量抓取的 Live 配对口径一致；
 * - 提交：[AppScope] 启动（转屏/退出不中断）+ [CrawlService.runPicked]（单帖任务，
 *   与增量抓取共用闸门与索引），完成后 toast 汇总。
 *
 * 勾选反馈走整表 notifyDataSetChanged（lint: NotifyDataSetChanged）：单帖最多 18 格，
 * 精确通知的复杂度没有意义。
 */
@Suppress("NotifyDataSetChanged")
@AndroidEntryPoint
class PostPickActivity : AppCompatActivity() {

    @Inject lateinit var crawlService: CrawlService

    /** 网格里的一张图。 */
    private data class PicItem(
        val picId: String,
        val thumbUrl: String,
        val fullUrl: String,
        val isLive: Boolean,
        val isGif: Boolean,
    )

    private val items = ArrayList<PicItem>()
    private val selected = LinkedHashSet<String>()

    private lateinit var bid: String
    private val uiState = MutableStateFlow(PickUiState())
    private var loadingNow = false
    private var emptyText = ""
    private var lastVisibleIndex = 0

    private var loaded = false
    private var submitting = false

    /** 重建后待恢复的勾选与滚动（图清单本身不缓存，[loadDetail] 会重拉一次详情）。 */
    private var pendingSelected: Set<String> = emptySet()
    private var pendingScroll = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        bid = intent.getStringExtra(EXTRA_BID) ?: ""
        if (bid.isEmpty()) {
            finish()
            return
        }
        if (savedInstanceState != null) {
            pendingSelected =
                savedInstanceState.getStringArrayList(KEY_SELECTED)?.toSet() ?: emptySet()
            pendingScroll = savedInstanceState.getInt(KEY_SCROLL, -1)
        }
        emptyText = getString(R.string.pick_no_pics)

        val composeView = ComposeView(this)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            val state by uiState.collectAsState()
            WcmTheme {
                PostPickScreen(
                    state = state,
                    onBack = { finish() },
                    onToggleAll = { toggleAll() },
                    onItemClick = { picId ->
                        if (!selected.remove(picId)) selected.add(picId)
                        render()
                    },
                    onItemLongClick = { index -> showFullPreview(index) },
                    onDownload = { submit(selected.toSet()) },
                    onVisibleIndex = { lastVisibleIndex = it },
                )
            }
        }
        setContentView(composeView)

        // 「哪些图已经在相册里」的快照（v0.9.18）：读完刷一遍格子，挑图时不用凭记忆
        CrawlMarks.ensure(this) {
            if (!isFinishing && !isDestroyed && loaded) render()
        }

        loadDetail()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_SELECTED, ArrayList(selected))
        outState.putInt(KEY_SCROLL, lastVisibleIndex)
    }

    // ---------- 数据 ----------

    private fun loadDetail() {
        loadingNow = true
        render()
        val ctx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val cookie = CrawlEngine.pickCrawlCookie(this@PostPickActivity, AccountStore.load(ctx), {})
                    ?: throw IllegalStateException(ctx.getString(R.string.browse_need_cookie))
                WeiboPostApi.fetchPostDetail(cookie, bid)
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                loadingNow = false
                result.fold(
                    onSuccess = { detail ->
                        loaded = true
                        parseItems(detail)
                        // 重建恢复：只保留仍在清单里的勾选（pic_ids 可能已变）
                        if (pendingSelected.isNotEmpty()) {
                            val keep = pendingSelected
                            selected.addAll(items.map { it.picId }.filter { it in keep })
                            pendingSelected = emptySet()
                        }
                        emptyText = if (items.isEmpty()) getString(R.string.pick_no_pics) else ""
                        render()
                    },
                    onFailure = { e ->
                        emptyText = getString(R.string.pick_load_fail, e.message ?: "")
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
        // pics[] 按 pid 建索引：type（livephoto/gif）与 url 都从这来。
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

    /** 从 large.url 反推 pid（与抓取详情解析的 pid 反推同口径：Live 条目两处 pid 不总一致）。 */
    private fun pidOf(pic: org.json.JSONObject): String {
        val url = pic.optJSONObject("large")?.optString("url", "")
            ?: pic.optString("url", "")
        if (url.isNotEmpty()) {
            // 先剥 query / fragment 再剥扩展名：URL 带 ?from=... 时，
            // "abc.jpg?from=x" 剥完扩展名不是 "abc" 而是原样，pid 与 pic_ids 对不上，
            // 这张图就丢了 livephoto / gif 类型标记与大图 URL，静默回落成普通缩略图。
            val bare = url.substringBefore('?').substringBefore('#')
            return bare.substringAfterLast('/')
                .replace(Regex("\\.(jpg|jpeg|png|gif|webp)$", RegexOption.IGNORE_CASE), "")
        }
        return pic.optString("pid", "")
    }

    // ---------- 渲染 ----------

    private fun render() {
        if (isFinishing || isDestroyed) return
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
        val initial = if (pendingScroll > 0) pendingScroll else 0
        pendingScroll = -1
        uiState.value = PickUiState(
            items = itemsUi,
            loading = loadingNow,
            emptyVisible = !loadingNow && items.isEmpty(),
            emptyText = emptyText.ifEmpty { getString(R.string.pick_no_pics) },
            selectedCount = selected.size,
            allSelected = items.isNotEmpty() && selected.size == items.size,
            submitting = submitting,
            initialScrollIndex = initial,
        )
    }

    private fun toggleAll() {
        if (items.isEmpty()) return
        if (selected.size == items.size) {
            selected.clear()
        } else {
            selected.clear()
            items.forEach { selected.add(it.picId) }
        }
        render()
    }

    // ---------- 提交 ----------

    private fun submit(picIds: Set<String>) {
        if (submitting) return
        if (picIds.isEmpty()) {
            toast(getString(R.string.browse_none_selected))
            return
        }
        val ctx = applicationContext
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
            withContext(Dispatchers.Main) {
                submitting = false
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
                // 刚落了新文件：索引变了，重读一次快照，让「已抓取」小勾立刻出现
                if ((summary?.saved ?: 0) > 0) {
                    CrawlMarks.invalidate()
                    CrawlMarks.ensure(this@PostPickActivity) { render() }
                }
                render()
            }
        }
    }

    // ---------- 全屏预览（黑底 Dialog，代码构建，无布局文件） ----------

    private fun showFullPreview(position: Int) {
        if (position !in items.indices) return
        val item = items[position]
        // 每次新建：AlertDialog.setView 只在 show() 前生效，复用实例没法换内容
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).create()
        val match = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val root = FrameLayout(this).apply {
            layoutParams = match
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val zoom = ZoomableImageView(this).apply {
            layoutParams = match
        }
        val progress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        val bar = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
            gravity = Gravity.CENTER
        }
        val only = TextView(this).apply {
            text = getString(R.string.pick_only_this)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setTextColor(android.graphics.Color.WHITE)
            // 48dp 触控区（无障碍基线）：14dp 文案内边距 + 文字高度，行高再补 20dp
            minHeight = resources.getDimensionPixelSize(R.dimen.touch_min)
            gravity = Gravity.CENTER
            setOnClickListener {
                dialog.dismiss()
                submit(setOf(item.picId))
            }
        }
        val close = TextView(this).apply {
            text = getString(R.string.pick_close)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setTextColor(0xB3FFFFFF.toInt())
            minHeight = resources.getDimensionPixelSize(R.dimen.touch_min)
            gravity = Gravity.CENTER
            setOnClickListener { dialog.dismiss() }
        }
        bar.addView(only)
        bar.addView(close)
        root.addView(zoom)
        root.addView(progress)
        root.addView(bar)
        zoom.setOnClickListener { dialog.dismiss() }
        // contentDescription：全屏预览的可读性
        zoom.contentDescription = getString(R.string.pick_preview_desc)
        dialog.setView(root)
        dialog.show()
        // 全屏黑底 Dialog 的 window 不会继承 Activity 的 insets 处理，
        // 手势导航条会直接压在底部操作条上 —— 在 decorView 上补一份避让
        dialog.window?.decorView?.let { dv ->
            ViewCompat.setOnApplyWindowInsetsListener(dv) { _, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                root.updatePadding(top = bars.top, bottom = bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }

        val key = item.fullUrl
        zoom.tag = key
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = runCatching { RemoteThumb.loadFull(item.fullUrl) }.getOrNull()
            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                if (bmp != null && dialog.isShowing && zoom.tag == key) {
                    // 刻意 BitmapDrawable(null, ...)：density=NONE 才不会被按屏幕密度缩放，
                    // ZoomableImageView 的缩放/位移坐标映射才不漂移（与 ThumbLoader.fullDrawable 同口径）
                    @Suppress("UseKtx")
                    zoom.setImageDrawable(BitmapDrawable(null, bmp))
                } else if (bmp == null) {
                    Toast.makeText(applicationContext, R.string.pick_full_fail, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_BID = "bid"
        private const val KEY_SELECTED = "pick_selected"
        private const val KEY_SCROLL = "pick_scroll"

        fun start(ctx: Context, bid: String) {
            ctx.startActivity(
                Intent(ctx, PostPickActivity::class.java).putExtra(EXTRA_BID, bid),
            )
        }
    }
}
