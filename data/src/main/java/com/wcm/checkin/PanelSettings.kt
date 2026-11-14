package com.wcm.checkin

import android.content.Context
import androidx.core.content.edit

/** 面板地址/令牌与「打开 App 自动签到」开关。 */
object PanelSettings {

    private const val KEY_PANEL_URL = "panel_url"
    private const val KEY_PANEL_TOKEN = "panel_token"
    private const val KEY_AUTO_ON_OPEN = "auto_on_open"
    private const val KEY_LAST_AUTO_AT = "last_auto_at"

    fun panelUrl(ctx: Context): String =
        SafePrefs.string(StorePrefs.get(ctx), KEY_PANEL_URL)

    fun panelToken(ctx: Context): String =
        SafePrefs.string(StorePrefs.get(ctx), KEY_PANEL_TOKEN)

    fun savePanel(ctx: Context, url: String, token: String) {
        StorePrefs.get(ctx).edit {
            putString(KEY_PANEL_URL, url.trim())
            putString(KEY_PANEL_TOKEN, token.trim())
        }
    }

    fun autoOnOpen(ctx: Context): Boolean =
        SafePrefs.bool(StorePrefs.get(ctx), KEY_AUTO_ON_OPEN, true)

    fun setAutoOnOpen(ctx: Context, value: Boolean) {
        StorePrefs.get(ctx).edit { putBoolean(KEY_AUTO_ON_OPEN, value) }
    }

    fun lastAutoAt(ctx: Context): Long =
        SafePrefs.long(StorePrefs.get(ctx), KEY_LAST_AUTO_AT, 0L)

    fun setLastAutoAt(ctx: Context, at: Long) {
        StorePrefs.get(ctx).edit { putLong(KEY_LAST_AUTO_AT, at) }
    }
}
