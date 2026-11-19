package com.wcm.checkin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 账号来源：把「按 uid 取一个可用账号」这件事从 `:domain` 里解耦出来。
 *
 * 为什么不直接用 `AccountPort`：那个端口的签名是为签到链路设计的（大批量读改写、
 * 判死标记、换发回写），本功能只需要「拿一份 cookie」和「把轮换后的 cookie 写回」。
 * 窄接口能让 `:domain` 不依赖 `:data`，也方便单测塞假的账号。
 */
data class EngageAccount(
    val uid: String,
    val name: String,
    val cookie: String,
    val cookieDomains: String = "",
    val dead: Boolean = false,
)

fun interface EngageAccountSource {
    fun byUid(uid: String): EngageAccount?
}

/** 换发回写。与读账号分开，避免实现方被迫同时实现写。 */
fun interface EngageAccountSink {
    fun updateCookie(uid: String, cookie: String, domains: String)
}

/** 一轮运行的结果。 */
data class EngageOutcome(
    val run: EngageRun? = null,
    /** 非空表示这一轮根本没跑起来，值是给用户看的原因。 */
    val blockedReason: String = "",
    val notes: List<String> = emptyList(),
)

/** 单条「确认后发送」的结果。 */
data class PublishOutcome(
    val record: EngagePostRecord? = null,
    val blockedReason: String = "",
)

/**
 * 超话互动任务的编排。
 *
 * 阶段：配置校验 → 账号 → 登录态 → 超话关注校验 → 配额与计划 → 文案与目标
 * → 逐动作执行（节流/预占/请求/分类/记账/熔断）→ 收尾（换发回写 + 记录 + 淘汰）。
 *
 * 三条硬规则贯穿全文：
 * 1. **发帖绝不重放**：请求前预占，并先把台账落成 UNCERTAIN 占位；网络类失败
 *    进 UNCERTAIN 并锁死当日发帖，直到用户人工收敛；
 * 2. **需要人工验证一律停下**，不做任何绕过；
 * 3. **所有网络调用都在 IO 线程**：本文件用的网络客户端全是 OkHttp 的同步
 *    `execute()`，跑在主线程会直接抛 `NetworkOnMainThreadException`
 *    （同样的坑见 `BrowsingViewModel` 的注释）。
 */
