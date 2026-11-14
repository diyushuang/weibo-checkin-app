package com.wcm.checkin

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图库浏览（v0.9.0）：按日期/博主分组、筛选、搜索、多选分享/删除、全屏预览入口。
 *
 * 数据侧（扫描、过滤、排序、分组）留在 Activity；整屏由 Compose 渲染。
 * 过滤/排序/分组全部在 Default 线程算完再上屏，主线程只负责状态更新。
 */
class GalleryBrowseActivity : AppCompatActivity() {

    private lateinit var treeUriRaw: String
    private lateinit var treeUri: Uri

    private val uiState = MutableStateFlow(GalleryBrowseUiState())

    private var allRows: List<GalleryFiles.Row> = emptyList()
    private var shownRows: List<GalleryFiles.Row> = emptyList()
    private var itemsUi: List<BrowseItemUi> = emptyList()
    private var filtersUi: List<BrowseFilterChipUi> = emptyList()

    private var filter = GalleryFiles.Filter()
    private var sort = GalleryFiles.SortOrder.NEWEST
    private var groupMode = GalleryGroupMode.DATE
    private var selectMode = false
    private val selected = LinkedHashSet<String>()

    private var loadJob: Job? = null
    private var rebuildJob: Job? = null
    private var searchJob: Job? = null

    private var searchOpen = false
    private var queryText = ""
    private var loadFailed = false
    private var emptyText = ""

    private var pendingScroll = -1
    private var lastVisibleIndex = 0
    private var scrollRequest = 0
    private var scrollIndex = 0
    private var loadingNow = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitSelectMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 边到边：图库是沉浸浏览的场景，工具栏底色延伸到状态栏下方更自然
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        treeUriRaw = intent.getStringExtra(EXTRA_TREE) ?: ""
        if (treeUriRaw.isEmpty()) {
            finish()
            return
        }
        treeUri = treeUriRaw.toUri()
        emptyText = getString(R.string.gallery_empty)

