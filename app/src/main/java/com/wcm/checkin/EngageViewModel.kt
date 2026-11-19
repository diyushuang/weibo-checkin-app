package com.wcm.checkin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/**
 * 任务页的状态编排。
 *
 * 刻意**不**持有长任务：真正的执行在 [EngageService]（`:domain`，实例级 Mutex，
 * 内部已切到 IO），ViewModel 只负责「显示 + 触发 + 刷新」。
 */
@HiltViewModel
class EngageViewModel @Inject constructor(
    private val configPort: EngageConfigPort,
    private val runPort: EngageRunPort,
    private val service: EngageService,
    private val accountPort: AccountPort,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(EngageUiState())
    val state: StateFlow<EngageUiState> = _state.asStateFlow()

    /** 预览用的文案下标，点「换一条看看」循环前进。 */
    private var previewIndex = 0

    /** 本次进程是否已经触发过自动补跑（避免每次刷新都重排一次）。 */
    private var autoTriggered = false

    /** 台账页是否处于「最近 14 天」视图；刷新时保持同一口径，避免列表突然缩水。 */
    private var postsAllDays = false

    /** 上一次已同步到界面的进度条数；只有变化时才重读运行记录。 */
    private var lastProgressDone = -1

    init {
        // 实时进度直接挂到服务的回调上：服务是 @Singleton、跑在 IO，
        // StateFlow 的 update 线程安全，这里不再切线程。
        service.onProgress = { p ->
            _state.update { it.copy(progress = p) }
            // 每完成一个动作，服务都会落一份草稿运行记录；界面这边只在 refresh()
            // 里读一次，所以不主动重读的话「运行记录」要到整轮结束才更新
            // （真机实测：进度条在动，记录卡片还停在上一轮）。
            if (p.done != lastProgressDone) {
                lastProgressDone = p.done
                viewModelScope.launch { reloadRuns() }
            }
        }
        refresh()
    }

    /** 只重读运行记录，不动其它状态（执行过程中被进度回调高频调用）。 */
    private suspend fun reloadRuns() {
        val uid = _state.value.config.accountUid
        if (uid.isEmpty()) return
        val runs = io { runPort.loadRuns(uid, 10) }
        _state.update { it.copy(runs = runs) }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    /** 一次读齐三套文案库。三个 key 一定建全，界面取值不必判空。 */
    private suspend fun loadTextLibraries(): Map<TextKind, List<EngageText>> = io {
        TextKind.entries.associateWith { configPort.loadTexts(it) }
    }

    override fun onCleared() {
        // 服务是单例，旧 ViewModel 被回收后不能再往里写状态
        service.onProgress = null
        super.onCleared()
    }

    // ---------- 读取 ----------

    fun refresh() {
        viewModelScope.launch {
            val cfg = io { configPort.loadConfig() }
            val accounts = io {
                accountPort.load(appContext).map { EngageAccountOption(it.uid, it.name.ifEmpty { it.uid }) }
            }
            val texts = loadTextLibraries()
            val today = EngageSchedule.dayKey(System.currentTimeMillis())
            val counter = if (cfg.accountUid.isNotEmpty()) {
                io { runPort.loadCounter(cfg.accountUid, today) }
            } else {
                EngageDayCounter(day = today)
            }
            val posts = loadPosts(cfg.accountUid)
            val runs = if (cfg.accountUid.isNotEmpty()) {
                io { runPort.loadRuns(cfg.accountUid, 10) }
            } else {
                emptyList()
            }
            val preview = io { buildPreview(cfg, texts) }

            _state.update {
                it.copy(
                    loading = false,
                    config = cfg,
                    accounts = accounts,
                    texts = texts,
                    today = today,
                    counter = counter,
                    posts = posts,
                    runs = runs,
                    hints = EngagePlanner.quotaHints(cfg),
                    preview = preview.first,
                    previewLength = preview.second,
                    previewError = preview.third,
                )
            }
            autoRunIfDue(cfg, runs.firstOrNull())
        }
    }

    /** 台账视图口径：主页面只看当天，台账页看最近 14 天。 */
    private suspend fun loadPosts(uid: String): List<EngagePostRecord> {
        if (uid.isEmpty()) return emptyList()
        return io {
            val today = LocalDate.now()
            if (postsAllDays) {
                (0 until POST_HISTORY_DAYS).flatMap { d ->
                    runPort.loadPosts(uid, today.minusDays(d.toLong()).format(DAY_FMT))
                }.sortedByDescending { it.at }
            } else {
                runPort.loadPosts(uid, EngageSchedule.dayKey(System.currentTimeMillis()))
                    .sortedByDescending { it.at }
            }
        }
    }

    /**
     * 打开任务页时的懒加载。
     *
     * 超话列表要翻页拉取，**不能**放在初始化里：这个 ViewModel 挂在 Activity 作用域，
     * 每次冷启动都会被创建，用户不进任务页也会白发十几页请求。
     */
    fun onEnter() {
        // 必须重新读一次账号：本 ViewModel 挂在 Activity 作用域，冷启动时用户可能还没登录，
        // 之后再登录就不会触发 refresh 了 —— 表现是任务页一直显示「还没有已登录的账号」，
        // 直到杀掉进程重开（真机实测踩到）。
        refresh()
        if (_state.value.topics.isEmpty() && !_state.value.topicsLoading) refreshTopics()
    }

    /**
     * 打开 App 时的自动补跑。
     *
     * **默认不跑**：必须用户显式打开「打开 App 时自动执行」开关（[EngageConfig.autoRunOnOpen]），
     * 否则只响应任务页的「立即执行一次」。默认让程序自己去发公开内容，风险太大。
     *
     * 判定用「今天有没有一次有成功动作的运行」而不是「今天跑没跑过」——上午失败一次之后，
     * 下午打开 App 还应该再试。只触发一次（[autoTriggered]），避免每次刷新都重排。
     */
    private fun autoRunIfDue(cfg: EngageConfig, lastRun: EngageRun?) {
        if (autoTriggered) return
        if (!cfg.autoRunOnOpen) return
        if (!cfg.enabled || cfg.validate() != null) return
        val now = System.currentTimeMillis()
        if (EngageSchedule.isQuietNow(now, cfg)) return
        val ranOkToday = lastRun != null &&
            EngageSchedule.ranToday(lastRun.startedAt, now) && lastRun.okCount > 0
        if (ranOkToday) return
        autoTriggered = true
        EngageScheduler.runOnce(appContext, cfg)
    }

    fun refreshTopics() {
        val cfg = _state.value.config
        if (cfg.accountUid.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(topicsLoading = true) }
            val topics = io {
                val acc = accountPort.load(appContext).firstOrNull { it.uid == cfg.accountUid }
                if (acc == null || acc.cookie.isEmpty()) {
                    emptyList()
                } else {
                    runCatching {
                        WeiboTopicApi.listTopics(acc.cookie).map {
                            // 必须存完整 containerid：写接口的 topic_id 与帖子流的 flowId
                            // 都要 `100808…`，而 extractTopicId 返回的是去掉前缀的 hash。
                            EngageTopicOption(
                                id = EngageSchedule.topicContainerId(it.topicId),
                                name = it.name,
                                level = it.level,
                            )
                        }
                    }.getOrDefault(emptyList())
                }
            }
            _state.update { it.copy(topicsLoading = false, topics = topics) }
        }
    }

    // ---------- 配置变更 ----------

    /** 统一的配置变更入口：落盘 + 重算提示 + 重算预览 + 重排定时。 */
    fun update(transform: (EngageConfig) -> EngageConfig) {
        val cfg = transform(_state.value.config)
        persist(cfg)
    }

    private fun persist(cfg: EngageConfig) {
        configPort.saveConfig(cfg)
        val preview = buildPreview(cfg, _state.value.texts)
        _state.update {
            it.copy(
                config = cfg,
                hints = EngagePlanner.quotaHints(cfg),
                preview = preview.first,
                previewLength = preview.second,
                previewError = preview.third,
            )
        }
        // 关闭任务时清掉可能还排着的执行；开启时不排期——执行由「打开 App 时补跑」触发
        if (!cfg.enabled) EngageScheduler.cancel(appContext)
    }

    /** 点开关：没同意过风险声明就先弹授权。 */
    fun requestEnable() {
        val cfg = _state.value.config
        if (cfg.enabled) {
            persist(cfg.copy(enabled = false))
            return
        }
        if (cfg.consentAt <= 0L || cfg.consentVersion != EngageConfig.CONSENT_VERSION) {
            _state.update { it.copy(showConsent = true) }
            return
        }
        persist(cfg.copy(enabled = true))
    }

    fun acceptConsent() {
        val cfg = _state.value.config
        persist(
            cfg.copy(
                enabled = true,
                consentAt = System.currentTimeMillis(),
                consentVersion = EngageConfig.CONSENT_VERSION,
                accountUid = cfg.accountUid.ifEmpty { _state.value.accounts.firstOrNull()?.uid.orEmpty() },
            )
        )
        _state.update { it.copy(showConsent = false) }
        refresh()
    }

    fun dismissConsent() {
        _state.update { it.copy(showConsent = false) }
    }

    fun selectAccount(uid: String) {
        persist(_state.value.config.copy(accountUid = uid, topicId = "", topicName = ""))
        _state.update { it.copy(topics = emptyList()) }
        refresh()
    }

    fun selectTopic(option: EngageTopicOption) {
        persist(_state.value.config.copy(topicId = option.id, topicName = option.name))
    }

    fun setCount(action: EngageAction, value: Int) {
        val max = _state.value.config.maxCountOf(action)
        val n = value.coerceIn(0, max)
        update {
            when (action) {
                EngageAction.POST -> it.copy(postCount = n)
                EngageAction.REPOST -> it.copy(repostCount = n)
                EngageAction.COMMENT -> it.copy(commentCount = n)
                EngageAction.LIKE -> it.copy(likeCount = n)
            }
        }
    }

    // ---------- 动作模式与节奏 ----------

    /**
     * 切换单个动作的三态（关闭 / 仅手动 / 自动）。
     *
     * 从「关闭」切到「手动」时如果次数是 0，顺手补成 1：否则用户点了开关却看不到
     * 任何效果（次数还是 0，等于没开），很容易以为按钮坏了。
     */
    fun setMode(action: EngageAction, mode: EngageActionMode) {
        val cfg = _state.value.config
        var next = cfg.withMode(action, mode)
        if (mode != EngageActionMode.OFF && next.countOf(action) <= 0) {
            next = when (action) {
                EngageAction.POST -> next.copy(postCount = 1)
                EngageAction.REPOST -> next.copy(repostCount = 1)
                EngageAction.COMMENT -> next.copy(commentCount = 1)
                EngageAction.LIKE -> next.copy(likeCount = 1)
            }
        }
        persist(next)
    }

    /** 套用节奏预设（稳健 / 均衡 / 快速）。 */
    fun applyPace(pace: EngagePace) {
        persist(EngagePacing.apply(_state.value.config, pace))
    }

    /** 微调某一项间隔；[action] 为 null 表示动作之间的通用间隔。 */
    fun adjustGap(action: EngageAction?, deltaMs: Long) {
        val cfg = _state.value.config
        val range = EngagePacing.gapOf(cfg, action)
        persist(EngagePacing.withGap(cfg, action, EngagePacing.shift(range, deltaMs)))
    }

    // ---------- 预览 ----------

    fun nextPreview() {
        previewIndex++
        val preview = buildPreview(_state.value.config, _state.value.texts)
        _state.update {
            it.copy(preview = preview.first, previewLength = preview.second, previewError = preview.third)
        }
    }

    /**
     * 组装发帖预览。
     *
     * 与真正发帖走**同一个** `ordered()` / `render()` / `compose()`，取用方式与随机种子
     * 也一致，所以预览看到的就是「下一轮会发的那条」。
     *
     * 0.14.0 起预览只负责「展示效果」：**不再因为今日额度用完、免打扰或配置非法而把正文藏起来**。
     * 那些状态由执行侧处理，预览里隐去反而让用户以为文案库坏了。
     * 只有「发帖文案库为空」才给提示——那时确实没有东西可预览。
     */
    private fun buildPreview(config: EngageConfig, texts: Map<TextKind, List<EngageText>>): Triple<String, Int, String> {
        val pool = texts[TextKind.POST].orEmpty()
        val empty = "发帖文案库为空，先添加文案才能预览"
        if (EngageTextPool.availableCount(pool) == 0) return Triple("", 0, empty)

        val now = System.currentTimeMillis()
        val day = EngageSchedule.dayKey(now)
        val ordered = EngageTextPool.ordered(
            pool = pool,
            forbiddenDays = EngageSchedule.forbiddenTextDays(day, config.strictTextGapDays),
            mode = config.postTextMode,
            random = EngageTextPool.randomFor(day, TextKind.POST),
            offset = previewIndex,
        )
        val text = ordered.firstOrNull() ?: return Triple("", 0, empty)

        // 序号取自当日真实计数，`{seq}` 占位符才不会与实际发出的一致性问题
        val seq = runPort.loadCounter(config.accountUid, day).doneOf(EngageAction.POST) + 1
        val rendered = EngageTextPool.render(
            text.body,
            EngageTextPool.RenderContext(
                topicName = config.topicName,
                date = EngageSchedule.dateLabel(now),
                dateCn = EngageSchedule.dateCnLabel(now),
                seq = seq,
                time = EngageSchedule.clockLabel(now),
            ),
        )
        val composed = EngageTextPool.compose(config.topicName, rendered)
        return Triple(composed, composed.length, EngageTextPool.validate(composed, config.maxTextLen).orEmpty())
    }

    // ---------- 文案（三套库） ----------

    /** 该库的单条上限：发帖按配置，评论与转发固定 140（平台硬限）。 */
    fun maxLenOf(kind: TextKind): Int =
        if (kind == TextKind.POST) _state.value.config.maxTextLen else EngageTextPool.COMMENT_LIMIT

    fun importTexts(
        kind: TextKind,
        raw: String,
        mode: EngageTextPool.ImportMode,
        separator: String,
    ): String {
        val parsed = EngageTextPool.splitBulk(raw, mode, separator, maxLenOf(kind))
        val existing = _state.value.textsOf(kind).map { it.digest }.toSet()
        val now = System.currentTimeMillis()
        val fresh = parsed.accepted
            .map {
                EngageText(
                    id = textId(now, kind, EngageTextPool.digest(it)),
                    body = it,
                    kind = kind,
                    createdAt = now,
                    source = TextSource.IMPORT,
                )
            }
            .filter { it.digest !in existing }
            .distinctBy { it.digest }

        if (fresh.isNotEmpty()) {
            viewModelScope.launch {
                val merged = io { configPort.appendTexts(kind, fresh) }
                applyTexts(kind, merged)
            }
        }
        val skipped = parsed.accepted.size - fresh.size
        return if (skipped > 0) "${parsed.summary}（其中 $skipped 条与现有文案重复）" else parsed.summary
    }

    fun addText(kind: TextKind, body: String) {
        val normalized = EngageTextPool.normalize(body)
        val bad = EngageTextPool.validate(normalized, maxLenOf(kind))
        if (bad != null) {
            _state.update { it.copy(message = bad) }
            return
        }
        val digest = EngageTextPool.digest(normalized)
        if (_state.value.textsOf(kind).any { it.digest == digest && !it.deleted }) {
            _state.update { it.copy(message = "已有相同文案") }
            return
        }
        val now = System.currentTimeMillis()
        val item = EngageText(
            id = textId(now, kind, digest),
            body = normalized,
            kind = kind,
            createdAt = now,
            source = TextSource.MANUAL,
        )
        viewModelScope.launch {
            val merged = io { configPort.appendTexts(kind, listOf(item)) }
            applyTexts(kind, merged)
            _state.update { it.copy(message = "已添加") }
        }
    }

    /**
     * 从发帖库挑出「≤140 字」的文案补进评论 / 转发库。
     *
     * 老池拆分时就按这个规则筛过，但用户可能一直在发帖库里加短文案；
     * 没有这个入口，评论库空着只能一条条手工重录。
     */
    fun importFromPostLibrary(kind: TextKind): String {
        if (kind == TextKind.POST) return "发帖库不需要补齐"
        val source = _state.value.textsOf(TextKind.POST)
            .filter { !it.deleted && it.body.length <= EngageTextPool.COMMENT_LIMIT }
        if (source.isEmpty()) return "发帖库里没有 ≤${EngageTextPool.COMMENT_LIMIT} 字的文案"

        val existing = _state.value.textsOf(kind).map { it.digest }.toSet()
        val now = System.currentTimeMillis()
        val fresh = source
            .filter { it.digest !in existing }
            .distinctBy { it.digest }
            .map {
                EngageText(
                    id = textId(now, kind, it.digest),
                    body = it.body,
                    kind = kind,
                    createdAt = now,
                    source = TextSource.IMPORT,
                )
            }
        if (fresh.isEmpty()) return "没有可补的文案（这些文案已在${kind.label}库里）"

        viewModelScope.launch {
            val merged = io { configPort.appendTexts(kind, fresh) }
            applyTexts(kind, merged)
        }
        return "已补入 ${fresh.size} 条"
    }

    /** 把某一套库的最新内容写回状态，并重算预览（预览只依赖发帖库）。 */
    private fun applyTexts(kind: TextKind, list: List<EngageText>) {
        val texts = _state.value.texts + (kind to list)
        val preview = buildPreview(_state.value.config, texts)
        _state.update {
            it.copy(
                texts = texts,
                preview = preview.first,
                previewLength = preview.second,
                previewError = preview.third,
            )
        }
    }

    fun deleteText(kind: TextKind, id: String) {
        viewModelScope.launch {
            // 走 mutateTexts：软删发生在锁内，不会与执行侧写使用统计互相覆盖
            val updated = io {
                configPort.mutateTexts(kind) { pool ->
                    pool.map { if (it.id == id) it.copy(deleted = true) else it }
                }
            }
            applyTexts(kind, updated)
        }
    }

    /** 切换某套库的取用方式（顺序 / 随机）。 */
    fun setPickMode(kind: TextKind, mode: TextPickMode) {
        persist(_state.value.config.withTextMode(kind, mode))
    }

    /** 关闭后转发走空正文（平台允许）。 */
    fun setRepostTextEnabled(enabled: Boolean) {
        persist(_state.value.config.copy(repostTextEnabled = enabled))
    }

    /** id 用 digest 派生：同毫秒批量导入时 `hashCode` 会撞，digest 不会；带库名避免跨库撞。 */
    private fun textId(now: Long, kind: TextKind, digest: String): String =
        "t${now}_${kind.key}_$digest"

    // ---------- 执行 ----------

    /** 手动跑一轮（四类动作按各自设置全部执行）。 */
    fun runNow() = launchRun(emptySet())

    /**
     * 只执行某一个动作。
     *
     * 这是「手动执行」的另一半：模式设为「仅手动」的动作只能从这里触发。
     * 只跑指定的动作，不动其它动作的额度——用户点一次发帖，不该顺带把今天的
     * 评论次数也扣掉。
     */
    fun runOnly(action: EngageAction) = launchRun(setOf(action))

    private fun launchRun(only: Set<EngageAction>) {
        if (_state.value.running) return
        val label = if (only.isEmpty()) "" else "（仅${only.joinToString("、") { it.label }}）"
        viewModelScope.launch {
            _state.update {
                it.copy(
                    running = true,
                    message = "",
                    progress = EngageProgress(
                        stage = "准备执行$label",
                        startedAt = System.currentTimeMillis(),
                        trigger = if (only.isEmpty()) EngageTrigger.MANUAL_ALL else EngageTrigger.MANUAL_ONLY,
                    ),
                )
            }
            // service.run 内部已切到 IO，这里不必再包一层
            val outcome = runCatching { service.run(background = false, only = only) }
                .getOrElse { EngageOutcome(blockedReason = "执行异常：${it.message.orEmpty().take(80)}") }
            val finishedRun = outcome.run
            val msg = when {
                outcome.blockedReason.isNotEmpty() -> outcome.blockedReason
                finishedRun != null -> buildString {
                    append(if (only.isEmpty()) "本轮完成：" else "${only.joinToString("、") { it.label }}完成：")
                    append(finishedRun.summary())
                    // 目标流不可用这类环境问题不走 blockedReason，只放在 notes 里；
                    // 不拼进来用户就只看到「评论 0/1」，看不出是没目标还是被风控。
                    outcome.notes.firstOrNull()?.let { append("（").append(it).append("）") }
                }
                else -> "未执行"
            }
            _state.update { it.copy(running = false) }
            refresh()
            _state.update { it.copy(message = msg) }
        }
    }

    /** 拉最近 14 天的发帖台账（台账页用；主页只显示当天）。 */
    fun loadPostHistory() {
        val uid = _state.value.config.accountUid
        if (uid.isEmpty()) return
        postsAllDays = true
        viewModelScope.launch {
            val list = loadPosts(uid)
            _state.update { it.copy(posts = list) }
        }
    }

    /**
     * 台账的人工收敛。
     *
     * `sent = true` 必须走 `commitSlot`（计数 +1 并更新 lastPostAt）：
     * 只清预占会让 planner 以为今天还没发过，下一轮再发一条 —— 正是「确认已发出
     * 却又发一次」的来源。`sent = false` 才只释放槽位。
     */
    fun settlePost(post: EngagePostRecord, sent: Boolean) {
        viewModelScope.launch {
            val day = EngageSchedule.dayKey(post.at)
            val updated = io {
                val rec = runPort.settlePost(
                    uid = post.accountUid,
                    day = day,
                    postId = post.id,
                    state = if (sent) ActionState.OK else ActionState.FAILED,
                    manualConfirmed = sent,
                )
                if (sent) {
                    runPort.commitSlot(post.accountUid, day, EngageAction.POST, post.seq, System.currentTimeMillis())
                } else {
                    runPort.releaseSlot(post.accountUid, day, EngageAction.POST, post.seq)
                }
                rec
            }
            refresh()
            _state.update {
                it.copy(
                    message = when {
                        updated == null -> "台账里找不到这条记录"
                        sent -> "已标记为发出，今日发帖计数 +1"
                        else -> "已标记为未发出，今日可重试"
                    }
                )
            }
        }
    }

    /** 人工确认后真正发送一条 AWAITING_CONFIRM 的发帖。 */
    fun confirmPost(post: EngagePostRecord) {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, message = "") }
            val outcome = runCatching {
                service.publishConfirmed(post.accountUid, EngageSchedule.dayKey(post.at), post.id)
            }.getOrElse { PublishOutcome(blockedReason = "发送异常：${it.message.orEmpty().take(80)}") }
            _state.update { it.copy(running = false) }
            refresh()
            _state.update {
                it.copy(
                    message = when {
                        outcome.blockedReason.isNotEmpty() -> outcome.blockedReason
                        outcome.record?.state == ActionState.OK -> "已发送"
                        outcome.record != null -> "结果未知，请到微博确认后在台账里收敛"
                        else -> "未发送"
                    }
                )
            }
        }
    }

    // ---------- 危险操作 ----------

    fun clearAll() {
        viewModelScope.launch {
            val uid = _state.value.config.accountUid
            io {
                configPort.clearAll()
                if (uid.isNotEmpty()) runPort.clearAll(uid)
            }
            postsAllDays = false
            autoTriggered = false
            _state.update { EngageUiState(loading = false) }
            refresh()
            _state.update { it.copy(message = "已清除任务数据") }
        }
    }

    fun consumeMessage() {
        if (_state.value.message.isNotEmpty()) _state.update { it.copy(message = "") }
    }

    private companion object {
        const val POST_HISTORY_DAYS = 14
        val DAY_FMT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
