package com.wcm.checkin

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图库（v0.4.0 建，v0.9.0 增强）：相册目录内图片的网格浏览。
 *
 * v0.9.0 新增：
 * - **筛选 / 搜索 / 排序 / 分组**：类型（静图/动图/视频）、作者、关键词、五种排序，
 *   按日期或按博主分组时插入跨行分组头；
 * - **多选批处理**：长按进入多选，批量分享 / 批量删除（删除同步回退抓取索引）；
 * - 缩略图加载改成「每格一个可取消的 Job」，快速滑动不再堆积解码。
 *
 * 所有筛选/排序/分组都是 [GalleryFiles] 里的纯函数，这里只负责把结果摆到屏幕上；
 * 文件名解析只在 [GalleryFiles.index] 跑一次（IO 线程），滑动时不再重复解析。
 */
class GalleryBrowseActivity : AppCompatActivity() {

    private lateinit var treeUriRaw: String
    private lateinit var treeUri: Uri
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var emptyAction: View
    private lateinit var filtersRow: LinearLayout
    private lateinit var searchRow: View
    private lateinit var searchInput: EditText
    private lateinit var selectBar: View
    private lateinit var tvSelected: TextView
    private val gridAdapter = GridAdapter()

    /** 目录里的全部行（已解析文件名），筛选/排序都基于它做内存运算。 */
    private var allRows: List<GalleryFiles.Row> = emptyList()

    /** 当前筛选后、按当前顺序的可见行（批量操作与预览定位都用它）。 */
    private var shownRows: List<GalleryFiles.Row> = emptyList()

    private var filter = GalleryFiles.Filter()
    private var sort = GalleryFiles.SortOrder.NEWEST
    private var groupMode = GroupMode.DATE
    private var selectMode = false
    private val selected = LinkedHashSet<String>()

    private var loadJob: Job? = null

    /** 单元格边长（px）：按列宽算成正方形，平板/横屏不再拉成细长条。0 = 还没测量。 */
    private var cellSizePx = 0

    /** 已排队的尺寸重算（防止同一个布局帧内重复 post）。 */
    private var metricsScheduled = false

    private enum class GroupMode { NONE, DATE, AUTHOR }

    /** 网格里的两种行：跨行分组头 / 图片格。 */
    private sealed interface GridItem {
        data class Header(val key: String, val title: String, val count: Int) : GridItem
        data class Cell(val row: GalleryFiles.Row) : GridItem
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitSelectMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 边到边：图库是沉浸浏览的场景，工具栏底色延伸到状态栏下方更自然
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery_browse)

        treeUriRaw = intent.getStringExtra(EXTRA_TREE) ?: ""
        if (treeUriRaw.isEmpty()) {
            finish()
            return
        }
        treeUri = treeUriRaw.toUri()

