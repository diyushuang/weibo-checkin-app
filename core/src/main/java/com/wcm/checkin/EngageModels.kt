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

/** 四个可执行动作。枚举声明顺序即界面展示顺序：评论 → 转发 → 点赞 → 发帖。 */
enum class EngageAction(val key: String, val label: String) {
    COMMENT("comment", "评论"),
    REPOST("repost", "转发"),
    LIKE("like", "点赞"),
    POST("post", "发帖"),
}

/**
 * 单个动作的参与方式（三态）。
 *
 * 0.12.x 只有「开启 / 关闭」两态，四个动作被绑在同一轮里跑，用户无法只让点赞自动、
 * 发帖必须自己点。三态把「要不要做」和「能不能自己动」拆成两件事：
 * - [OFF]    —— 完全不做，也不出现在计划里；
 * - [MANUAL] —— 只响应「单独执行」按钮，自动轮次（打开 App / 后台）会跳过；
 * - [AUTO]   —— 参与自动轮次，也能手动执行。
 *
 * 默认 [AUTO]：升级前的用户行为不变，不会因为加了开关就少做动作。
 */
enum class EngageActionMode(val label: String, val hint: String) {
    OFF("关闭", "本轮不执行这个动作"),
    MANUAL("仅手动", "只有点「单独执行」才做，自动轮次跳过"),
    AUTO("自动", "自动轮次与手动执行都会做"),
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

    /**
     * 中文短语。
     *
     * 枚举名只适合 logcat，日志与界面要给用户看，否则「失败（TARGET_GONE）」
     * 这种输出等于没说。集中在这里，避免每个调用点各写一份 when。
     */
    val label: String
        get() = when (this) {
            NONE -> "正常"
            LOGIN_WALL -> "登录墙拦截"
            NOT_LOGGED_IN -> "未登录"
            TOPIC_NOT_FOLLOWED -> "未关注超话"
            TOPIC_BLOCKED -> "被屏蔽或禁言"
            LEVEL_TOO_LOW -> "超话等级不足"
            RATE_LIMITED -> "操作频繁被限流"
            NEED_VERIFY -> "需要身份验证"
            CONTENT_REJECTED -> "内容被拦截"
            PARAM_INVALID -> "参数错误"
            ENDPOINT_INVALID -> "接口已变更"
            TARGET_GONE -> "目标已失效"
            DUPLICATE_ACTION -> "重复操作"
            NETWORK -> "网络异常"
            SERVER_ERROR -> "服务端异常"
            NO_TEXT -> "无可用文案"
            CONFIG_INVALID -> "配置非法"
            GUARD_TRIPPED -> "已熔断"
        }
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

    /**
     * 各动作是否参与**自动**轮次（打开 App 补跑 / 后台 Worker）。
     *
     * 与 `*Enabled` 组合成 [EngageActionMode] 三态。手动「单独执行」不受这里的限制——
     * 用户主动点了按钮，就不该再被自动开关挡住。
     */
    val postAuto: Boolean = true,
    val repostAuto: Boolean = true,
    val commentAuto: Boolean = true,
    val likeAuto: Boolean = true,

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

    /**
     * 各类动作之间的间隔区间。
     *
     * 默认值 = [EngagePace.FAST]（0.14.0 起）：发帖 5–15 秒、评论 1–10 秒。
     * 更慢的三档（稳健/均衡/慢速）与更快的一档（极速）都保留成预设，用户可在任务页一键切换。
     *
     * 历史默认值有两代：0.12.x 的 `5–8 分钟`（现为 [EngagePace.SLOW]）与
     * 0.13.x 的 `1–2 分钟`（现为 [EngagePace.GENTLE]）。这两代数值都只可能来自默认值
     * （当时没有 UI 入口），所以 `EngageCodec` 按「整组相等」做一次性迁移（见 `GAP_VERSION`）。
     */
    val postGapMinMs: Long = 5_000L,
    val postGapMaxMs: Long = 15_000L,
    val repostGapMinMs: Long = 5_000L,
    val repostGapMaxMs: Long = 12_000L,
    val commentGapMinMs: Long = 1_000L,
    val commentGapMaxMs: Long = 10_000L,
    val likeGapMinMs: Long = 2_000L,
    val likeGapMaxMs: Long = 6_000L,
    /** 动作之间的额外间隔。 */
    val actionGapMinMs: Long = 1_000L,
    val actionGapMaxMs: Long = 3_000L,

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

