package com.wcm.checkin

/**
 * 本地预览与远程预览共用的 UI 状态模型。两者只有图片来源不同，翻页/缩放/工具条行为一致，
 * 状态形状也就保持一致，避免两份几乎相同的 State 各自漂移。
 */
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