package com.wcm.checkin

import android.content.Context

/**
 * 全应用共享的 SharedPreferences 名称与入口。
 *
 * 账号表、面板设置、抓取设置同库不同键；拆成多个 Store 后仍必须指向同一个文件，
 * 否则升级/迁移会看到两套数据。
 */
internal object StorePrefs {
    private const val NAME = "wcm"

    fun get(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
