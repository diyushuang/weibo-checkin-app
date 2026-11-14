package com.wcm.checkin

data class ImagePreviewUiState(
    val count: Int = 0,
    val initialPage: Int = 0,
    val targetPage: Int = 0,
)

data class RemotePreviewUiState(
    val urls: List<String> = emptyList(),
    val initialPage: Int = 0,
    val marksVersion: Int = 0,
)