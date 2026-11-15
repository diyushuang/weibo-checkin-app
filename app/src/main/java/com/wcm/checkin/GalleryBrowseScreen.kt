package com.wcm.checkin

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GalleryBrowseScreen(
    state: GalleryBrowseUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectModeToggle: () -> Unit,
    onSort: (BrowseSort) -> Unit,
    onGroup: (GalleryGroupMode) -> Unit,
    onKindFilter: (BrowseKind) -> Unit,
    onAuthorFilter: (String) -> Unit,
    onCellClick: (String) -> Unit,
    onCellLongClick: (String) -> Unit,
    onExitSelect: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onEmptyAction: () -> Unit,
    onVisibleIndex: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopBar(state, onBack, onRefresh, onToggleSearch, onSelectModeToggle)
        if (state.searchOpen) {
            SearchRow(state.query, onQueryChange, onToggleSearch)
        }
        FilterRow(state, onKindFilter, onAuthorFilter, onSort, onGroup)
        Box(modifier = Modifier.weight(1f)) {
            if (state.emptyVisible) {
                EmptyState(state.emptyText, onEmptyAction)
            } else {
                Grid(state, onCellClick, onCellLongClick, onVisibleIndex)
            }
        }
        if (state.selectMode) {
            SelectBar(state, onExitSelect, onShare, onDelete)
        }
    }
}

@Composable
private fun TopBar(
    state: GalleryBrowseUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    onSelectModeToggle: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val refreshDesc = stringResource(R.string.gallery_refresh)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = "返回" },
        ) {
            Text("‹", style = MaterialTheme.typography.headlineMedium, color = colorResource(R.color.ink))
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colorResource(R.color.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
            )
        }
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.semantics { contentDescription = refreshDesc },
        ) {
            Text("⟳", style = MaterialTheme.typography.titleLarge, color = colorResource(R.color.ink_2))
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Text("⋮", style = MaterialTheme.typography.titleLarge, color = colorResource(R.color.ink_2))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (state.searchOpen) "关闭搜索" else "搜索") },
                    onClick = {
                        menu = false
                        onToggleSearch(!state.searchOpen)
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (state.selectMode) "退出多选" else "多选") },
                    onClick = {
                        menu = false
                        onSelectModeToggle()
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: (Boolean) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colorResource(R.color.ink)),
            cursorBrush = SolidColor(colorResource(R.color.brand)),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(colorResource(R.color.surface_2))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .focusRequester(focus),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.gallery_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorResource(R.color.ink_3),
                        )
                    }
                    inner()
                }
            },
        )
        IconButton(
            onClick = { onToggleSearch(false) },
            modifier = Modifier.semantics {
                contentDescription = "关闭搜索"
            },
        ) {
            Text("✕", style = MaterialTheme.typography.titleMedium, color = colorResource(R.color.ink_3))
        }
    }
}

@Composable
private fun FilterRow(
    state: GalleryBrowseUiState,
    onKindFilter: (BrowseKind) -> Unit,
    onAuthorFilter: (String) -> Unit,
    onSort: (BrowseSort) -> Unit,
    onGroup: (GalleryGroupMode) -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    var groupOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            state.filters.forEach { chip ->
                Chip(chip.label, chip.selected) {
                    val kind = chip.kind
                    if (kind != null) onKindFilter(kind) else onAuthorFilter(chip.author)
                }
            }
        }
        Box {
            TextButton(onClick = { sortOpen = true }) {
                Text(stringResource(R.string.gallery_sort_label), color = colorResource(R.color.ink_2))
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                BrowseSort.values().forEach { order ->
                    DropdownMenuItem(
                        text = { Text(stringResource(sortLabel(order))) },
                        onClick = {
                            sortOpen = false
                            onSort(order)
                        },
                    )
                }
            }
        }
        Box {
            TextButton(onClick = { groupOpen = true }) {
                Text("分组", color = colorResource(R.color.ink_2))
            }
            DropdownMenu(expanded = groupOpen, onDismissRequest = { groupOpen = false }) {
                GalleryGroupMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(stringResource(groupLabel(mode))) },
                        onClick = {
                            groupOpen = false
                            onGroup(mode)
                        },
                    )
                }
            }
        }
    }
}

