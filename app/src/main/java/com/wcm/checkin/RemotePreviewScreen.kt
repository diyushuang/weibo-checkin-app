package com.wcm.checkin

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun RemotePreviewScreen(
    state: RemotePreviewUiState,
    isDoneAt: (Int) -> Boolean,
    loadPage: suspend (Int) -> Drawable?,
    onPageChanged: (Int) -> Unit,
) {
    ZoomPager(
        pageCount = state.urls.size,
        initialPage = state.initialPage,
        targetPage = state.initialPage,
        loadPage = loadPage,
        onPageChanged = onPageChanged,
        contentDescription = stringResource(R.string.preview_remote_desc),
    ) { page ->
        if (page in state.urls.indices) {
            // 读 marksVersion：CrawlMarks 读完/刷新后触发重组，重算「已抓取」
            val done = state.marksVersion >= 0 && isDoneAt(page)
            Text(
                text = if (done) {
                    stringResource(R.string.preview_index_done_fmt, page + 1, state.urls.size)
                } else {
                    stringResource(R.string.preview_index_fmt, page + 1, state.urls.size)
                },
                style = MaterialTheme.typography.labelMedium,
                color = colorResource(R.color.on_preview_surface),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
                    .background(colorResource(R.color.preview_bar_scrim), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}