    /**
     * 三套文案库各自的取用方式。
     *
     * 发帖适合「顺序」（自己能预判、便于核对），评论/转发适合「随机」
     * （同一句话天天出现更像机器）。默认都用顺序：可预期、可测试。
     */
    val postTextMode: TextPickMode = TextPickMode.SEQUENTIAL,
    val commentTextMode: TextPickMode = TextPickMode.SEQUENTIAL,
    val repostTextMode: TextPickMode = TextPickMode.SEQUENTIAL,

    /**
     * 转发是否带文案。关闭时执行**空转发**（`comment` 留空）。
     *
     * 依据：`WeiboWriteApi.repost` 明确允许空正文，而发帖/评论都会拒绝空正文。
     */
    val repostTextEnabled: Boolean = true,

    /** 优先挑 0 评论帖（首评 +2 分）。 */
    val preferFirstComment: Boolean = true,
    /** >0 时同一条文案至少隔这么多天才复用。 */
    val strictTextGapDays: Int = 0,
    /** 发帖单条正文长度上限；超限拒绝发送而非截断。 */
    val maxTextLen: Int = 1000,
    /**
     * 已废弃：评论/转发长度上限改为固定 [EngageTextPool.COMMENT_LIMIT]（140 字）。
     *
     * 字段与校验区间都保留，是为了老配置（存过 `mcl=1000`）升级后仍然能通过
     * [validate]——一旦这里收紧到 140，整份配置会判非法，任务直接不执行。
     */
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

    /**
     * 单个动作每日次数的界面上限。
     *
     * 「+」按钮的可用状态与 [EngageConfig] 的写入夹取必须用同一个口径：
     * 按钮写死 30、写入夹到 10 时，用户点到 10 以后按了没反应，只会以为页面卡住。
     */
    fun maxCountOf(action: EngageAction): Int = when (action) {
        EngageAction.POST -> 10
        EngageAction.REPOST -> 10
        EngageAction.COMMENT -> 20
        EngageAction.LIKE -> 30
    }

    fun enabledOf(action: EngageAction): Boolean = when (action) {
        EngageAction.POST -> postEnabled
        EngageAction.REPOST -> repostEnabled
        EngageAction.COMMENT -> commentEnabled
        EngageAction.LIKE -> likeEnabled
    }

    fun autoOf(action: EngageAction): Boolean = when (action) {
        EngageAction.POST -> postAuto
        EngageAction.REPOST -> repostAuto
        EngageAction.COMMENT -> commentAuto
        EngageAction.LIKE -> likeAuto
    }

    /** 三态口径，界面与计划器都读这一个函数，避免两处各拼一次。 */
    fun modeOf(action: EngageAction): EngageActionMode = when {
        !enabledOf(action) -> EngageActionMode.OFF
        !autoOf(action) -> EngageActionMode.MANUAL
        else -> EngageActionMode.AUTO
    }

    fun withMode(action: EngageAction, mode: EngageActionMode): EngageConfig {
        val on = mode != EngageActionMode.OFF
        val auto = mode == EngageActionMode.AUTO
        return when (action) {
            EngageAction.POST -> copy(postEnabled = on, postAuto = auto)
            EngageAction.REPOST -> copy(repostEnabled = on, repostAuto = auto)
            EngageAction.COMMENT -> copy(commentEnabled = on, commentAuto = auto)
            EngageAction.LIKE -> copy(likeEnabled = on, likeAuto = auto)
        }
    }

    fun gapRangeOf(action: EngageAction): LongRange = when (action) {
        EngageAction.POST -> postGapMinMs..postGapMaxMs
        EngageAction.REPOST -> repostGapMinMs..repostGapMaxMs
        EngageAction.COMMENT -> commentGapMinMs..commentGapMaxMs
        EngageAction.LIKE -> likeGapMinMs..likeGapMaxMs
    }

    /** 跨动作的通用间隔。 */
    fun actionGapRange(): LongRange = actionGapMinMs..actionGapMaxMs

    /** 某一套文案库的取用方式。 */
    fun textModeOf(kind: TextKind): TextPickMode = when (kind) {
        TextKind.POST -> postTextMode
        TextKind.COMMENT -> commentTextMode
        TextKind.REPOST -> repostTextMode
    }

    fun withTextMode(kind: TextKind, mode: TextPickMode): EngageConfig = when (kind) {
        TextKind.POST -> copy(postTextMode = mode)
        TextKind.COMMENT -> copy(commentTextMode = mode)
        TextKind.REPOST -> copy(repostTextMode = mode)
    }

