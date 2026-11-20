package com.wcm.checkin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

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
 * 一轮互动里所有出站网络调用的间接层。
 *
 * 存在的唯一理由是**可测性**：`WeiboAuthApi` / `WeiboTopicApi` / `WeiboFeedApi` /
 * `WeiboWriteApi` 都是 object，单测里没法注入假实现，硬测只能变成真实 HTTP 集成
 * 测试（此前 `EngageServiceTest` 因此只敢测「到达网络之前」的拒绝分支）。生产代码
 * 永远用默认实例——逐条转发给对应 object；`EngageServiceRunTest` 子类化它，把每个
 * 出口换成内存假响应，整轮 run 就能在 JVM 上跑通。
 *
 * 这里刻意**不带任何逻辑**：多一层逻辑就多一层要测的东西，违背它存在的初衷。
 * （public 而非 internal：EngageService 的构造器是 public，参数类型也必须可见。）
 */
open class EngageGateway {
    open fun verify(cookie: String, force: Boolean): WeiboAuthApi.Verify =
        WeiboAuthApi.verify(cookie, force)

    open suspend fun listTopics(
        session: WeiboHttp.WeiboSession,
        maxPages: Int = 20,
    ): List<WeiboTopicApi.Topic> = WeiboTopicApi.listTopics(session, maxPages)

    open suspend fun listTopicFeed(
        session: WeiboHttp.WeiboSession,
        topicId: String,
    ): List<WeiboFeedApi.FeedPost> = WeiboFeedApi.listTopicFeed(session, topicId)

    open fun pickTargets(
        posts: List<WeiboFeedApi.FeedPost>,
        selfUid: String,
        limit: Int,
        preferFirstComment: Boolean,
    ): List<WeiboFeedApi.FeedPost> =
        WeiboFeedApi.pickTargets(posts = posts, selfUid = selfUid, limit = limit, preferFirstComment = preferFirstComment)

    open fun publishTopicPost(
        session: WeiboHttp.WeiboSession,
        topicId: String,
        content: String,
        syncToTimeline: Boolean,
    ): WeiboWriteApi.WriteResult =
        WeiboWriteApi.publishTopicPost(session, topicId, content, syncToTimeline)

    open fun comment(session: WeiboHttp.WeiboSession, mid: String, text: String): WeiboWriteApi.WriteResult =
        WeiboWriteApi.comment(session, mid, text)

    open fun repost(session: WeiboHttp.WeiboSession, mid: String, text: String): WeiboWriteApi.WriteResult =
        WeiboWriteApi.repost(session, mid, text)

