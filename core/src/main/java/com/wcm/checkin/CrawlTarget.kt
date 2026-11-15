package com.wcm.checkin

/**
 * 一个抓取目标：微博 UID + 展示名。
 *
 * 放在 `:core` 而不是 `:data`：它是 `CrawlSettingsPort` 的返回/参数类型，
 * 端口在 `:core`，模型就必须在 `:core`，否则端口签名会把 `:domain` 拽回对 `:data` 的依赖。
 */
data class CrawlTarget(val uid: String, val name: String)
