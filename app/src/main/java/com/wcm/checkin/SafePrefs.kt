package com.wcm.checkin

import android.content.SharedPreferences

/**
 * SharedPreferences 的类型安全读取。
 *
 * 为什么需要：`getBoolean/getLong/getString` 在**值的类型与调用不符**时会抛
 * `ClassCastException`（SharedPreferencesImpl 内部直接强转）。这可能来自：
 * - 旧版本把某个键存成字符串、新版本按布尔读（或反之）；
 * - 用户从别的渠道还原了一份 prefs 文件；
 * - 文件被手工改坏 / 半截写入。
 *
 * 而这些读取大多发生在**启动路径**上（首页 onViewCreated 第一件事就是读
 * `auto_on_open`）。一个类型不对的值就能让 App 一打开就闪退，且此时用户
 * 连界面都进不去，没有任何入口去重置 —— 属于最糟的故障形态。
 *
 * 口径：读不到 / 类型不符 → 当作默认值，绝不抛给调用方。写入侧保持强类型，
 * 让错误尽早暴露在开发期而不是用户设备上。
 */
internal object SafePrefs {

    fun string(p: SharedPreferences, key: String, def: String = ""): String =
        try {
            p.getString(key, def) ?: def
        } catch (e: ClassCastException) {
            def
        }

    fun bool(p: SharedPreferences, key: String, def: Boolean): Boolean =
        try {
            p.getBoolean(key, def)
        } catch (e: ClassCastException) {
            def
        }

    fun long(p: SharedPreferences, key: String, def: Long): Long =
        try {
            p.getLong(key, def)
        } catch (e: ClassCastException) {
            def
        }
}
