package com.wcm.checkin

import android.util.Log
import kotlin.random.Random

/**
 * 一轮互动的执行机械（runInternal 的下半场）。
 *
 * 上半场（准备阶段：配置/账号/登录态/超话/计划/文案/目标）留在
 * [EngageService]，产物是下面这几个数据包；本文件只负责「照着计划把动作一个个
 * 做掉并记账」。拆分动机见 `docs/review/code-audit-2026-11-20.md` §6：原
 * runInternal 605 行、30+ 个可变局部状态互相纠缠，连续三个版本都在里面修 bug。
 * 拆分以特征测试（`EngageServiceRunTest`，10 例）为护栏，行为不变。
 */

/** 前置阶段（配置/账号/登录态/超话校验）的产物。 */
internal class AccessPrep(
    val config: EngageConfig,
    val account: EngageAccount,
    val session: WeiboHttp.WeiboSession,
    /** 目标超话的完整 containerid（`100808` + hash）。 */
    val container: String,
    val topicName: String,
)

/** 计划阶段产物：要跑什么、按什么预算跑。 */
internal class PlannedRun(
    val plan: EngagePlanner.Plan,
    val total: Int,
    /** 实际生效的时间预算：配置值与调用方上限取小（后台不能超过 Worker 系统上限）。 */
    val budgetMs: Long,
    val deadline: Long,
    /** 计划生成时刻：文案渲染的占位符全用它，避免执行到一半日期翻篇。 */
    val now: Long,
    val day: String,
)

/** 三套文案库的执行队列（发帖/评论/转发各取各的，见 `EngageTextPool`）。 */
internal class TextQueues(
    val postQueue: ArrayDeque<EngageText>,
    val commentQueue: ArrayDeque<EngageText>,
    val repostQueue: ArrayDeque<EngageText>,
)

/** 互动目标流：队列 + 拉取失败的可读原因（空串 = 没有错误）。 */
internal class TargetSupply(
    val targetQueue: ArrayDeque<WeiboFeedApi.FeedPost>,
    val feedError: String,
)

/**
 * 一轮执行的共享上下文：唯一的可变进度对象 + 便捷发射器。
 *
 * 进度对象每轮一个（轮次结束后留在界面上供回看，下一轮从空白开始），全部变更
 * 经 [emit] 立即推给观察方。原来是 runInternal 里的 5 个局部函数闭包共享一个
 * `var p`，拆分后显式成类。
 */
internal class RunContext(
    val startedAt: Long,
    val trigger: EngageTrigger,
    val only: Set<EngageAction>,
    val clock: () -> Long,
    reportFn: (EngageProgress) -> Unit,
) {
    var p: EngageProgress = EngageProgress(startedAt = startedAt, trigger = trigger)
        private set
    private val reportFn: (EngageProgress) -> Unit = reportFn

    /** 本轮因什么被拒（[abort] 记录）；null = 走到了执行阶段。 */
    var blockedReason: String? = null
        private set

    fun emit(change: (EngageProgress) -> EngageProgress) {
        p = change(p)
        reportFn(p)
    }

    fun log(level: EngageLogLevel, text: String, action: EngageAction? = null, seq: Int = 0) {
        emit { it.log(clock(), level, text, action, seq) }
    }

    fun stepStart(id: String, title: String) {
        emit { it.stepStart(id, title, clock()) }
    }

    fun stepEnd(id: String, state: EngageStepState, detail: String = "") {
        emit { it.stepFinish(id, state, clock(), detail) }
    }

    /**
     * 提前收场：把没走完的步骤标成失败，并给出可读原因（界面不能永远停在「进行中」）。
     *
     * 返回 `Nothing?`：调用点写 `?: return ctx.abort(reason)`，既中断又把原因带出
     * （原来是 `return abort(reason)` 直接返回 EngageOutcome，语义等价）。
     */
    fun abort(reason: String): Nothing? {
        blockedReason = reason
        log(EngageLogLevel.ERROR, "本轮中止：$reason")
        val now = clock()
        emit {
            it.closeOpenSteps(EngageStepState.FAILED, now, reason)
                .copy(stage = "已中止", message = reason)
                .finish(now)
        }
        Log.w(EngageService.TAG, "本轮不执行：$reason")
        return null
    }

    /** 准备阶段被拒时给 `run()` 的返回值。 */
    fun blockedOutcome(): EngageOutcome = EngageOutcome(blockedReason = blockedReason.orEmpty())
}

