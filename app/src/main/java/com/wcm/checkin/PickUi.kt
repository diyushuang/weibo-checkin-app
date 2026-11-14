package com.wcm.checkin

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