private fun sortLabel(order: BrowseSort): Int = when (order) {
    BrowseSort.NEWEST -> R.string.gallery_sort_newest
    BrowseSort.OLDEST -> R.string.gallery_sort_oldest
    BrowseSort.NAME_ASC -> R.string.gallery_sort_name
    BrowseSort.SIZE_DESC -> R.string.gallery_sort_size_desc
    BrowseSort.SIZE_ASC -> R.string.gallery_sort_size_asc
}

private fun groupLabel(mode: GalleryGroupMode): Int = when (mode) {
    GalleryGroupMode.DATE -> R.string.gallery_group_by_date
    GalleryGroupMode.AUTHOR -> R.string.gallery_group_by_author
    GalleryGroupMode.NONE -> R.string.gallery_group_none
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) colorResource(R.color.brand_soft) else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, colorResource(R.color.line_2)),
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colorResource(R.color.brand_strong) else colorResource(R.color.ink_2),
        )
    }
}

@Composable
private fun Grid(
    state: GalleryBrowseUiState,
    onCellClick: (String) -> Unit,
    onCellLongClick: (String) -> Unit,
    onVisibleIndex: (Int) -> Unit,
) {
    val pagePad = dimensionResource(R.dimen.page_padding_horizontal)
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = state.initialScrollIndex)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { onVisibleIndex(it) }
    }
    LaunchedEffect(state.scrollRequest) {
        if (state.scrollRequest > 0 && state.items.isNotEmpty()) {
            gridState.scrollToItem(state.scrollIndex.coerceIn(0, state.items.lastIndex))
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(state.spanCount),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = pagePad, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(
            items = state.items,
            key = { item -> keyOf(item) },
            span = { item -> if (item is BrowseItemUi.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
        ) { item ->
            when (item) {
                is BrowseItemUi.Header -> Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorResource(R.color.ink_2),
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                is BrowseItemUi.Cell -> Cell(
                    uri = item.uri,
                    name = item.name,
                    ext = item.ext,
                    selected = item.uri in state.selected,
                    selectMode = state.selectMode,
                    onClick = { onCellClick(item.uri) },
                    onLongClick = { onCellLongClick(item.uri) },
                )
            }
        }
    }
}

private fun keyOf(item: BrowseItemUi): String = when (item) {
    is BrowseItemUi.Header -> "header:" + item.key
    is BrowseItemUi.Cell -> item.uri
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Cell(
    uri: String,
    name: String,
    ext: String,
    selected: Boolean,
    selectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(colorResource(R.color.surface_2))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = name },
    ) {
        Thumbnail(uri)
        if (ext == "gif") {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xCC000000),
                modifier = Modifier.padding(4.dp),
            ) {
                Text(
                    text = "GIF",
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        if (selectMode && selected) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        }
        if (selectMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) colorResource(R.color.brand) else Color(0x66000000),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Text("✓", fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun Thumbnail(uri: String) {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ThumbLoader.thumbnail(resolver, uri.toUri()) }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun EmptyState(text: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = colorResource(R.color.brand_soft)) {
            Icon(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = null,
                tint = colorResource(R.color.brand_strong),
                modifier = Modifier.padding(20.dp).size(28.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colorResource(R.color.ink_2),
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onAction) {
            Text(stringResource(R.string.gallery_empty_action))
        }
    }
}

@Composable
private fun SelectBar(
    state: GalleryBrowseUiState,
    onExit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = colorResource(R.color.surface), shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.gallery_selected_fmt, state.selectedCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colorResource(R.color.ink),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onShare, enabled = state.selectedCount > 0) {
                Text(stringResource(R.string.gallery_batch_share))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDelete, enabled = state.selectedCount > 0) {
                Text(stringResource(R.string.gallery_batch_delete))
            }
            TextButton(onClick = onExit) {
                Text(stringResource(R.string.gallery_select_cancel))
            }
        }
    }
}