        val composeView = ComposeView(this)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            val state by uiState.collectAsState()
            WcmTheme {
                GalleryBrowseScreen(
                    state = state,
                    onBack = { finish() },
                    onRefresh = { load() },
                    onToggleSearch = { open -> setSearchOpen(open) },
                    onQueryChange = { onQueryChanged(it) },
                    onSelectModeToggle = { toggleSelectMode() },
                    onSort = { order ->
                        sort = order
                        scrollToTop()
                        rebuild()
                    },
                    onGroup = { mode ->
                        groupMode = mode
                        scrollToTop()
                        rebuild()
                    },
                    onKindFilter = { kind ->
                        filter = filter.copy(kind = kind, label = "")
                        rebuild()
                    },
                    onAuthorFilter = { label ->
                        filter = filter.copy(label = if (filter.label == label) "" else label)
                        rebuild()
                    },
                    onCellClick = { row ->
                        if (selectMode) toggleSelection(row) else openPreview(row)
                    },
                    onCellLongClick = { row ->
                        if (!selectMode) enterSelectMode(row) else toggleSelection(row)
                    },
                    onExitSelect = { exitSelectMode() },
                    onShare = { batchShare() },
                    onDelete = { confirmBatchDelete() },
                    onEmptyAction = { finish() },
                    onVisibleIndex = { lastVisibleIndex = it },
                )
            }
        }
        setContentView(composeView)

        onBackPressedDispatcher.addCallback(this, backCallback)
        if (savedInstanceState == null) load() else restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_QUERY, queryText)
        outState.putString(KEY_KIND, filter.kind.name)
        outState.putString(KEY_LABEL, filter.label)
        outState.putString(KEY_SORT, sort.name)
        outState.putString(KEY_GROUP, groupMode.name)
        outState.putBoolean(KEY_SELECT, selectMode)
        outState.putStringArrayList(KEY_SELECTED, ArrayList(selected))
        outState.putInt(KEY_SCROLL, lastVisibleIndex)
    }

    /**
     * 重建后恢复现场：筛选 / 排序 / 分组 / 多选 / 滚动。
     *
     * 行数据本身不缓存（10 万行约几十 MB，进程级静态字段会把内存钉死），
     * 交给 [load] 重新扫一遍目录 —— 它在 IO 线程，用户只看得到一次刷新。
     */
    private fun restoreState(saved: Bundle) {
        filter = GalleryFiles.Filter(
            query = saved.getString(KEY_QUERY) ?: "",
            kind = runCatching { GalleryFiles.KindFilter.valueOf(saved.getString(KEY_KIND) ?: "") }
                .getOrDefault(GalleryFiles.KindFilter.ALL),
            label = saved.getString(KEY_LABEL) ?: "",
        )
        queryText = filter.query
        sort = runCatching { GalleryFiles.SortOrder.valueOf(saved.getString(KEY_SORT) ?: "") }
            .getOrDefault(GalleryFiles.SortOrder.NEWEST)
        groupMode = runCatching { GalleryGroupMode.valueOf(saved.getString(KEY_GROUP) ?: "") }
            .getOrDefault(GalleryGroupMode.DATE)
        searchOpen = filter.query.isNotEmpty()
        selected.clear()
        saved.getStringArrayList(KEY_SELECTED)?.let { selected.addAll(it) }
        if (saved.getBoolean(KEY_SELECT, false)) {
            selectMode = true
            backCallback.isEnabled = true
        }
        pendingScroll = saved.getInt(KEY_SCROLL, 0)
        load()
    }

    private fun savedInstanceStateGroup(saved: Bundle): String =
        saved.getString(KEY_GROUP) ?: ""

    override fun onResume() {
        super.onResume()
        // 从全屏预览删了图回来：目录内容变了，重读一次。
        // 用「预览页置过的标记」而不是无条件重扫 —— 从分享/打开返回也会走 onResume，
        // 每次都全量查一遍目录（10 万文件 ≈ 1s）会让返回动画后莫名其妙地卡一下。
        if (ImagePreviewActivity.contentChanged) {
            ImagePreviewActivity.contentChanged = false
            load()
        }
    }

    // ========================= 数据加载 =========================

    private fun load() {
        val resolver = contentResolver
        loadJob?.cancel()
        loadingNow = true
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            val rows = try {
                GalleryFiles.index(GalleryFiles.listImages(resolver, treeUri))
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (rows == null) {
                    loadFailed = true
                    loadingNow = false
                    allRows = emptyList()
                    shownRows = emptyList()
                    itemsUi = emptyList()
                    filtersUi = emptyList()
                    emptyText = getString(R.string.gallery_read_error)
                    if (selectMode) exitSelectMode()
                    renderBrowse()
                    return@withContext
                }
                loadFailed = false
                loadingNow = false
                allRows = rows
                emptyText = getString(R.string.gallery_empty)
                rebuild()
            }
        }
    }

    /**
     * 过滤 / 排序 / 分组 / 摊平的**全部**后台计算结果。
     *
     * items 也在这里生成：摊平要 new 出 n 个条目，10 万张的库在主线程做这一趟
     * 就是几十到几百毫秒 —— 主线程只该剩状态更新。
     */
    private class Computed(
        val sorted: List<GalleryFiles.Row>,
        val items: List<BrowseItemUi>,
    )

    private fun rebuild() {
        val source = allRows
        val f = filter
        val s = sort
        val g = groupMode
        rebuildJob?.cancel()
        rebuildJob = lifecycleScope.launch {
            val computed = withContext(Dispatchers.Default) {
                val sorted = GalleryFiles.sortRows(GalleryFiles.applyFilter(source, f), s)
                val groups = when (g) {
                    GalleryGroupMode.NONE -> null
                    GalleryGroupMode.DATE -> GalleryFiles.groupByDate(sorted)
                    GalleryGroupMode.AUTHOR -> GalleryFiles.groupByLabel(sorted)
                }
                // 摊平（连同分组标题文案）也留在这里，主线程只上屏。
                val items = ArrayList<BrowseItemUi>(sorted.size + (groups?.size ?: 0))
                if (groups == null) {
                    sorted.forEach { items.add(BrowseItemUi.Cell(it)) }
                } else {
                    groups.forEach { group ->
                        items.add(BrowseItemUi.Header(group.key, titleOf(group)))
                        group.rows.forEach { items.add(BrowseItemUi.Cell(it)) }
                    }
                }
                Computed(sorted, items)
            }
            if (isFinishing || isDestroyed) return@launch
            applyComputed(computed)
        }
    }

    private fun applyComputed(page: Computed) {
        shownRows = page.sorted
        itemsUi = page.items
        filtersUi = buildFilters()

        emptyText = when {
            allRows.isEmpty() -> getString(R.string.gallery_empty)
            page.sorted.isEmpty() -> getString(R.string.gallery_filter_empty)
            else -> ""
        }

        // 选中态里被筛掉的图不应该还算"已选"
        if (selectMode) {
            val visible = shownRows.map { it.uri }.toSet()
            selected.retainAll(visible)
            if (selected.isEmpty()) {
                exitSelectMode()
            }
        }
        if (pendingScroll >= 0) {
            scrollIndex = pendingScroll
            scrollRequest++
            pendingScroll = -1
        }
        renderBrowse()
    }

    private fun renderBrowse() {
        val subtitle = when {
            loadFailed -> ""
            shownRows.size == allRows.size -> getString(R.string.gallery_count_fmt, allRows.size)
            else -> getString(R.string.gallery_summary_fmt, allRows.size, shownRows.size)
        }
        uiState.value = GalleryBrowseUiState(
            title = if (selectMode) {
                getString(R.string.gallery_selected_fmt, selected.size)
            } else {
                getString(R.string.gallery_browse_title)
            },
            subtitle = subtitle,
            searchOpen = searchOpen,
            query = queryText,
            filters = filtersUi,
            spanCount = spanCount(),
            items = itemsUi,
            selectMode = selectMode,
            selected = selected.toSet(),
            selectedCount = selected.size,
            emptyVisible = shownRows.isEmpty() && !loadingNow,
            emptyText = emptyText.ifEmpty {
                if (loadFailed) getString(R.string.gallery_read_error) else getString(R.string.gallery_empty)
            },
            scrollRequest = scrollRequest,
            scrollIndex = scrollIndex,
        )
    }

    private fun buildFilters(): List<BrowseFilterChipUi> {
        // 只给「全部 / 静图 / 动图」三个：数据源（GalleryFiles.listImages）只收图片，
        // live 照片的 .mov 是配对资源、从不单独进图库，所以「视频」是个永远空的死入口。
        val kinds = listOf(
            GalleryFiles.KindFilter.ALL to R.string.gallery_filter_all,
            GalleryFiles.KindFilter.STILL to R.string.gallery_filter_still,
            GalleryFiles.KindFilter.GIF to R.string.gallery_filter_gif,
        )
        val chips = kinds.map { (kind, res) ->
            BrowseFilterChipUi(
                kind = kind,
                author = "",
                label = getString(res),
                selected = filter.kind == kind && filter.label.isEmpty(),
            )
        }.toMutableList()
        // 作者 pill：只列拍得最多的前 6 个，避免筛选条无限长
        GalleryFiles.labelTabs(allRows).take(6).forEach { (label, count) ->
            chips.add(
                BrowseFilterChipUi(
                    kind = null,
                    author = label,
                    label = getString(R.string.gallery_author_chip_fmt, label, count),
                    selected = filter.label == label,
                ),
            )
        }
        return chips
    }

    private fun titleOf(group: GalleryFiles.Group): String {
        val title = if (group.key == GalleryFiles.UNKNOWN_KEY) {
            getString(R.string.gallery_group_other)
        } else {
            group.title
        }
        return getString(R.string.gallery_group_title_fmt, title, group.count)
    }

    private fun spanCount(): Int = resources.getInteger(R.integer.gallery_columns)

    private fun scrollToTop() {
        scrollIndex = 0
        scrollRequest++
        renderBrowse()
    }

    // ========================= 搜索 =========================

    private fun setSearchOpen(open: Boolean) {
        searchOpen = open
        if (!open && queryText.isNotEmpty()) {
            queryText = ""
            filter = filter.copy(query = "")
            searchJob?.cancel()
            rebuild()
        } else {
            renderBrowse()
        }
    }

    private fun onQueryChanged(text: String) {
        queryText = text
        // 立即回显输入，防抖只控制重算
        uiState.update { it.copy(query = text) }
        // 防抖 250ms：否则每敲一个字符就触发一次全量过滤（10 万张时直接卡顿）
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            filter = filter.copy(query = text)
            rebuild()
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
        first?.let { selected.add(it.uri) }
        renderBrowse()
    }

    private fun exitSelectMode() {
        if (!selectMode) return
        selectMode = false
        selected.clear()
        backCallback.isEnabled = false
        renderBrowse()
    }

    private fun toggleSelection(row: GalleryFiles.Row) {
        if (!selected.remove(row.uri)) selected.add(row.uri)
        if (selected.isEmpty()) {
            exitSelectMode()
        } else {
            renderBrowse()
        }
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
                        android.provider.DocumentsContract.deleteDocument(
                            contentResolver,
                            row.uri.toUri(),
                        )
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

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L

        private const val KEY_QUERY = "gb_query"
        private const val KEY_KIND = "gb_kind"
        private const val KEY_LABEL = "gb_label"
        private const val KEY_SORT = "gb_sort"
        private const val KEY_GROUP = "gb_group"
        private const val KEY_SELECT = "gb_select"
        private const val KEY_SELECTED = "gb_selected"
        private const val KEY_SCROLL = "gb_scroll"

        const val EXTRA_TREE = "tree"

        fun start(ctx: android.content.Context, treeUriRaw: String) {
            ctx.startActivity(
                Intent(ctx, GalleryBrowseActivity::class.java)
                    .putExtra(EXTRA_TREE, treeUriRaw)
            )
        }
    }
}