/**
 * 单个动作执行完的流向。原 runInternal 主循环用 `continue`/`break` 直排，
 * 拆成函数后必须显式表达这三种出口，语义与原控制流一一对应。
 */
internal sealed interface StepFlow {
    /** 正常完成，继续下一个动作（原 `continue` 的正常路径）。 */
    object Next : StepFlow

    /**
     * 放弃当前动作的剩余次数（原 skipAction/skipReason + `continue`）。
     * @param uiLog true=写进界面流水（guard 判定路径）；false=只写 logcat（目标缺失路径）。
     */
    data class SkipRest(val reason: String, val uiLog: Boolean) : StepFlow

    /** 整轮提前结束（原 `break` + breakFrom/stopReason）。 */
    data class EndRun(
        val reason: String,
        /** 从这个下标开始把没执行到的计划补记 SKIPPED。 */
        val resumeFrom: Int,
        /** 预算路径在等待时已发过一条带秒数的日志，不再重复广播。 */
        val announce: Boolean = true,
    ) : StepFlow
}

/** 没执行到的动作：让摘要里的分母保持真实（发帖 0/1 而不是 0/0）。 */
internal fun skippedRecord(
    planned: EngagePlanner.PlannedAction,
    reason: String,
    now: Long,
) = EngageActionRecord(
    action = planned.action,
    seq = planned.seq,
    state = ActionState.SKIPPED,
    errorKind = EngageErrorKind.GUARD_TRIPPED,
    message = reason,
    at = now,
)

/**
 * 执行期状态：动作记录、熔断器、文案记账、循环控制位，以及逐动作的执行机械。
 *
 * 原来是 runInternal 里 10 个相互纠缠的局部变量 + 2 个闭包（snapshot/record）。
 * 状态还是那些状态，但从「函数内散落」变成「按生命周期聚拢」——每个子函数因此
 * 不需要把一堆参数传来传去（`buildPostRecord` 从 10 参降到 5 参）。
 *
 * 非线程安全：一轮运行一个实例，由 EngageService 持有，不跨轮复用。
 */
/**
 * 一次真实提交的全部上下文：从预占到收到响应。`settle` 据此记账。
 *
 * 把「发请求」和「记账收敛」拆成两个函数时，中间产物收在这里，避免
 * `settle` 需要 8 个参数（拆分前 runInternal 就是这么长的参数链）。
 */
private class Attempt(
    val plannedAction: EngagePlanner.PlannedAction,
    val target: WeiboFeedApi.FeedPost?,
    val text: EngageText?,
    val body: String,
    /** 发帖台账占位的时间戳；跨零点时占位与终态必须同桶。 */
    val ledgerAt: Long,
    val costMs: Long,
    val result: WeiboWriteApi.WriteResult,
)

