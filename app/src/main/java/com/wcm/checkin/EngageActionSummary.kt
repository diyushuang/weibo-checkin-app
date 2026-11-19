package com.wcm.checkin

/**
 * 单个动作的展示摘要：主页的「子任务入口」行与四个子页面共用同一份计算。
 *
 * 为什么放在 UI 层做纯函数，而不是塞进 [EngageUiState]：
 * - 这些量（剩余次数、最近一次结果）**每秒都在变**——运行中进度每秒 tick 一次，
 *   放进 StateFlow 会让整个 UiState 每秒重建，四个页面一起重组；
 * - 计算本身不碰 IO，成本可忽略；
 * - 只保留一份真相，避免「刷新时算一次、进度回调时不更新」。
 */
data class EngageActionSummary(
    val action: EngageAction,
    val mode: EngageActionMode,
    val count: Int,
    val done: Int,
    val lastAt: Long = 0L,
    val lastState: ActionState? = null,
    val lastMessage: String = "",
    /** 该动作消耗哪套文案库；null 表示不需要文案（点赞 / 关闭文案的转发）。 */
    val textKind: TextKind? = null,
    val pickMode: TextPickMode? = null,
    val availableTexts: Int = 0,
    /** 今日存在未收敛的发帖预占——此时发帖额度按 0 算。 */
    val pendingPost: Boolean = false,
) {
    val left: Int get() = (count - done).coerceAtLeast(0)

    val isOff: Boolean get() = mode == EngageActionMode.OFF

    /** 今日还能做的次数；发帖有未收敛预占时为 0。 */
    val remaining: Int get() = if (action == EngageAction.POST && pendingPost) 0 else left

    /** 「3/4」这样的额度展示。 */
    val quotaLabel: String get() = "$done/$count"

    /**
     * 最近一次结果的一句话；没有记录时返回空串。
     *
     * [today] 传当前 dayKey：不是今天的结果会带上日期前缀，否则「12:03 成功」
     * 会被误读成「今天中午成功过」，而实际是昨天跑的。
     */
    fun lastLabel(today: String = ""): String {
        if (lastAt <= 0L) return ""
        val state = lastState?.label.orEmpty()
        val day = EngageSchedule.dayKey(lastAt)
        val prefix = if (today.isNotEmpty() && day != today) EngageSchedule.dateLabel(lastAt) + " " else ""
        return prefix + EngageSchedule.clockLabel(lastAt) + " " + state
    }
}

/** 算某个动作的摘要。最近一次结果取运行记录里该动作的最新一条（只有 records 带状态）。 */
fun EngageUiState.summaryOf(action: EngageAction): EngageActionSummary {
    val kind = config.textKindOf(action)
    val latest = runs
        .asSequence()
        .flatMap { it.records.asSequence() }
        .filter { it.action == action }
        .maxByOrNull { it.at }
    return EngageActionSummary(
        action = action,
        mode = config.modeOf(action),
        count = config.countOf(action),
        done = counter.doneOf(action),
        lastAt = latest?.at ?: 0L,
        lastState = latest?.state,
        lastMessage = latest?.message.orEmpty(),
        textKind = kind,
        pickMode = kind?.let { config.textModeOf(it) },
        availableTexts = kind?.let { availableTexts(it) } ?: 0,
        pendingPost = action == EngageAction.POST && counter.hasPendingPost(),
    )
}

/**
 * 某个动作的独立日志。
 *
 * **必须带上 `action == null` 的全局流水**：登录失效、未关注超话、免打扰、配置非法、
 * 文案库为空全打在没有 action 的流水上。只过滤 `action == X` 的话，用户点进评论页
 * 遇到「登录态已失效」会看到零条日志，反而以为页面坏了。
 *
 * [includeGlobal] 只是给界面一个「只看本动作」的开关，默认包含。
 */
fun EngageUiState.logsOf(
    action: EngageAction,
    includeGlobal: Boolean = true,
    limit: Int = 120,
): List<EngageLogEntry> {
    val live = progress?.logs.orEmpty()
    val history = runs.flatMap { it.logs }
    return (live + history)
        .filter { it.action == action || (includeGlobal && it.action == null) }
        .distinctBy { "${it.at}|${it.level}|${it.action}|${it.seq}|${it.text}" }
        .sortedBy { it.at }
        .takeLast(limit)
}
