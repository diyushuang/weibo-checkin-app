package com.wcm.checkin

/**
 * 任务规划器：把「用户设的次数」翻译成「这一轮具体要做哪些动作」。
 *
 * 它是纯函数集合，不碰网络也不碰存储，所以「配 4 条评论但今天已经发了 3 条」
 * 这种边界完全可以在 JVM 单测里穷举。
 *
 * 执行顺序固定为 **发帖 → 评论 → 转发 → 点赞**（见 [EXECUTION_ORDER]）。
 * 0.12.6 之前是「发帖放最后」，真机实测发现前面的间隔会把时间预算吃光、
 * 导致整轮跑完一条帖子都没发出去，已调整。
 */
object EngagePlanner {

    /**
     * **执行顺序：发帖 → 评论 → 转发 → 点赞。**
     *
     * 发帖排最前是刻意的，理由有两条：
     * 1. 它是用户唯一在意「必须成功」的动作，也是唯一有硬额度、最容易被限流的动作；
     * 2. 排在最后时，前面动作的间隔会吃掉时间预算 —— 真机实测默认配置跑到发帖
     *    已耗约 300 秒，随机间隔取到上限时会被预算直接砍掉，整轮跑完一条帖子都没发出去。
     *    放在最前，点执行后 1–2 秒就能发出。
     *
     * 注意：这**只影响执行顺序**，不影响界面展示顺序 —— `EngageAction.entries`
     * 仍是 评论/转发/点赞/发帖，设置页的次数排列与运行摘要都按那个顺序显示。
     */
    val EXECUTION_ORDER = listOf(
        EngageAction.POST,
        EngageAction.COMMENT,
        EngageAction.REPOST,
        EngageAction.LIKE,
    )

    /** 计划里的一个动作。 */
    data class PlannedAction(
        val action: EngageAction,
        /** 当日第几次（1 起），用于预占键 `POST#3` 与文案序号占位符。 */
        val seq: Int,
    )

    data class Plan(
        val actions: List<PlannedAction> = emptyList(),
        /** 非空表示这一轮不能跑，值是给用户看的原因。 */
        val blocked: String = "",
        /** 不影响执行、但要显示给用户的提示。 */
        val notes: List<String> = emptyList(),
    ) {
        fun countOf(action: EngageAction): Int = actions.count { it.action == action }
    }

    /**
     * 各动作当天还能做几次。
     *
     * 发帖额外受两件事压制：存在未收敛的预占（上次结果未知）时清零——
     * 宁可今天不发，也不能重复发。
     *
     * [only] 非空时只统计这些动作（单项执行）：其它动作直接归零，
     * 这样「只点一次发帖」不会顺带把评论额度也扣掉。
     */
    fun remaining(
        config: EngageConfig,
        counter: EngageDayCounter,
        only: Set<EngageAction> = emptySet(),
    ): Map<EngageAction, Int> {
        val map = LinkedHashMap<EngageAction, Int>(4)
        for (action in EngageAction.entries) {
            if (!config.enabledOf(action) || (only.isNotEmpty() && action !in only)) {
                map[action] = 0
                continue
            }
            var left = (config.countOf(action) - counter.doneOf(action)).coerceAtLeast(0)
            if (action == EngageAction.POST && counter.hasPendingPost()) left = 0
            map[action] = left
        }
        return map
    }

    /**
     * 一轮需要的**互动目标**数量。
     *
     * 评论、转发、点赞各要一条不同的帖子（队列里每弹一个就被消耗掉），
     * 所以是三类次数**之和**；用最大值会让默认配置（4+2+3）只取到 6 条，
     * 后面的动作全部落空。
     */
    fun targetDemand(plan: Plan): Int = plan.actions.count { it.action != EngageAction.POST }

    /**
     * 软约束提示。**只提示不拦截**——用户明确要求「允许设任意次数，超出部分提示」。
     *
     * 顺序固定，因为要按重要度排列显示：先讲"白跑"，再讲"没用"，最后讲"危险"。
     */
    fun quotaHints(config: EngageConfig): List<String> {
        val hints = ArrayList<String>(6)
        val manualOnly = EngageAction.entries.filter {
            config.enabledOf(it) && !config.autoOf(it)
        }
        if (manualOnly.isNotEmpty()) {
            hints.add(
                "${manualOnly.joinToString("、") { it.label }}设为「仅手动」：自动轮次不会做，只能点该动作的「单独执行」"
            )
        }
        if (config.commentEnabled && config.commentCount > 4) {
            hints.add("评论每日仅前 4 条计入超话经验，超出部分不产生经验且增加风控风险")
        }
        if (config.commentEnabled) {
            hints.add("评论正文超过 ${config.maxCommentLen} 字会被跳过（按平台限制拒绝，不截断）")
        }
        if (config.repostEnabled && config.repostCount > 2) {
            hints.add("转发每日仅前 2 条计入超话经验，超出部分不产生经验")
        }
        if (config.likeEnabled && config.likeCount > 0) {
            hints.add("点赞不产生超话经验，仅作为互动行为")
        }
        if (config.postEnabled) {
            hints.add("发帖本身不加经验，需被他人评论才加分")
            if (config.postCount > 3) hints.add("单日超过 3 帖极易触发风控，建议保持 1–3 条")
        }
        val total = EngageAction.entries.sumOf { if (config.enabledOf(it)) config.countOf(it) else 0 }
        if (total > config.maxActionsPerRun) {
            hints.add("计划动作 $total 个，超过单轮上限 ${config.maxActionsPerRun}，将截断执行")
        }
        return hints
    }

