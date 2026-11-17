package com.wcm.checkin

/**
 * 超话互动任务的领域模型。
 *
 * 全部为纯数据 + 纯枚举，不碰 Android、不碰 prefs、不发请求，
 * 保证可以在 JVM 单测里直接构造与断言。
 *
 * 设计约束（来自 `docs/research/weibo-write-endpoints.md` 的实测结论）：
 * - 超话归属靠 `topic_id=1022:<tid>`，所以 [EngageConfig.topicId] 存的是裸 tid
 *   （形如 `1008088cf9880c8ec5f07f84c2f30e74d43574`），拼 `1022:` 前缀是客户端的事。
 * - 点赞不产生超话经验，发帖本身也不加分（只有被他人评论才加），
 *   这两条在 [EngagePlanner.quotaHints] 里必须对用户显式提示。
 */

/** 四个可执行动作。枚举声明顺序即默认执行顺序：评论 → 转发 → 点赞 → 发帖。 */
enum class EngageAction(val key: String, val label: String) {
    COMMENT("comment", "评论"),
    REPOST("repost", "转发"),
    LIKE("like", "点赞"),
    POST("post", "发帖"),
}

/**
 * 单个动作的终态。
 *
 * [UNCERTAIN] 是刻意独立出来的第三种态：发帖请求超时/断网时，**客户端无法自证
 * 这条帖子到底发出去没有**。把这种情形混进 FAILED 会导致重发（重复发帖），
 * 混进 OK 会导致计数虚高。它只能由用户到微博里亲眼确认后手工收敛。
 */
enum class ActionState(val label: String) {
    OK("成功"),
    FAILED("失败"),
    SKIPPED("跳过"),
    UNCERTAIN("结果未知"),

    /**
     * 已组好正文、等用户点「确认发送」。
     *
     * 只在 [EngageConfig.requireConfirmBeforePost] 打开时出现：此时执行环节
     * **不预占、不请求**，只把最终正文落进台账；点确认后由
     * `EngageService.publishConfirmed` 走与自动发帖完全相同的预占/收敛路径。
     */
    AWAITING_CONFIRM("待确认"),
}

/**
 * 写接口失败的分类。每类的处置规则见 [EngageGuard]。
 *
 * 分类依据全部来自实测请求体与响应形态（`docs/research/weibo-write-endpoints.md`）：
 * - `errno=404` + 「链接…无效」是**接口改版**信号，不是参数错误；
 * - `ok=-100` 是登录墙（weibo.com 回 `newlogin`，m 站回 `signin`）；
 * - 需要人工验证时必须停下，不做任何绕过。
 */
enum class EngageErrorKind {
    NONE,

    /** 登录墙：响应指向 signin/newlogin，或 body 是登录页 HTML。 */
    LOGIN_WALL,

    /** 接口明确要求登录（`ok=-100`）。 */
    NOT_LOGGED_IN,

    /** 目标超话未关注/未加入 —— 计分的前置条件不满足。 */
    TOPIC_NOT_FOLLOWED,

    /** 被超话主持人屏蔽或禁言。 */
    TOPIC_BLOCKED,

    /** 超话等级不足，该动作被拒。 */
    LEVEL_TOO_LOW,

    /** 操作频繁（`errno 10022/10023`、HTTP 429/432）。 */
    RATE_LIMITED,

    /** 需要身份验证/验证码 —— 一律停下交给人工。 */
    NEED_VERIFY,

    /** 内容被审核/违规拦截。换文案，不重试同一条。 */
    CONTENT_REJECTED,

    /** 参数错误（HTTP 400、「参数错误」）。 */
    PARAM_INVALID,

    /** 接口改版（`errno=404` + 「链接…无效」）—— 只能升级 App 解决。 */
    ENDPOINT_INVALID,

    /** 目标帖子已删除/不可评论。换下一个目标。 */
    TARGET_GONE,

    /** 已转发/已点赞/重复操作。按已成功记账。 */
    DUPLICATE_ACTION,

    /** 网络层失败（超时、DNS、断流）—— 结果可能未知。 */
    NETWORK,

    /** 服务端 5xx —— 结果可能未知。 */
    SERVER_ERROR,

    /** 文案池为空或全部不可用。 */
    NO_TEXT,

    /** 配置非法（未授权、超话 ID 非法、账号不存在）。 */
    CONFIG_INVALID,

    /** 熔断已触发。 */
    GUARD_TRIPPED,
    ;

    /** 这类失败是否意味着「这次请求可能已经生效」——发帖遇到时必须进 UNCERTAIN。 */
    val outcomeUnknown: Boolean
        get() = this == NETWORK || this == SERVER_ERROR
}

