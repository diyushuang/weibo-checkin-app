package com.wcm.checkin

/**
 * 一轮执行的**过程态**：阶段、步骤清单、实时日志、进度与预计剩余时间。
 *
 * 为什么需要它（0.12.x 的真实问题）：此前一轮动辄跑十几分钟，界面只有一行
 * 「正在执行…」，用户分不清「在等间隔」「在请求」「已经挂了」；跑完之后运行记录里
 * 只剩几条动作结果，没有任何过程信息，出问题只能靠 logcat。
 *
 * 展示模型参考 GitHub Actions 的 job 页（步骤名 + 状态 + 每步耗时 + 可折叠日志流），
 * 具体落到三块：
 * 1. [steps]：固定步骤的清单，每步带状态与耗时 —— 一眼看出卡在哪一步；
 * 2. [logs]：带时间戳、级别、动作序号的流水 —— 一眼看出刚才发生了什么；
 * 3. [done]/[total] + [etaMs] + [waitUntilMs]：还要多久、正在等几秒。
 *
 * 与 [EngageActionRecord] 的分工：本类型是过程态（实时、可丢），
 * [EngageActionRecord] 是结果态（落进运行记录）。跑完后 [EngageRun] 会把过程态
 * 一并快照下来，所以「跑完还能回看过程」也是成立的。
 */

/** 这一轮是谁触发的。影响界面文案，也影响是否受「自动」开关约束。 */
enum class EngageTrigger(val label: String) {
    MANUAL_ALL("手动执行"),
    MANUAL_ONLY("单项执行"),
    AUTO("自动执行"),
}

enum class EngageStepState(val label: String) {
    PENDING("待执行"),
    RUNNING("进行中"),
    OK("完成"),
    WARN("有提示"),
    FAILED("失败"),
    SKIPPED("跳过"),
}

/** 步骤清单里的一步。耗时由 [startedAt]/[endedAt] 现场算，避免存冗余字段。 */
data class EngageStep(
    val id: String,
    val title: String,
    val state: EngageStepState = EngageStepState.PENDING,
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val detail: String = "",
) {
    val isActive: Boolean get() = state == EngageStepState.RUNNING || state == EngageStepState.PENDING
    val isDone: Boolean get() = state != EngageStepState.RUNNING && state != EngageStepState.PENDING

    fun elapsedMs(now: Long): Long {
        if (startedAt <= 0L) return 0L
        val end = if (endedAt > 0L) endedAt else now
        return (end - startedAt).coerceAtLeast(0L)
    }

    fun elapsedLabel(now: Long): String =
        if (startedAt <= 0L) "" else EngagePacing.formatDuration(elapsedMs(now))
}

enum class EngageLogLevel(val label: String) {
    INFO("信息"),
    STEP("步骤"),
    OK("成功"),
    WAIT("等待"),
    WARN("警告"),
    ERROR("错误"),
}

/** 一条流水。`action`/`seq` 非空时界面会拼出「评论#2」这样的前缀。 */
data class EngageLogEntry(
    val at: Long,
    val level: EngageLogLevel = EngageLogLevel.INFO,
    val action: EngageAction? = null,
    val seq: Int = 0,
    val text: String = "",
) {
    fun clock(): String = EngageSchedule.clockLabel(at)

    /** 「12:03:41  评论#2  成功」——界面按段上色，这里只给纯文本兜底。 */
    fun line(): String = buildString {
        append(clock())
        append("  ")
        val target = action?.let { if (seq > 0) "${it.label}#$seq" else it.label }
        if (!target.isNullOrEmpty()) {
            append(target)
            append("  ")
        }
        append(text)
    }
}

