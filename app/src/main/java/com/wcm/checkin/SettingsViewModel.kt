package com.wcm.checkin

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SettingsUiState(
    val panelUrl: String = "",
    val panelToken: String = "",
    val autoOnOpen: Boolean = true,
    val crawlGif: Boolean = true,
    val autoRelogin: Boolean = true,
    val urlError: String? = null,
    val about: String = "",
)

sealed interface SettingsEvent {
    /** 保存成功；[parsed] 为 null 表示用户留空关闭了面板同步。 */
    data class Saved(val parsed: PanelUrl.Parsed?) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    private fun load(): SettingsUiState {
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        return SettingsUiState(
            panelUrl = PanelSettings.panelUrl(ctx),
            panelToken = PanelSettings.panelToken(ctx),
            autoOnOpen = PanelSettings.autoOnOpen(ctx),
            crawlGif = CrawlSettings.crawlGif(ctx),
            autoRelogin = AccountStore.autoReloginEnabled(ctx),
            about = ctx.getString(R.string.settings_about_body, version),
        )
    }

    fun updateUrl(value: String) =
        _state.update { it.copy(panelUrl = value, urlError = null) }

    fun updateToken(value: String) = _state.update { it.copy(panelToken = value) }

    fun setAutoOnOpen(value: Boolean) {
        PanelSettings.setAutoOnOpen(ctx, value)
        _state.update { it.copy(autoOnOpen = value) }
    }

    fun setCrawlGif(value: Boolean) {
        CrawlSettings.setCrawlGif(ctx, value)
        _state.update { it.copy(crawlGif = value) }
    }

    fun setAutoRelogin(value: Boolean) {
        AccountStore.setAutoReloginEnabled(ctx, value)
        _state.update { it.copy(autoRelogin = value) }
    }

    fun save() {
        val current = _state.value
        val raw = current.panelUrl.trim()
        // token 也要 trim：从剪贴板粘贴常带尾部换行，原样存进去每次同步都 401
        val token = current.panelToken.trim()
        if (raw.isEmpty()) {
            // 留空 = 关闭面板同步
            PanelSettings.savePanel(ctx, "", token)
            _state.update { it.copy(panelUrl = "", panelToken = token, urlError = null) }
            _events.tryEmit(SettingsEvent.Saved(null))
            return
        }
        val parsed = try {
            PanelUrl.parse(raw)
        } catch (e: Exception) {
            // 公网明文、非法 scheme 等在这里就被挡下，不会等到同步时才失败
            _state.update { it.copy(urlError = e.message) }
            return
        }
        PanelSettings.savePanel(ctx, parsed.base, token)
        // 回填规范化后的值：用户输入 "https://host/" 会被解析成 base
        _state.update { it.copy(panelUrl = parsed.base, panelToken = token, urlError = null) }
        _events.tryEmit(SettingsEvent.Saved(parsed))
    }
}
