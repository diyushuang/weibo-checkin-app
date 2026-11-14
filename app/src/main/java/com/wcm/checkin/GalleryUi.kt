package com.wcm.checkin

data class GalleryTargetUi(
    val uid: String,
    val name: String,
    val progress: String,
)

data class GalleryProgressUi(
    val ratio: Float,
    val step: String,
    val detail: String,
)

data class GalleryUiState(
    val galleryCount: String = "",
    val dirPath: String = "",
    val dirStatus: String = "",
    val targetsTitle: String = "",
    val targets: List<GalleryTargetUi> = emptyList(),
    val targetsEmptyHint: String = "",
    val busy: Boolean = false,
    val running: Boolean = false,
    val modeAlbum: Boolean = true,
    val modeLabel: String = "",
    val autoAfterCheckin: Boolean = false,
    val progress: GalleryProgressUi? = null,
    val retryCount: Int = 0,
    val retryText: String = "",
    val log: LogCardUi = LogCardUi(),
)