internal class RunExecution(
    val ctx: RunContext,
    val access: AccessPrep,
    val planned: PlannedRun,
    val texts: TextQueues,
    val targets: TargetSupply,
    private val runId: String,
    private val runPort: EngageRunPort,
    private val gateway: EngageGateway,
) {
    val records = ArrayList<EngageActionRecord>(planned.plan.actions.size)
    val guard = EngageGuard(access.config.failStreakAbort)

    /** 按库汇总本轮用到的文案 id：写回统计时必须知道这条属于哪个库。 */
    val usedTextIds: MutableMap<TextKind, MutableSet<String>> =
        TextKind.entries.associateWith { mutableSetOf<String>() }.toMutableMap()

    // 今日已存在的「待确认」发帖：这些槽位没进计数（还没发），但不能再建第二条，
    // 否则每次运行都会用同一个台账 id 覆盖上一条待确认内容。
    private val awaitingIds = runPort.loadPosts(access.account.uid, planned.day)
        .filter { it.state == ActionState.AWAITING_CONFIRM }
        .map { it.id }
        .toMutableSet()

    var currentAction: EngageAction? = null
    var skipAction: EngageAction? = null
    var skipReason: String = ""
    var breakFrom: Int = -1
    var stopReason: String = ""

    /** 一轮进行中的运行记录快照：`finishedAt = 0` 表示还没跑完。 */
    fun snapshot(finishedAtValue: Long): EngageRun = EngageRun(
        runId = runId,
        accountUid = access.account.uid,
        topicId = access.config.topicId,
        topicName = access.topicName,
        startedAt = ctx.startedAt,
        finishedAt = finishedAtValue,
        stopReason = guard.currentStopReason,
        records = records.toList(),
        trigger = ctx.trigger,
        only = ctx.only.toList(),
        steps = ctx.p.steps,
        logs = ctx.p.logs,
    )

    /**
     * 记一条动作结果：落内存 → 推进度写流水 → **立刻写一份草稿运行记录**。
     *
     * 草稿这一步是排查能力的关键：原来只在收尾写一次，跑的过程中「运行记录」
     * 永远是空的，界面上除了一行「正在执行…」什么都看不到。
     */
    fun record(rec: EngageActionRecord, done: Int) {
        records.add(rec)
        ctx.emit { it.copy(done = done, total = planned.total, action = rec.action, message = rec.message) }
        val level = when (rec.state) {
            ActionState.OK -> EngageLogLevel.OK
            ActionState.FAILED -> EngageLogLevel.ERROR
            ActionState.UNCERTAIN -> EngageLogLevel.WARN
            ActionState.SKIPPED, ActionState.AWAITING_CONFIRM -> EngageLogLevel.INFO
        }
        ctx.log(
            level,
            rec.state.label + if (rec.message.isEmpty()) "" else "：${rec.message}",
            rec.action,
            rec.seq,
        )
        Log.i(EngageService.TAG, "[$done/${planned.total}] ${rec.action.label}#${rec.seq} ${rec.state} ${rec.errorKind} ${rec.message}")
        runCatching { runPort.saveRun(snapshot(0L)) }
    }

    // ========================= 主循环 =========================

    /** 按计划逐个执行动作，处理三种流向。STEP_RUN 步骤的开启与收尾由 EngageService 负责。 */
    suspend fun runLoop() {
        for ((idx, plannedAction) in planned.plan.actions.withIndex()) {
            if (guard.isStopped) {
                stopReason = guard.currentStopReason.ifEmpty { "已熔断" }
                breakFrom = idx
                break
            }
            if (plannedAction.action == skipAction) {
                // 该动作已判定放弃，剩余次数不再空转等待，但记录要补齐，
                // 否则摘要里只看得到「0/1」，看不出原本计划了几条。
                record(skippedRecord(plannedAction, skipReason, ctx.clock()), idx + 1)
                continue
            }

            val switching = currentAction != plannedAction.action
            if (switching) {
                guard.nextAction()
                currentAction = plannedAction.action
                ctx.log(
                    EngageLogLevel.STEP,
                    "开始${plannedAction.action.label}（本轮计划 ${planned.plan.countOf(plannedAction.action)} 次）",
                    plannedAction.action,
                )
            }

            when (val flow = executeAction(plannedAction, idx, switching)) {
                StepFlow.Next -> Unit
                is StepFlow.SkipRest -> {
                    skipAction = plannedAction.action
                    skipReason = flow.reason
                    if (flow.uiLog) ctx.log(EngageLogLevel.WARN, flow.reason) else Log.w(EngageService.TAG, flow.reason)
                }
                is StepFlow.EndRun -> {
                    stopReason = flow.reason
                    guard.trip(flow.reason)
                    breakFrom = flow.resumeFrom
                    if (flow.announce) ctx.log(EngageLogLevel.WARN, "本轮提前结束：${flow.reason}")
                    break
                }
            }
        }
    }

    // ========================= 单动作 =========================

    private suspend fun executeAction(
        plannedAction: EngagePlanner.PlannedAction,
        idx: Int,
        switching: Boolean,
    ): StepFlow {
        // 等间隔 + 预算判定：预算不够整轮结束（本动作未执行，从当前格补 SKIPPED）
        awaitThrottle(plannedAction, switching, idx)?.let { return it }

        val content = prepareContent(plannedAction, idx) ?: return StepFlow.Next

        // 人工确认模式：只组正文、落台账，不预占也不发请求
        if (plannedAction.action == EngageAction.POST && access.config.requireConfirmBeforePost) {
            prepareAwaitingConfirm(plannedAction, idx, content.body, content.text)
            return StepFlow.Next
        }

        val resolved = resolveTarget(plannedAction, idx)
        val skipReason = resolved.skipReason
        if (skipReason != null) {
            return StepFlow.SkipRest(skipReason, uiLog = false)
        }

        return submitAndSettle(plannedAction, idx, resolved.target, content.text, content.body)
    }

    /**
     * 等动作间隔并判预算。
     *
     * 返回 null 表示可以继续；返回 EndRun 表示预算不够（本动作**尚未执行**，
     * 所以从当前格开始补 SKIPPED）。**预算必须按「实际还要等多久」判**：同一个
     * key 的首次请求根本不等待，按配置的理论间隔去扣，会把当天第一条帖子直接
     * 判死——这正是 `WeiboThrottle.remaining` 存在的理由。
     */
    private suspend fun awaitThrottle(
        plannedAction: EngagePlanner.PlannedAction,
        switching: Boolean,
        idx: Int,
    ): StepFlow? {
        val config = access.config
        val gapRange = config.gapRangeOf(plannedAction.action)
        val nominalWait = randomIn(gapRange) + if (switching) randomIn(config.actionGapRange()) else 0L
        val realWait = WeiboThrottle.remaining(throttleKey(plannedAction.action), nominalWait)
        if (ctx.clock() + realWait + REQUEST_MARGIN_MS > planned.deadline) {
            val reason = "本轮已达时间预算（${planned.budgetMs / 60_000} 分钟），剩余动作留待下一轮"
            ctx.log(EngageLogLevel.WARN, "$reason（还要等 ${(realWait + 999) / 1000} 秒）")
            return StepFlow.EndRun(reason, resumeFrom = idx, announce = false)
        }
        if (realWait > 0) {
            // 等间隔是整轮里最长的一段「什么都不发生」，必须让界面看到倒计时，
            // 否则用户会以为卡死。
            ctx.emit { it.waiting(ctx.clock() + realWait) }
            ctx.log(
                EngageLogLevel.WAIT,
                "等待 ${(realWait + 999) / 1000} 秒（${plannedAction.action.label}间隔 ${EngagePacing.describe(gapRange)}）",
                plannedAction.action,
                plannedAction.seq,
            )
        }
        WeiboThrottle.await(throttleKey(plannedAction.action), nominalWait)
        ctx.emit { it.clearWait() }
        return null
    }

    /**
     * 弹文案、渲染、本地校验。返回 null 表示这条动作已记过失败
     * （文案缺失或内容非法——这两个分支都不进熔断计数），调用方直接继续。
     */
    private fun prepareContent(plannedAction: EngagePlanner.PlannedAction, idx: Int): Content? {
        // 点赞没有文案；转发在「不带文案」时也不取（空转发由 WeiboWriteApi 允许）
        val textKind = access.config.textKindOf(plannedAction.action)
        val text = when (textKind) {
            TextKind.POST -> texts.postQueue.removeFirstOrNull()
            TextKind.COMMENT -> texts.commentQueue.removeFirstOrNull()
            TextKind.REPOST -> texts.repostQueue.removeFirstOrNull()
            null -> null
        }

        // 文案缺失：发帖、评论、带文案的转发都做不了（先判，后面才能安全读 text.body）
        if (textKind != null && text == null) {
            record(
                failedRecord(
                    plannedAction, EngageErrorKind.NO_TEXT,
                    "${textKind.label}文案库已无可用文案",
                ),
                idx + 1,
            )
            return null
        }

        // 正文：发帖要「渲染 + 组装超话标签」，评论/转发只渲染占位符。
        // 三者都不该把 `{topic}` 这类占位符原样发出去。
        val rendered = if (text != null) renderForAction(text.body, plannedAction) else ""
        val body = if (plannedAction.action == EngageAction.POST) {
            EngageTextPool.compose(access.topicName, rendered)
        } else {
            rendered
        }

        // 本地能判的先判，别浪费一次请求
        val localBad = when (plannedAction.action) {
            EngageAction.POST -> EngageTextPool.validate(body, access.config.maxTextLen)
            EngageAction.COMMENT -> EngageTextPool.validateComment(body)
            // 空转发是合法的（平台允许 comment 留空），不能过 validateComment：
            // 它会把空串判成「正文为空」而整条跳过。
            EngageAction.REPOST -> if (body.isNotBlank()) EngageTextPool.validateComment(body) else null
            EngageAction.LIKE -> null
        }
        if (localBad != null) {
            record(failedRecord(plannedAction, EngageErrorKind.CONTENT_REJECTED, localBad), idx + 1)
            return null
        }
        return Content(text, body)
    }

    /**
     * 评论/转发/点赞的目标：从目标流弹一个。
     *
     * 目标缺失时**整个动作提前放弃**：目标流为空时，按原逻辑每条都要白等几十秒，
     * 用户只看到「什么都没执行」。发帖没有目标（skipReason 与 target 都是 null）。
     */
    private fun resolveTarget(plannedAction: EngagePlanner.PlannedAction, idx: Int): ResolvedTarget {
        if (plannedAction.action == EngageAction.POST) return ResolvedTarget(null, null)
        val target = targets.targetQueue.removeFirstOrNull()
        if (target != null) return ResolvedTarget(target, null)
        val reason = targets.feedError.ifEmpty { "没有可互动的帖子" }
        record(failedRecord(plannedAction, EngageErrorKind.TARGET_GONE, reason), idx + 1)
        return ResolvedTarget(null, "已放弃剩余${plannedAction.action.label}：$reason")
    }

    /** 人工确认模式：只组正文、落台账，不预占也不发请求。 */
    private fun prepareAwaitingConfirm(
        plannedAction: EngagePlanner.PlannedAction,
        idx: Int,
        body: String,
        text: EngageText?,
    ) {
        val recId = EngageService.postIdOf(access.account.uid, planned.day, plannedAction.seq)
        val at = ctx.clock()
        if (recId in awaitingIds) {
            record(
                failedRecord(
                    plannedAction, EngageErrorKind.DUPLICATE_ACTION,
                    "已有一条待确认发帖，请先在发帖记录里处理",
                ),
                idx + 1,
            )
            return
        }
        awaitingIds.add(recId)
        runPort.savePost(buildPostRecord(plannedAction.seq, at, body, text, ActionState.AWAITING_CONFIRM))
        record(
            EngageActionRecord(
                action = EngageAction.POST,
                seq = plannedAction.seq,
                state = ActionState.SKIPPED,
                textId = text?.id.orEmpty(),
                message = "正文已组好，等待你在发帖记录里确认发送",
                at = at,
            ),
            idx + 1,
        )
    }

    /** 预占 → 发帖占位 → 提交。一个动作里唯一会真实出站的一段；null = 已记失败，继续下一个动作。 */
    private suspend fun reserveAndSubmit(
        plannedAction: EngagePlanner.PlannedAction,
        idx: Int,
        target: WeiboFeedApi.FeedPost?,
        text: EngageText?,
        body: String,
    ): Attempt? {
        // 预占：占不到就跳过（并发或重放）
        val reserved = runPort.reserveSlot(
            uid = access.account.uid, day = planned.day, action = plannedAction.action,
            seq = plannedAction.seq, textId = text?.id.orEmpty(), now = ctx.clock(),
        )
        if (!reserved) {
            record(failedRecord(plannedAction, EngageErrorKind.DUPLICATE_ACTION, "槽位已被占用，跳过"), idx + 1)
            return null
        }

        // 发帖：**请求前**先把台账落成 UNCERTAIN 占位。
        // 否则进程在请求途中被杀时，计数里有 pending、台账里却没有记录，
        // 用户既看不到也收敛不了，「已确认发出/未发出」的入口根本不会出现。
        // 占位与最终写入必须用同一个时间戳：台账按 `dayKey(at)` 分桶，
        // 跨零点时用两个时间戳会把同一条帖子拆到两天的文件里。
        var ledgerAt = 0L
        if (plannedAction.action == EngageAction.POST) {
            ledgerAt = ctx.clock()
            runPort.savePost(buildPostRecord(plannedAction.seq, ledgerAt, body, text, ActionState.UNCERTAIN))
        }

        ctx.log(
            EngageLogLevel.INFO,
            "提交中：${submitHint(plannedAction.action, target, body, text)}",
            plannedAction.action,
            plannedAction.seq,
        )
        val sentAt = ctx.clock()
        val result = if (plannedAction.action == EngageAction.POST) {
            gateway.publishTopicPost(
                session = access.session,
                topicId = access.config.topicId,
                content = body,
                syncToTimeline = access.config.syncToTimeline,
            )
        } else {
            // 上面的「目标缺失」守卫已保证这里非空；仍显式判一次，
            // 因为 planner 的契约若将来变化，这里必须退化成一条失败记录而不是 NPE。
            val t = target
            if (t == null) {
                runPort.releaseSlot(access.account.uid, planned.day, plannedAction.action, plannedAction.seq)
                record(failedRecord(plannedAction, EngageErrorKind.TARGET_GONE, "缺少目标帖子，已跳过"), idx + 1)
                return null
            }
            executeOnTarget(plannedAction.action, access.session, t, body)
        }
        return Attempt(plannedAction, target, text, body, ledgerAt, ctx.clock() - sentAt, result)
    }

    /** 三态收敛：槽位（提交/撤销/保留）→ 记录 → 发帖台账终态 → 文案统计 → 熔断处置。 */
    private fun settle(a: Attempt, idx: Int): StepFlow {
        val plannedAction = a.plannedAction
        val result = a.result

        val state = when {
            result.ok -> ActionState.OK
            result.outcomeUnknown && plannedAction.action == EngageAction.POST -> ActionState.UNCERTAIN
            else -> ActionState.FAILED
        }

        when (state) {
            ActionState.OK -> runPort.commitSlot(access.account.uid, planned.day, plannedAction.action, plannedAction.seq, ctx.clock())
            ActionState.FAILED -> runPort.releaseSlot(access.account.uid, planned.day, plannedAction.action, plannedAction.seq)
            ActionState.UNCERTAIN, ActionState.AWAITING_CONFIRM ->
                runPort.markUncertain(access.account.uid, planned.day, plannedAction.action, plannedAction.seq, ctx.clock())
            ActionState.SKIPPED -> Unit
        }

        val at = ctx.clock()
        val detail = listOfNotNull(
            WeiboHttp.safeBodyExcerpt(result.message, 100).takeIf { it.isNotEmpty() },
            "用时 ${costLabel(a.costMs)}",
        ).joinToString(" · ")
        record(
            EngageActionRecord(
                action = plannedAction.action,
                seq = plannedAction.seq,
                state = state,
                errorKind = result.kind,
                targetMid = a.target?.mid.orEmpty(),
                remoteId = result.remoteId,
                textId = a.text?.id.orEmpty(),
                message = detail,
                at = at,
            ),
            idx + 1,
        )

        // 发帖台账（无论成败都留痕，UNCERTAIN 尤其重要）
        if (plannedAction.action == EngageAction.POST) {
            runPort.savePost(
                buildPostRecord(plannedAction.seq, a.ledgerAt, a.body, a.text, state).copy(
                    remoteId = result.remoteId,
                    errorKind = result.kind,
                    message = WeiboHttp.safeBodyExcerpt(result.message, 120),
                )
            )
        }

        if (a.text != null && state == ActionState.OK) usedTextIds.getValue(a.text.kind).add(a.text.id)

        return when (guard.onResult(result.kind)) {
            Disposition.CONTINUE -> StepFlow.Next
            // 只放弃这个动作的剩余部分，其它动作照跑（契约见 EngageGuard）
            Disposition.ABORT_ACTION ->
                StepFlow.SkipRest("已放弃剩余${plannedAction.action.label}：${result.kind.label}", uiLog = true)
            Disposition.ABORT_RUN, Disposition.REQUIRE_HUMAN, Disposition.RETRY_LATER ->
                StepFlow.EndRun(guard.currentStopReason.ifEmpty { result.kind.label }, resumeFrom = idx + 1)
        }
    }

    /** 提交与收敛的总装：两段都完成后把流向交回主循环。 */
    private suspend fun submitAndSettle(
        plannedAction: EngagePlanner.PlannedAction,
        idx: Int,
        target: WeiboFeedApi.FeedPost?,
        text: EngageText?,
        body: String,
    ): StepFlow {
        val attempt = reserveAndSubmit(plannedAction, idx, target, text, body) ?: return StepFlow.Next
        return settle(attempt, idx)
    }

    // ========================= 私有工具 =========================

    /**
     * 评论 / 转发 / 点赞。目标由调用方保证非空——planner 只在 `target != null` 时
     * 才会产出这三个动作。
     *
     * 0.15.0 之前这里是 `execute(target: FeedPost?)` 里三处 `target!!`：判空在 60 行
     * 之外的一次 `continue` 里，靠注释维持类型不变式。拆成两个函数后，非空由签名保证。
     */
    private fun executeOnTarget(
        action: EngageAction,
        session: WeiboHttp.WeiboSession,
        target: WeiboFeedApi.FeedPost,
        body: String,
    ): WeiboWriteApi.WriteResult = when (action) {
        EngageAction.COMMENT -> gateway.comment(session, target.mid, body)
        EngageAction.REPOST -> gateway.repost(session, target.mid, body)
        EngageAction.LIKE -> gateway.like(session, target.mid)
        EngageAction.POST -> throw IllegalArgumentException("发帖请走 publishTopicPost()")
    }

    /** 发帖台账记录。`seq` 由 planner 按「当日已完成数」接续，同一天的第二轮不会覆盖第一轮。 */
    private fun buildPostRecord(
        seq: Int,
        at: Long,
        body: String,
        text: EngageText?,
        state: ActionState,
    ) = EngagePostRecord(
        id = EngageService.postIdOf(access.account.uid, planned.day, seq),
        runId = runId,
        seq = seq,
        at = at,
        accountUid = access.account.uid,
        accountName = access.account.name,
        topicId = access.config.topicId,
        topicName = access.topicName,
        body = body,
        textId = text?.id.orEmpty(),
        textDigest = text?.digest.orEmpty(),
        state = state,
    )

    /** 文案渲染：占位符全部用计划生成时刻（避免执行到一半日期翻篇）。 */
    private fun renderForAction(raw: String, plannedAction: EngagePlanner.PlannedAction): String =
        EngageTextPool.render(
            raw,
            EngageTextPool.RenderContext(
                topicName = access.topicName,
                date = EngageSchedule.dateLabel(planned.now),
                dateCn = EngageSchedule.dateCnLabel(planned.now),
                seq = plannedAction.seq,
                time = EngageSchedule.clockLabel(planned.now),
            ),
        )

    private fun failedRecord(
        plannedAction: EngagePlanner.PlannedAction,
        kind: EngageErrorKind,
        message: String,
    ) = EngageActionRecord(
        action = plannedAction.action,
        seq = plannedAction.seq,
        state = ActionState.FAILED,
        errorKind = kind,
        message = message,
        at = ctx.clock(),
    )

    /** 请求前一行的「在做什么」，尽量带上目标与正文规模，便于事后追。 */
    private fun submitHint(
        action: EngageAction,
        target: WeiboFeedApi.FeedPost?,
        body: String,
        text: EngageText?,
    ): String {
        val head = target?.let { WeiboHttp.safeBodyExcerpt(it.text.replace('\n', ' '), 18) }
        return if (action == EngageAction.POST) {
            "发帖 ${body.length} 字（文案 ${text?.id?.takeLast(6).orEmpty()}）"
        } else {
            buildString {
                if (action == EngageAction.LIKE) {
                    append("点赞帖子 ").append(target?.mid.orEmpty())
                } else {
                    append(action.label).append("帖子 ").append(target?.mid.orEmpty())
                }
                if (!head.isNullOrEmpty()) append("「").append(head).append("」")
                when {
                    action == EngageAction.LIKE -> Unit
                    // 关闭转发文案时正文为空，写成「0 字」会让人以为取文案失败了
                    body.isBlank() -> append("（空转发）")
                    else -> append("（").append(body.length).append(" 字）")
                }
            }
        }
    }

    /** 请求耗时：小于 10 秒保留一位小数，够用且不啰嗦。 */
    private fun costLabel(ms: Long): String {
        if (ms < 0L) return "0.0 秒"
        if (ms >= 10_000L) return "${(ms + 500) / 1_000} 秒"
        val tenth = (ms + 50) / 100
        return "${tenth / 10}.${tenth % 10} 秒"
    }

    private fun randomIn(range: LongRange): Long {
        if (range.isEmpty()) return 0L
        return Random.nextLong(range.first, range.last + 1)
    }

    private fun throttleKey(action: EngageAction): String = when (action) {
        EngageAction.POST -> EngageService.KEY_POST
        EngageAction.COMMENT -> EngageService.KEY_COMMENT
        EngageAction.REPOST -> EngageService.KEY_REPOST
        EngageAction.LIKE -> EngageService.KEY_LIKE
    }

    companion object {
        /** 判定时间预算时预留的余量：一次请求 + 记账开销。 */
        private const val REQUEST_MARGIN_MS = 30_000L
    }
}

/** 弹文案 + 渲染 + 本地校验的产物；[text] 为 null 表示该动作无文案（点赞/空转发）。 */
internal class Content(val text: EngageText?, val body: String)

/** 目标弹取结果：[skipReason] 非空即「整个动作放弃」（此时 target 一定是 null）。 */
internal class ResolvedTarget(val target: WeiboFeedApi.FeedPost?, val skipReason: String?)
