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
     * [background] 为 true 时遵守「后台是否允许发帖」开关；手动补跑不受限。
     * [budgetCapMs] 非空时把时间预算再压低到这个值（取两者更小）。
     * 后台由 `CoroutineWorker` 执行，系统 10 分钟就会掐断，所以那里传 9 分钟；
     * 手动执行只在 Activity 存活期间跑，可以用配置里更宽的预算。
     */
    suspend fun run(background: Boolean = false, budgetCapMs: Long? = null): EngageOutcome {
        if (!gate.tryLock()) return EngageOutcome(blockedReason = "已有任务在运行")
        isRunning = true
        try {
            // 必须切 IO：调用方可能是 viewModelScope（默认 Main.immediate）。
            return withContext(Dispatchers.IO) { runInternal(background, budgetCapMs) }
        } finally {
            isRunning = false
            gate.unlock()
        }
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

    private suspend fun runInternal(background: Boolean, budgetCapMs: Long? = null): EngageOutcome {
        val config = configPort.loadConfig()
        config.validate()?.let {
            Log.w(TAG, "配置不可执行：$it")
            return EngageOutcome(blockedReason = it)
        }

        val account = accountSource.byUid(config.accountUid)
            ?: return EngageOutcome(blockedReason = "账号不存在或已删除").also {
                Log.w(TAG, "账号不存在 uid=${config.accountUid}")
            }
        if (account.dead) return EngageOutcome(blockedReason = "账号登录态已失效")
        if (account.cookie.isBlank()) return EngageOutcome(blockedReason = "账号缺少 Cookie")

        // ---- 登录态 ----
        report(EngageProgress(stage = "校验登录态"))
        val verified = try {
            WeiboAuthApi.verify(account.cookie, force = true)
        } catch (e: Exception) {
            Log.w(TAG, "登录态校验异常：${e.message}")
            return EngageOutcome(blockedReason = "登录态校验失败：${WeiboHttp.safeBodyExcerpt(e.message ?: "", 60)}")
        }
        if (!verified.login) {
            Log.w(TAG, "登录态已失效 uid=${account.uid}")
            return EngageOutcome(blockedReason = "登录态已失效，请重新登录")
        }

        val session = WeiboHttp.WeiboSession.of(account.cookie, account.cookieDomains)
        session.merge(verified.cookie)

        // ---- 超话关注校验（计分前置条件）----
        // 两边都归一成 containerid 再比：`Topic.topicId` 是去掉 100808 的 hash，
        // 配置里存的是完整 containerid，直接 == 永远匹配不上。
        report(EngageProgress(stage = "读取已关注超话"))
        val container = EngageSchedule.topicContainerId(config.topicId)
        val topics = try {
            WeiboTopicApi.listTopics(session)
        } catch (e: Exception) {
            Log.w(TAG, "读取超话列表失败：${e.message}")
            return EngageOutcome(blockedReason = "读取超话列表失败：${WeiboHttp.safeBodyExcerpt(e.message ?: "", 60)}")
        }
        val topic = topics.firstOrNull { EngageSchedule.topicContainerId(it.topicId) == container }
            ?: return EngageOutcome(blockedReason = "未关注该超话，请先在微博内关注后再运行").also {
                Log.w(TAG, "未关注该超话 container=$container 已关注=${topics.size} 个")
            }
        val topicName = topic.name.ifEmpty { config.topicName }

        // ---- 计划 ----
        val now = clock()
        val day = EngageSchedule.dayKey(now)
        val counter = runPort.loadCounter(account.uid, day)
        val plan = EngagePlanner.plan(config, counter, now, background)
        if (plan.blocked.isNotEmpty()) {
            Log.i(TAG, "本轮不执行：${plan.blocked}")
            return EngageOutcome(blockedReason = plan.blocked, notes = plan.notes)
        }

        // ---- 文案池 ----
        val pool = configPort.loadTexts()
        val forbiddenDays = EngageSchedule.forbiddenTextDays(day, config.strictTextGapDays)
        val needTexts = plan.countOf(EngageAction.COMMENT) +
            plan.countOf(EngageAction.REPOST) +
            plan.countOf(EngageAction.POST)
        val picked = EngageTextPool.pick(pool, needTexts, forbiddenDays)
        val textQueue = ArrayDeque(picked)

        // ---- 互动目标 ----
        val targetDemand = EngagePlanner.targetDemand(plan)
        var feedError = ""
        val targets = if (targetDemand > 0) {
            report(EngageProgress(stage = "拉取超话帖子流"))
            try {
                // 三类动作各弹一个目标，需求量是**次数之和**而不是最大值：
                // 用 max 时默认配置（4 评论 + 2 转发 + 3 点赞）只取 6 个目标，
                // 后面的点赞必然全部落空。
                WeiboFeedApi.pickTargets(
                    posts = WeiboFeedApi.listTopicFeed(session, container),
                    selfUid = account.uid,
                    limit = targetDemand + TARGET_SLACK,
                    preferFirstComment = config.preferFirstComment,
                )
            } catch (e: Exception) {
                // 不再静默吞掉：目标流拿不到时，评论/转发/点赞都会逐个空转等待，
                // 8 分钟后只留下一句「0/0」，用户完全无从判断。
                feedError = "超话帖子流不可用：${WeiboHttp.safeBodyExcerpt(e.message ?: "", 60)}"
                Log.w(TAG, feedError)
                emptyList()
            }
        } else {
            emptyList()
        }
        if (targetDemand > 0 && targets.isEmpty() && feedError.isEmpty()) {
            feedError = "超话帖子流没有可用目标（可能被风控或接口改版）"
            Log.w(TAG, feedError)
        }
        val targetQueue = ArrayDeque(targets)

        // ---- 执行 ----
        val runId = "r$now"
        val startedAt = now
        // 预算取「配置值」与「调用方上限」的较小者：后台执行不能超过 Worker 的系统上限
        val budgetMs = minOf(config.maxRunDurationMs, budgetCapMs ?: Long.MAX_VALUE)
        val deadline = startedAt + budgetMs
        val records = ArrayList<EngageActionRecord>(plan.actions.size)
        val guard = EngageGuard(config.failStreakAbort)
        val usedTextIds = LinkedHashSet<String>()
        // 今日已存在的「待确认」发帖：这些槽位没进计数（还没发），但不能再建第二条，
        // 否则每次运行都会用同一个台账 id 覆盖上一条待确认内容。
        val awaitingIds = runPort.loadPosts(account.uid, day)
            .filter { it.state == ActionState.AWAITING_CONFIRM }
            .map { it.id }
            .toMutableSet()
        var currentAction: EngageAction? = null
        var skipAction: EngageAction? = null
        var skipReason = ""
        val total = plan.actions.size

        /** 一轮进行中的运行记录快照：`finishedAt = 0` 表示还没跑完。 */
        fun snapshot(finishedAt: Long): EngageRun = EngageRun(
            runId = runId,
            accountUid = account.uid,
            topicId = config.topicId,
            topicName = topicName,
            startedAt = startedAt,
            finishedAt = finishedAt,
            stopReason = guard.currentStopReason,
            records = records.toList(),
        )

        fun publish(done: Int, action: EngageAction?, message: String) {
            report(
                EngageProgress(
                    stage = "执行动作",
                    action = action,
                    done = done,
                    total = total,
                    message = WeiboHttp.safeBodyExcerpt(message, 40),
                )
            )
        }

        /**
         * 记一条动作结果：落内存 → 推进度 → **立刻写一份草稿运行记录**。
         *
         * 草稿这一步是排查能力的关键：原来只在 `finally` 写一次，跑的过程中
         * 「运行记录」永远是空的，界面上除了一行「正在执行…」什么都看不到。
         */
        fun record(rec: EngageActionRecord, done: Int) {
            records.add(rec)
            publish(done, rec.action, rec.message)
            Log.i(TAG, "[$done/$total] ${rec.action.label}#${rec.seq} ${rec.state} ${rec.errorKind} ${rec.message}")
            runCatching { runPort.saveRun(snapshot(0L)) }
        }

        var breakFrom = -1
        var stopReason = ""

        try {
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
                }

                // 动作间隔：同一动作用该动作的间隔区间，跨动作再加一段通用抖动。
                // **预算必须按「实际还要等多久」判**：同一个 key 的首次请求根本不等待，
                // 按配置的理论间隔（发帖 5–8 分钟）去扣，会把当天第一条帖子直接判死。
                val nominalWait = randomIn(config.gapRangeOf(planned.action)) +
                    if (switching) randomIn(config.actionGapMinMs..config.actionGapMaxMs) else 0L
                val realWait = WeiboThrottle.remaining(throttleKey(planned.action), nominalWait)
                if (clock() + realWait + REQUEST_MARGIN_MS > deadline) {
                    stopReason = "本轮已达时间预算（${budgetMs / 60_000} 分钟），剩余动作留待下一轮"
                    guard.trip(stopReason)
                    Log.w(TAG, "$stopReason（realWait=${realWait}ms）")
                    breakFrom = idx
                    break
                }
                if (realWait > 0) {
                    publish(idx, planned.action, "等待 ${realWait / 1000} 秒后执行")
                }
                WeiboThrottle.await(throttleKey(planned.action), nominalWait)

                val needsText = planned.action != EngageAction.LIKE
                val text = if (needsText) textQueue.removeFirstOrNull() else null

                // 文案缺失：发帖、评论、转发都做不了（先判，后面才能安全用 text!!）
                if (needsText && text == null) {
                    record(failedRecord(planned, EngageErrorKind.NO_TEXT, "文案池已无可用文案", clock()), idx + 1)
                    continue
                }

                // 正文：发帖要「渲染 + 组装超话标签/视频行」，评论/转发只渲染占位符。
                // 三者都不该把 `{topic}` 这类占位符原样发出去。
                val rendered = if (needsText) renderForAction(text!!.body, planned, topicName, now, config) else ""
                val body = if (planned.action == EngageAction.POST) {
                    EngageTextPool.compose(topicName, rendered, config.videoUrl)
                } else {
                    rendered
                }

                // 本地能判的先判，别浪费一次请求
                val localBad = when (planned.action) {
                    EngageAction.POST -> EngageTextPool.validate(body, config.maxTextLen)
                    EngageAction.COMMENT, EngageAction.REPOST ->
                        EngageTextPool.validateComment(body, config.maxCommentLen)
                    EngageAction.LIKE -> null
                }
                if (localBad != null) {
                    record(failedRecord(planned, EngageErrorKind.CONTENT_REJECTED, localBad, clock()), idx + 1)
                    continue
                }

                // 目标缺失：评论/转发/点赞做不了。**整个动作提前放弃**：
                // 目标流为空时，按原逻辑每条都要白等 25–180 秒，最后 8 分钟过去
                // 用户只看到「什么都没执行」。
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
                            message = "等待人工确认后发送",
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

                val result = execute(planned, session, config, target, body)

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
                val actionRecord = EngageActionRecord(
                    action = planned.action,
                    seq = planned.seq,
                    state = state,
                    errorKind = result.kind,
                    targetMid = target?.mid.orEmpty(),
                    remoteId = result.remoteId,
                    textId = text?.id.orEmpty(),
                    message = WeiboHttp.safeBodyExcerpt(result.message, 120),
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

                if (text != null && state == ActionState.OK) usedTextIds.add(text.id)

                when (guard.onResult(result.kind)) {
                    Disposition.CONTINUE -> Unit
                    // 只放弃这个动作的剩余部分，其它动作照跑（契约见 EngageGuard）
                    Disposition.ABORT_ACTION -> {
                        skipAction = planned.action
                        skipReason = "已放弃剩余${planned.action.label}：${result.kind}"
                    }
                    Disposition.ABORT_RUN, Disposition.REQUIRE_HUMAN, Disposition.RETRY_LATER -> {
                        stopReason = guard.currentStopReason.ifEmpty { result.kind.name }
                        breakFrom = idx + 1
                        break
                    }
                }
            }

            // 被提前终止时把没走到的计划补齐成 SKIPPED：
            // 摘要里要能看出「发帖 0/1 是被预算砍掉的」，而不是只剩一个 0/0。
            if (breakFrom in 0 until plan.actions.size && stopReason.isNotEmpty()) {
                Log.w(TAG, "提前结束：$stopReason，剩余 ${plan.actions.size - breakFrom} 个动作未执行")
                for (i in breakFrom until plan.actions.size) {
                    record(skippedRecord(plan.actions[i], stopReason, clock()), i + 1)
                }
            }
        } finally {
            // 收尾必须在 finally 里：异常/取消时换发 cookie 与运行记录同样不能丢。
            val finished = clock()
            markTextsUsed(usedTextIds, finished, day)
            runPort.saveRun(snapshot(finished))
            Log.i(TAG, "本轮结束 records=${records.size} stop=${guard.currentStopReason}")
            publish(total, null, "本轮结束")
            runCatching { runPort.prune(account.uid, day) }
            // 换发回写：整轮结束时把会话里吸收到的 Set-Cookie 落回账号
            runCatching { accountSink.updateCookie(account.uid, session.cookie, session.domainHints) }
        }

        return EngageOutcome(
            run = snapshot(clock()),
            blockedReason = "",
            notes = if (feedError.isEmpty()) plan.notes else plan.notes + feedError,
        )
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
                markTextsUsed(setOf(record.textId), clock(), day)
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
        config: EngageConfig,
    ): String = EngageTextPool.render(
        raw,
        EngageTextPool.RenderContext(
            topicName = topicName,
            date = EngageSchedule.dateLabel(now),
            dateCn = EngageSchedule.dateCnLabel(now),
            seq = planned.seq,
            time = EngageSchedule.clockLabel(now),
            videoUrl = config.videoUrl,
        ),
    )

    /** 一轮结束后统一写一次使用统计（原来每个动作都整池读改写一次，最多 500 条 × 10 次）。 */
    private fun markTextsUsed(ids: Set<String>, now: Long, day: String) {
        if (ids.isEmpty()) return
        val pool = configPort.loadTexts()
        val updated = pool.map {
            if (it.id in ids) {
                it.copy(usedCount = it.usedCount + 1, lastUsedAt = now, lastUsedDay = day)
            } else {
                it
            }
        }
        configPort.saveTexts(updated)
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