    /**
     * 该动作需要的文案库；返回 null 表示这个动作不消耗文案。
     *
     * 点赞没有文案；转发在 [repostTextEnabled] 关闭时做空转发，同样不取文案。
     */
    fun textKindOf(action: EngageAction): TextKind? = when (action) {
        EngageAction.POST -> TextKind.POST
        EngageAction.COMMENT -> TextKind.COMMENT
        EngageAction.REPOST -> if (repostTextEnabled) TextKind.REPOST else null
        EngageAction.LIKE -> null
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
        // 间隔配得比单轮预算还长时，每个动作都会被判「来不及」而跳过，
        // 表现成「设了却不执行」，所以在这里直接拦住。
        listOf(
            postGapMaxMs, repostGapMaxMs, commentGapMaxMs, likeGapMaxMs, actionGapMaxMs,
        ).any { it > EngagePacing.MAX_GAP_MS } -> "间隔上限不能超过 ${EngagePacing.MAX_GAP_MS / 60_000} 分钟"
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
 * 文案库的种类。
 *
 * 0.13.x 及以前只有一个池子，发帖、评论、转发共用；三者的长度上限（发帖 1000 /
 * 评论与转发 140）与语言风格差别很大，混在一起时「一条长文只能发帖、短句只能评论」，
 * 用户无法分别管理。拆成三套库之后，每个库有自己的条数统计、淘汰上限与取用方式。
 *
 * [key] 同时用作路由参数与 prefs 键后缀，**不要改**（改了老数据就读不回来）。
 */
enum class TextKind(val key: String, val label: String) {
    POST("post", "发帖"),
    COMMENT("comment", "评论"),
    REPOST("repost", "转发"),
    ;

    /** 该库单条正文的硬上限；0 表示「按配置里的 [EngageConfig.maxTextLen]」。 */
    val limit: Int get() = if (this == POST) 0 else EngageTextPool.COMMENT_LIMIT

    companion object {
        fun ofKey(key: String?): TextKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 文案取用方式。
 *
 * - [SEQUENTIAL]：按「最久未用优先」排队，用完排到队尾——等价于一个不用落盘的游标；
 * - [RANDOM]：在同一批候选里打乱顺序，避免同一条文案天天出现。
 *
 * 两种模式共用同一套「今日/近 N 日已用排除」规则（`EngageSchedule.forbiddenTextDays`），
 * 区别只在排序，所以预览与实发永远看到同一个结果。
 */
enum class TextPickMode(val label: String, val hint: String) {
    SEQUENTIAL("顺序", "按导入顺序轮着用，用完排到队尾"),
    RANDOM("随机", "每次从可用文案里随机挑，不易重复"),
}

/**
 * 文案库里的一条。
 *
 * [digest] 是规范化正文的短哈希，用于去重（批量导入时最容易重复）。
 * 删除走软删（[deleted]），保留使用统计，便于用户判断哪些文案真的在用。
 */
data class EngageText(
    val id: String,
    val body: String,
    /** 所属库。老数据（拆库之前）一律按 [TextKind.POST] 读。 */
    val kind: TextKind = TextKind.POST,
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
 * 一轮执行的实时进度见单独的 `EngageProgress.kt`（步骤清单 + 流水 + ETA）。
 * 本文件只留持久化模型与配置。
 */

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
    /** 这一轮是谁触发的（手动全部 / 单项 / 自动）。 */
    val trigger: EngageTrigger = EngageTrigger.MANUAL_ALL,
    /** 单项执行时限定做的动作；为空表示不限制。 */
    val only: List<EngageAction> = emptyList(),
    /**
     * 过程快照：步骤清单与流水。
     *
     * 之前只存 [records]，跑完就只剩「评论 1/0 失败」这种结论，没有任何上下文
     * （在等间隔卡住？被风控？文案没取到？）。把过程一起存下来，运行记录页
     * 才能做到「点开一轮，按时间顺序看它到底经历了什么」。
     */
    val steps: List<EngageStep> = emptyList(),
    val logs: List<EngageLogEntry> = emptyList(),
) {
    val okCount: Int get() = records.count { it.state == ActionState.OK }
    val failCount: Int get() = records.count { it.state == ActionState.FAILED }
    val uncertainCount: Int get() = records.count { it.state == ActionState.UNCERTAIN }

    /** 一轮总耗时；未结束时返回 0（界面自己按当前时间算）。 */
    val durationMs: Long get() = if (finishedAt > 0L) (finishedAt - startedAt).coerceAtLeast(0L) else 0L

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
 * [body] 存的是**最终提交给服务端的完整正文快照**（含 `#超话名#` 行），
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
