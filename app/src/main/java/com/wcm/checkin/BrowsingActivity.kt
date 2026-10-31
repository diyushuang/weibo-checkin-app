package com.wcm.checkin

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 浏览博主页（v0.9.11）：博主微博预览 + 单图 / 批量勾选抓取。
 *
 * - **列表**：`fetchUserPosts`（m 站 107603，必须账号身份）翻页加载，
 *   请求走 [WeiboThrottle]；卡片展示正文摘要 + 九宫格 orj360 缩略图；
 * - **筛选**：「只看原创」（滤掉 `retweeted_status`）与「只看有图」两个独立开关，
 *   默认全开 —— 对齐「批量勾选博主原创微博中的多张图」的需求口径；
 * - **多选**：长按卡片进入，点按勾选整帖；提交时 picIds 传 null = 整帖全抓，
 *   想在某帖里精选就退出多选点进它的选图页；
 * - **提交**：[AppScope] 启动 + [CrawlRunner.runPicked]，与增量抓取共用闸门/索引/命名；
 * - 浏览本身（列表 / 缩略图）只读、不占抓取闸门，可与后台增量任务并行。
 *
 * 本页刻意全列表 notifyDataSetChanged（lint: NotifyDataSetChanged）：
 * 筛选开关 / 多选切换 / 翻页插入都会改变「可见集合」本身，精确通知的复杂度
 * 配不上一页十来条卡片的量级 —— 与图库页（万级文件）的取舍不同。
 */
@Suppress("NotifyDataSetChanged")
class BrowsingActivity : AppCompatActivity() {

    private val posts = ArrayList<WeiboApi.RawPost>()
    private val visible = ArrayList<WeiboApi.RawPost>()
    private val selectedBids = LinkedHashSet<String>()
    private val adapter = PostAdapter()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var etInput: TextInputEditText
    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var loading: ProgressBar
    private lateinit var selectBar: View
    private lateinit var tvSelected: TextView
    private lateinit var btnDownload: MaterialButton
    private lateinit var tvCount: TextView
    private lateinit var tvHint: TextView
    private lateinit var chipOriginal: TextView
    private lateinit var chipPic: TextView
    private lateinit var targetsRow: LinearLayout
    private lateinit var targetsScroll: HorizontalScrollView

    private var uid = ""
    private var page = 0
    private var hasMore = true
    private var listBusy = false
    private var filterOriginal = true
    private var filterPic = true
    private var selectMode = false

