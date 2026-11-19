package com.wcm.checkin

/**
 * 执行节奏：把「一轮动作之间隔多久」从写死的默认值变成用户可选的档位 + 可微调的区间。
 *
 * 为什么单独抽一个 object：
 * - 0.12.x 之前这套间隔只存在于 [EngageConfig] 的默认值里，**没有任何 UI 入口**，
 *   用户抱怨「默认间隔太长」时只能改代码重装；
 * - 现在既要给预设档位，又要允许逐项微调，还要能反推「当前是哪一档」，
 *   这些换算都是纯函数，放在 `:core` 才能脱机单测。
 *
 * 五档的取舍（发帖 / 评论 / 转发 / 点赞 / 动作间），由慢到快：
 * - [SLOW] 就是 0.12.x 的老默认值（发帖 5–8 分钟），保留给「宁可慢也别被限流」的用户；
 * - [GENTLE] 是 0.13.x 的默认值（发帖 1–2 分钟），社区经验里最稳的一档；
 * - [BALANCED] 是 0.13.x 的「快速」档（发帖 30–60 秒），速度与安全折中；
 * - [FAST] 是 0.14.0 的新默认（发帖 5–15 秒、评论 1–10 秒），一轮压到 1 分钟左右；
 * - [TURBO] 把间隔压到 2–5 秒，只建议手动补跑：超话发帖的社区规范普遍要求
 *   两帖间隔 ≥5 分钟，[TURBO] 与 [FAST] 都在明显越线的区间里，界面必须提示风险。
 */
enum class EngagePace(val label: String, val hint: String) {
    SLOW("慢速", "最保守，发帖间隔 5–8 分钟，一轮可能十几分钟"),
    GENTLE("稳健", "发帖 1–2 分钟、评论 10–20 秒，风控风险最低的日常档"),
    BALANCED("均衡", "发帖 30–60 秒、评论 5–10 秒，速度与安全折中"),
    FAST("快速", "默认档：发帖 5–15 秒、评论 1–10 秒，一轮约 1 分钟"),
    TURBO("极速", "发帖 2–5 秒，只建议手动补跑；风控风险明显更高"),
    CUSTOM("自定义", "逐项调过间隔，点上面的档位可一键还原"),
}

object EngagePacing {

    /**
     * 单个间隔的上限：30 分钟。
     *
     * 不设上限的后果很实在——用户把发帖间隔拖到两小时，一轮预算只有十几分钟，
     * 于是**每次都判定「来不及」而跳过发帖**，表现就是「设了却不发」，
     * 而且日志里只有一句预算不足，很难联想到是间隔配错了。
     */
    const val MAX_GAP_MS = 30 * 60_000L

    /**
     * 社区经验安全线。
     *
     * 微博没有公开的频率契约，但实测与超话管理规范高度一致：评论「1 分钟内超过 5 条」
     * 很容易弹「操作频繁」，超话发帖普遍要求两帖间隔 ≥5 分钟。取一个明显宽松、
     * 但仍能挡住「连点」的门槛用于提示。
     */
    const val SAFE_POST_GAP_MS = 15_000L
    const val SAFE_COMMENT_GAP_MS = 5_000L

    /**
     * 间隔风险提示：发帖下限 <15 秒或评论下限 <5 秒时返回人话，否则 null。
     *
     * **只提示不拦截**——用户明确要求默认就是 5–15 秒 / 1–10 秒，
     * 这里的作用是把「可能被限流」这件事说清楚，而不是替用户改配置。
     */
    fun riskHint(config: EngageConfig): String? {
        val risky = ArrayList<String>(2)
        if (config.postGapMinMs < SAFE_POST_GAP_MS) risky.add("发帖")
        if (config.commentGapMinMs < SAFE_COMMENT_GAP_MS) risky.add("评论")
        if (risky.isEmpty()) return null
        return "${risky.joinToString("、")}间隔低于社区经验安全线" +
            "（建议发帖 ≥15 秒、评论 ≥5 秒），容易触发 20016「发布过于频繁」；" +
            "无人值守时建议切到「均衡」或更慢的档位。"
    }

