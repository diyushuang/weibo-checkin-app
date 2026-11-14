package com.wcm.checkin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 图库页删除后通知浏览页重扫目录（预览页与浏览页不再同 Activity）。 */
object GalleryContent {
    @Volatile var changed = false
}

sealed interface GalleryBrowseEvent {
    data class Toast(val text: String) : GalleryBrowseEvent
    data class Share(val uris: List<String>) : GalleryBrowseEvent
    data class OpenPreview(val index: Int, val name: String) : GalleryBrowseEvent
}

/** 图库浏览状态与编排（route 版）。 */
@HiltViewModel
class GalleryBrowseViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryBrowseUiState())
    val state: StateFlow<GalleryBrowseUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GalleryBrowseEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<GalleryBrowseEvent> = _events.asSharedFlow()

    private var treeUriRaw = ""
    private var started = false

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
    private var scrollRequest = 0
    private var scrollIndex = 0
    private var loadingNow = false

    fun start(tree: String) {
        if (started) return
        started = true
        treeUriRaw = tree
        emptyText = ctx.getString(R.string.gallery_empty)
        if (tree.isEmpty()) {
            loadFailed = true
            renderBrowse()
            return
        }
        load()
    }

    fun onResume() {
        if (GalleryContent.changed) {
            GalleryContent.changed = false
            load()
        }
    }

    fun reload() = load()

    fun setSearchOpen(open: Boolean) {
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

    fun onQueryChanged(text: String) {
        queryText = text
        _state.update { it.copy(query = text) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            filter = filter.copy(query = text)
            rebuild()
        }
    }

    fun setSort(order: BrowseSort) {
        sort = when (order) {
            BrowseSort.NEWEST -> GalleryFiles.SortOrder.NEWEST
            BrowseSort.OLDEST -> GalleryFiles.SortOrder.OLDEST
            BrowseSort.NAME_ASC -> GalleryFiles.SortOrder.NAME_ASC
            BrowseSort.SIZE_DESC -> GalleryFiles.SortOrder.SIZE_DESC
            BrowseSort.SIZE_ASC -> GalleryFiles.SortOrder.SIZE_ASC
        }
        scrollToTop()
        rebuild()
    }

    fun setGroup(mode: GalleryGroupMode) {
        groupMode = mode
        scrollToTop()
        rebuild()
    }

    fun setKind(kind: BrowseKind) {
        filter = filter.copy(
            kind = when (kind) {
                BrowseKind.ALL -> GalleryFiles.KindFilter.ALL
                BrowseKind.STILL -> GalleryFiles.KindFilter.STILL
                BrowseKind.GIF -> GalleryFiles.KindFilter.GIF
            },
            label = "",
        )
        rebuild()
    }

    fun setAuthor(label: String) {
        filter = filter.copy(label = if (filter.label == label) "" else label)
        rebuild()
    }

    fun toggleSelectMode() {
        if (selectMode) exitSelectMode() else enterSelectMode(null)
    }

    fun exitSelectMode() {
        if (!selectMode) return
        selectMode = false
        selected.clear()
        renderBrowse()
    }

    fun onCellClick(uri: String) {
        val row = shownRows.firstOrNull { it.uri == uri } ?: return
        if (selectMode) toggleSelection(row) else openPreview(row)
    }

    fun onCellLongClick(uri: String) {
        val row = shownRows.firstOrNull { it.uri == uri } ?: return
        if (!selectMode) enterSelectMode(row) else toggleSelection(row)
    }

    fun shareSelected() {
        val rows = pickedRows()
        if (rows.isEmpty()) {
            toast(ctx.getString(R.string.gallery_share_empty))
            return
        }
        _events.tryEmit(GalleryBrowseEvent.Share(rows.map { it.uri }))
    }

    fun batchDelete() {
        val rows = pickedRows()
        if (rows.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val index = runCatching {
                CrawlIndex.open(ctx.filesDir, CrawlIndex.treeTagOf(treeUriRaw)).also { it.load() }
            }.getOrNull()
            var ok = 0
            var fail = 0
            try {
                rows.forEach { row ->
                    val deleted = runCatching {
                        android.provider.DocumentsContract.deleteDocument(
                            ctx.contentResolver,
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
                if (ok > 0) toast(ctx.getString(R.string.gallery_batch_deleted_fmt, ok))
                if (fail > 0) toast(ctx.getString(R.string.gallery_batch_delete_fail_fmt, fail))
                exitSelectMode()
                load()
            }
        }
    }

    private fun load() {
        if (treeUriRaw.isEmpty()) return
        loadJob?.cancel()
        loadingNow = true
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val rows = try {
                GalleryFiles.index(GalleryFiles.listImages(ctx.contentResolver, treeUriRaw.toUri()))
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (rows == null) {
                    loadFailed = true
                    loadingNow = false
                    allRows = emptyList()
                    shownRows = emptyList()
                    itemsUi = emptyList()
                    filtersUi = emptyList()
                    emptyText = ctx.getString(R.string.gallery_read_error)
                    if (selectMode) exitSelectMode()
                    renderBrowse()
                    return@withContext
                }
                loadFailed = false
                loadingNow = false
                allRows = rows
                emptyText = ctx.getString(R.string.gallery_empty)
                rebuild()
            }
        }
    }

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
        rebuildJob = viewModelScope.launch {
            val computed = withContext(Dispatchers.Default) {
                val sorted = GalleryFiles.sortRows(GalleryFiles.applyFilter(source, f), s)
                val groups = when (g) {
                    GalleryGroupMode.NONE -> null
                    GalleryGroupMode.DATE -> GalleryFiles.groupByDate(sorted)
                    GalleryGroupMode.AUTHOR -> GalleryFiles.groupByLabel(sorted)
                }
                val items = ArrayList<BrowseItemUi>(sorted.size + (groups?.size ?: 0))
                if (groups == null) {
                    sorted.forEach { items.add(BrowseItemUi.Cell(it.uri, it.name, it.ext)) }
                } else {
                    groups.forEach { group ->
                        items.add(BrowseItemUi.Header(group.key, titleOf(group)))
                        group.rows.forEach { items.add(BrowseItemUi.Cell(it.uri, it.name, it.ext)) }
                    }
                }
                Computed(sorted, items)
            }
            applyComputed(computed)
        }
    }

    private fun applyComputed(page: Computed) {
        shownRows = page.sorted
        itemsUi = page.items
        filtersUi = buildFilters()
        emptyText = when {
            allRows.isEmpty() -> ctx.getString(R.string.gallery_empty)
            page.sorted.isEmpty() -> ctx.getString(R.string.gallery_filter_empty)
            else -> ""
        }
        if (selectMode) {
            val visible = shownRows.map { it.uri }.toSet()
            selected.retainAll(visible)
            if (selected.isEmpty()) exitSelectMode()
        }
        renderBrowse()
    }

    private fun renderBrowse() {
        val subtitle = when {
            loadFailed -> ""
            shownRows.size == allRows.size -> ctx.getString(R.string.gallery_count_fmt, allRows.size)
            else -> ctx.getString(R.string.gallery_summary_fmt, allRows.size, shownRows.size)
        }
        _state.value = GalleryBrowseUiState(
            title = if (selectMode) {
                ctx.getString(R.string.gallery_selected_fmt, selected.size)
            } else {
                ctx.getString(R.string.gallery_browse_title)
            },
            subtitle = subtitle,
            searchOpen = searchOpen,
            query = queryText,
            filters = filtersUi,
            spanCount = ctx.resources.getInteger(R.integer.gallery_columns),
            items = itemsUi,
            selectMode = selectMode,
            selected = selected.toSet(),
            selectedCount = selected.size,
            emptyVisible = shownRows.isEmpty() && !loadingNow,
            emptyText = emptyText.ifEmpty {
                if (loadFailed) ctx.getString(R.string.gallery_read_error)
                else ctx.getString(R.string.gallery_empty)
            },
            scrollRequest = scrollRequest,
            scrollIndex = scrollIndex,
        )
    }

    private fun buildFilters(): List<BrowseFilterChipUi> {
        val kinds = listOf(
            BrowseKind.ALL to R.string.gallery_filter_all,
            BrowseKind.STILL to R.string.gallery_filter_still,
            BrowseKind.GIF to R.string.gallery_filter_gif,
        )
        val chips = kinds.map { (kind, res) ->
            BrowseFilterChipUi(
                kind = kind,
                author = "",
                label = ctx.getString(res),
                selected = when (kind) {
                    BrowseKind.ALL -> filter.kind == GalleryFiles.KindFilter.ALL
                    BrowseKind.STILL -> filter.kind == GalleryFiles.KindFilter.STILL
                    BrowseKind.GIF -> filter.kind == GalleryFiles.KindFilter.GIF
                } && filter.label.isEmpty(),
            )
        }.toMutableList()
        GalleryFiles.labelTabs(allRows).take(6).forEach { (label, count) ->
            chips.add(
                BrowseFilterChipUi(
                    kind = null,
                    author = label,
                    label = ctx.getString(R.string.gallery_author_chip_fmt, label, count),
                    selected = filter.label == label,
                ),
            )
        }
        return chips
    }

    private fun titleOf(group: GalleryFiles.Group): String {
        val title = if (group.key == GalleryFiles.UNKNOWN_KEY) {
            ctx.getString(R.string.gallery_group_other)
        } else {
            group.title
        }
        return ctx.getString(R.string.gallery_group_title_fmt, title, group.count)
    }

    private fun scrollToTop() {
        scrollIndex = 0
        scrollRequest++
        renderBrowse()
    }

    private fun openPreview(row: GalleryFiles.Row) {
        val index = shownRows.indexOfFirst { it.uri == row.uri }
        _events.tryEmit(GalleryBrowseEvent.OpenPreview(index.coerceAtLeast(0), row.name))
    }

    private fun enterSelectMode(first: GalleryFiles.Row?) {
        selectMode = true
        first?.let { selected.add(it.uri) }
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

    private fun toast(text: String) {
        _events.tryEmit(GalleryBrowseEvent.Toast(text))
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
    }
}