/** 熔断器给出的处置建议。 */
enum class Disposition {
    /** 继续下一动作。 */
    CONTINUE,

    /** 放弃当前动作，其它动作照跑（例如等级不足只挡发帖）。 */
    ABORT_ACTION,

    /** 终止整轮执行。 */
    ABORT_RUN,

    /** 必须人工介入（登录失效、需验证码），且当日不再自动重试。 */
    REQUIRE_HUMAN,

    /** 本轮先退避（限流），稍后再跑。 */
    RETRY_LATER,
}

/**
 * 任务配置。全字段带默认值——老版本存下的 JSON 缺字段时按默认值读，
 * 这是 `:data` 层反序列化的硬要求（见架构文档「数据兼容」不变量）。
 */
data class EngageConfig(
    val enabled: Boolean = false,
    /** 用户勾选风险声明的时间戳；0 表示没同意过，任何执行入口都应拒绝。 */
    val consentAt: Long = 0L,
    /** 声明文本版本号；升级文案后强制重新同意。 */
    val consentVersion: Int = 0,

    /** 执行账号 uid。必须落盘——`TopicViewModel.currentUid` 只存在内存，Worker 读不到。 */
    val accountUid: String = "",
    /** 目标超话的**完整 containerid**（`100808` + hash，形如 `1008088cf988…`）。 */
    val topicId: String = "",
    val topicName: String = "",

    val postEnabled: Boolean = true,
    val repostEnabled: Boolean = true,
    val commentEnabled: Boolean = true,
    val likeEnabled: Boolean = true,

    val postCount: Int = 1,
    val repostCount: Int = 2,
    val commentCount: Int = 4,
    val likeCount: Int = 3,

    /** 免打扰时段 [quietStartHour, quietEndHour)，跨夜用取模。默认 0..7 点不跑。 */
    val quietStartHour: Int = 0,
    val quietEndHour: Int = 7,

    /**
     * 打开 App 时是否自动补跑。
     *
     * **默认关闭**：开启任务不等于允许它自己动，用户必须显式打开这个开关，
     * 否则只能在任务页点「立即执行一次」。默认让程序自己发公开内容，
     * 风险太大，不适合做成默认行为。
     */
    val autoRunOnOpen: Boolean = false,

    val postGapMinMs: Long = 5 * 60_000L,
    val postGapMaxMs: Long = 8 * 60_000L,
    val repostGapMinMs: Long = 60_000L,
    val repostGapMaxMs: Long = 180_000L,
    val commentGapMinMs: Long = 25_000L,
    val commentGapMaxMs: Long = 60_000L,
    val likeGapMinMs: Long = 15_000L,
    val likeGapMaxMs: Long = 40_000L,
    /** 动作之间的额外间隔。 */
    val actionGapMinMs: Long = 5_000L,
    val actionGapMaxMs: Long = 20_000L,

    /** 连续失败达到该值即熔断。 */
    val failStreakAbort: Int = 3,
    /** 单轮动作数硬上限，防止配置异常导致一跑几十条。 */
    val maxActionsPerRun: Int = 15,

    /** 发帖前人工确认。默认 false：用户要求自动任务按固定文案直接发。 */
    val requireConfirmBeforePost: Boolean = false,
    /** 后台定时任务是否允许发帖。默认 true。 */
    val postInBackground: Boolean = true,

    /** 发帖是否同步到个人主页（对应 `sync_mblog`）。默认不同步。 */
    val syncToTimeline: Boolean = false,
    /** 追加到正文的视频链接；只作为文本，不上传、不校验域名。 */
    val videoUrl: String = "",

    /** 优先挑 0 评论帖（首评 +2 分）。 */
    val preferFirstComment: Boolean = true,
    /** >0 时同一条文案至少隔这么多天才复用。 */
    val strictTextGapDays: Int = 0,
    /** 单条文案长度上限；超限拒绝发送而非截断。 */
    val maxTextLen: Int = 1000,
    /** 单条评论长度上限（微博评论正文限制）。超限跳过该条，不截断。 */
    val maxCommentLen: Int = 140,
    /**
     * 单轮时间预算。到达后主动收尾，把剩余动作留待下一轮。
     *
     * 存在的理由：一轮含发帖间隔最坏可达 20 分钟以上，而 WorkManager 的
     * `CoroutineWorker` 超过 10 分钟会被系统直接停掉——那种停法既不写运行记录、
     * 也不回写换发 cookie，还可能停在「已预占未收敛」上。主动停在预算边界内，
     * 至少能让状态收敛。
     *
     * 默认 12 分钟（0.12.6 起，原 8 分钟）：发帖已提到最前，用户最在意的动作先完成；
     * 后面的评论/转发/点赞是「能补就补」，预算放宽可减少跨轮次数。
     * 后台执行时仍会被 Worker 侧压到 9 分钟以内（系统上限），见 `EngageWorker`。
     */
    val maxRunDurationMs: Long = 12 * 60_000L,
) {
    fun countOf(action: EngageAction): Int = when (action) {
        EngageAction.POST -> postCount
        EngageAction.REPOST -> repostCount
        EngageAction.COMMENT -> commentCount
        EngageAction.LIKE -> likeCount
    }

    fun enabledOf(action: EngageAction): Boolean = when (action) {
        EngageAction.POST -> postEnabled
        EngageAction.REPOST -> repostEnabled
        EngageAction.COMMENT -> commentEnabled
        EngageAction.LIKE -> likeEnabled
    }

    fun gapRangeOf(action: EngageAction): LongRange = when (action) {
        EngageAction.POST -> postGapMinMs..postGapMaxMs
        EngageAction.REPOST -> repostGapMinMs..repostGapMaxMs
        EngageAction.COMMENT -> commentGapMinMs..commentGapMaxMs
        EngageAction.LIKE -> likeGapMinMs..likeGapMaxMs
    }

    /** 配置是否具备执行条件。返回非空字符串即不可执行。 */
    fun validate(consentVersionExpected: Int = CONSENT_VERSION): String? = when {
        !enabled -> "任务未开启"
        consentAt <= 0L || consentVersion != consentVersionExpected -> "尚未确认风险声明"
        accountUid.isBlank() -> "未选择账号"
        !EngageSchedule.isValidTopicId(topicId) -> "超话 ID 非法"
        // 统一到「带 100808 的完整 containerid」：写接口的 topic_id 与帖子流的 flowId
        // 都要求这个形态，存半截 tid 会让请求悄悄发错对象。
        !EngageSchedule.isContainerTopicId(topicId) -> "超话 ID 缺少 containerid 前缀"
        listOf(postCount, repostCount, commentCount, likeCount).any { it < 0 } -> "次数不能为负"
        failStreakAbort < 1 -> "熔断阈值非法"
        maxActionsPerRun < 1 -> "单轮上限非法"
        maxTextLen !in 100..2000 -> "文案长度上限非法"
        maxCommentLen !in 20..1000 -> "评论长度上限非法"
        // 上限 20 分钟：手动执行只在 Activity 存活期间跑，不需要迁就 Worker 的 10 分钟；
        // 后台执行由 `EngageWorker` 另外压到 9 分钟以内。
        maxRunDurationMs !in 60_000L..20 * 60_000L -> "单轮时间预算非法（1–20 分钟）"
        quietStartHour !in 0..23 -> "免打扰开始时刻非法"
        quietEndHour !in 0..24 -> "免打扰结束时刻非法"
        listOf(
            postGapMinMs to postGapMaxMs,
            repostGapMinMs to repostGapMaxMs,
            commentGapMinMs to commentGapMaxMs,
            likeGapMinMs to likeGapMaxMs,
            actionGapMinMs to actionGapMaxMs,
        ).any { (min, max) -> min < 0L || max < min } -> "间隔区间非法（上限不能小于下限）"
        else -> null
    }

    companion object {
        /** 当前风险声明版本。文案改动时 +1，强制用户重新确认。 */
        const val CONSENT_VERSION = 1
    }
}

