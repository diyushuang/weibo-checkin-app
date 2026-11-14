package com.wcm.checkin

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 远程大图预览（v0.9.17）：浏览页点小图后的全屏查看。
 *
 * 背景恒为纯黑，系统栏图标恒为浅色；左右翻页 + 双指缩放复用 [ZoomPager] /
 * [ZoomableImageView]（放大态停用翻页手势）。
 */
class RemotePreviewActivity : AppCompatActivity() {

    private val urls = ArrayList<String>()
    private val uiState = MutableStateFlow(RemotePreviewUiState())

    /** 已出过结果的页：失败提示只弹一次。 */
    private var failToasted = false
    private var lastPage = 0

    private val fullLoadSlot = Semaphore(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        savedInstanceState?.getStringArrayList(KEY_URLS)?.let { urls.addAll(it) }
        if (urls.isEmpty()) {
            intent.getStringArrayListExtra(EXTRA_URLS)?.let { urls.addAll(it) }
        }
        if (urls.isEmpty()) {
            finish()
            return
        }
        var startIndex = intent.getIntExtra(EXTRA_INDEX, 0)
        savedInstanceState?.let { startIndex = it.getInt(KEY_INDEX, startIndex) }
        lastPage = startIndex.coerceIn(0, urls.size - 1)
        uiState.value = RemotePreviewUiState(
            urls = urls.toList(),
            initialPage = lastPage,
        )

        val composeView = ComposeView(this)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            val state by uiState.collectAsState()
            WcmTheme {
                RemotePreviewScreen(
                    state = state,
                    isDoneAt = { index ->
                        val url = urls.getOrNull(index) ?: return@RemotePreviewScreen false
                        CrawlMarks.hasPic(WeiboImageUrl.pidFromImageUrl(url))
                    },
                    loadPage = { index -> loadPage(index) },
                    onPageChanged = { lastPage = it },
                )
            }
        }
        setContentView(composeView)

        // 索引快照读完后刷一次：进来时可能还没读完，那时先不标「已抓取」
        CrawlMarks.ensure(this) {
            if (!isFinishing && !isDestroyed) {
                uiState.value = uiState.value.copy(marksVersion = uiState.value.marksVersion + 1)
            }
        }
    }

    private suspend fun loadPage(index: Int): Drawable? {
        val url = urls.getOrNull(index) ?: return null
        return withContext(Dispatchers.IO) {
            val big = WeiboImageUrl.fullFromThumb(url).ifEmpty { url }
            val bmp = try {
                fullLoadSlot.withPermit { RemoteThumb.loadFull(big) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (bmp == null) {
                withContext(Dispatchers.Main) {
                    if (!failToasted && !isFinishing && !isDestroyed) {
                        failToasted = true
                        Toast.makeText(
                            applicationContext,
                            R.string.preview_remote_fail,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            @Suppress("UseKtx")
            bmp?.let { android.graphics.drawable.BitmapDrawable(null, it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_URLS, ArrayList(urls))
        outState.putInt(KEY_INDEX, lastPage)
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_INDEX = "index"
        private const val KEY_URLS = "remote_preview_urls"
        private const val KEY_INDEX = "remote_preview_index"

        /** 单帖最多看 18 张（与选图页的网格上限一致），再多就不是「看一眼」了。 */
        private const val MAX_PAGES = 18

        /** [urls] 是小图地址（orj360 等），本页自己换到大图段再加载。 */
        fun start(ctx: Context, urls: List<String>, index: Int) {
            if (urls.isEmpty()) return
            val safe = ArrayList(urls.take(MAX_PAGES))
            ctx.startActivity(
                Intent(ctx, RemotePreviewActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URLS, safe)
                    .putExtra(EXTRA_INDEX, index.coerceIn(0, safe.size - 1)),
            )
        }
    }
}