package com.wcm.checkin

import com.wcm.checkin.databinding.ActivityBrowsingBinding
import com.wcm.checkin.databinding.ItemBrowsePostBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import kotlinx.coroutines.Job
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
    private lateinit var binding: ActivityBrowsingBinding

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

    /**
     * 小图一格的边长 / 间距 / 列数（px）。
     *
     * v0.9.17：卡片里的图从「撑满整行的正方形、CENTER_CROP 裁一刀」改成
     * 「56dp 小图、整张缩进去」。用户反馈原来的图是一条被裁掉的长方形，
     * 压根看不出拍的是什么 —— 裁切是为了填满格子，而格子只有屏幕三分之一宽，
     * 宽图必被拦腰裁断。现在格子小、图片按比例缩放（FIT_CENTER），
     * 整张画面都在，细节靠点开大图看（[RemotePreviewActivity]）。
     *
     * 列数按可用宽度算（360dp 屏 5 列、窄屏 4 列、平板更多），见 [measureThumbs]。
     */
    private var thumbPx = 0
    private var thumbGap = 0
    private var thumbCols = 5

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitSelectMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityBrowsingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        toolbar = binding.browseToolbar
        etInput = binding.etBrowseInput
        recycler = binding.browseList
        tvEmpty = binding.tvBrowseEmpty
        loading = binding.browseLoading
        selectBar = binding.browseSelectBar
        tvSelected = binding.tvBrowseSelected
        btnDownload = binding.btnBrowseDownload
        tvCount = binding.tvBrowseCount
        tvHint = binding.tvBrowseHint
        chipOriginal = binding.chipBrowseOriginal
        chipPic = binding.chipBrowsePic
        targetsRow = binding.browseTargetsRow
        targetsScroll = binding.browseTargetsScroll

        val btnLoad = binding.btnBrowseLoad
        val btnExitSelect = binding.btnBrowseExitSelect

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
        recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> measureThumbs() }

        binding.browseRoot.let { root ->
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

        // 先给上令牌值：第一帧的 fillPics 不能因为「还没测量」而整块跳过
        thumbPx = resources.getDimensionPixelSize(R.dimen.browse_thumb_size)
        thumbGap = resources.getDimensionPixelSize(R.dimen.gallery_gutter)

        // 「哪些图已经抓过」的本地快照（v0.9.18）：索引解析在后台，读完刷一遍列表。
        // 没读完之前一律不标 —— 宁可不标，也不能给没抓过的图打勾。
        CrawlMarks.ensure(this) {
            if (!isFinishing && !isDestroyed && visible.isNotEmpty()) adapter.notifyDataSetChanged()
        }

        renderTargets()
        if (savedInstanceState == null) {
            // 全新进入：丢掉上一次遗留的进程级缓存，避免串页
            keptPosts = null
            keptUid = ""
            // 预填：从图片页点进来时若只有一个抓取目标，直接载入它
            val prefill = intent.getStringExtra(EXTRA_UID) ?: ""
            if (prefill.isNotEmpty()) {
                etInput.setText(prefill)
                startBrowse()
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
        outState.putString(KEY_INPUT, etInput.text?.toString().orEmpty())
        outState.putInt(
            KEY_SCROLL,
            (recycler.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0,
        )
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
        chipOriginal.applyChipStyle(filterOriginal)
        chipPic.applyChipStyle(filterPic)
        etInput.setText(saved.getString(KEY_INPUT) ?: "")

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
                selectBar.visibility = View.VISIBLE
                tvHint.text = getString(R.string.browse_select_mode_hint)
                renderSelectBar()
                adapter.notifyDataSetChanged()
            }
            val at = saved.getInt(KEY_SCROLL, 0)
            if (at > 0) recycler.scrollToPosition(at)
        } else if (savedUid.isNotEmpty()) {
            // 进程被杀：列表没了，按恢复出的 uid 重跑一次首屏
            etInput.setText(savedUid)
            startBrowse()
        }
    }

    /**
     * 量一次小图的列数。格子边长是固定令牌（56dp），只有列数随可用宽度变：
     * 卡片内宽 = 列表宽 - 页面左右留白 - 卡片左右内边距。
     *
     * 列数变了才重绑整表 —— 首次布局（recycler 宽度还是 0 时用屏幕宽兜底）
     * 算出的值与初始值相同时什么也不做，避免开屏就白刷一遍列表。
     */
    private fun measureThumbs() {
        val listW = if (recycler.width > 0) recycler.width else resources.displayMetrics.widthPixels
        val side = resources.getDimensionPixelSize(R.dimen.page_padding_horizontal)
        val cardPad = resources.getDimensionPixelSize(R.dimen.card_padding)
        val content = listW - side * 2 - cardPad * 2
        if (content <= 0 || thumbPx <= 0 || thumbGap < 0) return
        val cols = ((content + thumbGap) / (thumbPx + thumbGap)).coerceIn(3, 9)
        if (cols != thumbCols) {
            thumbCols = cols
            if (posts.isNotEmpty()) adapter.notifyDataSetChanged()
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
        val input = etInput.text?.toString()?.trim().orEmpty()
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
                    toolbar.subtitle = "$name · $resolved"
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
            loading.visibility = View.GONE
            if (!isFinishing && !isDestroyed) {
                toast(getString(R.string.browse_fetch_fail, msg))
                tvEmpty.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onListArrived() {
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
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        renderCount()
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
        val targets = CrawlSettings.crawlTargets(this)
        if (targets.isEmpty()) {
            targetsScroll.visibility = View.GONE
            return
        }
        targetsScroll.visibility = View.VISIBLE
        val padH = resources.getDimensionPixelSize(R.dimen.pill_padding_horizontal) * 2
        val padV = resources.getDimensionPixelSize(R.dimen.space_xs)
        // 间距直接取令牌：thumbGap 直到 onCreate 末尾才赋值，而 renderTargets() 在
        // 更早就跑完了（之后再没人调它），读字段会恒为 0。
        val gap = resources.getDimensionPixelSize(R.dimen.gallery_gutter)
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
                if (idx < targets.size - 1) lp.marginEnd = gap
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
        // submitting 必须参与：setSubmitting 先设 isEnabled = false，紧接着调本函数
        // 又被无条件赋回 true，提交中还能再点一次（第二次弹「正忙」）
        btnDownload.isEnabled = selectedBids.isNotEmpty() && !submitting
        btnDownload.text = getString(R.string.browse_download_fmt, selectedBids.size)
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
                    CrawlMarks.ensure(this@BrowsingActivity) { adapter.notifyDataSetChanged() }
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- 列表 ----------

    private inner class PostAdapter : RecyclerView.Adapter<PostAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemBrowsePostBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return Holder(binding)
        }

        override fun getItemCount(): Int = visible.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(visible[position])
        }

        /**
         * 缩略图只在卡片真正进入可见区时才发起，滑出即取消：
         * 滚动时是「一屏一屏、一格一格」地加载，而不是进页面就把所有卡片的
         * 所有格子一起排进队列。
         */
        override fun onViewAttachedToWindow(holder: Holder) {
            super.onViewAttachedToWindow(holder)
            holder.startThumbs()
        }

        override fun onViewDetachedFromWindow(holder: Holder) {
            holder.stopThumbs()
            super.onViewDetachedFromWindow(holder)
        }

        override fun onViewRecycled(holder: Holder) {
            holder.stopThumbs()
            super.onViewRecycled(holder)
        }

        inner class Holder(private val binding: ItemBrowsePostBinding) :
            RecyclerView.ViewHolder(binding.root) {
            private val meta = binding.tvBrowseMeta
            private val text = binding.tvBrowseText
            private val pics = binding.browsePics
            private val scrim = binding.browseScrim
            private val check = binding.browseCheck

            /** 当前卡的缩略图请求；换绑 / 滑出可见区时取消，避免排队请求堆积。 */
            private var thumbJob: Job? = null

            /** 本卡待加载的格子（按九宫格顺序）。 */
            private var pendingThumbs: List<Pair<ImageView, String>> = emptyList()

            /** 下次从第几格开始：滑出再滑回来要接着拉，不能从头把 9 格再压一遍。 */
            private var nextThumb = 0

            /** 请求代次：取消后旧循环即使还没跑到挂起点，也不会再发请求。 */
            private var thumbGen = 0

            /** 当前是否在可见区内（RecyclerView 的 attach 状态，与 bind 是两条时间线）。 */
            private var attached = false

            fun bind(post: WeiboPostApi.RawPost) {
                cancelThumbs()
                meta.text = buildString {
                    append(metaLine(post))
                }
                text.text = post.text.ifEmpty { "（无文字）" }
                fillPics(post)
                val checked = post.bid in selectedBids
                scrim.visibility = if (checked) View.VISIBLE else View.GONE
                check.visibility = if (checked) View.VISIBLE else View.GONE
                // bind 之后 RecyclerView 才会回调 attach；已经在可见区（筛选/勾选重绑）时直接开工
                if (attached) startThumbs()
            }

            private fun cancelThumbs() {
                thumbGen++
                thumbJob?.cancel()
                thumbJob = null
            }

            /** 按顺序逐张拉：9 格同时并发会让靠前的图反而最后出现。 */
            fun startThumbs() {
                attached = true
                if (thumbJob?.isActive == true) return
                if (nextThumb >= pendingThumbs.size) return
                val gen = ++thumbGen
                thumbJob = lifecycleScope.launch(Dispatchers.IO) {
                    while (true) {
                        // 换绑 / 滑出可见区之后，旧循环停在下一格就收工
                        if (gen != thumbGen) return@launch
                        val idx = nextThumb
                        if (idx >= pendingThumbs.size) return@launch
                        val (iv, url) = pendingThumbs[idx]
                        val bmp = RemoteThumb.load(url)
                        // 这一格真的结束了才前进：中途被取消时游标留在原地，
                        // 滑回来会重下它，而不是漏成一张永远空白的格子
                        nextThumb = idx + 1
                        if (bmp == null) continue
                        withContext(Dispatchers.Main) {
                            if (iv.tag == url) {
                                @Suppress("UseKtx")
                                iv.setImageDrawable(BitmapDrawable(null, bmp))
                            }
                        }
                    }
                }
            }

            fun stopThumbs() {
                attached = false
                cancelThumbs()
            }

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

            /** 右下角「已抓取」小勾：品牌实底圆 + 白勾，压在图片上（v0.9.18）。 */
            private fun doneBadge(ctx: Context): ImageView {
                val side = resources.getDimensionPixelSize(R.dimen.browse_thumb_badge)
                val inset = dp(2)
                return ImageView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        side, side, Gravity.BOTTOM or Gravity.END,
                    ).apply {
                        marginEnd = inset
                        bottomMargin = inset
                    }
                    setBackgroundResource(R.drawable.bg_cell_check)
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(getColor(R.color.brand_on))
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    contentDescription = getString(R.string.browse_done_badge)
                    isClickable = false
                }
            }

            /**
             * 小图行：每次 bind 重建（不搞视图复用），tag 防旧请求盖图。
             *
             * v0.9.17 起这里摆的是**整张缩进去的小图**（56dp、FIT_CENTER），
             * 不再是撑满整行的正方形裁切图：裁切必然把宽图拦腰截断，列表里看到的
             * 只是一条色带。小图点开才是大图（[RemotePreviewActivity]），
             * 多选态下点小图等同于点卡片（勾选整帖）。
             *
             * **这里只摆格子、登记待加载清单，不发起请求** —— 真正的下载由
             * [startThumbs] 在卡片进入可见区后按顺序一格一格来。
             */
            private fun fillPics(post: WeiboPostApi.RawPost) {
                pics.removeAllViews()
                val thumbs = post.thumbs
                if (thumbs.isEmpty() || thumbPx <= 0) {
                    pendingThumbs = emptyList()
                    nextThumb = 0
                    pics.visibility = View.GONE
                    return
                }
                pics.visibility = View.VISIBLE
                // 列数写在容器上：子 view 不指定行列，GridLayout 按列数自动换行
                pics.columnCount = thumbCols
                val ctx = pics.context
                val tiles = thumbs.take(MAX_TILES)
                val jobs = ArrayList<Pair<ImageView, String>>(tiles.size)
                tiles.forEachIndexed { i, url ->
                    // 一格 = 圆角底托 + 图片 +（抓过的）右下角小勾。用 FrameLayout 包一层
                    // 是因为 ImageView 装不下子视图，而勾要压在图上面（v0.9.18）
                    val cell = FrameLayout(ctx).apply {
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = thumbPx
                            height = thumbPx
                            marginEnd = if (i % thumbCols == thumbCols - 1) 0 else thumbGap
                            if (i >= thumbCols) topMargin = thumbGap
                        }
                        setBackgroundResource(R.drawable.bg_thumb)
                        clipToOutline = true
                        setOnClickListener {
                            // 多选态：点图就是点卡片（勾选/取消整帖），不能跳去大图
                            if (selectMode) itemView.performClick() else openPreview(post, i)
                        }
                    }
                    val iv = ImageView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = getString(R.string.browse_pic_desc_fmt, i + 1)
                    }
                    iv.tag = url
                    cell.addView(iv)
                    if (CrawlMarks.hasPic(WeiboImageUrl.pidFromImageUrl(url))) cell.addView(doneBadge(ctx))
                    pics.addView(cell)
                    jobs.add(iv to url)
                }
                pendingThumbs = jobs
                nextThumb = 0
                val total = maxOf(thumbs.size, post.picNum)
                if (total > tiles.size) {
                    val at = tiles.size
                    val more = TextView(ctx).apply {
                        text = getString(R.string.browse_more_pics_fmt, total - tiles.size)
                        setTextAppearance(R.style.TextAppearance_Wcm_Xs)
                        setTextColor(getColor(R.color.ink_3))
                        gravity = Gravity.CENTER
                        setBackgroundResource(R.drawable.bg_thumb)
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = thumbPx
                            height = thumbPx
                            marginEnd = if (at % thumbCols == thumbCols - 1) 0 else thumbGap
                            if (at >= thumbCols) topMargin = thumbGap
                        }
                        setOnClickListener { PostPickActivity.start(this@BrowsingActivity, post.bid) }
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