/** 文案来源。 */
enum class TextSource { MANUAL, IMPORT }

/**
 * 文案池里的一条。
 *
 * [digest] 是规范化正文的短哈希，用于去重（批量导入时最容易重复）。
 * 删除走软删（[deleted]），保留使用统计，便于用户判断哪些文案真的在用。
 */
data class EngageText(
    val id: String,
    val body: String,
    val createdAt: Long = 0L,
    val usedCount: Int = 0,
    val lastUsedAt: Long = 0L,
    /** 最后一次被使用那天的 dayKey（`yyyy-MM-dd`），用于「同日不复用」。 */
    val lastUsedDay: String = "",
    val deleted: Boolean = false,
    val source: TextSource = TextSource.MANUAL,
    val digest: String = EngageTextPool.digest(body),
)

/**
 * 一轮执行的实时进度。
 *
 * 为什么需要它：一轮动作含间隔最长 8 分钟，此前 `EngageService` 只在一轮**结束**
 * 时写运行记录，界面除了一行「正在执行…」之外没有任何可观测输出 —— 用户无法
 * 区分「在等间隔」「在跑」还是「已经挂了」。这里把阶段、当前动作、已完成条数
 * 与最近一条结果直接推给界面。
 *
 * 与 [EngageActionRecord] 不同，这是**过程态**：实时推给界面、不落盘
 * （落盘的是运行记录 [EngageRun]）。
 */
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
) {
    /** 「3/10 · 评论：成功」这样的单行进度。 */
    fun line(): String {
        val prefix = if (action != null) "${action.label} " else ""
        val counter = if (total > 0) "$done/$total" else ""
        val tail = listOf(prefix.trim(), counter).filter { it.isNotEmpty() }.joinToString(" ")
        return listOf(stage, tail, message)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    }
}

