package com.wcm.checkin

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface ImagePreviewEvent {
    data class Toast(val text: String) : ImagePreviewEvent
    data object Finish : ImagePreviewEvent
}

/** 本地图库全屏预览状态与删除/分享（route 版）。 */

/**
 * 删除有顺序铁律：**先物理删除成功，再回退抓取索引**。反过来会出现「索引说没抓过、
 * 文件其实还在」或「索引说抓过、文件已经没了」两种脏状态。
 */
@HiltViewModel
class ImagePreviewViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ImagePreviewUiState())
    val state: StateFlow<ImagePreviewUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ImagePreviewEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ImagePreviewEvent> = _events.asSharedFlow()

    private val items = ArrayList<GalleryFiles.GalleryItem>()
    private val fullLoadSlot = Semaphore(2)
    private var treeUriRaw = ""
    private var started = false
    private var deleting = false

    private val previewMaxSize: Int by lazy {
        val dm = ctx.resources.displayMetrics
        maxOf(dm.widthPixels, dm.heightPixels).coerceIn(720, 2048)
    }

    fun start(tree: String, startIndex: Int, startName: String?) {
        if (started) return
        started = true
        treeUriRaw = tree
        if (tree.isEmpty()) {
            _events.tryEmit(ImagePreviewEvent.Finish)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val rows = try {
                GalleryFiles.listImages(ctx.contentResolver, tree.toUri())
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (rows.isEmpty()) {
                    _events.tryEmit(ImagePreviewEvent.Toast(ctx.getString(R.string.preview_load_fail)))
                    _events.tryEmit(ImagePreviewEvent.Finish)
                    return@withContext
                }
                items.addAll(rows)
                val pos = startName?.let { n -> items.indexOfFirst { it.name == n } }
                    ?.takeIf { it >= 0 } ?: startIndex
                val safe = pos.coerceIn(0, items.size - 1)
                _state.value = ImagePreviewUiState(
                    count = items.size,
                    initialPage = safe,
                    targetPage = safe,
                )
            }
        }
    }

    fun nameAt(index: Int): String = items.getOrNull(index)?.name.orEmpty()

    fun onPageChanged(index: Int) {
        // targetPage 同步成当前页：旋转重建时 ZoomPager 用 targetPage 做一次性定位，
        // 若仍停留在删除时的旧目标，会把用户拉回那一页。
        _state.update { it.copy(initialPage = index, targetPage = index) }
    }

    suspend fun loadPage(index: Int): Drawable? {
        val item = items.getOrNull(index) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                fullLoadSlot.withPermit {
                    ThumbLoader.fullDrawable(ctx.contentResolver, item.uri, previewMaxSize)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    fun share(index: Int) {
        val item = items.getOrNull(index) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(item.name)
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = android.content.ClipData.newRawUri("image", item.uri)
        }
        try {
            ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.preview_share)))
        } catch (e: Exception) {
            toast(ctx.getString(R.string.preview_share_fail))
        }
    }

    fun open(index: Int) {
        val item = items.getOrNull(index) ?: return
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(item.uri, mimeOf(item.name))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (e: Exception) {
            toast(ctx.getString(R.string.preview_open_fail))
        }
    }

    fun delete(index: Int) {
        if (deleting) return
        val item = items.getOrNull(index) ?: return
        deleting = true
        viewModelScope.launch(Dispatchers.IO) {
            var released = false
            try {
                val ok = try {
                    android.provider.DocumentsContract.deleteDocument(
                        ctx.contentResolver, item.uri,
                    )
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    // 顺序铁律：先物理删除成功，再回退索引。
                    runCatching { rollbackIndex(item.name) }
                }
                withContext(Dispatchers.Main) {
                    released = true
                    deleting = false
                    if (!ok) {
                        toast(ctx.getString(R.string.preview_delete_fail))
                        return@withContext
                    }
                    GalleryContent.changed = true
                    val at = items.indexOfFirst { it.uri == item.uri }
                    if (at < 0) {
                        if (items.isEmpty()) _events.tryEmit(ImagePreviewEvent.Finish)
                        return@withContext
                    }
                    items.removeAt(at)
                    if (items.isEmpty()) {
                        _events.tryEmit(ImagePreviewEvent.Finish)
                    } else {
                        val target = at.coerceIn(0, items.size - 1)
                        _state.value = ImagePreviewUiState(
                            count = items.size,
                            initialPage = _state.value.initialPage.coerceIn(0, items.size - 1),
                            targetPage = target,
                        )
                    }
                }
            } finally {
                if (!released) withContext(NonCancellable + Dispatchers.Main) { deleting = false }
            }
        }
    }

    private fun rollbackIndex(fileName: String) {
        if (treeUriRaw.isEmpty()) return
        val idx = CrawlIndex.open(ctx.filesDir, CrawlIndex.treeTagOf(treeUriRaw))
        try {
            idx.load()
            idx.onFileDeleted(fileName)
        } finally {
            idx.close()
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/*"
    }

    private fun toast(text: String) {
        _events.tryEmit(ImagePreviewEvent.Toast(text))
    }
}