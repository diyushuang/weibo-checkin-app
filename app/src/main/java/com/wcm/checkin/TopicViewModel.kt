package com.wcm.checkin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 超话页的一次性 UI 事件：Toast / 只读弹窗 / 剪贴板 / 外跳。 */
sealed interface TopicEvent {
    data class Toast(val text: String) : TopicEvent
    data class ShowText(val title: String, val body: String) : TopicEvent
    data class Copy(val text: String) : TopicEvent
    data class OpenUrl(val url: String) : TopicEvent
}

/**
 * 超话页状态与编排。
 *
 * 页面只做渲染；刷新/签到/详情/筛选全部在这里，
 * 页面对应的 route 因 `saveState/restoreState` 被移出组合时，展开态与筛选也不会丢。
 */
@HiltViewModel
class TopicViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(TopicUiState())
    val state: StateFlow<TopicUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TopicEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TopicEvent> = _events.asSharedFlow()

    /** "" = 全部账号；否则为某个账号的 uid。 */
    private var currentUid = ""
    private var filter = TopicFilter.ALL
    private var sort = TopicSort.PENDING_FIRST
    private var loading = false
    private var lastAutoRefreshAt = 0L
    private var multiAccountMode = false

    private val expanded = HashSet<String>()
    private val detailBusy = HashSet<String>()
    private val detailErrors = HashMap<String, String>()
    private val checkinBusy = java.util.Collections.synchronizedSet(HashSet<String>())

    private var rowsByKey: Map<String, TopicRow> = emptyMap()

    // SimpleDateFormat 非线程安全。原先这两个字段靠「只在 render() 的主线程用」这条
    // 隐性约定，noteCheckin() 在 IO 线程就得单独 new（见该方法注释）。
    // 改成 ThreadLocal 后，任何线程调用都安全，约定不再靠人记。
    private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.CHINA) }
    private val dateFmt =
        ThreadLocal.withInitial { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA) }

    data class TopicRow(
        val uid: String,
        val accountName: String,
        val topic: WeiboTopicApi.Topic,
    )

    companion object {
        private const val AUTO_REFRESH_AFTER_MS = 10 * 60_000L
    }

    private fun rowKey(uid: String, key: String): String = uid + "\u0000" + key

    /** 首页点账号行 / 进度环时调用；页面还没显示也没关系。 */
    fun selectAccount(uid: String) {
        currentUid = uid
        if (_state.value.rows.isNotEmpty() || _state.value.filters.isNotEmpty()) render()
    }

    /** 页面进入组合时调用：先渲染缓存，再判断是否要自动刷新。 */
    fun onShown() {
        TopicStore.ensureLoaded(ctx)
        render()
        maybeAutoRefresh()
    }

    private fun toast(text: String) {
        _events.tryEmit(TopicEvent.Toast(text))
    }

    // ========================= 刷新 =========================

    private fun maybeAutoRefresh() {
        if (loading) return
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) return
        val snaps = TopicStore.all(ctx)
        val now = System.currentTimeMillis()
        // 需要刷新的三种情况，按账号逐个判断（不能只看最新那份）：
        // 没有快照 / 快照跨天 / 快照超过 10 分钟。
        // 跨天是硬条件：签到状态每天重置，沿用昨天的「已签」就是把错误结论当事实。
        val need = accounts.any { acc ->
            val snap = snaps[acc.uid]
            snap == null || !TopicStore.isToday(snap.at, now) || now - snap.at >= AUTO_REFRESH_AFTER_MS
        }
        if (!need) return
        if (now - lastAutoRefreshAt < 5_000L) return
        refresh(manual = false)
    }

    fun refresh(manual: Boolean) {
        if (loading) {
            if (manual) toast(ctx.getString(R.string.busy))
            return
        }
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) {
            render()
            return
        }
        loading = true
        lastAutoRefreshAt = System.currentTimeMillis()
        render()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                accounts.forEachIndexed { index, acc ->
                    if (index > 0) delay(800)   // 多账号之间留间隔，避免被风控
                    loadAccount(acc)
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    loading = false
                    render()
                }
            }
        }
    }

    /** 拉一个账号的超话列表并落缓存；失败只写错误，不清掉上次的数据。 */
    private suspend fun loadAccount(acc: Account) {
        try {
            val verify = WeiboAuthApi.verify(acc.cookie)
            if (!verify.login) {
                TopicStore.saveError(ctx, acc.uid, ctx.getString(R.string.home_cookie_dead))
                return
            }
            rotateCookie(acc.uid, verify.cookie)
            val topics = WeiboTopicApi.listTopics(verify.cookie)
            TopicStore.save(ctx, acc.uid, topics)
        } catch (e: CancellationException) {
            // 取消不是失败：不拦住就会被写成「Job was cancelled」并落盘，
            // 重启后这条假错误还挂在快照上，用户以为账号出问题了。
            throw e
        } catch (e: Exception) {
            TopicStore.saveError(ctx, acc.uid, e.message ?: "超话列表失败")
        }
        withContext(Dispatchers.Main) { render() }
    }

    /** 顺带把轮换后的 cookie 写回账号表（否则下一轮会用到过期的 SUB）。 */
    private fun rotateCookie(uid: String, cookie: String) {
        if (cookie.isEmpty()) return
        // 必须走 patchAccount：只改 cookie 会漏掉 cookieDead = false 与各时间锚点，
        // 失效标记会一直挂着，CookieLife 的倒计时也会停在旧值上；而且整表 save()
        // 没有加 AccountStore.LOCK，会与首页/Worker 的 patchAccount 互相吞写
        AccountStore.markVerified(ctx, uid, cookie)
    }

    // ThreadLocal.get() 在 Kotlin 侧是平台类型（可空），但 withInitial 保证首次访问就初始化，
    // 运行期永不返回 null。集中在这里断一次，避免每个调用点各写一次 !! 或 ?: 兜底。
    private fun fmtTime(at: Long): String = timeFmt.get()!!.format(Date(at))

    private fun fmtDate(at: Long): String = dateFmt.get()!!.format(Date(at))

    private fun topicStateTime(at: Long): String = when {
        at <= 0L -> ""
        TopicStore.isToday(at) -> fmtTime(at)
        else -> fmtDate(at)
    }

    // ========================= 渲染 =========================

    private fun render() {
        val accounts = AccountStore.load(ctx)
        val snaps = TopicStore.all(ctx)

        // 账号被删后，选择器里可能还停在一个不存在的 uid 上
        if (currentUid.isNotEmpty() && accounts.none { it.uid == currentUid }) currentUid = ""
        multiAccountMode = currentUid.isEmpty() && accounts.size > 1

        val rows = buildRows(accounts, snaps)
        val shown = applySort(rows.filter { matchFilter(it.topic) })
        rowsByKey = shown.associateBy { rowKey(it.uid, it.topic.key) }

        // 概览永远统计「全部已加载的超话」，不受筛选影响 —— 筛选是看的方式，不是事实。
        // 但「今日已签 / 待签」是按天的结论：快照跨天时这里必须显示占位符，
        // 否则会把昨天的已签数当成今天的结果（待签还会变成 0）。
        // 只认当前账号的快照：删掉的账号留下的错误提示/时间会一直挂在副标题上，
        // 用户看到「登录已失效」却找不到是哪个账号（它已经不在列表里了）。
        val liveUids = accounts.mapTo(HashSet()) { it.uid }
        val liveSnaps = snaps.filterKeys { it in liveUids }
        val error = errorOf(liveSnaps)
        val at = updatedAt(liveSnaps)
        val atLabel = topicStateTime(at)
        val staleDay = anyStaleDay(accounts, snaps)

        val total = rows.size
        val done = rows.count { it.topic.done }
        val dash = ctx.getString(R.string.topic_metric_empty)

        val subtitle = when {
            loading -> ctx.getString(R.string.topic_subtitle_loading)
            error.isNotEmpty() -> ctx.getString(R.string.topic_subtitle_failed, error)
            staleDay -> ctx.getString(R.string.topic_subtitle_stale, atLabel)
            at > 0L -> ctx.getString(R.string.topic_subtitle_updated, atLabel)
            else -> ctx.getString(R.string.topic_subtitle_idle)
        }
        val status = when {
            accounts.isEmpty() -> ctx.getString(R.string.topic_empty_no_account)
            error.isNotEmpty() -> ctx.getString(R.string.topic_subtitle_failed, error)
            staleDay -> ctx.getString(R.string.topic_subtitle_stale, atLabel)
            at > 0L -> ctx.getString(R.string.topic_subtitle_updated, atLabel)
            else -> ctx.getString(R.string.topic_subtitle_idle)
        }

        // 空态 / 错误态：错误优先（否则会把"失败"伪装成"没有超话"）
        //
        // 判空必须按**筛选后**的 shown，不能按 rows：筛选命中 0 条时 rows 非空，
        // 于是既不显示空态、列表又显示空表 —— 整屏一片空白，而且
        // topic_empty_filter 那段文案永远走不到。
        val showError = shown.isEmpty() && error.isNotEmpty() && !loading
        val showEmpty = shown.isEmpty() && !showError && !loading
        val emptyText = when {
            accounts.isEmpty() -> ctx.getString(R.string.topic_empty_no_account)
            rows.isEmpty() -> ctx.getString(R.string.topic_empty_no_topic)
            else -> ctx.getString(R.string.topic_empty_filter)
        }

        val pills = if (accounts.size >= 2) {
            buildList {
                add(TopicPillUi("", ctx.getString(R.string.topic_account_all), currentUid.isEmpty()))
                accounts.forEach {
                    add(TopicPillUi(it.uid, it.name.ifEmpty { it.uid }, it.uid == currentUid))
                }
            }
        } else {
            emptyList()
        }
        val filters = listOf(
            TopicFilter.ALL to R.string.topic_filter_all,
            TopicFilter.PENDING to R.string.topic_filter_pending,
            TopicFilter.DONE to R.string.topic_filter_done,
            TopicFilter.LEVEL7 to R.string.topic_filter_level,
        ).map { (value, labelRes) ->
            TopicFilterUi(value, ctx.getString(labelRes), value == filter)
        }

        _state.value = TopicUiState(
            pills = pills,
            filters = filters,
            sort = sort,
            metricTotal = if (total > 0) total.toString() else dash,
            metricDone = if (staleDay || total == 0) dash else done.toString(),
            metricPending = if (staleDay || total == 0) dash else (total - done).toString(),
            progress = if (total > 0 && !staleDay) done.toFloat() / total else 0f,
            loading = loading,
            subtitle = subtitle,
            status = status,
            rows = shown.map { buildRowUi(it) },
            showError = showError,
            errorText = ctx.getString(R.string.topic_subtitle_failed, error),
            showEmpty = showEmpty,
            emptyText = emptyText,
            emptyActionVisible = accounts.isEmpty(),
        )
    }

    private fun buildRowUi(row: TopicRow): TopicRowUi {
        val topic = row.topic
        val stateKey = rowKey(row.uid, topic.key)
        val tail = when {
            topic.desc2.isNotEmpty() -> topic.desc2
            topic.topicId.isNotEmpty() -> ctx.getString(R.string.topic_meta_hint)
            else -> ctx.getString(R.string.topic_detail_none)
        }
        val isExpanded = expanded.contains(stateKey)
        var intro = ""
        var stats = ""
        var hint = ""
        var detailLoading = false
        if (isExpanded) {
            val detail = if (topic.topicId.isEmpty()) null else TopicStore.detail(ctx, topic.topicId)
            when {
                detailBusy.contains(stateKey) -> {
                    detailLoading = true
                    hint = ctx.getString(R.string.topic_detail_loading)
                }
                detail != null -> {
                    intro = detail.intro
                    stats = detail.stats.take(3).joinToString(" · ")
                    hint = buildString {
                        if (topic.topicId.isNotEmpty()) {
                            append(ctx.getString(R.string.topic_id_fmt, topic.topicId))
                            if (detail.fetchedAt > 0) append(" · ")
                        }
                        if (detail.fetchedAt > 0) {
                            append(
                                ctx.getString(
                                    R.string.topic_detail_fetched,
                                    fmtTime(detail.fetchedAt),
                                )
                            )
                        }
                        if (detail.portraitText.isNotEmpty()) {
                            append(" · ").append(detail.portraitText)
                        }
                    }
                }
                else -> {
                    hint = when {
                        detailErrors[stateKey] != null -> ctx.getString(R.string.topic_detail_failed)
                        topic.topicId.isEmpty() -> ctx.getString(R.string.topic_detail_none)
                        else -> ctx.getString(R.string.topic_detail_failed)
                    }
                }
            }
        }

        return TopicRowUi(
            uid = row.uid,
            key = topic.key,
            name = topic.name,
            levelLabel = topic.levelLabel,
            levelTier = WeiboTopicApi.topicLevelTier(topic.level),
            meta = if (multiAccountMode) "${row.accountName} · $tail" else tail,
            done = topic.done,
            expanded = isExpanded,
            intro = intro,
            stats = stats,
            detailHint = hint,
            detailLoading = detailLoading,
        )
    }

    private fun errorOf(snaps: Map<String, TopicStore.Snapshot>): String =
        if (currentUid.isEmpty()) {
            snaps.values.firstOrNull { it.error.isNotEmpty() }?.error ?: ""
        } else {
            snaps[currentUid]?.error ?: ""
        }

    private fun updatedAt(snaps: Map<String, TopicStore.Snapshot>): Long =
        if (currentUid.isEmpty()) {
            snaps.values.maxOfOrNull { it.at } ?: 0L
        } else {
            snaps[currentUid]?.at ?: 0L
        }

    private fun anyStaleDay(
        accounts: List<Account>,
        snaps: Map<String, TopicStore.Snapshot>,
    ): Boolean {
        val picked = if (currentUid.isNotEmpty()) listOf(currentUid) else accounts.map { it.uid }
        return picked.any { uid ->
            val at = snaps[uid]?.at ?: 0L
            at > 0L && !TopicStore.isToday(at)
        }
    }

    private fun buildRows(
        accounts: List<Account>,
        snaps: Map<String, TopicStore.Snapshot>,
    ): List<TopicRow> {
        val nameOf = accounts.associate { it.uid to (it.name.ifEmpty { it.uid }) }
        if (currentUid.isNotEmpty()) {
            val snap = snaps[currentUid] ?: return emptyList()
            val label = nameOf[currentUid] ?: currentUid
            return snap.topics.map { TopicRow(currentUid, label, it) }
        }
        // 按「账号 + 超话」去重，而不是只按超话去重：
        // 同一个超话被两个账号关注时，只留先遍历到的那条会**拿错账号的签到状态**
        // （A 已签、B 未签 → 列表只有一条「已签」，B 根本看不到该签的），
        // 行上的 uid 也决定了点签到用谁的 cookie。行的身份判定同样是 (uid, key)。
        val merged = LinkedHashMap<String, TopicRow>()
        accounts.forEach { acc ->
            val snap = snaps[acc.uid] ?: return@forEach
            snap.topics.forEach { topic ->
                merged.putIfAbsent(
                    acc.uid + "\u0000" + topic.key,
                    TopicRow(acc.uid, nameOf[acc.uid] ?: acc.uid, topic),
                )
            }
        }
        return merged.values.toList()
    }

    private fun matchFilter(topic: WeiboTopicApi.Topic): Boolean = when (filter) {
        TopicFilter.ALL -> true
        TopicFilter.PENDING -> !topic.done
        TopicFilter.DONE -> topic.done
        TopicFilter.LEVEL7 -> topic.level >= 7
    }

    private fun applySort(rows: List<TopicRow>): List<TopicRow> = when (sort) {
        TopicSort.PENDING_FIRST -> rows.sortedWith(
            compareBy<TopicRow> { it.topic.done }.thenByDescending { it.topic.level }
        )
        TopicSort.LEVEL_DESC -> rows.sortedByDescending { it.topic.level }
        TopicSort.NAME -> rows.sortedBy { it.topic.name }
    }

    // ========================= 筛选 / 账号 =========================

    fun setFilter(filter: TopicFilter) {
        this.filter = filter
        render()
    }

    fun setSort(sort: TopicSort) {
        this.sort = sort
        render()
    }

    // ========================= 展开详情 =========================

    fun toggleDetail(rowUi: TopicRowUi) {
        val row = rowsByKey[rowKey(rowUi.uid, rowUi.key)] ?: return
        val key = row.topic.key
        val stateKey = rowKey(row.uid, key)
        if (expanded.contains(stateKey)) {
            expanded.remove(stateKey)
            render()
            return
        }
        expanded.add(stateKey)
        render()
        val topicId = row.topic.topicId
        if (topicId.isEmpty()) return

        val cached = TopicStore.detail(ctx, topicId)
        if (cached != null && TopicStore.isFresh(cached.fetchedAt)) return
        loadDetail(row.uid, topicId, key)
    }

    /** 懒加载详情：只有用户点开时才发这一次请求（自带 1.2s 节流在 API 层）。 */
    private fun loadDetail(uid: String, topicId: String, key: String) {
        val stateKey = rowKey(uid, key)
        if (!detailBusy.add(stateKey)) return
        detailErrors.remove(stateKey)
        render()
        viewModelScope.launch(Dispatchers.IO) {
            var error = ""
            try {
                val acc = AccountStore.load(ctx).firstOrNull { it.uid == uid }
                if (acc == null) {
                    error = "账号已不存在"
                } else {
                    val verify = WeiboAuthApi.verify(acc.cookie)
                    if (!verify.login) {
                        error = ctx.getString(R.string.home_cookie_dead)
                    } else {
                        val detail = WeiboTopicApi.fetchTopicDetail(verify.cookie, topicId)
                        TopicStore.saveDetail(ctx, detail)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "详情加载失败"
            }
            withContext(Dispatchers.Main + NonCancellable) {
                detailBusy.remove(stateKey)
                if (error.isNotEmpty()) detailErrors[stateKey] = error
                render()
            }
        }
    }

    // ========================= 签到 / 行操作 =========================

    fun copySignLink(rowUi: TopicRowUi) {
        val row = rowsByKey[rowKey(rowUi.uid, rowUi.key)] ?: return
        val scheme = row.topic.scheme
        if (scheme.isNullOrEmpty()) {
            toast(ctx.getString(R.string.topic_copied_none))
            return
        }
        toast(ctx.getString(R.string.topic_copied))
        _events.tryEmit(TopicEvent.Copy(scheme))
    }

    fun openInWeibo(rowUi: TopicRowUi) {
        val row = rowsByKey[rowKey(rowUi.uid, rowUi.key)] ?: return
        val id = row.topic.topicId
        val web = if (id.isNotEmpty()) {
            "https://m.weibo.cn/p/100808$id/super_index"
        } else {
            row.topic.scheme ?: return
        }
        _events.tryEmit(TopicEvent.OpenUrl(web))
    }

    fun showRawJson(rowUi: TopicRowUi) {
        val row = rowsByKey[rowKey(rowUi.uid, rowUi.key)] ?: return
        val topic = row.topic
        val body = topic.raw.ifEmpty { ctx.getString(R.string.topic_json_empty) }
        _events.tryEmit(
            TopicEvent.ShowText(ctx.getString(R.string.topic_json_title, topic.name), body),
        )
    }

    fun showFieldHelp() {
        _events.tryEmit(
            TopicEvent.ShowText(
                ctx.getString(R.string.topic_field_title),
                ctx.getString(R.string.topic_field_body),
            ),
        )
    }

    /** 签到一个超话：缓存里的链接会过期，先按需重新取一次最新列表。 */
    fun checkin(rowUi: TopicRowUi) {
        val row = rowsByKey[rowKey(rowUi.uid, rowUi.key)] ?: return
        // 连点会起 N 个协程同时 verify + sign：多花风控额度，还会叠 N 个 Toast。
        // 详情拉取那边早就有 detailBusy，签到这条路径漏了。
        if (!checkinBusy.add(row.uid + "|" + row.topic.key)) {
            toast(ctx.getString(R.string.busy))
            return
        }
        val key = row.uid + "|" + row.topic.key
        viewModelScope.launch(Dispatchers.IO) {
            var message: String
            try {
                val acc = AccountStore.load(ctx).firstOrNull { it.uid == row.uid }
                if (acc == null) {
                    message = "账号已不存在"
                } else {
                    val verify = WeiboAuthApi.verify(acc.cookie)
                    if (!verify.login) {
                        message = ctx.getString(R.string.home_cookie_dead)
                    } else {
                        rotateCookie(acc.uid, verify.cookie)
                        var scheme = row.topic.scheme
                        if (scheme.isNullOrEmpty()) {
                            val fresh = WeiboTopicApi.listTopics(verify.cookie)
                            TopicStore.save(ctx, acc.uid, fresh)
                            scheme = fresh.firstOrNull { it.key == row.topic.key }?.scheme
                        }
                        if (scheme.isNullOrEmpty()) {
                            message = ctx.getString(R.string.topic_copied_none)
                        } else {
                            val result = WeiboTopicApi.signDetailed(verify.cookie, scheme, verify.st)
                            message = if (result.ok) {
                                if (result.summary.isNotEmpty()) {
                                    ctx.getString(R.string.topic_checkin_ok_fmt, result.summary)
                                } else {
                                    ctx.getString(R.string.topic_checkin_ok_plain)
                                }
                            } else {
                                ctx.getString(
                                    R.string.topic_checkin_fail_fmt,
                                    result.msg.ifEmpty { "接口未返回原因" },
                                )
                            }
                            if (result.ok) {
                                // 签到成功后把该条置为已签（不等整轮刷新，界面立刻一致）
                                markDone(acc.uid, row.topic.key)
                                noteCheckin(acc.uid)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = ctx.getString(R.string.topic_checkin_fail_fmt, e.message ?: "异常")
            } finally {
                // 解闸必须无条件执行：checkinBusy 是 ViewModel 级字段，
                // 取消（重抛 CancellationException）走不到下面就会永久泄漏。
                checkinBusy.remove(key)
            }
            withContext(Dispatchers.Main + NonCancellable) {
                toast(message)
                render()
            }
        }
    }

    private fun markDone(uid: String, key: String) {
        val snap = TopicStore.snapshot(ctx, uid) ?: return
        val updated = snap.topics.map { if (it.key == key) it.copy(done = true) else it }
        // 时间戳用「现在」：沿用旧快照的时间会让刚签的这一条仍被判成「非今日」，
        // 指标区继续显示占位符 —— 用户明明刚签成功，界面却说没有今天的数据。
        TopicStore.save(ctx, uid, updated, at = System.currentTimeMillis())
    }

    /**
     * 把这次签到同步到首页账号行的状态行。
     *
     * 首页账号卡上有**两处**签到状态，来源不同：
     * - 「已签 / 待签」chip 读的是 [TopicStore] 快照（[markDone] 会更新它）；
     * - 状态行读的是 `AccountStore` 的 `lastResult`。
     *
     * 只在超话页签到而不写 `lastResult`，就会出现「提示签到成功 + chip 已签 7」
     * 与「账号信息区仍写尚未签到」并排显示的矛盾。这里按快照的最终口径补写一笔，
     * 让同一行里的两个状态来自同一次签到结果。
     */
    private fun noteCheckin(uid: String) {
        val snap = TopicStore.snapshot(ctx, uid) ?: return
        if (snap.topics.isEmpty()) return
        // 调用点在 IO 线程；timeFmt 是 ThreadLocal，各线程各拿一份，这里直接复用
        val at = fmtTime(System.currentTimeMillis())
        AccountStore.setLastResult(
            ctx, uid,
            ctx.getString(R.string.home_status_signed_fmt, snap.done, snap.total, at),
        )
    }
}