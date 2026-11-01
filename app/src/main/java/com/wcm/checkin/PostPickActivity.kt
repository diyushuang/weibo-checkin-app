package com.wcm.checkin

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * - 提交：[AppScope] 启动（转屏/退出不中断）+ [CrawlRunner.runPicked]（单帖任务，
 *   与增量抓取共用闸门与索引），完成后 toast 汇总。
 *
 * 勾选反馈走整表 notifyDataSetChanged（lint: NotifyDataSetChanged）：单帖最多 18 格，
 * 精确通知的复杂度没有意义。
 */
@Suppress("NotifyDataSetChanged")
class PostPickActivity : AppCompatActivity() {

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
    private val adapter = GridAdapter()

    private lateinit var bid: String
    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var loading: ProgressBar
    private lateinit var btnDownload: MaterialButton
    private lateinit var toolbar: MaterialToolbar

    /** 单元格边长（px）：按可用宽度对半算，首次测量后覆写。 */
    private var cellPx = 0

    private var loaded = false
    private var submitting = false

    /** 重建后待恢复的勾选与滚动（图清单本身不缓存，[loadDetail] 会重拉一次详情）。 */
    private var pendingSelected: Set<String> = emptySet()
    private var pendingScroll = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_post_pick)

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
        toolbar = findViewById(R.id.pickToolbar)
        recycler = findViewById(R.id.pickGrid)
        tvEmpty = findViewById(R.id.tvPickEmpty)
        loading = findViewById(R.id.pickLoading)
        btnDownload = findViewById(R.id.btnPickDownload)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_pick_toggle_all) {
                toggleAll()
                true
            } else {
                false
            }
        }
        recycler.layoutManager = GridLayoutManager(this, 2)
        recycler.adapter = adapter

        findViewById<View>(R.id.pickRoot)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                root.updatePadding(top = bars.top, bottom = bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }
        // 列宽测量：网格单元 = (可用宽 - 列间隙) / 2，保证正方形。
        recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            measureCells()
        }
        btnDownload.setOnClickListener { submit(selected.toSet()) }

        loadDetail()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_SELECTED, ArrayList(selected))
        outState.putInt(
            KEY_SCROLL,
            (recycler.layoutManager as? GridLayoutManager)?.findFirstVisibleItemPosition() ?: 0,
        )
    }

    private fun measureCells() {
        val w = recycler.width
        if (w <= 0) return
        val gutter = resources.getDimensionPixelSize(R.dimen.gallery_gutter)
        val side = (w - resources.getDimensionPixelSize(R.dimen.page_padding_horizontal) * 2 - gutter) / 2
        if (side > 0 && side != cellPx) {
            cellPx = side
            if (loaded) adapter.notifyDataSetChanged()
        }
    }

    // ---------- 数据 ----------

    private fun loadDetail() {
        loading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        val ctx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val cookie = Crawler.pickCrawlCookie(this@PostPickActivity, AccountStore.load(ctx), {})
                    ?: throw IllegalStateException(ctx.getString(R.string.browse_need_cookie))
                WeiboApi.fetchPostDetail(cookie, bid)
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                loading.visibility = View.GONE
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
                        measureCells()
                        render()
                        if (pendingScroll > 0) {
                            recycler.scrollToPosition(pendingScroll)
                            pendingScroll = -1
                        }
                    },
                    onFailure = { e ->
                        tvEmpty.text = getString(R.string.pick_load_fail, e.message ?: "")
                        tvEmpty.visibility = View.VISIBLE
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
            val thumbUrl = WeiboApi.sanitizeThumbUrl(p?.optString("url", "").orEmpty())
                ?: WeiboApi.thumbnailUrl(pid)
            val fullUrl = WeiboApi.sanitizeThumbUrl(
                p?.optJSONObject("large")?.optString("url", "").orEmpty(),
            ) ?: WeiboApi.originalImageUrl(pid)
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

    /** 从 large.url 反推 pid（与 Crawler.pidOf 同口径：Live 条目两处 pid 不总一致）。 */
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
        tvEmpty.visibility = if (loaded && items.isEmpty()) View.VISIBLE else View.GONE
        btnDownload.isEnabled = selected.isNotEmpty() && !submitting
        btnDownload.text = when {
            submitting -> getString(R.string.pick_downloading)
            selected.isEmpty() -> getString(R.string.pick_download_zero)
            else -> getString(R.string.pick_download_fmt, selected.size)
        }
        toolbar.menu.findItem(R.id.action_pick_toggle_all)?.setTitle(
            if (items.isNotEmpty() && selected.size == items.size) R.string.pick_deselect_all
            else R.string.pick_all,
        )
        adapter.notifyDataSetChanged()
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
            var summary: Crawler.PickedSummary? = null
            var err: String? = null
            try {
                val r = CrawlRunner.runPicked(ctx, listOf(Crawler.PickTask(bid, picIds))) { }
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

    // ---------- 网格 ----------

    private inner class GridAdapter : RecyclerView.Adapter<GridAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pick_pic, parent, false)
            if (cellPx > 0) {
                view.layoutParams = RecyclerView.LayoutParams(cellPx, cellPx)
            }
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], position)
        }

        override fun onViewRecycled(holder: Holder) {
            // 不复用前先掐掉还没回来的缩略图请求：单帖最多 18 格，翻来翻去会堆一串 Job
            holder.cancelLoad()
            super.onViewRecycled(holder)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val image = view.findViewById<ImageView>(R.id.pickImage)
            private val badge = view.findViewById<TextView>(R.id.pickBadge)
            private val scrim = view.findViewById<View>(R.id.pickScrim)
            private val check = view.findViewById<ImageView>(R.id.pickCheck)

            /** 当前格的缩略图请求；换绑/回收时取消，避免 Job 堆积与错位回填。 */
            private var thumbJob: Job? = null

            fun cancelLoad() {
                thumbJob?.cancel()
                thumbJob = null
            }

            fun bind(item: PicItem, position: Int) {
                image.setImageDrawable(null)
                image.contentDescription = getString(R.string.pick_pic_desc_fmt, position + 1)
                badge.visibility = if (item.isLive || item.isGif) View.VISIBLE else View.GONE
                badge.text = getString(if (item.isLive) R.string.pick_live_badge else R.string.pick_gif_badge)
                val checked = item.picId in selected
                scrim.visibility = if (checked) View.VISIBLE else View.GONE
                check.visibility = if (checked) View.VISIBLE else View.GONE

                // 远程缩略图：tag 防错位（快速滚动时旧请求回来不能盖到新格子）
                val key = item.thumbUrl
                image.tag = key
                thumbJob?.cancel()
                thumbJob = lifecycleScope.launch(Dispatchers.IO) {
                    val bmp = runCatching { RemoteThumb.load(item.thumbUrl) }.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (image.tag == key && bmp != null) {
                            @Suppress("UseKtx")
                            image.setImageDrawable(BitmapDrawable(null, bmp))
                        }
                    }
                }

                itemView.setOnClickListener {
                    if (selected.contains(item.picId)) selected.remove(item.picId) else selected.add(item.picId)
                    render()
                }
                itemView.setOnLongClickListener {
                    // 用实时位置而不是闭包里捕获的 position：整表重绑（勾选态刷新）
                    // 之后旧闭包的 position 会指到别的图，大图页打开错那张。
                    val at = bindingAdapterPosition
                    if (at != RecyclerView.NO_POSITION) showFullPreview(at)
                    true
                }
            }
        }
    }

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