    /** 一次写请求的平均耗时估计，只用于「预计一轮多久」，不参与调度。 */
    private const val REQUEST_COST_MS = 1_500L

    /** 一档预设对应的五组间隔。 */
    data class Gaps(
        val actionGap: LongRange,
        val post: LongRange,
        val comment: LongRange,
        val repost: LongRange,
        val like: LongRange,
    ) {
        fun of(action: EngageAction): LongRange = when (action) {
            EngageAction.POST -> post
            EngageAction.COMMENT -> comment
            EngageAction.REPOST -> repost
            EngageAction.LIKE -> like
        }
    }

    /** 各档预设的具体数值。**这就是 [EngageConfig] 默认值的来源**，改这里要同步改默认值。 */
    fun gapsOf(pace: EngagePace): Gaps? = when (pace) {
        // 0.12.x 的默认值，原样保留：这是「觉得太快了」的退路
        EngagePace.SLOW -> Gaps(
            actionGap = 5_000L..20_000L,
            post = 5 * 60_000L..8 * 60_000L,
            comment = 25_000L..60_000L,
            repost = 60_000L..180_000L,
            like = 15_000L..40_000L,
        )
        // 0.13.x 的默认值
        EngagePace.GENTLE -> Gaps(
            actionGap = 3_000L..8_000L,
            post = 60_000L..120_000L,
            comment = 10_000L..20_000L,
            repost = 20_000L..45_000L,
            like = 8_000L..15_000L,
        )
        // 0.13.x 的「快速」档
        EngagePace.BALANCED -> Gaps(
            actionGap = 1_000L..3_000L,
            post = 30_000L..60_000L,
            comment = 5_000L..10_000L,
            repost = 10_000L..20_000L,
            like = 3_000L..8_000L,
        )
        // 0.14.0 的新默认
        EngagePace.FAST -> Gaps(
            actionGap = 1_000L..3_000L,
            post = 5_000L..15_000L,
            comment = 1_000L..10_000L,
            repost = 5_000L..12_000L,
            like = 2_000L..6_000L,
        )
        EngagePace.TURBO -> Gaps(
            actionGap = 1_000L..2_000L,
            post = 2_000L..5_000L,
            comment = 1_000L..3_000L,
            repost = 2_000L..5_000L,
            like = 1_000L..3_000L,
        )
        EngagePace.CUSTOM -> null
    }

    /** 当前配置落在哪一档；与预设不完全一致即 [EngagePace.CUSTOM]。 */
    fun presetOf(config: EngageConfig): EngagePace {
        for (pace in EngagePace.entries) {
            val g = gapsOf(pace) ?: continue
            if (g.actionGap == config.actionGapRange() &&
                g.post == config.gapRangeOf(EngageAction.POST) &&
                g.comment == config.gapRangeOf(EngageAction.COMMENT) &&
                g.repost == config.gapRangeOf(EngageAction.REPOST) &&
                g.like == config.gapRangeOf(EngageAction.LIKE)
            ) {
                return pace
            }
        }
        return EngagePace.CUSTOM
    }

    /** 套用一档预设。[EngagePace.CUSTOM] 不改变配置（它不是一个具体档位）。 */
    fun apply(config: EngageConfig, pace: EngagePace): EngageConfig {
        val g = gapsOf(pace) ?: return config
        return config.copy(
            actionGapMinMs = g.actionGap.first,
            actionGapMaxMs = g.actionGap.last,
            postGapMinMs = g.post.first,
            postGapMaxMs = g.post.last,
            commentGapMinMs = g.comment.first,
            commentGapMaxMs = g.comment.last,
            repostGapMinMs = g.repost.first,
            repostGapMaxMs = g.repost.last,
            likeGapMinMs = g.like.first,
            likeGapMaxMs = g.like.last,
        )
    }