    /**
     * 生成这一轮的执行计划。
     *
     * [nowMillis] 参与免打扰判断；[background] 为 true 时是**自动轮次**
     * （打开 App 补跑 / 后台 Worker）：
     * - 设为「仅手动」的动作会被扣掉，并记一条 note —— 否则用户会看到
     *   「我明明关了自动，它还是跑了」；
     * - 若配置禁止后台发帖，发帖会被扣掉并记一条 note（而不是整轮不跑）。
     *
     * [only] 非空表示**只执行这些动作**（单项执行按钮），此时不受自动/手动开关约束：
     * 用户主动点了按钮，就该按他点的做。
     */
    fun plan(
        config: EngageConfig,
        counter: EngageDayCounter,
        nowMillis: Long,
        background: Boolean = false,
        only: Set<EngageAction> = emptySet(),
    ): Plan {
        val invalid = config.validate()
        if (invalid != null) return Plan(blocked = invalid)

        if (EngageSchedule.isQuietNow(nowMillis, config)) {
            return Plan(blocked = "当前处于免打扰时段（${config.quietStartHour}–${config.quietEndHour} 点）")
        }

        val notes = ArrayList<String>(4)
        val left = remaining(config, counter, only).toMutableMap()

        if (counter.hasPendingPost()) notes.add("上次发帖结果未知，今日发帖已暂停，请到微博确认后在台账里处理")

        // 自动轮次里，「仅手动」的动作一律不做。放在 postInBackground 之前：
        // 两者叠加时先说「你设了仅手动」，这比「后台不允许发帖」更贴近用户的心智。
        val manualSkipped = ArrayList<EngageAction>(4)
        if (background) {
            for (action in EngageAction.entries) {
                if ((left[action] ?: 0) > 0 && !config.autoOf(action)) {
                    left[action] = 0
                    manualSkipped.add(action)
                }
            }
            if (manualSkipped.isNotEmpty()) {
                notes.add("${manualSkipped.joinToString("、") { it.label }}设为「仅手动」，本次自动轮次已跳过")
            }
            if (!config.postInBackground && (left[EngageAction.POST] ?: 0) > 0) {
                left[EngageAction.POST] = 0
                notes.add("后台任务未允许发帖，本次跳过发帖")
            }
        }

        val actions = ArrayList<PlannedAction>()
        var truncated = false
        for (action in EXECUTION_ORDER) {
            val n = left[action] ?: 0
            // 序号必须接续当日已完成数：每轮都从 1 重排会让 `POST#1` 与台账 id
            // 在同一天的第二次运行里重复，台账旧记录会被新记录覆盖掉。
            val base = counter.doneOf(action)
            for (i in 0 until n) {
                if (actions.size >= config.maxActionsPerRun) {
                    truncated = true
                    break
                }
                actions.add(PlannedAction(action, base + i + 1))
            }
            if (truncated) break
        }

        if (truncated) {
            notes.add("已达单轮上限 ${config.maxActionsPerRun}，其余留待下一轮")
        }

        // 空计划的三种成因要分开说，否则用户只看到「今日已无待执行动作」，
        // 完全想不到是自己把动作关成了「仅手动」。
        val blocked = when {
            actions.isNotEmpty() -> ""
            only.isNotEmpty() -> {
                val name = only.joinToString("、") { it.label }
                "本次没有可执行的${name}：今日额度已用完或该动作已关闭"
            }
            background && manualSkipped.size == EngageAction.entries.size ->
                "自动轮次没有可执行动作（四项都已设为「仅手动」或关闭）"
            background && manualSkipped.isNotEmpty() ->
                "自动轮次没有可执行动作（${manualSkipped.joinToString("、") { it.label }}已设为仅手动）"
            else -> "今日已无待执行动作"
        }

        return Plan(actions = actions, blocked = blocked, notes = notes)
    }
}
