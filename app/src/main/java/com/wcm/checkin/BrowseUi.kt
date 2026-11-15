package com.wcm.checkin

/**
 * 图库浏览页的 UI 状态模型：分组方式、排序、类型筛选三个枚举 + 网格项 + 整页状态。
 *
 * 页面只认这里的类型，不直接持有 `:data` 的 `GalleryFiles` 结果 —— 数据层换了结构
 * （比如以后加视频时长、加分辨率）只需在映射处加字段，Screen 不用改。
 * `scrollIndex` 是上一次离开本页时网格的首可见项下标，重新进入时作为起始位置：
 * 不存它的话，看第 200 张图退出来再进去就回顶了。
 */
enum class GalleryGroupMode { NONE, DATE, AUTHOR }

enum class BrowseSort { NEWEST, OLDEST, NAME_ASC, SIZE_DESC, SIZE_ASC }

enum class BrowseKind { ALL, STILL, GIF }

sealed interface BrowseItemUi {
    data class Header(val key: String, val title: String) : BrowseItemUi
    data class Cell(
        val uri: String,
        val name: String,
        val ext: String,
    ) : BrowseItemUi
}

data class BrowseFilterChipUi(
    val kind: BrowseKind?,
    val author: String,
    val label: String,
    val selected: Boolean,
)

data class GalleryBrowseUiState(
    val title: String = "",
    val subtitle: String = "",
    val searchOpen: Boolean = false,
    val query: String = "",
    val filters: List<BrowseFilterChipUi> = emptyList(),
    val spanCount: Int = 3,
    val items: List<BrowseItemUi> = emptyList(),
    val selectMode: Boolean = false,
    val selected: Set<String> = emptySet(),
    val selectedCount: Int = 0,
    val emptyVisible: Boolean = false,
    val emptyText: String = "",
    val scrollRequest: Int = 0,
    val scrollIndex: Int = 0,
    /** 上一次离开本页时网格的首可见项下标；重新进入组合时作为起始位置。 */
    val initialScrollIndex: Int = 0,
)