class EngageService(
    private val configPort: EngageConfigPort,
    private val runPort: EngageRunPort,
    private val accountSource: EngageAccountSource,
    private val accountSink: EngageAccountSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val gate = Mutex()

    /**
     * 实时进度接收方（界面用）。
     *
     * 用可变回调而不是构造参数注入：`EngageService` 是 `@Singleton`，由
     * `DomainModule` 装配，而观察方是随 Activity 生灭的 ViewModel。把回调做成
     * 可替换字段，ViewModel 重建时重新挂上即可，不用改组合根、也不会因为
     * 旧 ViewModel 被回收而让整个服务不可用。
     *
     * 回调在 IO 线程触发；实现方必须自己保证线程安全（`MutableStateFlow.update` 满足）。
     */
    @Volatile
    var onProgress: ((EngageProgress) -> Unit)? = null

    private fun report(progress: EngageProgress) {
        // 进度是纯展示，绝不能让它的异常打断真正的一轮
        runCatching { onProgress?.invoke(progress) }
    }

    /** 正在跑（供界面禁用按钮，避免误以为可以并发补跑）。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * 跑一轮。
     *
     * [background] 为 true 时是**自动轮次**（打开 App 补跑 / 后台 Worker）：
     * 设为「仅手动」的动作会被跳过，发帖还要看「后台是否允许发帖」开关。
     * [budgetCapMs] 非空时把时间预算再压低到这个值（取两者更小）。
     * [only] 非空时只做这些动作（任务页每个动作的「单独执行」）。
     *
     * 后台由 `CoroutineWorker` 执行，系统 10 分钟就会掐断，所以那里传 9 分钟；
     * 手动执行只在 Activity 存活期间跑，可以用配置里更宽的预算。
     */
    suspend fun run(
        background: Boolean = false,
        budgetCapMs: Long? = null,
        only: Set<EngageAction> = emptySet(),
    ): EngageOutcome {
        if (!gate.tryLock()) {
            reportBlocked("已有任务在运行")
            return EngageOutcome(blockedReason = "已有任务在运行")
        }
        isRunning = true
        try {
            // 必须切 IO：调用方可能是 viewModelScope（默认 Main.immediate）。
            return withContext(Dispatchers.IO) { runInternal(background, budgetCapMs, only) }
        } finally {
            isRunning = false
            gate.unlock()
        }
    }

    /** 被拒绝时也要推一帧进度，否则界面会一直停在上一轮的状态。 */
    private fun reportBlocked(reason: String, trigger: EngageTrigger = EngageTrigger.MANUAL_ALL) {
        val now = clock()
        report(
            EngageProgress(stage = "未执行", startedAt = now, trigger = trigger)
                .log(now, EngageLogLevel.WARN, reason)
                .finish(now)
                .copy(message = reason)
        )
    }

    /**
     * 人工确认后发送一条「等待确认」的发帖。
     *
     * 与自动发帖走完全相同的预占 / 三态收敛路径，唯一区别是正文取自台账里
     * 已经组好并落盘的那一份——用户在界面上看到的，就是真正发出去的。
     */
    suspend fun publishConfirmed(uid: String, day: String, postId: String): PublishOutcome {
        if (!gate.tryLock()) return PublishOutcome(blockedReason = "已有任务在运行")
        isRunning = true
        try {
            return withContext(Dispatchers.IO) { publishInternal(uid, day, postId) }
        } finally {
            isRunning = false
            gate.unlock()
        }
    }

    // ========================= 一轮执行 =========================

    private suspend fun runInternal(
        background: Boolean,
        budgetCapMs: Long? = null,
        only: Set<EngageAction> = emptySet(),
    ): EngageOutcome {
        val startedAt = clock()
        val trigger = when {
            only.isNotEmpty() -> EngageTrigger.MANUAL_ONLY
            background -> EngageTrigger.AUTO
            else -> EngageTrigger.MANUAL_ALL
        }

        // 全过程只有一个可变进度对象 p，每次变更立刻推给界面。
        //
        // 局部函数而不是字段：一轮结束后进度要留在界面上供回看，下一轮从空白开始，
        // 不需要跨轮保留状态；同时这些函数天然共享本次的 clock 与 report，不用传参。
        var p = EngageProgress(startedAt = startedAt, trigger = trigger)

        fun emit(change: (EngageProgress) -> EngageProgress) {
            p = change(p)
            report(p)
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

        /** 提前收场：把没走完的步骤标成失败，并给出可读原因（界面不能永远停在「进行中」）。 */
        fun abort(reason: String): EngageOutcome {
            log(EngageLogLevel.ERROR, "本轮中止：$reason")
            val now = clock()
            emit {
                it.closeOpenSteps(EngageStepState.FAILED, now, reason)
                    .copy(stage = "已中止", message = reason)
                    .finish(now)
            }
            Log.w(TAG, "本轮不执行：$reason")
            return EngageOutcome(blockedReason = reason)
        }

        // ---- 配置与账号 ----
        stepStart(STEP_PREPARE, "读取配置")
        log(
            EngageLogLevel.INFO,
            "开始执行（${trigger.label}）" +
                if (only.isEmpty()) "" else "，仅做 ${only.joinToString("、") { it.label }}",
        )
        val config = configPort.loadConfig()
        config.validate()?.let { return abort(it) }

        val account = accountSource.byUid(config.accountUid)
            ?: return abort("账号不存在或已删除")
        if (account.dead) return abort("账号登录态已失效")
        if (account.cookie.isBlank()) return abort("账号缺少 Cookie")
        stepEnd(STEP_PREPARE, EngageStepState.OK, config.accountUid)
        log(EngageLogLevel.INFO, "执行账号：${account.name.ifEmpty { account.uid }}")

        // ---- 登录态 ----
        stepStart(STEP_AUTH, "校验登录态")
        val verified = try {
            WeiboAuthApi.verify(account.cookie, force = true)
        } catch (e: Exception) {
            val detail = WeiboHttp.safeBodyExcerpt(e.message ?: "", 60)
            stepEnd(STEP_AUTH, EngageStepState.FAILED, "网络异常")
            return abort("登录态校验失败：$detail")
        }
        if (!verified.login) {
            stepEnd(STEP_AUTH, EngageStepState.FAILED, "登录态已失效")
            return abort("登录态已失效，请重新登录")
        }
        stepEnd(STEP_AUTH, EngageStepState.OK, "有效")

        val session = WeiboHttp.WeiboSession.of(account.cookie, account.cookieDomains)
        session.merge(verified.cookie)

        // ---- 超话关注校验（计分前置条件）----
        // 两边都归一成 containerid 再比：`Topic.topicId` 是去掉 100808 的 hash，
        // 配置里存的是完整 containerid，直接 == 永远匹配不上。
        stepStart(STEP_TOPIC, "校验目标超话")
        val container = EngageSchedule.topicContainerId(config.topicId)
        val topics = try {
            WeiboTopicApi.listTopics(session)
        } catch (e: Exception) {
            val detail = WeiboHttp.safeBodyExcerpt(e.message ?: "", 60)
            stepEnd(STEP_TOPIC, EngageStepState.FAILED, "读取超话列表失败")
            return abort("读取超话列表失败：$detail")
        }
        val topic = topics.firstOrNull { EngageSchedule.topicContainerId(it.topicId) == container }
            ?: return abort("未关注该超话，请先在微博内关注后再运行")
        val topicName = topic.name.ifEmpty { config.topicName }
        stepEnd(STEP_TOPIC, EngageStepState.OK, "#$topicName#")
        log(EngageLogLevel.INFO, "目标超话：#$topicName#（已关注 ${topics.size} 个）")

        // ---- 计划 ----
        stepStart(STEP_PLAN, "生成执行计划")
        val now = clock()
        val day = EngageSchedule.dayKey(now)
        val counter = runPort.loadCounter(account.uid, day)
        val plan = EngagePlanner.plan(config, counter, now, background, only)
        if (plan.blocked.isNotEmpty()) {
            stepEnd(STEP_PLAN, EngageStepState.WARN, plan.blocked)
            return abort(plan.blocked)
        }
        val total = plan.actions.size
        emit { it.progress(0, total) }
        stepEnd(STEP_PLAN, EngageStepState.OK, "$total 个动作")
        log(
            EngageLogLevel.INFO,
            "本轮计划 $total 个动作：" + EngageAction.entries
                .mapNotNull { a -> plan.countOf(a).takeIf { n -> n > 0 }?.let { "${a.label} $it" } }
                .joinToString(" · "),
        )
        plan.notes.forEach { log(EngageLogLevel.WARN, it) }

        // 预算取「配置值」与「调用方上限」的较小者：后台执行不能超过 Worker 的系统上限
        val budgetMs = minOf(config.maxRunDurationMs, budgetCapMs ?: Long.MAX_VALUE)
        val deadline = startedAt + budgetMs
        val estimate = EngagePacing.estimateRunMs(config, plan)
        emit { it.copy(etaMs = estimate) }
        log(
            EngageLogLevel.INFO,
            "按当前节奏预计 ${EngagePacing.formatDuration(estimate)}，" +
                "本轮预算 ${EngagePacing.formatDuration(budgetMs)}",
        )

        // ---- 文案（三套独立库：发帖 / 评论 / 转发）----
        //
        // 0.14.0 之前是一个池子按「评论+转发+发帖」总量取、再顺序弹给三类动作，
        // 于是 1200 字的发帖文案会被当成评论发出去（再被 140 字校验拦掉）。
        // 现在按动作各取各库，转发可以整库关闭（走空转发）。
        stepStart(STEP_TEXT, "准备文案")
        val forbiddenDays = EngageSchedule.forbiddenTextDays(day, config.strictTextGapDays)
        val postNeed = plan.countOf(EngageAction.POST)
        val commentNeed = plan.countOf(EngageAction.COMMENT)
        val repostNeed = plan.countOf(EngageAction.REPOST)
        val repostNeedsText = config.repostTextEnabled

        val postPool = configPort.loadTexts(TextKind.POST)
        val commentPool = configPort.loadTexts(TextKind.COMMENT)
        val repostPool = if (repostNeedsText) configPort.loadTexts(TextKind.REPOST) else emptyList()

        fun pickFrom(kind: TextKind, pool: List<EngageText>, count: Int): List<EngageText> =
            EngageTextPool.pick(
                pool = pool,
                count = count,
                forbiddenDays = forbiddenDays,
                mode = config.textModeOf(kind),
                random = EngageTextPool.randomFor(day, kind),
            )

        val postPicked = pickFrom(TextKind.POST, postPool, postNeed)
        val commentPicked = pickFrom(TextKind.COMMENT, commentPool, commentNeed)
        val repostPicked = if (repostNeedsText) pickFrom(TextKind.REPOST, repostPool, repostNeed) else emptyList()

        val postQueue = ArrayDeque(postPicked)
        val commentQueue = ArrayDeque(commentPicked)
        val repostQueue = ArrayDeque(repostPicked)

        val textShort = postPicked.size < postNeed ||
            commentPicked.size < commentNeed ||
            (repostNeedsText && repostPicked.size < repostNeed)
        val textDetail = buildString {
            append("发帖 ").append(postPicked.size).append('/').append(postNeed)
            append(" · 评论 ").append(commentPicked.size).append('/').append(commentNeed)
            append(" · 转发 ")
            if (repostNeedsText) {
                append(repostPicked.size).append('/').append(repostNeed)
            } else {
                append("不带文案")
            }
        }
        stepEnd(STEP_TEXT, if (textShort) EngageStepState.WARN else EngageStepState.OK, textDetail)
        log(
            if (textShort) EngageLogLevel.WARN else EngageLogLevel.INFO,
            "本轮文案：$textDetail" +
                "（发帖库 ${EngageTextPool.availableCount(postPool)} 条 / " +
                "评论库 ${EngageTextPool.availableCount(commentPool)} 条 / " +
                (if (repostNeedsText) "转发库 ${EngageTextPool.availableCount(repostPool)} 条）" else "转发不带文案）"),
        )

        // ---- 互动目标 ----
        val targetDemand = EngagePlanner.targetDemand(plan)
        var feedError = ""
        var feedCandidates = 0
        val targets: List<WeiboFeedApi.FeedPost>
        if (targetDemand > 0) {
            stepStart(STEP_FEED, "拉取超话帖子流")
            targets = try {
                // 三类动作各弹一个目标，需求量是**次数之和**而不是最大值：
                // 用 max 时默认配置（4 评论 + 2 转发 + 3 点赞）只取 6 个目标，
                // 后面的点赞必然全部落空。
                val posts = WeiboFeedApi.listTopicFeed(session, container)
                feedCandidates = posts.size
                WeiboFeedApi.pickTargets(
                    posts = posts,
                    selfUid = account.uid,
                    limit = targetDemand + TARGET_SLACK,
                    preferFirstComment = config.preferFirstComment,
                )
            } catch (e: Exception) {
                // 不再静默吞掉：目标流拿不到时，评论/转发/点赞都会逐个空转等待，
                // 最后只留下一句「0/0」，用户完全无从判断。
                feedError = "超话帖子流不可用：${WeiboHttp.safeBodyExcerpt(e.message ?: "", 60)}"
                emptyList()
            }
            if (targets.isEmpty() && feedError.isEmpty()) {
                feedError = "超话帖子流没有可用目标（可能被风控或接口改版）"
            }
            stepEnd(
                STEP_FEED,
                if (targets.isEmpty()) EngageStepState.WARN else EngageStepState.OK,
                "${targets.size} 条可用目标",
            )
            log(
                if (targets.isEmpty()) EngageLogLevel.WARN else EngageLogLevel.INFO,
                "候选帖 $feedCandidates 条，过滤自己与转发帖后可用 ${targets.size} 条（需要 $targetDemand 条）",
            )
            if (feedError.isNotEmpty()) log(EngageLogLevel.ERROR, feedError)
        } else {
            targets = emptyList()
            log(EngageLogLevel.INFO, "本轮没有互动动作，跳过帖子流拉取")
        }
        val targetQueue = ArrayDeque(targets)

        // ---- 执行 ----
        val runId = "r$startedAt"
        val records = ArrayList<EngageActionRecord>(plan.actions.size)
        val guard = EngageGuard(config.failStreakAbort)
        // 按库汇总本轮用到的文案 id：写回统计时必须知道这条属于哪个库
        val usedTextIds: MutableMap<TextKind, MutableSet<String>> =
            TextKind.entries.associateWith { mutableSetOf<String>() }.toMutableMap()
        // 今日已存在的「待确认」发帖：这些槽位没进计数（还没发），但不能再建第二条，
        // 否则每次运行都会用同一个台账 id 覆盖上一条待确认内容。
        val awaitingIds = runPort.loadPosts(account.uid, day)
            .filter { it.state == ActionState.AWAITING_CONFIRM }
            .map { it.id }
            .toMutableSet()
        var currentAction: EngageAction? = null
        var skipAction: EngageAction? = null
        var skipReason = ""
        var breakFrom = -1
        var stopReason = ""

        /** 一轮进行中的运行记录快照：`finishedAt = 0` 表示还没跑完。 */
        fun snapshot(finishedAtValue: Long): EngageRun = EngageRun(
            runId = runId,
            accountUid = account.uid,
            topicId = config.topicId,
            topicName = topicName,
            startedAt = startedAt,
            finishedAt = finishedAtValue,
            stopReason = guard.currentStopReason,
            records = records.toList(),
            trigger = trigger,
            only = only.toList(),
            steps = p.steps,
            logs = p.logs,
        )

        /**
         * 记一条动作结果：落内存 → 推进度写流水 → **立刻写一份草稿运行记录**。
         *
         * 草稿这一步是排查能力的关键：原来只在 `finally` 写一次，跑的过程中
         * 「运行记录」永远是空的，界面上除了一行「正在执行…」什么都看不到。
         */
        fun record(rec: EngageActionRecord, done: Int) {
            records.add(rec)
            emit { it.copy(done = done, total = total, action = rec.action, message = rec.message) }
            val level = when (rec.state) {
                ActionState.OK -> EngageLogLevel.OK
                ActionState.FAILED -> EngageLogLevel.ERROR
                ActionState.UNCERTAIN -> EngageLogLevel.WARN
                ActionState.SKIPPED, ActionState.AWAITING_CONFIRM -> EngageLogLevel.INFO
            }
            log(
                level,
                rec.state.label + if (rec.message.isEmpty()) "" else "：${rec.message}",
                rec.action,
                rec.seq,
            )
            Log.i(TAG, "[$done/$total] ${rec.action.label}#${rec.seq} ${rec.state} ${rec.errorKind} ${rec.message}")
            runCatching { runPort.saveRun(snapshot(0L)) }
        }

        try {
            stepStart(STEP_RUN, "执行动作")
            for ((idx, planned) in plan.actions.withIndex()) {
                if (guard.isStopped) {
                    stopReason = guard.currentStopReason.ifEmpty { "已熔断" }
                    breakFrom = idx
                    break
                }
                if (planned.action == skipAction) {
                    // 该动作已判定放弃，剩余次数不再空转等待，但记录要补齐，
                    // 否则摘要里只看得到「0/1」，看不出原本计划了几条。
                    record(skippedRecord(planned, skipReason, clock()), idx + 1)
                    continue
                }

                val switching = currentAction != planned.action
                if (switching) {
                    guard.nextAction()
                    currentAction = planned.action
                    log(
                        EngageLogLevel.STEP,
                        "开始${planned.action.label}（本轮计划 ${plan.countOf(planned.action)} 次）",
                        planned.action,
                    )
                }

                // 动作间隔：同一动作用该动作的间隔区间，跨动作再加一段通用抖动。
                // **预算必须按「实际还要等多久」判**：同一个 key 的首次请求根本不等待，
                // 按配置的理论间隔去扣，会把当天第一条帖子直接判死。
                val gapRange = config.gapRangeOf(planned.action)
                val nominalWait = randomIn(gapRange) +
                    if (switching) randomIn(config.actionGapRange()) else 0L
                val realWait = WeiboThrottle.remaining(throttleKey(planned.action), nominalWait)
                if (clock() + realWait + REQUEST_MARGIN_MS > deadline) {
                    stopReason = "本轮已达时间预算（${budgetMs / 60_000} 分钟），剩余动作留待下一轮"
                    guard.trip(stopReason)
                    log(EngageLogLevel.WARN, "$stopReason（还要等 ${(realWait + 999) / 1000} 秒）")
                    breakFrom = idx
                    break
                }
                if (realWait > 0) {
                    // 等间隔是整轮里最长的一段「什么都不发生」，必须让界面看到倒计时，
                    // 否则用户会以为卡死。
                    emit { it.waiting(clock() + realWait) }
                    log(
                        EngageLogLevel.WAIT,
                        "等待 ${(realWait + 999) / 1000} 秒（${planned.action.label}间隔 ${EngagePacing.describe(gapRange)}）",
                        planned.action,
                        planned.seq,
                    )
                }
                WeiboThrottle.await(throttleKey(planned.action), nominalWait)
                emit { it.clearWait() }

                // 点赞没有文案；转发在「不带文案」时也不取（空转发由 WeiboWriteApi 允许）
                val textKind = config.textKindOf(planned.action)
                val text = when (textKind) {
                    TextKind.POST -> postQueue.removeFirstOrNull()
                    TextKind.COMMENT -> commentQueue.removeFirstOrNull()
                    TextKind.REPOST -> repostQueue.removeFirstOrNull()
                    null -> null
                }

                // 文案缺失：发帖、评论、带文案的转发都做不了（先判，后面才能安全用 text!!）
                if (textKind != null && text == null) {
                    record(
                        failedRecord(
                            planned, EngageErrorKind.NO_TEXT,
                            "${textKind.label}文案库已无可用文案", clock(),
                        ),
                        idx + 1,
                    )
                    continue
                }

                // 正文：发帖要「渲染 + 组装超话标签」，评论/转发只渲染占位符。
                // 三者都不该把 `{topic}` 这类占位符原样发出去。
                val rendered = if (text != null) renderForAction(text.body, planned, topicName, now) else ""
                val body = if (planned.action == EngageAction.POST) {
                    EngageTextPool.compose(topicName, rendered)
                } else {
                    rendered
                }

                // 本地能判的先判，别浪费一次请求
                val localBad = when (planned.action) {
                    EngageAction.POST -> EngageTextPool.validate(body, config.maxTextLen)
                    EngageAction.COMMENT -> EngageTextPool.validateComment(body)
                    // 空转发是合法的（平台允许 comment 留空），不能过 validateComment：
                    // 它会把空串判成「正文为空」而整条跳过。
                    EngageAction.REPOST -> if (body.isNotBlank()) EngageTextPool.validateComment(body) else null
                    EngageAction.LIKE -> null
                }
                if (localBad != null) {
                    record(failedRecord(planned, EngageErrorKind.CONTENT_REJECTED, localBad, clock()), idx + 1)
                    continue
                }

                // 目标缺失：评论/转发/点赞做不了。**整个动作提前放弃**：
                // 目标流为空时，按原逻辑每条都要白等几十秒，用户只看到「什么都没执行」。
                val target = if (planned.action == EngageAction.POST) null else targetQueue.removeFirstOrNull()
                if (planned.action != EngageAction.POST && target == null) {
                    val reason = feedError.ifEmpty { "没有可互动的帖子" }
                    record(failedRecord(planned, EngageErrorKind.TARGET_GONE, reason, clock()), idx + 1)
                    skipAction = planned.action
                    skipReason = "已放弃剩余${planned.action.label}：$reason"
                    Log.w(TAG, skipReason)
                    continue
                }

                // 人工确认模式：只组正文、落台账，不预占也不发请求
                if (planned.action == EngageAction.POST && config.requireConfirmBeforePost) {
                    val recId = postIdOf(account.uid, day, planned.seq)
                    val at = clock()
                    if (recId in awaitingIds) {
                        record(
                            failedRecord(
                                planned, EngageErrorKind.DUPLICATE_ACTION,
                                "已有一条待确认发帖，请先在发帖记录里处理", at,
                            ),
                            idx + 1,
                        )
                        continue
                    }
                    awaitingIds.add(recId)
                    runPort.savePost(
                        buildPostRecord(
                            config = config, account = account, topicName = topicName, day = day,
                            runId = runId, seq = planned.seq, at = at, body = body,
                            text = text, state = ActionState.AWAITING_CONFIRM,
                        )
                    )
                    record(
                        EngageActionRecord(
                            action = EngageAction.POST,
                            seq = planned.seq,
                            state = ActionState.SKIPPED,
                            textId = text?.id.orEmpty(),
                            message = "正文已组好，等待你在发帖记录里确认发送",
                            at = at,
                        ),
                        idx + 1,
                    )
                    continue
                }

                // 预占：占不到就跳过（并发或重放）
                val reserved = runPort.reserveSlot(
                    uid = account.uid, day = day, action = planned.action,
                    seq = planned.seq, textId = text?.id.orEmpty(), now = clock(),
                )
                if (!reserved) {
                    record(failedRecord(planned, EngageErrorKind.DUPLICATE_ACTION, "槽位已被占用，跳过", clock()), idx + 1)
                    continue
                }

                // 发帖：**请求前**先把台账落成 UNCERTAIN 占位。
                // 否则进程在请求途中被杀时，计数里有 pending、台账里却没有记录，
                // 用户既看不到也收敛不了，「已确认发出/未发出」的入口根本不会出现。
                // 占位与最终写入必须用同一个时间戳：台账按 `dayKey(at)` 分桶，
                // 跨零点时用两个时间戳会把同一条帖子拆到两天的文件里。
                var ledgerAt = 0L
                if (planned.action == EngageAction.POST) {
                    ledgerAt = clock()
                    runPort.savePost(
                        buildPostRecord(
                            config = config, account = account, topicName = topicName, day = day,
                            runId = runId, seq = planned.seq, at = ledgerAt, body = body,
                            text = text, state = ActionState.UNCERTAIN,
                        )
                    )
                }

                log(EngageLogLevel.INFO, "提交中：${submitHint(planned.action, target, body, text)}", planned.action, planned.seq)
                val sentAt = clock()
                val result = execute(planned, session, config, target, body)
                val costMs = clock() - sentAt

                val state = when {
                    result.ok -> ActionState.OK
                    result.outcomeUnknown && planned.action == EngageAction.POST -> ActionState.UNCERTAIN
                    else -> ActionState.FAILED
                }

                when (state) {
                    ActionState.OK -> runPort.commitSlot(account.uid, day, planned.action, planned.seq, clock())
                    ActionState.FAILED -> runPort.releaseSlot(account.uid, day, planned.action, planned.seq)
                    ActionState.UNCERTAIN, ActionState.AWAITING_CONFIRM ->
                        runPort.markUncertain(account.uid, day, planned.action, planned.seq, clock())
                    ActionState.SKIPPED -> Unit
                }

                val at = clock()
                val detail = listOfNotNull(
                    WeiboHttp.safeBodyExcerpt(result.message, 100).takeIf { it.isNotEmpty() },
                    "用时 ${costLabel(costMs)}",
                ).joinToString(" · ")
                val actionRecord = EngageActionRecord(
                    action = planned.action,
                    seq = planned.seq,
                    state = state,
                    errorKind = result.kind,
                    targetMid = target?.mid.orEmpty(),
                    remoteId = result.remoteId,
                    textId = text?.id.orEmpty(),
                    message = detail,
                    at = at,
                )
                record(actionRecord, idx + 1)

                // 发帖台账（无论成败都留痕，UNCERTAIN 尤其重要）
                if (planned.action == EngageAction.POST) {
                    runPort.savePost(
                        buildPostRecord(
                            config = config, account = account, topicName = topicName, day = day,
                            runId = runId, seq = planned.seq, at = ledgerAt, body = body,
                            text = text, state = state,
                        ).copy(
                            remoteId = result.remoteId,
                            errorKind = result.kind,
                            message = WeiboHttp.safeBodyExcerpt(result.message, 120),
                        )
                    )
                }

                if (text != null && state == ActionState.OK) usedTextIds.getValue(text.kind).add(text.id)

                when (guard.onResult(result.kind)) {
                    Disposition.CONTINUE -> Unit
                    // 只放弃这个动作的剩余部分，其它动作照跑（契约见 EngageGuard）
                    Disposition.ABORT_ACTION -> {
                        skipAction = planned.action
                        skipReason = "已放弃剩余${planned.action.label}：${result.kind.label}"
                        log(EngageLogLevel.WARN, skipReason)
                    }
                    Disposition.ABORT_RUN, Disposition.REQUIRE_HUMAN, Disposition.RETRY_LATER -> {
                        stopReason = guard.currentStopReason.ifEmpty { result.kind.label }
                        breakFrom = idx + 1
                        log(EngageLogLevel.WARN, "本轮提前结束：$stopReason")
                        break
                    }
                }
            }
        } finally {
            // 收尾必须在 finally 里：异常/取消时换发 cookie 与运行记录同样不能丢。
            val finishedAt = clock()

            // 被提前终止时把没走到的计划补齐成 SKIPPED：
            // 摘要里要能看出「发帖 0/1 是被预算砍掉的」，而不是只剩一个 0/0。
            if (breakFrom in 0 until plan.actions.size && stopReason.isNotEmpty()) {
                for (i in breakFrom until plan.actions.size) {
                    record(skippedRecord(plan.actions[i], stopReason, clock()), i + 1)
                }
            }

            val ok = records.count { it.state == ActionState.OK }
            val failed = records.count { it.state == ActionState.FAILED }
            val uncertain = records.count { it.state == ActionState.UNCERTAIN }
            stepEnd(
                STEP_RUN,
                if (failed > 0 || uncertain > 0) EngageStepState.WARN else EngageStepState.OK,
                "$ok/$total 成功",
            )

            stepStart(STEP_FINISH, "保存结果")
            markTextsUsed(usedTextIds, finishedAt, day)
            runPort.saveRun(snapshot(finishedAt))
            runCatching { runPort.prune(account.uid, day) }
            // 换发回写：整轮结束时把会话里吸收到的 Set-Cookie 落回账号
            runCatching { accountSink.updateCookie(account.uid, session.cookie, session.domainHints) }
            stepEnd(STEP_FINISH, EngageStepState.OK, "已保存")

            log(
                if (failed > 0 || uncertain > 0) EngageLogLevel.WARN else EngageLogLevel.OK,
                buildString {
                    append("本轮结束：成功 ").append(ok)
                    if (failed > 0) append(" · 失败 ").append(failed)
                    if (uncertain > 0) append(" · 结果未知 ").append(uncertain)
                    append(" · 总用时 ").append(EngagePacing.formatDuration(finishedAt - startedAt))
                },
            )
            emit {
                it.closeOpenSteps(EngageStepState.SKIPPED, finishedAt)
                    .copy(stage = if (guard.isStopped) "已结束（${guard.currentStopReason}）" else "已完成")
                    .finish(finishedAt)
            }
            Log.i(TAG, "本轮结束 records=${records.size} stop=${guard.currentStopReason}")
        }

        return EngageOutcome(
            run = snapshot(clock()),
            blockedReason = "",
            notes = if (feedError.isEmpty()) plan.notes else plan.notes + feedError,
        )
    }

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

    // ========================= 确认后发送 =========================

    private fun publishInternal(uid: String, day: String, postId: String): PublishOutcome {
        val record = runPort.loadPosts(uid, day).firstOrNull { it.id == postId }
            ?: return PublishOutcome(blockedReason = "台账里找不到这条记录").also {
                Log.w(TAG, "确认发送失败：台账里没有 $postId")
            }
        if (record.state != ActionState.AWAITING_CONFIRM) {
            return PublishOutcome(blockedReason = "这条记录不在等待确认状态").also {
                Log.w(TAG, "确认发送失败：状态是 ${record.state}")
            }
        }

        val config = configPort.loadConfig()
        config.validate()?.let { return PublishOutcome(blockedReason = it) }

        val account = accountSource.byUid(uid)
            ?: return PublishOutcome(blockedReason = "账号不存在或已删除")
        if (account.dead || account.cookie.isBlank()) {
            return PublishOutcome(blockedReason = "账号登录态已失效")
        }

        val verified = try {
            WeiboAuthApi.verify(account.cookie, force = true)
        } catch (e: Exception) {
            return PublishOutcome(blockedReason = "登录态校验失败：${WeiboHttp.safeBodyExcerpt(e.message ?: "", 60)}")
        }
        if (!verified.login) return PublishOutcome(blockedReason = "登录态已失效，请重新登录")

        val session = WeiboHttp.WeiboSession.of(account.cookie, account.cookieDomains)
        session.merge(verified.cookie)

        try {
            val reserved = runPort.reserveSlot(
                uid = uid, day = day, action = EngageAction.POST,
                seq = record.seq, textId = record.textId, now = clock(),
            )
            if (!reserved) return PublishOutcome(blockedReason = "该槽位已被占用，请刷新后重试")

            // 先落 UNCERTAIN 占位，理由同自动发帖：请求途中被杀也要留痕
            runPort.savePost(record.copy(state = ActionState.UNCERTAIN))

            val result = WeiboWriteApi.publishTopicPost(
                session = session,
                topicId = config.topicId,
                content = record.body,
                syncToTimeline = config.syncToTimeline,
            )
            val state = when {
                result.ok -> ActionState.OK
                result.outcomeUnknown -> ActionState.UNCERTAIN
                else -> ActionState.FAILED
            }
            when (state) {
                ActionState.OK -> runPort.commitSlot(uid, day, EngageAction.POST, record.seq, clock())
                ActionState.FAILED -> runPort.releaseSlot(uid, day, EngageAction.POST, record.seq)
                else -> runPort.markUncertain(uid, day, EngageAction.POST, record.seq, clock())
            }

            val updated = record.copy(
                state = state,
                remoteId = result.remoteId,
                errorKind = result.kind,
                message = WeiboHttp.safeBodyExcerpt(result.message, 120),
            )
            runPort.savePost(updated)
            Log.i(TAG, "确认发送 #${record.seq} → $state ${result.kind} ${updated.message}")
            if (state == ActionState.OK && record.textId.isNotEmpty()) {
                markTextsUsed(mapOf(TextKind.POST to setOf(record.textId)), clock(), day)
            }
            return PublishOutcome(record = updated)
        } finally {
            runCatching { accountSink.updateCookie(account.uid, session.cookie, session.domainHints) }
        }
    }

    // ========================= 内部工具 =========================

    private fun execute(
        planned: EngagePlanner.PlannedAction,
        session: WeiboHttp.WeiboSession,
        config: EngageConfig,
        target: WeiboFeedApi.FeedPost?,
        body: String,
    ): WeiboWriteApi.WriteResult = when (planned.action) {
        EngageAction.POST -> WeiboWriteApi.publishTopicPost(
            session = session,
            topicId = config.topicId,
            content = body,
            syncToTimeline = config.syncToTimeline,
        )
        EngageAction.COMMENT -> WeiboWriteApi.comment(session, target!!.mid, body)
        EngageAction.REPOST -> WeiboWriteApi.repost(session, target!!.mid, body)
        EngageAction.LIKE -> WeiboWriteApi.like(session, target!!.mid)
    }

    private fun buildPostRecord(
        config: EngageConfig,
        account: EngageAccount,
        topicName: String,
        day: String,
        runId: String,
        seq: Int,
        at: Long,
        body: String,
        text: EngageText?,
        state: ActionState,
    ) = EngagePostRecord(
        // seq 由 planner 按「当日已完成数」接续，所以同一天的第二轮不会覆盖第一轮的记录。
        id = postIdOf(account.uid, day, seq),
        runId = runId,
        seq = seq,
        at = at,
        accountUid = account.uid,
        accountName = account.name,
        topicId = config.topicId,
        topicName = topicName,
        body = body,
        textId = text?.id.orEmpty(),
        textDigest = text?.digest.orEmpty(),
        state = state,
    )

    private fun renderForAction(
        raw: String,
        planned: EngagePlanner.PlannedAction,
        topicName: String,
        now: Long,
    ): String = EngageTextPool.render(
        raw,
        EngageTextPool.RenderContext(
            topicName = topicName,
            date = EngageSchedule.dateLabel(now),
            dateCn = EngageSchedule.dateCnLabel(now),
            seq = planned.seq,
            time = EngageSchedule.clockLabel(now),
        ),
    )

    /**
     * 一轮结束后统一写一次使用统计。
     *
     * 每库各做一次 `mutateTexts`（锁内读改写）：原来走「整池 load → 整池 save」，
     * 中间只要用户导入一次文案，整池回写就会把新导入的条目悄悄覆盖掉。
     */
    private fun markTextsUsed(ids: Map<TextKind, Set<String>>, now: Long, day: String) {
        for ((kind, set) in ids) {
            if (set.isEmpty()) continue
            configPort.mutateTexts(kind) { pool ->
                pool.map {
                    if (it.id in set) {
                        it.copy(usedCount = it.usedCount + 1, lastUsedAt = now, lastUsedDay = day)
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun failedRecord(
        planned: EngagePlanner.PlannedAction,
        kind: EngageErrorKind,
        message: String,
        now: Long,
    ) = EngageActionRecord(
        action = planned.action,
        seq = planned.seq,
        state = ActionState.FAILED,
        errorKind = kind,
        message = message,
        at = now,
    )

    /** 没执行到的动作：让摘要里的分母保持真实（发帖 0/1 而不是 0/0）。 */
    private fun skippedRecord(
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

    private fun randomIn(range: LongRange): Long {
        if (range.isEmpty()) return 0L
        return Random.nextLong(range.first, range.last + 1)
    }

    private fun throttleKey(action: EngageAction): String = when (action) {
        EngageAction.POST -> KEY_POST
        EngageAction.COMMENT -> KEY_COMMENT
        EngageAction.REPOST -> KEY_REPOST
        EngageAction.LIKE -> KEY_LIKE
    }

    companion object {
        /** logcat 过滤：`adb logcat -s WcmEngage`。 */
        private const val TAG = "WcmEngage"

        /** 节流键统一前缀，避免和签到链路的 key 撞车。 */
        const val KEY_POST = "engage-post"
        const val KEY_COMMENT = "engage-comment"
        const val KEY_REPOST = "engage-repost"
        const val KEY_LIKE = "engage-like"

        /**
         * 步骤 id。
         *
         * 固定字符串而不是枚举：运行记录里存的是名字，改名会让老记录里的步骤
         * 显示不出来；用常量既避免拼错，也方便以后加步骤而不破坏历史数据。
         */
        const val STEP_PREPARE = "prepare"
        const val STEP_AUTH = "auth"
        const val STEP_TOPIC = "topic"
        const val STEP_PLAN = "plan"
        const val STEP_TEXT = "text"
        const val STEP_FEED = "feed"
        const val STEP_RUN = "run"
        const val STEP_FINISH = "finish"

        /** 台账 id 的分隔符单独抽出，避免拼串时手滑。 */
        const val ID_SEP = "_"

        /** 目标多取几条余量：列表里可能夹着自己的帖子或转发帖被过滤掉。 */
        private const val TARGET_SLACK = 3

        /** 判定时间预算时预留的余量：一次请求 + 记账开销。 */
        private const val REQUEST_MARGIN_MS = 30_000L

        /** 台账 id：同一账号同一天同一序号唯一。 */
        fun postIdOf(uid: String, day: String, seq: Int): String = "$uid$ID_SEP$day$ID_SEP$seq"
    }
}