    open fun like(session: WeiboHttp.WeiboSession, mid: String): WeiboWriteApi.WriteResult =
        WeiboWriteApi.like(session, mid)
}

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
    /** 出站网络调用的可替换缝：生产用默认实例（直连 object），单测换成假响应。 */
    private val gateway: EngageGateway = EngageGateway(),
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

    /**
     * 一轮的编排器：准备（配置/账号/登录态/超话/计划/文案/目标）→ 执行 → 收尾。
     *
     * 原本 605 行全在这里（30+ 个可变局部状态互相纠缠），拆分后这个函数只保留
     * 「阶段顺序 + 共享对象的组装」，每个阶段的具体机械看对应函数；执行期的全部
     * 可变状态收进 [RunExecution]，进度发射收进 [RunContext]。
     */
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

        // 全过程只有一个可变进度对象：轮次结束后留在界面上供回看，下一轮从空白开始，
        // 不需要跨轮保留状态。发射器收进 RunContext（原来是一组闭包共享 var p）。
        val ctx = RunContext(startedAt, trigger, only, clock, ::report)

        // 准备阶段：任一步被拒即整轮中止（进度里已给出可读原因）。
        val access = prepareAccess(ctx, trigger, only) ?: return ctx.blockedOutcome()
        val plannedRun = preparePlan(ctx, access, background, budgetCapMs, only) ?: return ctx.blockedOutcome()
        val texts = prepareTextQueues(ctx, access.config, plannedRun.plan, plannedRun.day)
        val targets = prepareTargetSupply(ctx, access, plannedRun.plan)

        val ex = RunExecution(ctx, access, plannedRun, texts, targets, runId = "r$startedAt", runPort, gateway)
        try {
            ctx.stepStart(STEP_RUN, "执行动作")
            ex.runLoop()
        } finally {
            // 收尾必须在 finally 里：异常/取消时换发 cookie 与运行记录同样不能丢。
            finishRun(ex)
        }
        return EngageOutcome(
            run = ex.snapshot(clock()),
            notes = if (targets.feedError.isEmpty()) plannedRun.plan.notes else plannedRun.plan.notes + targets.feedError,
        )
    }

    /** 配置与账号、登录态、超话关注校验。任一步不过即整轮中止（返回 null）。 */
    private suspend fun prepareAccess(
        ctx: RunContext,
        trigger: EngageTrigger,
        only: Set<EngageAction>,
    ): AccessPrep? {
        // ---- 配置与账号 ----
        ctx.stepStart(STEP_PREPARE, "读取配置")
        ctx.log(
            EngageLogLevel.INFO,
            "开始执行（${trigger.label}）" +
                if (only.isEmpty()) "" else "，仅做 ${only.joinToString("、") { it.label }}",
        )
        val config = configPort.loadConfig()
        config.validate()?.let { return ctx.abort(it) }

        val account = accountSource.byUid(config.accountUid)
            ?: return ctx.abort("账号不存在或已删除")
        if (account.dead) return ctx.abort("账号登录态已失效")
        if (account.cookie.isBlank()) return ctx.abort("账号缺少 Cookie")
        ctx.stepEnd(STEP_PREPARE, EngageStepState.OK, config.accountUid)
        ctx.log(EngageLogLevel.INFO, "执行账号：${account.name.ifEmpty { account.uid }}")

        // ---- 登录态 ----
        ctx.stepStart(STEP_AUTH, "校验登录态")
        val verified = try {
            gateway.verify(account.cookie, force = true)
        } catch (e: Exception) {
            val detail = WeiboHttp.safeBodyExcerpt(e.message ?: "", 60)
            ctx.stepEnd(STEP_AUTH, EngageStepState.FAILED, "网络异常")
            return ctx.abort("登录态校验失败：$detail")
        }
        if (!verified.login) {
            ctx.stepEnd(STEP_AUTH, EngageStepState.FAILED, "登录态已失效")
            return ctx.abort("登录态已失效，请重新登录")
        }
        ctx.stepEnd(STEP_AUTH, EngageStepState.OK, "有效")

        val session = WeiboHttp.WeiboSession.of(account.cookie, account.cookieDomains)
        session.merge(verified.cookie)

        // ---- 超话关注校验（计分前置条件）----
        // 两边都归一成 containerid 再比：`Topic.topicId` 是去掉 100808 的 hash，
        // 配置里存的是完整 containerid，直接 == 永远匹配不上。
        ctx.stepStart(STEP_TOPIC, "校验目标超话")
        val container = EngageSchedule.topicContainerId(config.topicId)
        val topics = try {
            gateway.listTopics(session)
        } catch (e: Exception) {
            val detail = WeiboHttp.safeBodyExcerpt(e.message ?: "", 60)
            ctx.stepEnd(STEP_TOPIC, EngageStepState.FAILED, "读取超话列表失败")
            return ctx.abort("读取超话列表失败：$detail")
        }
        val topic = topics.firstOrNull { EngageSchedule.topicContainerId(it.topicId) == container }
            ?: return ctx.abort("未关注该超话，请先在微博内关注后再运行")
        val topicName = topic.name.ifEmpty { config.topicName }
        ctx.stepEnd(STEP_TOPIC, EngageStepState.OK, "#$topicName#")
        ctx.log(EngageLogLevel.INFO, "目标超话：#$topicName#（已关注 ${topics.size} 个）")

        return AccessPrep(config, account, session, container, topicName)
    }

    /** 计划与预算。plan.blocked 非空即整轮中止（返回 null）。 */
    private fun preparePlan(
        ctx: RunContext,
        access: AccessPrep,
        background: Boolean,
        budgetCapMs: Long?,
        only: Set<EngageAction>,
    ): PlannedRun? {
        ctx.stepStart(STEP_PLAN, "生成执行计划")
        val now = clock()
        val day = EngageSchedule.dayKey(now)
        val counter = runPort.loadCounter(access.account.uid, day)
        val plan = EngagePlanner.plan(access.config, counter, now, background, only)
        if (plan.blocked.isNotEmpty()) {
            ctx.stepEnd(STEP_PLAN, EngageStepState.WARN, plan.blocked)
            return ctx.abort(plan.blocked)
        }
        val total = plan.actions.size
        ctx.emit { it.progress(0, total) }
        ctx.stepEnd(STEP_PLAN, EngageStepState.OK, "$total 个动作")
        ctx.log(
            EngageLogLevel.INFO,
            "本轮计划 $total 个动作：" + EngageAction.entries
                .mapNotNull { a -> plan.countOf(a).takeIf { n -> n > 0 }?.let { "${a.label} $it" } }
                .joinToString(" · "),
        )
        plan.notes.forEach { ctx.log(EngageLogLevel.WARN, it) }

        // 预算取「配置值」与「调用方上限」的较小者：后台执行不能超过 Worker 的系统上限
        val budgetMs = minOf(access.config.maxRunDurationMs, budgetCapMs ?: Long.MAX_VALUE)
        val deadline = ctx.startedAt + budgetMs
        val estimate = EngagePacing.estimateRunMs(access.config, plan)
        ctx.emit { it.copy(etaMs = estimate) }
        ctx.log(
            EngageLogLevel.INFO,
            "按当前节奏预计 ${EngagePacing.formatDuration(estimate)}，" +
                "本轮预算 ${EngagePacing.formatDuration(budgetMs)}",
        )
        return PlannedRun(plan, total, budgetMs, deadline, now, day)
    }

    /**
     * 三套文案库各取各的（0.14.0 起拆库，防把 1200 字的发帖文案当评论发出去）。
     *
     * 文案不够**不拦整轮**：缺哪类记哪类的 WARN，执行到那条动作时自然按 NO_TEXT
     * 失败并放弃剩余（那个分支不进熔断计数，见 EngageGuard）。
     */
    private fun prepareTextQueues(
        ctx: RunContext,
        config: EngageConfig,
        plan: EngagePlanner.Plan,
        day: String,
    ): TextQueues {
        ctx.stepStart(STEP_TEXT, "准备文案")
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
        ctx.stepEnd(STEP_TEXT, if (textShort) EngageStepState.WARN else EngageStepState.OK, textDetail)
        ctx.log(
            if (textShort) EngageLogLevel.WARN else EngageLogLevel.INFO,
            "本轮文案：$textDetail" +
                "（发帖库 ${EngageTextPool.availableCount(postPool)} 条 / " +
                "评论库 ${EngageTextPool.availableCount(commentPool)} 条 / " +
                (if (repostNeedsText) "转发库 ${EngageTextPool.availableCount(repostPool)} 条）" else "转发不带文案）"),
        )
        return TextQueues(ArrayDeque(postPicked), ArrayDeque(commentPicked), ArrayDeque(repostPicked))
    }

    /**
     * 拉取互动目标流（评论/转发/点赞的目标）。
     *
     * 目标流拿不到**不拦整轮**：评论/转发/点赞会逐个按 TARGET_GONE 失败并放弃剩余，
     * feedError 会进进度日志和 notes——不再静默吞掉（此前用户只看到一句「0/0」）。
     */
    private suspend fun prepareTargetSupply(
        ctx: RunContext,
        access: AccessPrep,
        plan: EngagePlanner.Plan,
    ): TargetSupply {
        val demand = EngagePlanner.targetDemand(plan)
        if (demand == 0) {
            ctx.log(EngageLogLevel.INFO, "本轮没有互动动作，跳过帖子流拉取")
            return TargetSupply(ArrayDeque(), "")
        }
        ctx.stepStart(STEP_FEED, "拉取超话帖子流")
        var feedError = ""
        var feedCandidates = 0
        val picked: List<WeiboFeedApi.FeedPost> = try {
            // 三类动作各弹一个目标，需求量是**次数之和**而不是最大值：
            // 用 max 时默认配置（4 评论 + 2 转发 + 3 点赞）只取 6 个目标，
            // 后面的点赞必然全部落空。
            val posts = gateway.listTopicFeed(access.session, access.container)
            feedCandidates = posts.size
            gateway.pickTargets(
                posts = posts,
                selfUid = access.account.uid,
                limit = demand + TARGET_SLACK,
                preferFirstComment = access.config.preferFirstComment,
            )
        } catch (e: Exception) {
            feedError = "超话帖子流不可用：${WeiboHttp.safeBodyExcerpt(e.message ?: "", 60)}"
            emptyList()
        }
        if (picked.isEmpty() && feedError.isEmpty()) {
            feedError = "超话帖子流没有可用目标（可能被风控或接口改版）"
        }
        ctx.stepEnd(
            STEP_FEED,
            if (picked.isEmpty()) EngageStepState.WARN else EngageStepState.OK,
            "${picked.size} 条可用目标",
        )
        ctx.log(
            if (picked.isEmpty()) EngageLogLevel.WARN else EngageLogLevel.INFO,
            "候选帖 $feedCandidates 条，过滤自己与转发帖后可用 ${picked.size} 条（需要 $demand 条）",
        )
        if (feedError.isNotEmpty()) ctx.log(EngageLogLevel.ERROR, feedError)
        return TargetSupply(ArrayDeque(picked), feedError)
    }

    /** 收尾：补记 SKIPPED → 汇总 → 落运行记录 → 换发回写。异常/取消同样走这里。 */
    private fun finishRun(ex: RunExecution) {
        val ctx = ex.ctx
        val finishedAt = clock()

        // 被提前终止时把没走到的计划补齐成 SKIPPED：
        // 摘要里要能看出「发帖 0/1 是被预算砍掉的」，而不是只剩一个 0/0。
        if (ex.breakFrom in 0 until ex.planned.plan.actions.size && ex.stopReason.isNotEmpty()) {
            for (i in ex.breakFrom until ex.planned.plan.actions.size) {
                ex.record(skippedRecord(ex.planned.plan.actions[i], ex.stopReason, clock()), i + 1)
            }
        }

        val ok = ex.records.count { it.state == ActionState.OK }
        val failed = ex.records.count { it.state == ActionState.FAILED }
        val uncertain = ex.records.count { it.state == ActionState.UNCERTAIN }
        ctx.stepEnd(
            STEP_RUN,
            if (failed > 0 || uncertain > 0) EngageStepState.WARN else EngageStepState.OK,
            "$ok/${ex.planned.total} 成功",
        )

        ctx.stepStart(STEP_FINISH, "保存结果")
        markTextsUsed(ex.usedTextIds, finishedAt, ex.planned.day)
        runPort.saveRun(ex.snapshot(finishedAt))
        runCatching { runPort.prune(ex.access.account.uid, ex.planned.day) }
        // 换发回写：整轮结束时把会话里吸收到的 Set-Cookie 落回账号
        runCatching {
            accountSink.updateCookie(ex.access.account.uid, ex.access.session.cookie, ex.access.session.domainHints)
        }
        ctx.stepEnd(STEP_FINISH, EngageStepState.OK, "已保存")

        ctx.log(
            if (failed > 0 || uncertain > 0) EngageLogLevel.WARN else EngageLogLevel.OK,
            buildString {
                append("本轮结束：成功 ").append(ok)
                if (failed > 0) append(" · 失败 ").append(failed)
                if (uncertain > 0) append(" · 结果未知 ").append(uncertain)
                append(" · 总用时 ").append(EngagePacing.formatDuration(finishedAt - ctx.startedAt))
            },
        )
        ctx.emit {
            it.closeOpenSteps(EngageStepState.SKIPPED, finishedAt)
                .copy(stage = if (ex.guard.isStopped) "已结束（${ex.guard.currentStopReason}）" else "已完成")
                .finish(finishedAt)
        }
        Log.i(TAG, "本轮结束 records=${ex.records.size} stop=${ex.guard.currentStopReason}")
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
            gateway.verify(account.cookie, force = true)
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

            val result = gateway.publishTopicPost(
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
    companion object {
        /** logcat 过滤：`adb logcat -s WcmEngage`。EngageRunExecution.kt 里的执行机械也用它。 */
        internal const val TAG = "WcmEngage"

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
