package com.wcm.checkin

/**
 * 抓图模式的取值常量。
 *
 * 放在 `:core` 是因为 `:domain` 的 `CrawlService` 要按模式分派，而常量原本跟着
 * `CrawlSettings`（`:data`）走。字符串值必须与历史 prefs 里存的完全一致——
 * 改成别的值等于让老用户的模式设置回落成默认。
 */
object CrawlModes {
    /** 相册墙模式（游客身份，默认）。 */
    const val ALBUM = "album"

    /** 时间线模式（原实现，兜底）。 */
    const val TIMELINE = "timeline"
}
