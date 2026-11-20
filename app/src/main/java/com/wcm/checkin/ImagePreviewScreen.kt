package com.wcm.checkin

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 本地图库全屏预览：左右翻页器 + 底部工具条（计数 / 文件名 / 分享 / 打开 / 删除）。
 *
 * 图片复用 `ZoomableImageView`，与远程预览共用同一套翻页与缩放行为。
 */
@Composable
fun ImagePreviewScreen(
    state: ImagePreviewUiState,
    nameAt: (Int) -> String,
    loadPage: suspend (Int) -> Drawable?,
    onShare: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onPageChanged: (Int) -> Unit,
) {
    ZoomPager(
        pageCount = state.count,
        initialPage = state.initialPage,
        targetPage = state.targetPage,
        loadPage = loadPage,
        onPageChanged = onPageChanged,
        contentDescription = null,
    ) { page ->
        if (state.count > 0 && page in 0 until state.count) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(colorResource(R.color.preview_bar_scrim))
                    .padding(start = 20.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.preview_index_fmt, page + 1, state.count),
                            style = MaterialTheme.typography.labelMedium,
                            color = colorResource(R.color.on_preview_surface_secondary),
                        )
                        Text(
                            text = nameAt(page),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorResource(R.color.on_preview_surface),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    PreviewAction(
                        icon = R.drawable.ic_share,
                        desc = stringResource(R.string.preview_share),
                        tint = colorResource(R.color.brand),
                    ) { onShare(page) }
                    PreviewAction(
                        icon = R.drawable.ic_open_in_new,
                        desc = stringResource(R.string.preview_open),
                        tint = colorResource(R.color.on_preview_surface),
                    ) { onOpen(page) }
                    PreviewAction(
                        icon = R.drawable.ic_delete,
                        desc = stringResource(R.string.preview_delete),
                        tint = colorResource(R.color.on_preview_danger),
                    ) { onDelete(page) }
                }
            }
        }
    }
}

@Composable
private fun PreviewAction(
    icon: Int,
    desc: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}