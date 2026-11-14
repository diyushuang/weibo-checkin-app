package com.wcm.checkin

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
)