        toolbar = findViewById(R.id.galleryToolbar)
        recycler = findViewById(R.id.galleryGrid)
        emptyView = findViewById(R.id.galleryEmpty)
        emptyText = findViewById(R.id.tvGalleryEmpty)
        emptyAction = findViewById(R.id.btnGalleryEmptyAction)
        filtersRow = findViewById(R.id.galleryFilters)
        searchRow = findViewById(R.id.searchRow)
        searchInput = findViewById(R.id.searchInput)
        selectBar = findViewById(R.id.selectBar)
        tvSelected = findViewById(R.id.tvSelectedCount)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.gallery_browser)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> { load(); true }
                R.id.action_search -> { toggleSearch(); true }
                R.id.action_select -> { toggleSelectMode(); true }
                R.id.action_group -> {
                    showGroupMenu(toolbar.findViewById<View>(R.id.action_group) ?: toolbar)
                    true
                }
                else -> false
            }
        }
        recycler.layoutManager = buildLayoutManager(spanCount())
        recycler.adapter = gridAdapter
        // 列数与单元格高度按实际宽度算（横屏/平板自适应），不再写死 120dp 高度。
        // 注意：回调里只"排队"，真正干活在 applyGridMetrics（post 出去执行）——
        // 绝不能在布局回调里同步换 layoutManager / notifyDataSetChanged，
        // 否则"布局变化 -> 换 span -> 再触发布局"存在自激风险。
        recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleGridMetrics() }
        emptyAction.setOnClickListener { finish() }

        findViewById<View>(R.id.btnGallerySort).setOnClickListener { showSortMenu(it) }
        findViewById<View>(R.id.btnSearchClose).setOnClickListener { toggleSearch(forceOpen = false) }
        findViewById<View>(R.id.btnSelectCancel).setOnClickListener { exitSelectMode() }
        findViewById<View>(R.id.btnSelectShare).setOnClickListener { batchShare() }
        findViewById<View>(R.id.btnSelectDelete).setOnClickListener { confirmBatchDelete() }
        searchInput.addTextChangedListenerText { text ->
            filter = filter.copy(query = text)
            rebuild()
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
        applyInsets()
        load()
    }

    // ========================= 尺寸 / insets =========================

    /** 把尺寸重算排到下一次消息循环，同一帧内只排一次。 */
    private fun scheduleGridMetrics() {
        if (metricsScheduled) return
        metricsScheduled = true
        recycler.post {
            metricsScheduled = false
            applyGridMetrics()
        }
    }

    /**
     * 按当前宽度决定单元格边长（只在**确实变化**时才动布局）。
     *
     * - 列数取资源 `@integer/gallery_columns`：手机 3 / 横屏 4 / 平板 4 / 桌面 5，
     *   由资源限定符自动切换（换屏、折叠屏展开、分屏都会触发配置变更）；
     * - 单元格边长 = (可用宽度 - 间隙 × (列数-1)) / 列数，始终正方形，
     *   避免固定高度在大屏上被拉成细长条；
     * - 双重守卫（span 未变 且 尺寸未变）让它一次收敛，不会来回抖。
     */
    private fun applyGridMetrics() {
        val width = recycler.width - recycler.paddingStart - recycler.paddingEnd
        if (width <= 0) return
        val span = spanCount()
        val gutter = gutterPx()
        val size = (width - gutter * (span - 1)) / span
        if (size <= 0) return
        val manager = recycler.layoutManager as? GridLayoutManager
        val spanChanged = manager == null || manager.spanCount != span
        if (!spanChanged && size == cellSizePx) return
        cellSizePx = size
        if (spanChanged) recycler.layoutManager = buildLayoutManager(span)
        // 单元格边长在 onBindViewHolder 里落到 LayoutParams 上，所以这里只要让所有格子重绑一次；
        // 不用 notifyDataSetChanged：条目身份没变，逐条 notifyItemRangeChanged 就能让
        // RecyclerView 保留动画与滚动位置，而不是整表重建。
        gridAdapter.notifyItemRangeChanged(0, gridAdapter.itemCount)
    }

    /** 分组头要占满整行，所以 spanSize 交给 lookup 判断 */
    private fun buildLayoutManager(span: Int): GridLayoutManager =
        GridLayoutManager(this, span).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (gridAdapter.isHeader(position)) span else 1

                override fun getSpanIndex(position: Int, spanCount: Int): Int =
                    if (gridAdapter.isHeader(position)) 0 else super.getSpanIndex(position, spanCount)
            }
        }

    /** 列数：由 values / values-land / values-sw600dp / values-w840dp 的 integers 决定。 */
    private fun spanCount(): Int = resources.getInteger(R.integer.gallery_columns)

    /** 网格间隙（px）：列数越多，间隙越大，视觉密度才均衡。 */
    private fun gutterPx(): Int = resources.getDimensionPixelSize(R.dimen.gallery_gutter)

    private fun applyInsets() {
        val root = findViewById<View>(R.id.galleryRoot)
        val bottomBar = findViewById<View>(R.id.selectBar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // 上边给状态栏/挖孔，左右给横屏侧挖孔或手势区
            root.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            recycler.updatePadding(bottom = bars.bottom)
            bottomBar.updatePadding(bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    // ========================= 数据加载 =========================

    private fun load() {
        val resolver = contentResolver
        loadJob?.cancel()
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            val rows = try {
                GalleryFiles.index(GalleryFiles.listImages(resolver, treeUri))
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (rows == null) {
                    allRows = emptyList()
                    shownRows = emptyList()
                    gridAdapter.submit(emptyList())
                    showEmptyState(getString(R.string.gallery_read_error))
                    toolbar.subtitle = ""
                    return@withContext
                }
                allRows = rows
                rebuild()
            }
        }
    }

    /** 过滤 → 排序 → 分组 → 摊平成「分组头 + 格子」的扁平列表。 */
    private fun rebuild() {
        val filtered = GalleryFiles.applyFilter(allRows, filter)
        val sorted = GalleryFiles.sortRows(filtered, sort)
        shownRows = sorted

        val items = ArrayList<GridItem>()
        when (groupMode) {
            GroupMode.NONE -> sorted.forEach { items.add(GridItem.Cell(it)) }
            GroupMode.DATE -> GalleryFiles.groupByDate(sorted).forEach { group ->
                items.add(GridItem.Header(group.key, titleOf(group), group.count))
                group.rows.forEach { items.add(GridItem.Cell(it)) }
            }
            GroupMode.AUTHOR -> GalleryFiles.groupByLabel(sorted).forEach { group ->
                items.add(GridItem.Header(group.key, titleOf(group), group.count))
                group.rows.forEach { items.add(GridItem.Cell(it)) }
            }
        }
        gridAdapter.submit(items)
        renderFilters()

        toolbar.subtitle = if (sorted.size == allRows.size) {
            getString(R.string.gallery_count_fmt, allRows.size)
        } else {
            getString(R.string.gallery_summary_fmt, allRows.size, sorted.size)
        }

        when {
            allRows.isEmpty() -> showEmptyState(getString(R.string.gallery_empty))
            sorted.isEmpty() -> showEmptyState(getString(R.string.gallery_filter_empty))
            else -> {
                emptyView.visibility = View.GONE
                recycler.visibility = View.VISIBLE
            }
        }
        // 选中态里被筛掉的图不应该还算"已选"
        if (selectMode) {
            val visible = shownRows.map { it.uri }.toSet()
            selected.retainAll(visible)
            if (selected.isEmpty()) {
                exitSelectMode()
            } else {
                updateSelectUi()
            }
        }
    }

    private fun showEmptyState(message: String) {
        emptyView.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        emptyText.text = message
        // 目录空 / 读失败时给「去配置抓取目标」的出口；仅筛选无结果时不给（会误导）
        emptyAction.visibility =
            if (allRows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun titleOf(group: GalleryFiles.Group): String {
        val title = if (group.key == GalleryFiles.UNKNOWN_KEY) {
            getString(R.string.gallery_group_other)
        } else {
            group.title
        }
        return getString(R.string.gallery_group_title_fmt, title, group.count)
    }

    // ========================= 筛选条 / 排序菜单 =========================

    private fun renderFilters() {
        filtersRow.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val kinds = listOf(
            GalleryFiles.KindFilter.ALL to getString(R.string.gallery_filter_all),
            GalleryFiles.KindFilter.STILL to getString(R.string.gallery_filter_still),
            GalleryFiles.KindFilter.GIF to getString(R.string.gallery_filter_gif),
            GalleryFiles.KindFilter.MOV to getString(R.string.gallery_filter_mov),
        )
        kinds.forEach { (kind, label) ->
            val chip = inflater.inflate(R.layout.item_chip, filtersRow, false) as TextView
            chip.text = label
            paintChip(chip, filter.kind == kind && filter.label.isEmpty())
            chip.setOnClickListener {
                filter = filter.copy(kind = kind, label = "")
                rebuild()
            }
            filtersRow.addView(chip)
        }
        // 作者 pill：只列拍得最多的前 6 个，避免筛选条无限长
        GalleryFiles.labelTabs(allRows).take(6).forEach { (label, count) ->
            val chip = inflater.inflate(R.layout.item_chip, filtersRow, false) as TextView
            chip.text = getString(R.string.gallery_author_chip_fmt, label, count)
            paintChip(chip, filter.label == label)
            chip.setOnClickListener {
                filter = filter.copy(label = if (filter.label == label) "" else label)
                rebuild()
            }
            filtersRow.addView(chip)
        }
    }

    private fun paintChip(chip: TextView, selected: Boolean) {
        if (selected) {
            chip.setBackgroundResource(R.drawable.bg_pill_brand)
            chip.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.brand_strong))
        } else {
            chip.setBackgroundResource(R.drawable.bg_pill_line)
            chip.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.ink_2))
        }
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val sorts = listOf(
            GalleryFiles.SortOrder.NEWEST to getString(R.string.gallery_sort_newest),
            GalleryFiles.SortOrder.OLDEST to getString(R.string.gallery_sort_oldest),
            GalleryFiles.SortOrder.NAME_ASC to getString(R.string.gallery_sort_name),
            GalleryFiles.SortOrder.SIZE_DESC to getString(R.string.gallery_sort_size_desc),
            GalleryFiles.SortOrder.SIZE_ASC to getString(R.string.gallery_sort_size_asc),
        )
        sorts.forEachIndexed { index, (order, label) ->
            popup.menu.add(0, 100 + index, index, label).isChecked = order == sort
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            val order = sorts.getOrNull(item.itemId - 100)?.first ?: return@setOnMenuItemClickListener false
            sort = order
            rebuild()
            recycler.scrollToPosition(0)
            true
        }
        popup.show()
    }

    /** 分组方式：不分组 / 按日期 / 按博主（默认按日期，组头给出每组张数）。 */
    private fun showGroupMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val modes = listOf(
            GroupMode.DATE to getString(R.string.gallery_group_by_date),
            GroupMode.AUTHOR to getString(R.string.gallery_group_by_author),
            GroupMode.NONE to getString(R.string.gallery_group_none),
        )
        modes.forEachIndexed { index, (mode, label) ->
            popup.menu.add(0, 200 + index, index, label).isChecked = mode == groupMode
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            val mode = modes.getOrNull(item.itemId - 200)?.first
                ?: return@setOnMenuItemClickListener false
            groupMode = mode
            rebuild()
            recycler.scrollToPosition(0)
            true
        }
        popup.show()
    }

    private fun toggleSearch(forceOpen: Boolean? = null) {        val open = forceOpen ?: (searchRow.visibility != View.VISIBLE)
        searchRow.visibility = if (open) View.VISIBLE else View.GONE
        if (open) {
            searchInput.requestFocus()
            searchInput.post {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        } else if (searchInput.text.isNotEmpty()) {
            searchInput.setText("")
        }
    }

    // ========================= 预览 =========================

    private fun openPreview(row: GalleryFiles.Row) {
        val index = shownRows.indexOfFirst { it.uri == row.uri }
        // 传文件名：图库页可能已筛选/排序，预览页的"全量列表"下标与之对不上
        ImagePreviewActivity.start(this, treeUriRaw, index.coerceAtLeast(0), row.name)
    }

    // ========================= 多选 =========================

    private fun toggleSelectMode() {
        if (selectMode) exitSelectMode() else enterSelectMode(null)
    }

    private fun enterSelectMode(first: GalleryFiles.Row?) {
        selectMode = true
        backCallback.isEnabled = true
        selectBar.visibility = View.VISIBLE
        first?.let { selected.add(it.uri) }
        updateSelectUi()
        // 多选态会改变**每一格**的外观（遮罩 + 勾选圈），所以整段重绑是对的；
        // 但条目集合没变，用范围刷新而不是整表重建，滚动位置与缩略图缓存都保得住。
        gridAdapter.notifyItemRangeChanged(0, gridAdapter.itemCount)
    }

    private fun exitSelectMode() {
        if (!selectMode) return
        selectMode = false
        selected.clear()
        backCallback.isEnabled = false
        selectBar.visibility = View.GONE
        toolbar.title = getString(R.string.gallery_browse_title)
        gridAdapter.notifyItemRangeChanged(0, gridAdapter.itemCount)
    }

    private fun toggleSelection(row: GalleryFiles.Row) {
        if (!selected.remove(row.uri)) selected.add(row.uri)
        if (selected.isEmpty()) {
            exitSelectMode()
        } else {
            updateSelectUi()
            // 只有被点的那一格变了（以及底栏计数），不需要惊动整表
            val pos = gridAdapter.positionOf(row.uri)
            if (pos >= 0) gridAdapter.notifyItemChanged(pos)
        }
    }

    private fun updateSelectUi() {
        if (!selectMode) return
        toolbar.title = getString(R.string.gallery_selected_fmt, selected.size)
        tvSelected.text = getString(R.string.gallery_selected_fmt, selected.size)
    }

    private fun pickedRows(): List<GalleryFiles.Row> =
        shownRows.filter { selected.contains(it.uri) }

    private fun batchShare() {
        val rows = pickedRows()
        if (rows.isEmpty()) {
            toast(getString(R.string.gallery_share_empty))
            return
        }
        val uris = ArrayList<Uri>(rows.map { it.uri.toUri() })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            // content URI 批量授权：flag + clipData 双保险（chooser 会重建 intent）
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "images", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.gallery_batch_share)))
        } catch (e: Exception) {
            toast(getString(R.string.preview_share_fail))
        }
    }

    private fun confirmBatchDelete() {
        val rows = pickedRows()
        if (rows.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.gallery_batch_delete_title, rows.size))
            .setMessage(R.string.gallery_batch_delete_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.gallery_batch_delete) { _, _ -> batchDelete(rows) }
            .show()
    }

    /**
     * 批量删除图片。
     *
     * 顺序铁律与单图删除一致：**先物理删除成功，再回退索引**。
     * 反过来一旦删除失败，索引会以为丢了而重下，文件其实还在 → L1 又认领回来 → 死循环。
     * 串行执行（不并发 deleteDocument），索引只开一次，全部处理完再关。
     */
    private fun batchDelete(rows: List<GalleryFiles.Row>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val index = runCatching {
                CrawlIndex.open(filesDir, CrawlIndex.treeTagOf(treeUriRaw)).also { it.load() }
            }.getOrNull()
            var ok = 0
            var fail = 0
            try {
                rows.forEach { row ->
                    val deleted = runCatching {
                        DocumentsContract.deleteDocument(contentResolver, row.uri.toUri())
                    }.getOrDefault(false)
                    if (deleted) {
                        ok++
                        runCatching { index?.onFileDeleted(row.name) }
                    } else {
                        fail++
                    }
                }
            } finally {
                runCatching { index?.close() }
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (ok > 0) toast(getString(R.string.gallery_batch_deleted_fmt, ok))
                if (fail > 0) toast(getString(R.string.gallery_batch_delete_fail_fmt, fail))
                exitSelectMode()
                load()
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ========================= 适配器 =========================

    private inner class GridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = ArrayList<GridItem>()

        /**
         * 提交新列表。
         *
         * 用 DiffUtil 而不是 `clear + addAll + notifyDataSetChanged()`：
         * 筛选/排序每变一次都会重建整份列表，但屏幕上的条目大多没变 —— 整表重建会让
         * 每格的缩略图重新解码、丢失条目动画、把滚动位置推到顶部。DiffUtil 只把
         * 真正新增/移除/变化的位置通知出去，代价是一次 O(n) 比较（n ≤ 几百，可忽略）。
         */
        fun submit(list: List<GridItem>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = list.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val a = items[oldPos]
                    val b = list[newPos]
                    return when {
                        a is GridItem.Header && b is GridItem.Header -> a.key == b.key
                        a is GridItem.Cell && b is GridItem.Cell -> a.row.uri == b.row.uri
                        else -> false
                    }
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    items[oldPos] == list[newPos]
            })
            items.clear()
            items.addAll(list)
            diff.dispatchUpdatesTo(this)
        }

        /** 某张图当前在列表里的位置（多选时只刷新这一格用）；找不到返回 -1。 */
        fun positionOf(uri: String): Int =
            items.indexOfFirst { it is GridItem.Cell && it.row.uri == uri }

        fun isHeader(position: Int): Boolean =
            position in items.indices && items[position] is GridItem.Header

        override fun getItemViewType(position: Int): Int =
            if (items[position] is GridItem.Header) TYPE_HEADER else TYPE_CELL

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_HEADER) {
                val tv = TextView(parent.context, null, 0, R.style.Widget_Wcm_GroupHeader)
                tv.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.gallery_group_header_height),
                )
                return HeaderHolder(tv)
            }
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_gallery_grid, parent, false)
            // 高度按列宽算好再上屏（具体在 bind 里落，列数/尺寸变化时复用的 holder 也能跟上）。
            return CellHolder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderHolder -> holder.bind(items[position] as GridItem.Header)
                is CellHolder -> {
                    holder.applyCellSize()
                    holder.bind((items[position] as GridItem.Cell).row)
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? CellHolder)?.cancelLoad()
        }

        inner class HeaderHolder(tv: TextView) : RecyclerView.ViewHolder(tv) {
            fun bind(header: GridItem.Header) {
                (itemView as TextView).text = header.title
            }
        }

        inner class CellHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val card = itemView
            private val image: ImageView = view.findViewById(R.id.gridImage)
            private val badge: TextView = view.findViewById(R.id.gifBadge)
            private val scrim: View = view.findViewById(R.id.selectScrim)
            private val check: ImageView = view.findViewById(R.id.selectCheck)
            private var job: Job? = null

            fun cancelLoad() {
                job?.cancel()
                job = null
            }

            /**
             * 把「列宽算出的正方形边长」落到这一格上。
             *
             * 放在 bind 而不是 onCreateViewHolder：holder 是复用的，换列数/换屏幕宽度后
             * 复用的 holder 不会重建，只有重量尺寸才能让整屏跟上。
             * 外边距取间隙的一半 —— 相邻两格各让一半，正好拼出 gutter。
             */
            fun applyCellSize() {
                val size = cellSizePx
                if (size <= 0) return
                val lp = itemView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                val half = gutterPx() / 2
                if (lp.height == size && lp.leftMargin == half) return
                lp.height = size
                lp.setMargins(half, half, half, half)
                itemView.layoutParams = lp
            }

            fun bind(row: GalleryFiles.Row) {
                badge.visibility =
                    if (row.ext == "gif") View.VISIBLE else View.GONE
                val picked = selected.contains(row.uri)
                scrim.visibility = if (selectMode && picked) View.VISIBLE else View.GONE
                check.visibility = if (selectMode && picked) View.VISIBLE else View.GONE

                image.setImageDrawable(null)
                image.tag = row.uri
                card.setOnClickListener {
                    if (selectMode) toggleSelection(row) else openPreview(row)
                }
                card.setOnLongClickListener {
                    if (!selectMode) enterSelectMode(row) else toggleSelection(row)
                    true
                }

                val uri = row.uri.toUri()
                val resolver = contentResolver
                cancelLoad()
                job = lifecycleScope.launch(Dispatchers.IO) {
                    val bmp = try {
                        ThumbLoader.thumbnail(resolver, uri)
                    } catch (e: Exception) {
                        null
                    }
                    withContext(Dispatchers.Main) {
                        // 复用错位防护：仅当此视图仍绑定同一文件时上屏
                        if (image.tag == row.uri && bmp != null) image.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CELL = 1
        const val EXTRA_TREE = "tree"

        fun start(ctx: Context, treeUriRaw: String) {
            ctx.startActivity(
                Intent(ctx, GalleryBrowseActivity::class.java)
                    .putExtra(EXTRA_TREE, treeUriRaw)
            )
        }
    }
}

/** 极简 TextWatcher：只关心"变了没"，避免每个调用点都写三个空方法。 */
private fun EditText.addTextChangedListenerText(onChanged: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) {
            onChanged(s?.toString() ?: "")
        }
    })
}