data class EngageProgress(
    /** 阶段说明，例如「校验登录态」「拉取超话帖子流」「执行动作」。 */
    val stage: String = "",
    /** 正在执行的动作；为空表示还在准备阶段。 */
    val action: EngageAction? = null,
    /** 已完成（含失败/跳过）的动作数。 */
    val done: Int = 0,
    /** 本轮计划动作总数。 */
    val total: Int = 0,
    /** 最近一条结果的一句话说明。 */
    val message: String = "",
    /** 步骤清单（GitHub Actions 风格的 checklist）。 */
    val steps: List<EngageStep> = emptyList(),
    /** 实时流水，超出上限时丢最旧的。 */
    val logs: List<EngageLogEntry> = emptyList(),
    val startedAt: Long = 0L,
    /** 非 0 表示已收尾。 */
    val finishedAt: Long = 0L,
    /** 非 0 表示正在等间隔，界面据此显示倒计时。 */
    val waitUntilMs: Long = 0L,
    val trigger: EngageTrigger = EngageTrigger.MANUAL_ALL,
    /** 预计还要多久；-1 表示还算不出来。 */
    val etaMs: Long = -1L,
) {
    val isFinished: Boolean get() = finishedAt > 0L

    val isWaiting: Boolean get() = waitUntilMs > 0L

    /** 0f..1f。总数为 0 时按「准备阶段」处理成 0。 */
    val fraction: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    val percent: Int get() = (fraction * 100).toInt()

    val elapsedMs: Long
        get() {
            if (startedAt <= 0L) return 0L
            val end = if (finishedAt > 0L) finishedAt else System.currentTimeMillis()
            return (end - startedAt).coerceAtLeast(0L)
        }

    /** 正在跑的步骤（没有则取最后一个进行中的，再没有取最后一步）。 */
    fun currentStep(): EngageStep? =
        steps.firstOrNull { it.state == EngageStepState.RUNNING } ?: steps.lastOrNull()

    /** 「3/10 · 评论：成功」这样的单行进度（保留给通知/Toast 这类无排版位置）。 */
    fun line(): String {
        val prefix = if (action != null) "${action.label} " else ""
        val counter = if (total > 0) "$done/$total" else ""
        val tail = listOf(prefix.trim(), counter).filter { it.isNotEmpty() }.joinToString(" ")
        return listOf(stage, tail, message)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    }

    /** 追加一条流水；超过 [MAX_LOGS] 条时丢弃最旧的，避免长期运行把内存撑大。 */
    fun log(entry: EngageLogEntry, max: Int = MAX_LOGS): EngageProgress {
        val next = logs + entry
        return copy(logs = if (next.size > max) next.takeLast(max) else next)
    }

    fun log(
        at: Long,
        level: EngageLogLevel,
        text: String,
        action: EngageAction? = this.action,
        seq: Int = 0,
        max: Int = MAX_LOGS,
    ): EngageProgress = log(EngageLogEntry(at, level, action, seq, text), max)

    /** 把某一步置为进行中；不存在就追加到末尾（步骤顺序即首次出现顺序）。 */
    fun stepStart(id: String, title: String, now: Long): EngageProgress {
        val exists = steps.any { it.id == id }
        val next = if (exists) {
            steps.map {
                if (it.id == id) {
                    it.copy(state = EngageStepState.RUNNING, startedAt = now, endedAt = 0L, detail = "")
                } else {
                    it
                }
            }
        } else {
            steps + EngageStep(id, title, EngageStepState.RUNNING, startedAt = now)
        }
        return copy(steps = next, stage = title)
    }

    /** 结束某一步。找不到就忽略（例如被提前终止的步骤从未开始）。 */
    fun stepFinish(
        id: String,
        state: EngageStepState,
        now: Long,
        detail: String = "",
    ): EngageProgress {
        if (steps.none { it.id == id }) return this
        return copy(
            steps = steps.map {
                if (it.id == id) {
                    it.copy(
                        state = state,
                        startedAt = if (it.startedAt > 0L) it.startedAt else now,
                        endedAt = now,
                        detail = detail,
                    )
                } else {
                    it
                }
            }
        )
    }

    /** 把所有还没结束的步骤收尾（异常/熔断时用，避免界面永远显示「进行中」）。 */
    fun closeOpenSteps(state: EngageStepState, now: Long, detail: String = ""): EngageProgress =
        copy(
            steps = steps.map {
                if (it.state == EngageStepState.RUNNING || it.state == EngageStepState.PENDING) {
                    it.copy(
                        state = state,
                        startedAt = if (it.startedAt > 0L) it.startedAt else now,
                        endedAt = now,
                        detail = if (it.detail.isEmpty()) detail else it.detail,
                    )
                } else {
                    it
                }
            }
        )

    fun waiting(untilMs: Long): EngageProgress = copy(waitUntilMs = untilMs)

    fun clearWait(): EngageProgress = if (waitUntilMs == 0L) this else copy(waitUntilMs = 0L)

    fun progress(done: Int, total: Int): EngageProgress = copy(done = done, total = total)

    fun finish(now: Long): EngageProgress =
        copy(finishedAt = now, waitUntilMs = 0L, etaMs = 0L, action = null)

    /**
     * 剩余时间提示。
     *
     * 用「已用时间 / 已完成数」推算单条均值，再乘剩余条数——不做复杂预测，
     * 因为这里的耗时主要由间隔构成，均值已经足够准。已完成数为 0 时退回 [etaMs]
     * （服务端按计划估算的值）。
     */
    fun etaLabel(now: Long = System.currentTimeMillis()): String {
        if (isFinished) return ""
        val left = total - done
        if (left <= 0) return ""
        val estimate = when {
            done > 0 && elapsedMs > 0 -> elapsedMs / done * left
            etaMs > 0 -> etaMs
            else -> return ""
        }
        return "约剩 ${EngagePacing.formatDuration(estimate)}"
    }

    companion object {
        /** 内存里保留的流水条数上限。落盘时另外再截（见 `EngageCodec`）。 */
        const val MAX_LOGS = 140
    }
}
