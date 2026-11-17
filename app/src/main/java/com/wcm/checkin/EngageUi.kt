package com.wcm.checkin

/**
 * 任务页的 UI 状态。
 *
 * 纯数据、不含 Compose，便于在 ViewModel 里整体替换（架构约定：`*Ui.kt` 只放状态模型）。
 */
data class EngageAccountOption(
    val uid: String,
    val name: String,
)

data class EngageTopicOption(
    val id: String,
    val name: String,
    val level: Int = 0,
)

data class EngageUiState(
    val loading: Boolean = true,
    val config: EngageConfig = EngageConfig(),
    val accounts: List<EngageAccountOption> = emptyList(),
    val topics: List<EngageTopicOption> = emptyList(),
    val topicsLoading: Boolean = false,
    val texts: List<EngageText> = emptyList(),
    val posts: List<EngagePostRecord> = emptyList(),
    val runs: List<EngageRun> = emptyList(),
    /** 软约束提示（评论超 4、转发超 2、点赞不记分…）。 */
    val hints: List<String> = emptyList(),
    /** 下一条将要发送的完整正文。 */
    val preview: String = "",
    val previewLength: Int = 0,
    val previewError: String = "",
    val running: Boolean = false,
    /** 执行中的实时进度；一轮结束后保留最后一条，便于回看停在哪个动作。 */
    val progress: EngageProgress? = null,
    val showConsent: Boolean = false,
    /** 一次性提示（执行结果、导入结果），展示后由界面清空。 */
    val message: String = "",
) {
    val enabled: Boolean get() = config.enabled
    val uncertainPosts: List<EngagePostRecord> get() = posts.filter { it.state == ActionState.UNCERTAIN }
    val awaitingPosts: List<EngagePostRecord> get() = posts.filter { it.state == ActionState.AWAITING_CONFIRM }
    val availableTexts: Int get() = texts.count { !it.deleted }
    val hasUncertain: Boolean get() = uncertainPosts.isNotEmpty()
    val hasAwaiting: Boolean get() = awaitingPosts.isNotEmpty()
}
