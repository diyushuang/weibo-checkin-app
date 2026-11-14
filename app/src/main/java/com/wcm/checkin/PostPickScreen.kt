package com.wcm.checkin

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PostPickScreen(
    state: PickUiState,
    onBack: () -> Unit,
    onToggleAll: () -> Unit,
    onItemClick: (String) -> Unit,
    onItemLongClick: (Int) -> Unit,
    onDownload: () -> Unit,
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
            Text(
                text = stringResource(R.string.pick_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colorResource(R.color.ink),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleAll, enabled = state.items.isNotEmpty()) {
                Text(
                    stringResource(
                        if (state.allSelected) R.string.pick_deselect_all else R.string.pick_all,
                    ),
                )
            }
        }
        Text(
            text = stringResource(R.string.pick_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(R.color.ink_3),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            PickGrid(state, onItemClick, onItemLongClick, onVisibleIndex)
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
        Surface(color = colorResource(R.color.bg)) {
            Button(
                onClick = onDownload,
                enabled = state.selectedCount > 0 && !state.submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    when {
                        state.submitting -> stringResource(R.string.pick_downloading)
                        state.selectedCount == 0 -> stringResource(R.string.pick_download_zero)
                        else -> stringResource(R.string.pick_download_fmt, state.selectedCount)
                    },
                )
            }
        }
    }
}

@Composable
private fun PickGrid(
    state: PickUiState,
    onItemClick: (String) -> Unit,
    onItemLongClick: (Int) -> Unit,
    onVisibleIndex: (Int) -> Unit,
) {
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = state.initialScrollIndex)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { onVisibleIndex(it) }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(state.items, key = { _, item -> item.picId }) { index, item ->
            PickCell(
                item = item,
                index = index,
                onClick = { onItemClick(item.picId) },
                onLongClick = { onItemLongClick(index) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickCell(item: PickItemUi, index: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    val desc = stringResource(R.string.pick_pic_desc_fmt, index + 1)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(colorResource(R.color.surface_2))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = desc },
    ) {
        PickThumb(item.thumbUrl)
        if (item.isLive || item.isGif) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xCC000000),
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            ) {
                Text(
                    text = stringResource(
                        if (item.isLive) R.string.pick_live_badge else R.string.pick_gif_badge,
                    ),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        if (item.done) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(colorResource(R.color.brand)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        if (item.selected) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)))
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
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
private fun PickThumb(url: String) {
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
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
@Suppress("UseKtx") // BitmapDrawable(null, …) 保持 density=NONE，缩放坐标才不漂移
@Composable
fun FullPickPreviewDialog(
    fullUrl: String,
    onOnlyThis: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val zoom = remember { ZoomableImageView(context) }
            val loading by produceState(initialValue = true, fullUrl) {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { RemoteThumb.loadFull(fullUrl) }.getOrNull()
                }
                zoom.setImageDrawable(bmp?.let { android.graphics.drawable.BitmapDrawable(null, it) })
                value = false
            }
            AndroidView(
                factory = {
                    zoom.apply {
                        setOnClickListener { onDismiss() }
                        contentDescription = context.getString(R.string.pick_preview_desc)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (loading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onOnlyThis) {
                    Text(
                        text = stringResource(R.string.pick_only_this),
                        color = Color.White,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.pick_close),
                        color = Color(0xB3FFFFFF),
                    )
                }
            }
        }
    }
}