/** 一条动作的执行记录。 */
data class EngageActionRecord(
    val action: EngageAction,
    val seq: Int,
    val state: ActionState,
    val errorKind: EngageErrorKind = EngageErrorKind.NONE,
    /** 目标帖子 mid；发帖为 0。 */
    val targetMid: String = "",
    /** 发帖成功后返回的 mid。 */
    val remoteId: String = "",
    /** 用掉的文案 id，便于追溯。 */
    val textId: String = "",
    /** 脱敏后的错误说明，≤120 字。 */
    val message: String = "",
    val at: Long = 0L,
)

/** 一轮运行的小结。 */
data class EngageRun(
    val runId: String,
    val accountUid: String,
    val topicId: String,
    val topicName: String,
    val startedAt: Long,
    val finishedAt: Long = 0L,
    /** 终止原因；空串表示跑完计划内全部动作。 */
    val stopReason: String = "",
    val records: List<EngageActionRecord> = emptyList(),
) {
    val okCount: Int get() = records.count { it.state == ActionState.OK }
    val failCount: Int get() = records.count { it.state == ActionState.FAILED }
    val uncertainCount: Int get() = records.count { it.state == ActionState.UNCERTAIN }

    /** 「评论 3/4 · 转发 2/2 · 点赞 3/3 · 发帖 1/1」这样的单行摘要。 */
    fun summary(): String = EngageAction.entries.joinToString(" · ") { a ->
        val ok = records.count { it.action == a && it.state == ActionState.OK }
        val total = records.count { it.action == a }
        "${a.label} $ok/${total.coerceAtLeast(ok)}"
    }
}

/**
 * 当日计数。以「账号 + 日期」为键落盘，[done] 记录每个动作已成功多少次。
 *
 * [pending] 是写前预占（write-ahead）：发请求之前先把槽占住，
 * 进程被杀/超时也不会让下一次运行误以为「还没发过」而重复发帖。
 */
data class EngageDayCounter(
    val accountUid: String = "",
    val day: String = "",
    val done: Map<EngageAction, Int> = emptyMap(),
    /** 最近一次成功发帖的时刻，用于跨轮次强制最小间隔。 */
    val lastPostAt: Long = 0L,
    /** 预占未收敛的槽位。 */
    val pending: List<PendingSlot> = emptyList(),
) {
    fun doneOf(action: EngageAction): Int = done[action] ?: 0

    fun hasPendingPost(): Boolean = pending.any { it.action == EngageAction.POST }
}

/** 写前预占槽位。[key] 形如 `POST#3`，保证同一天同一序号不会被扣两次。 */
data class PendingSlot(
    val key: String,
    val action: EngageAction,
    val at: Long,
    val textId: String = "",
)

/**
 * 发帖台账：比运行记录活得更久，也更容易被人工核对。
 *
 * [body] 存的是**最终提交给服务端的完整正文快照**（含视频链接行与 `#超话名#`），
 * 这样事后可以回答「我到底发了什么」，而预览与实发是否一致也能靠它验证。
 */
data class EngagePostRecord(
    val id: String,
    val runId: String,
    val seq: Int,
    val at: Long,
    val accountUid: String,
    val accountName: String = "",
    val topicId: String,
    val topicName: String = "",
    /** 最终提交的完整正文。 */
    val body: String,
    /** 用了文案池里哪一条（软删后仍可追溯）。 */
    val textId: String = "",
    val textDigest: String = "",
    val state: ActionState,
    /** 成功时的微博 mid，可用于跳转查看。 */
    val remoteId: String = "",
    val errorKind: EngageErrorKind = EngageErrorKind.NONE,
    val message: String = "",
    /** 用户手工确认过「确实发出去了」，此时 remoteId 可能为空。 */
    val manualConfirmed: Boolean = false,
)
