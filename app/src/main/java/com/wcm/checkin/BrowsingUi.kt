package com.wcm.checkin

/**
 * 浏览博主页的 UI 状态模型（帖子、缩略图、博主 chip、整页状态）。
 *
 * `BrowseThumbUi.done` 就是「这张已经抓过」的小勾来源，由 `CrawlMarks` 的快照填充。
 */
data class BrowseThumbUi(val url: String, val done: Boolean)

data class BrowsePostUi(
    val bid: String,
    val meta: String,
    val text: String,
    val thumbs: List<BrowseThumbUi>,
    val moreCount: Int,
    val selected: Boolean,
)

data class BrowseAuthorChipUi(val uid: String, val label: String)

data class BrowsingUiState(
    val title: String = "",
    val subtitle: String = "",
    val input: String = "",
    val targets: List<BrowseAuthorChipUi> = emptyList(),
    val filterOriginal: Boolean = true,
    val filterPic: Boolean = true,
    val countText: String = "",
    val hint: String = "",
    val posts: List<BrowsePostUi> = emptyList(),
    val selectMode: Boolean = false,
    val selectedCount: Int = 0,
    val submitting: Boolean = false,
    val loading: Boolean = false,
    val emptyVisible: Boolean = false,
    val emptyText: String = "",
    val initialScrollIndex: Int = 0,
)