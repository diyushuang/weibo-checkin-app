package com.wcm.checkin

/**
 * 选图页的 UI 状态模型（图项、整页状态）。
 */
data class PickItemUi(
    val picId: String,
    val thumbUrl: String,
    val isLive: Boolean,
    val isGif: Boolean,
    val done: Boolean,
    val selected: Boolean,
)

data class PickUiState(
    val items: List<PickItemUi> = emptyList(),
    val loading: Boolean = false,
    val emptyVisible: Boolean = false,
    val emptyText: String = "",
    val selectedCount: Int = 0,
    val allSelected: Boolean = false,
    val submitting: Boolean = false,
    val initialScrollIndex: Int = 0,
)