package com.wcm.checkin

enum class TopicFilter { ALL, PENDING, DONE, LEVEL7 }

enum class TopicSort { PENDING_FIRST, LEVEL_DESC, NAME }

data class TopicPillUi(val uid: String, val label: String, val selected: Boolean)

data class TopicFilterUi(val filter: TopicFilter, val label: String, val selected: Boolean)

data class TopicRowUi(
    val uid: String,
    val key: String,
    val name: String,
    val levelLabel: String,
    val levelTier: Int,
    val meta: String,
    val done: Boolean,
    val expanded: Boolean,
    val intro: String = "",
    val stats: String = "",
    val detailHint: String = "",
    val detailLoading: Boolean = false,
)

data class TopicUiState(
    val pills: List<TopicPillUi> = emptyList(),
    val filters: List<TopicFilterUi> = emptyList(),
    val sort: TopicSort = TopicSort.PENDING_FIRST,
    val metricTotal: String = "—",
    val metricDone: String = "—",
    val metricPending: String = "—",
    val progress: Float = 0f,
    val loading: Boolean = false,
    val subtitle: String = "",
    val status: String = "",
    val rows: List<TopicRowUi> = emptyList(),
    val showError: Boolean = false,
    val errorText: String = "",
    val showEmpty: Boolean = false,
    val emptyText: String = "",
    val emptyActionVisible: Boolean = false,
)