    /** 九宫格格子边长（px），按列表可用宽度 / 3 计算。0 = 未测量。 */
    private var cellPx = 0
    private var cellGap = 0

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitSelectMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browsing)

        toolbar = findViewById(R.id.browseToolbar)
        etInput = findViewById(R.id.etBrowseInput)
        recycler = findViewById(R.id.browseList)
        tvEmpty = findViewById(R.id.tvBrowseEmpty)
        loading = findViewById(R.id.browseLoading)
        selectBar = findViewById(R.id.browseSelectBar)
        tvSelected = findViewById(R.id.tvBrowseSelected)
        btnDownload = findViewById(R.id.btnBrowseDownload)
        tvCount = findViewById(R.id.tvBrowseCount)
        tvHint = findViewById(R.id.tvBrowseHint)
        chipOriginal = findViewById(R.id.chipBrowseOriginal)
        chipPic = findViewById(R.id.chipBrowsePic)
        targetsRow = findViewById(R.id.browseTargetsRow)
        targetsScroll = findViewById(R.id.browseTargetsScroll)

        val btnLoad = findViewById<MaterialButton>(R.id.btnBrowseLoad)
        val btnExitSelect = findViewById<MaterialButton>(R.id.btnBrowseExitSelect)

        toolbar.setNavigationOnClickListener { finish() }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || !hasMore || listBusy) return
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= visible.size - 3) loadMore()
            }
        })
        recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> measureCells() }

        findViewById<View>(R.id.browseRoot)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                root.updatePadding(top = bars.top, bottom = bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }

        chipOriginal.setOnClickListener {
            filterOriginal = !filterOriginal
            chipOriginal.applyChipStyle(filterOriginal)
            applyFilter()
        }
        chipPic.setOnClickListener {
            filterPic = !filterPic
            chipPic.applyChipStyle(filterPic)
            applyFilter()
        }
        btnLoad.setOnClickListener { startBrowse() }
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                startBrowse(); true
            } else {
                false
            }
        }
        btnDownload.setOnClickListener { submitSelected() }
        btnExitSelect.setOnClickListener { exitSelectMode() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        chipOriginal.isSelected = filterOriginal
        chipPic.isSelected = filterPic
        chipOriginal.applyChipStyle(filterOriginal)
        chipPic.applyChipStyle(filterPic)

        renderTargets()
        // 预填：从图片页点进来时若只有一个抓取目标，直接载入它
        val prefill = intent.getStringExtra(EXTRA_UID) ?: ""
        if (prefill.isNotEmpty()) {
            etInput.setText(prefill)
            startBrowse()
        }
    }

    private fun measureCells() {
        val w = recycler.width
        if (w <= 0) return
        cellGap = resources.getDimensionPixelSize(R.dimen.gallery_gutter)
        val side = (w - resources.getDimensionPixelSize(R.dimen.page_padding_horizontal) * 2 - cellGap * 2) / 3
        if (side > 0 && side != cellPx) {
            cellPx = side
            if (posts.isNotEmpty()) adapter.notifyDataSetChanged()
        }
    }

    // ---------- 载入 ----------

    private fun startBrowse() {
        val input = etInput.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            toast(getString(R.string.browse_need_uid))
            return
        }
        if (listBusy) {
            toast(getString(R.string.busy))
            return
        }
        val ctx = applicationContext
        listBusy = true
        loading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        posts.clear()
        visible.clear()
        selectedBids.clear()
        page = 0
        hasMore = true
        adapter.notifyDataSetChanged()
        tvHint.text = getString(R.string.browse_select_hint)

        AppScope.launch {
            try {
                val resolved = WeiboApi.resolveUid(input)
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
                val cookie = Crawler.pickCrawlCookie(accounts) { }
                    ?: run {
                        failList(getString(R.string.browse_need_cookie))
                        return@launch
                    }
                // 昵称：游客身份优先，取不到用 UID 兜底
                val name = WeiboApi.fetchUserName(cookie, resolved).ifEmpty { resolved }
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    toolbar.subtitle = "$name · $resolved"
                }
                // 首屏拉到「可见 ≥ 6」或 3 页为止：前几条常是置顶/转发，一页筛完可能只剩两三张卡
                var fetched = 0
                while (hasMore && fetched < 3 && visibleCount() < 6) {
                    if (!fetchOnePage(cookie)) break
                    fetched++
                }
                withContext(Dispatchers.Main) { onListArrived() }
            } catch (e: Exception) {
                failList(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** 拉一页并入库；返回是否拉到了内容。网络失败抛给上层。 */
    private suspend fun fetchOnePage(cookie: String): Boolean {
        WeiboThrottle.await(KEY_BROWSE_PAGE, 700)
        val (_, list) = WeiboApi.fetchUserPosts(cookie, uid, page + 1)
        if (list.isEmpty()) {
            hasMore = false
            return false
        }
        page++
        posts.addAll(list)
        applyFilter()
        return true
    }

    private fun visibleCount(): Int {
        // applyFilter 里已同步 visible；这里只是读数
        return visible.size
    }

    private suspend fun failList(msg: String) {
        withContext(Dispatchers.Main) {
            listBusy = false
            loading.visibility = View.GONE
            if (!isFinishing && !isDestroyed) {
                toast(getString(R.string.browse_fetch_fail, msg))
                tvEmpty.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private suspend fun onListArrived() {
        listBusy = false
        loading.visibility = View.GONE
        if (isFinishing || isDestroyed) return
        adapter.notifyDataSetChanged()
        // 空态要区分两种：还没载入过（引导文案）vs 载入了但筛完是 0 条（换 uid 再试）
        tvEmpty.text = if (posts.isEmpty()) getString(R.string.browse_no_posts) else getString(R.string.browse_empty)
        tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        renderCount()
    }

    private fun loadMore() {
        if (listBusy || !hasMore || uid.isEmpty()) return
        val ctx = applicationContext
        listBusy = true
        AppScope.launch {
            try {
                val accounts = AccountStore.load(ctx)
                val cookie = Crawler.pickCrawlCookie(accounts) { }
                if (cookie == null) {
                    failList(getString(R.string.browse_need_cookie))
                    return@launch
                }
                fetchOnePage(cookie)
                withContext(Dispatchers.Main) { onListArrived() }
            } catch (e: Exception) {
                hasMore = false
                failList(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---------- 筛选与渲染 ----------

    private fun applyFilter() {
        visible.clear()
        for (p in posts) {
            if (filterOriginal && p.isRetweet) continue
            if (filterPic && p.picNum <= 0 && p.thumbs.isEmpty()) continue
            visible.add(p)
        }
        if (!listBusy && !isDestroyed) {
            runOnUiThread {
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
                renderCount()
            }
        }
    }

    private fun renderCount() {
        tvCount.text = when {
            posts.isEmpty() -> ""
            hasMore -> getString(R.string.browse_load_more)
            else -> getString(R.string.browse_no_more)
        }
    }

    private fun renderTargets() {
        targetsRow.removeAllViews()
        val targets = AccountStore.crawlTargets(this)
        if (targets.isEmpty()) {
            targetsScroll.visibility = View.GONE
            return
        }
        targetsScroll.visibility = View.VISIBLE
        val padH = resources.getDimensionPixelSize(R.dimen.pill_padding_horizontal) * 2
        val padV = resources.getDimensionPixelSize(R.dimen.space_xs)
        targets.forEachIndexed { idx, t ->
            val chip = TextView(this).apply {
                text = t.name.ifEmpty { "UID ${t.uid}" }
                setTextAppearance(R.style.TextAppearance_Wcm_Xs)
                setPadding(padH, padV, padH, padV)
                background = AppCompatResources.getDrawable(context, R.drawable.bg_pill_line)
                setTextColor(getColor(R.color.ink_2))
                minHeight = resources.getDimensionPixelSize(R.dimen.pill_height)
                gravity = Gravity.CENTER
                setOnClickListener {
                    etInput.setText(t.uid)
                    startBrowse()
                }
            }
            targetsRow.addView(chip)
            (chip.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                if (idx < targets.size - 1) lp.marginEnd = cellGap
            }
        }
    }

    // ---------- 多选 ----------

    private fun enterSelectMode(firstBid: String) {
        selectMode = true
        selectedBids.clear()
        selectedBids.add(firstBid)
        backCallback.isEnabled = true
        selectBar.visibility = View.VISIBLE
        tvHint.text = getString(R.string.browse_select_mode_hint)
        renderSelectBar()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectMode() {
        selectMode = false
        selectedBids.clear()
        backCallback.isEnabled = false
        selectBar.visibility = View.GONE
        tvHint.text = getString(R.string.browse_select_hint)
        adapter.notifyDataSetChanged()
    }

    private fun renderSelectBar() {
        tvSelected.text = getString(R.string.browse_selected_fmt, selectedBids.size)
        btnDownload.isEnabled = selectedBids.isNotEmpty()
        btnDownload.text = getString(R.string.browse_download_fmt, selectedBids.size)
    }

    private fun submitSelected() {
        if (selectedBids.isEmpty()) {
            toast(getString(R.string.browse_none_selected))
            return
        }
        val tasks = visible.filter { it.bid in selectedBids }.map { Crawler.PickTask(it.bid, null) }
        val ctx = applicationContext
        setSubmitting(true)
        AppScope.launch {
            var busy = false
            var summary: Crawler.PickedSummary? = null
            var err: String? = null
            try {
                val r = CrawlRunner.runPicked(ctx, tasks) { }
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
                exitSelectMode()
            }
        }
    }

    private var submitting = false

    private fun setSubmitting(v: Boolean) {
        submitting = v
        btnDownload.isEnabled = selectedBids.isNotEmpty() && !v
        renderSelectBar()
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---------- 列表 ----------

    private inner class PostAdapter : RecyclerView.Adapter<PostAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_browse_post, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = visible.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(visible[position])
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val meta = view.findViewById<TextView>(R.id.tvBrowseMeta)
            private val text = view.findViewById<TextView>(R.id.tvBrowseText)
            private val pics = view.findViewById<GridLayout>(R.id.browsePics)
            private val scrim = view.findViewById<View>(R.id.browseScrim)
            private val check = view.findViewById<ImageView>(R.id.browseCheck)

            fun bind(post: WeiboApi.RawPost) {
                meta.text = buildString {
                    append(metaLine(post))
                }
                text.text = post.text.ifEmpty { "（无文字）" }
                fillPics(post)
                val checked = post.bid in selectedBids
                scrim.visibility = if (checked) View.VISIBLE else View.GONE
                check.visibility = if (checked) View.VISIBLE else View.GONE
            }

            private fun metaLine(post: WeiboApi.RawPost): String {
                val t = WeiboApi.parseWeiboTime(post.createdAt)
                val whenStr = t?.let { "${it.first.substring(5)} ${it.second.substring(0, 5)}" } ?: "未知时间"
                val tags = buildList {
                    if (post.pinned) add(getString(R.string.browse_meta_pinned))
                    if (post.isRetweet) {
                        add(getString(R.string.browse_meta_retweet))
                    } else if (post.picNum > 0) {
                        add(getString(R.string.browse_meta_pics_fmt, post.picNum))
                    } else {
                        add(getString(R.string.browse_meta_no_pic))
                    }
                }
                return "$whenStr · ${tags.joinToString(" · ")}"
            }

            /** 九宫格：每次 bind 重建（不搞视图复用），tag 防旧请求盖图。 */
            private fun fillPics(post: WeiboApi.RawPost) {
                pics.removeAllViews()
                val thumbs = post.thumbs
                if (thumbs.isEmpty() || cellPx <= 0) {
                    pics.visibility = if (thumbs.isEmpty()) View.GONE else View.VISIBLE
                    return
                }
                pics.visibility = View.VISIBLE
                val ctx = pics.context
                thumbs.take(9).forEachIndexed { i, url ->
                    val iv = ImageView(ctx).apply {
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = cellPx
                            height = cellPx
                            if (i % 3 != 2) marginEnd = cellGap
                            if (i < 6) bottomMargin = cellGap
                            rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = getString(R.string.browse_pic_desc_fmt, i + 1)
                    }
                    iv.tag = url
                    lifecycleScope.launch(Dispatchers.IO) {
                        val bmp = runCatching { RemoteThumb.load(url) }.getOrNull()
                        withContext(Dispatchers.Main) {
                            if (iv.tag == url && bmp != null) {
                                @Suppress("UseKtx")
                                iv.setImageDrawable(BitmapDrawable(null, bmp))
                            }
                        }
                    }
                    pics.addView(iv)
                }
                if (thumbs.size > 9 || post.picNum > 9) {
                    val more = TextView(ctx).apply {
                        text = getString(R.string.browse_more_pics_fmt, maxOf(thumbs.size, post.picNum) - 9)
                        setTextAppearance(R.style.TextAppearance_Wcm_Xs)
                        setTextColor(getColor(R.color.ink_3))
                        gravity = Gravity.CENTER
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = GridLayout.LayoutParams.WRAP_CONTENT
                            height = GridLayout.LayoutParams.WRAP_CONTENT
                            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 3f)
                        }
                    }
                    pics.addView(more)
                }
            }

            init {
                itemView.setOnClickListener {
                    val post = visible.getOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                    if (selectMode) {
                        if (post.bid in selectedBids) selectedBids.remove(post.bid)
                        else selectedBids.add(post.bid)
                        renderSelectBar()
                        notifyItemChanged(bindingAdapterPosition)
                    } else {
                        PostPickActivity.start(this@BrowsingActivity, post.bid)
                    }
                }
                itemView.setOnLongClickListener {
                    val post = visible.getOrNull(bindingAdapterPosition) ?: return@setOnLongClickListener true
                    if (!selectMode) enterSelectMode(post.bid)
                    true
                }
            }
        }
    }

    companion object {
        const val EXTRA_UID = "uid"
        private const val KEY_BROWSE_PAGE = "browse-page"

        fun start(ctx: Context, uid: String = "") {
            ctx.startActivity(
                Intent(ctx, BrowsingActivity::class.java).putExtra(EXTRA_UID, uid),
            )
        }
    }
}
