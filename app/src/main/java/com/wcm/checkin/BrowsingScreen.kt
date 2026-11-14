package com.wcm.checkin

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BrowsingScreen(
    state: BrowsingUiState,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onLoad: () -> Unit,
    onTargetClick: (String) -> Unit,
    onToggleOriginal: () -> Unit,
    onTogglePic: () -> Unit,
    onPostClick: (String) -> Unit,
    onPostLongClick: (String) -> Unit,
    onThumbClick: (String, Int) -> Unit,
    onMorePics: (String) -> Unit,
    onExitSelect: () -> Unit,
    onDownload: () -> Unit,
    onLoadMore: () -> Unit,
    onVisibleIndex: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "关闭" },
            ) {
                Text("✕", style = MaterialTheme.typography.titleLarge, color = colorResource(R.color.ink_2))
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colorResource(R.color.ink),
                )
                if (state.subtitle.isNotEmpty()) {
                    Text(
                        text = state.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(R.color.ink_3),
                    )
                }
            }
        }
        InputRow(state.input, onInputChange, onLoad)
        if (state.targets.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.targets.forEach { target ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, colorResource(R.color.line_2)),
                        color = Color.Transparent,
                        modifier = Modifier.clickable { onTargetClick(target.uid) },
                    ) {
                        Text(
                            text = target.label,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = colorResource(R.color.ink_2),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(stringResource(R.string.browse_filter_original), state.filterOriginal, onToggleOriginal)
            Spacer(Modifier.width(6.dp))
            FilterChip(stringResource(R.string.browse_filter_pic), state.filterPic, onTogglePic)
            Text(
                text = state.countText,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = state.hint,
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(R.color.ink_3),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            PostList(state, onPostClick, onPostLongClick, onThumbClick, onMorePics, onLoadMore, onVisibleIndex)
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (state.emptyVisible) {
                Text(
                    text = state.emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(R.color.ink_3),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
        }
        if (state.selectMode) {
            SelectBar(state, onExitSelect, onDownload)
        }
    }
}

@Composable
private fun InputRow(input: String, onInputChange: (String) -> Unit, onLoad: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colorResource(R.color.ink)),
            cursorBrush = SolidColor(colorResource(R.color.brand)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onLoad() }),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(colorResource(R.color.surface_2))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (input.isEmpty()) {
                        Text(
                            text = stringResource(R.string.browse_input_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorResource(R.color.ink_3),
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onLoad) {
            Text(stringResource(R.string.browse_load))
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) colorResource(R.color.brand_soft) else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, colorResource(R.color.line_2)),
        modifier = Modifier.clickable { onClick() },
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
private fun PostList(
    state: BrowsingUiState,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    onThumbClick: (String, Int) -> Unit,
    onMorePics: (String) -> Unit,
    onLoadMore: () -> Unit,
    onVisibleIndex: (Int) -> Unit,
) {
    val pagePad = dimensionResource(R.dimen.page_padding_horizontal)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.initialScrollIndex)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { onVisibleIndex(it) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= state.posts.size - 3) onLoadMore()
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = pagePad, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.posts.size, key = { state.posts[it].bid }) { index ->
            val post = state.posts[index]
            PostCard(post, onClick, onLongClick, onThumbClick, onMorePics)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PostCard(
    post: BrowsePostUi,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    onThumbClick: (String, Int) -> Unit,
    onMorePics: (String) -> Unit,
) {
    Box {
        androidx.compose.material3.Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { onClick(post.bid) }, onLongClick = { onLongClick(post.bid) }),
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = colorResource(R.color.surface),
            ),
            border = BorderStroke(1.dp, colorResource(R.color.line)),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = post.meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = post.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(R.color.ink),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (post.thumbs.isNotEmpty() || post.moreCount > 0) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        maxItemsInEachRow = 5,
                    ) {
                        post.thumbs.forEachIndexed { i, thumb ->
                            ThumbTile(
                                thumb = thumb,
                                index = i,
                                onClick = { onThumbClick(post.bid, i) },
                            )
                        }
                        if (post.moreCount > 0) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colorResource(R.color.surface_2))
                                    .clickable { onMorePics(post.bid) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "+${post.moreCount}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colorResource(R.color.ink_3),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (post.selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x22000000)),
            )
        }
        if (post.selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(colorResource(R.color.brand)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

@Composable
private fun ThumbTile(thumb: BrowseThumbUi, index: Int, onClick: () -> Unit) {
    val desc = stringResource(R.string.browse_pic_desc_fmt, index + 1)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colorResource(R.color.surface_2))
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc },
    ) {
        RemoteImage(thumb.url)
        if (thumb.done) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(colorResource(R.color.brand)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

@Composable
private fun RemoteImage(url: String) {
    val bitmap by produceState<Bitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching { RemoteThumb.load(url) }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SelectBar(state: BrowsingUiState, onExit: () -> Unit, onDownload: () -> Unit) {
    Surface(color = colorResource(R.color.surface), shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.browse_selected_fmt, state.selectedCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colorResource(R.color.ink),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onExit) {
                Text(stringResource(R.string.browse_exit_select))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDownload, enabled = state.selectedCount > 0 && !state.submitting) {
                Text(stringResource(R.string.browse_download_fmt, state.selectedCount))
            }
        }
    }
}