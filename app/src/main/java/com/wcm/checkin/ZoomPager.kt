package com.wcm.checkin

import android.graphics.drawable.Drawable
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException

/**
 * 全屏大图翻页器：纯黑背景 + HorizontalPager + 现有 [ZoomableImageView]。
 *
 * - 放大态停用翻页手势，回到 1x 恢复（与旧 ViewPager2 行为一致）；
 * - 每页只在进入组合时发起一次解码，离开组合即取消（由 LaunchedEffect 作用域保证）；
 * - overlay 拿到当前页下标，用来画顶部计数/底部工具条。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ZoomPager(
    pageCount: Int,
    initialPage: Int,
    targetPage: Int,
    loadPage: suspend (Int) -> Drawable?,
    onPageChanged: (Int) -> Unit,
    contentDescription: String?,
    overlay: @Composable BoxScope.(Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (pageCount <= 0) return@Box
        val safeInitial = initialPage.coerceIn(0, pageCount - 1)
        val pagerState = rememberPagerState(initialPage = safeInitial) { pageCount }
        var zoomed by remember { mutableStateOf(false) }

        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect(onPageChanged)
        }
        LaunchedEffect(targetPage, pageCount) {
            if (pageCount > 0) {
                pagerState.scrollToPage(targetPage.coerceIn(0, pageCount - 1))
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
        ) { page ->
            ZoomPage(
                page = page,
                desc = contentDescription,
                loadPage = loadPage,
                onZoomChanged = { zoomed = it },
            )
        }
        overlay(pagerState.currentPage)
    }
}

@Composable
private fun ZoomPage(
    page: Int,
    desc: String?,
    loadPage: suspend (Int) -> Drawable?,
    onZoomChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val zoomView = remember { ZoomableImageView(context) }
    var loading by remember(page) { mutableStateOf(true) }

    AndroidView(
        factory = {
            zoomView.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                contentDescription = desc
                this.onZoomChanged = onZoomChanged
            }
        },
        update = { it.onZoomChanged = onZoomChanged },
        modifier = Modifier.fillMaxSize(),
    )

    LaunchedEffect(page) {
        zoomView.setImageDrawable(null)
        loading = true
        val drawable = try {
            loadPage(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        zoomView.setImageDrawable(drawable)
        loading = false
    }

    DisposableEffect(zoomView) {
        onDispose {
            zoomView.onZoomChanged = null
            zoomView.setImageDrawable(null)
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colorResource(R.color.on_preview_surface))
        }
    }
}