    /** 取某一项间隔。[action] 为 null 表示「动作之间」的通用间隔。 */
    fun gapOf(config: EngageConfig, action: EngageAction?): LongRange =
        if (action == null) config.actionGapRange() else config.gapRangeOf(action)    /** 写回某一项间隔，自动夹到 `[0, MAX_GAP_MS]` 并保证 `min <= max`。 */
    fun withGap(config: EngageConfig, action: EngageAction?, range: LongRange): EngageConfig {
        val fixed = normalize(range)
        return when (action) {
            null -> config.copy(actionGapMinMs = fixed.first, actionGapMaxMs = fixed.last)
            EngageAction.POST -> config.copy(postGapMinMs = fixed.first, postGapMaxMs = fixed.last)
            EngageAction.COMMENT -> config.copy(commentGapMinMs = fixed.first, commentGapMaxMs = fixed.last)
            EngageAction.REPOST -> config.copy(repostGapMinMs = fixed.first, repostGapMaxMs = fixed.last)
            EngageAction.LIKE -> config.copy(likeGapMinMs = fixed.first, likeGapMaxMs = fixed.last)
        }
    }

    /**
     * 整体平移一个区间，宽度保持不变。
     *
     * 界面上每项只有「− / +」两个按钮，滑的是整个区间而不是单独一端：
     * 单独调一端很容易把区间调成 0 宽（变成固定间隔），失去随机性反而更像机器。
     */
    fun shift(range: LongRange, deltaMs: Long): LongRange {
        val width = (range.last - range.first).coerceAtLeast(0L)
        val lo = (range.first + deltaMs).coerceIn(0L, MAX_GAP_MS)
        val hi = (lo + width).coerceAtMost(MAX_GAP_MS)
        return lo..hi.coerceAtLeast(lo)
    }

    /** 区间的 UI 步进：秒级用 5 秒，分钟级用 30 秒。 */
    fun stepOf(range: LongRange): Long = if (range.last < 60_000L) 5_000L else 30_000L

    /** 平均间隔，用于估算耗时。 */
    fun averageOf(range: LongRange): Long = (range.first + range.last) / 2

    /**
     * 估算一轮耗时。
     *
     * 只做加法不做模拟：**同一个动作的第一次不等待**（节流键首次没有 lastAt），
     * 所以是 `(n-1) × 平均间隔`；跨动作再加一段通用间隔。估算结果用于界面提示与
     * 预算是否够用的直觉判断，不参与实际调度（真机上以 `WeiboThrottle.remaining` 为准）。
     */
    fun estimateRunMs(config: EngageConfig, counts: Map<EngageAction, Int>): Long {
        var total = 0L
        var started = false
        for (action in EngagePlanner.EXECUTION_ORDER) {
            val n = counts[action] ?: 0
            if (n <= 0) continue
            total += averageOf(config.gapRangeOf(action)) * (n - 1)
            if (started) total += averageOf(config.actionGapRange())
            started = true
            total += n * REQUEST_COST_MS
        }
        return total
    }

    fun estimateRunMs(config: EngageConfig, plan: EngagePlanner.Plan): Long {
        val counts = LinkedHashMap<EngageAction, Int>()
        for (a in EngageAction.entries) {
            val n = plan.countOf(a)
            if (n > 0) counts[a] = n
        }
        return estimateRunMs(config, counts)
    }

    /** 「8–15 秒」「1–2 分钟」「立即」这样的人话。 */
    fun describe(range: LongRange): String {
        if (range.last <= 0L) return "立即"
        val lo = describeOne(range.first)
        val hi = describeOne(range.last)
        return if (lo == hi) lo else "$lo–$hi"
    }

    private fun describeOne(ms: Long): String = when {
        ms < 1_000L -> "立即"
        ms < 60_000L -> "${ms / 1_000} 秒"
        ms % 60_000L == 0L -> "${ms / 60_000} 分钟"
        else -> "${ms / 60_000} 分 ${(ms % 60_000) / 1_000} 秒"
    }

    /** 「2 分 10 秒」「45 秒」这样的耗时。 */
    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0 秒"
        if (ms < 60_000L) return "${(ms + 999) / 1_000} 秒"
        val min = ms / 60_000L
        val sec = (ms % 60_000L) / 1_000L
        return if (sec == 0L) "$min 分钟" else "$min 分 $sec 秒"
    }

    private fun normalize(range: LongRange): LongRange {
        val lo = range.first.coerceIn(0L, MAX_GAP_MS)
        val hi = range.last.coerceIn(lo, MAX_GAP_MS)
        return lo..hi
    }
}
