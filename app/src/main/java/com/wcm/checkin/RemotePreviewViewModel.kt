package com.wcm.checkin

import android.content.Context
import android.graphics.drawable.Drawable
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface RemotePreviewEvent {
    data class Toast(val text: String) : RemotePreviewEvent
}

/** 远程大图预览状态与加载（route 版）。 */
@HiltViewModel
class RemotePreviewViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(RemotePreviewUiState())
    val state: StateFlow<RemotePreviewUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RemotePreviewEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<RemotePreviewEvent> = _events.asSharedFlow()

    private val fullLoadSlot = Semaphore(2)
    private var started = false
    private var failToasted = false

    fun start(urls: List<String>, index: Int) {
        if (started) return
        started = true
        val safe = urls.take(18)
        if (safe.isEmpty()) return
        _state.value = RemotePreviewUiState(
            urls = safe,
            initialPage = index.coerceIn(0, safe.size - 1),
        )
        CrawlMarks.ensure(ctx) {
            _state.update { it.copy(marksVersion = it.marksVersion + 1) }
        }
    }

    fun urlAt(index: Int): String = _state.value.urls.getOrNull(index).orEmpty()

    fun onPageChanged(index: Int) {
        _state.update { it.copy(initialPage = index) }
    }

    fun isDone(index: Int): Boolean {
        val url = _state.value.urls.getOrNull(index) ?: return false
        return CrawlMarks.hasPic(WeiboImageUrl.pidFromImageUrl(url))
    }

    suspend fun loadPage(index: Int): Drawable? {
        val url = _state.value.urls.getOrNull(index) ?: return null
        return withContext(Dispatchers.IO) {
            val big = WeiboImageUrl.fullFromThumb(url).ifEmpty { url }
            val bmp = try {
                fullLoadSlot.withPermit { RemoteThumb.loadFull(big) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (bmp == null && !failToasted) {
                failToasted = true
                _events.tryEmit(
                    RemotePreviewEvent.Toast(ctx.getString(R.string.preview_remote_fail)),
                )
            }
            @Suppress("UseKtx")
            bmp?.let { android.graphics.drawable.BitmapDrawable(null, it) }
        }
    }
}