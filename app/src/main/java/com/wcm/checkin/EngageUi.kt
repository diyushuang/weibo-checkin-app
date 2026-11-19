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
    /**
     * 三套文案库（发帖 / 评论 / 转发）。
     *
     * 默认值就把三个 key 建全，界面取值不必再判 null；[refresh] 之后同样是全集。
     */
    val texts: Map<TextKind, List<EngageText>> = TextKind.entries.associateWith { emptyList() },
    /** 当天 dayKey（`yyyy-MM-dd`），与 [counter] 同一口径。 */
    val today: String = "",
    /** 当日计数：各动作已成功次数与未收敛的预占。 */
    val counter: EngageDayCounter = EngageDayCounter(),
    val posts: List<EngagePostRecord> = emptyList(),
    val runs: List<EngageRun> = emptyList(),
    /** 软约束提示（评论超 4、转发超 2、点赞不记分…）。 */
    val hints: List<String> = emptyList(),
    /** 下一条将要发送的完整正文。 */
    val preview: String = "",
    val previewLength: Int = 0,
    /** 预览的提示或错误（文案库为空、正文超限），**不再用于「额度用完」**。 */
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
    val hasUncertain: Boolean get() = uncertainPosts.isNotEmpty()
    val hasAwaiting: Boolean get() = awaitingPosts.isNotEmpty()

    fun textsOf(kind: TextKind): List<EngageText> = texts[kind].orEmpty()

    /** 该库可用条数（软删不计）。 */
    fun availableTexts(kind: TextKind): Int = textsOf(kind).count { !it.deleted }

    val postTexts: List<EngageText> get() = textsOf(TextKind.POST)
}
