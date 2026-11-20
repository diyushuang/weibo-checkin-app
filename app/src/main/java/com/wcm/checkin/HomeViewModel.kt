package com.wcm.checkin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** 各账号最近一次超话状态（key = uid）。 */
data class TopicState(
    val topics: List<WeiboTopicApi.Topic> = emptyList(),
    val atMs: Long = 0L,
    val error: String = "",
    val loading: Boolean = false,
) {
    val total: Int get() = topics.size
    val done: Int get() = topics.count { it.done }
    val freshToday: Boolean get() = TopicStore.isToday(atMs)
}

/** 超话状态表：StateFlow + 不可变 Map 整体替换（JVM 单测直接覆盖）。 */
class HomeTopicStates {
    private val _states = MutableStateFlow<Map<String, TopicState>>(emptyMap())
    val states: StateFlow<Map<String, TopicState>> = _states.asStateFlow()

    fun set(uid: String, state: TopicState) {
        _states.update { it + (uid to state) }
    }

    fun remove(uid: String) {
        _states.update { it - uid }
    }
}

data class HomeHeaderUi(
    val greeting: String = "",
    val today: String = "",
    val accountsTitle: String = "",
)

data class HomeRunUi(
    val busy: Boolean = false,
    val syncing: Boolean = false,
)

sealed interface HomeEvent {
    data class Toast(val text: String) : HomeEvent
    data object OpenLogin : HomeEvent
    data object OpenSettings : HomeEvent
    data class OpenTopicsTab(val uid: String?) : HomeEvent
    data class ShowMessage(val title: String, val message: String) : HomeEvent
}

/**
 * 首页状态与编排：签到/同步/重登/超话清单/日志全部在这里。
 *
 * 长任务仍挂在 [AppScope]：切 Tab / 转屏不中断；UI 只订阅状态。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val crawlService: CrawlService,
    /** 签到编排（首页「立即签到」与补签共用）。 */
    private val checkinService: CheckinService,
    /** 探活 / 补昵称 / 面板同步。 */
    private val maintenance: AccountMaintenanceService,
    /** 账号密码自动重登。 */
    private val autoRelogin: AutoRelogin,
) : ViewModel() {

    private val _hero = MutableStateFlow(HomeHeroUiState())
    val hero: StateFlow<HomeHeroUiState> = _hero.asStateFlow()

    private val _accounts = MutableStateFlow<List<AccountRowUi>>(emptyList())
    val accounts: StateFlow<List<AccountRowUi>> = _accounts.asStateFlow()

    private val _cookie = MutableStateFlow(CookieCardUi())
    val cookie: StateFlow<CookieCardUi> = _cookie.asStateFlow()

    private val _log = MutableStateFlow(LogCardUi())
    val log: StateFlow<LogCardUi> = _log.asStateFlow()

    private val _header = MutableStateFlow(HomeHeaderUi())
    val header: StateFlow<HomeHeaderUi> = _header.asStateFlow()

    private val _run = MutableStateFlow(HomeRunUi())
    val run: StateFlow<HomeRunUi> = _run.asStateFlow()

    private val _badge = MutableStateFlow<Int?>(null)
    val badge: StateFlow<Int?> = _badge.asStateFlow()

    private val _topicsDialog = MutableStateFlow<TopicsDialogUi?>(null)
    val topicsDialog: StateFlow<TopicsDialogUi?> = _topicsDialog.asStateFlow()

    private val _reloginDialog = MutableStateFlow<ReloginDialogUi?>(null)
    val reloginDialog: StateFlow<ReloginDialogUi?> = _reloginDialog.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    @Volatile var autoHandled = false
    @Volatile var topicsAutoHandled = false
    @Volatile var busy = false
    @Volatile var topicBusy = false
    @Volatile var taskJob: Job? = null
    @Volatile var topicJob: Job? = null
    @Volatile var nameBackfillFor = ""
    @Volatile var busySyncing = false

    val logLines = ArrayDeque<String>()

    private var logExpanded = false
    private var pendingAutoRefresh = false
    private var dialogTopicMap: Map<String, WeiboTopicApi.Topic> = emptyMap()

    private val topicStore = HomeTopicStates()
    val topicStates: StateFlow<Map<String, TopicState>> = topicStore.states

    private val timeFmt: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }
    private val stampFmt: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA) }

    companion object {
        private const val LOG_MAX_LINES = 500
    }

    fun setTopicState(uid: String, state: TopicState) {
        topicStore.set(uid, state)
    }

    fun removeTopicState(uid: String) {
        topicStore.remove(uid)
    }

    private fun toast(text: String) = _events.tryEmit(HomeEvent.Toast(text))

    private fun formatTime(at: Long): String = timeFmt.get().format(Date(at))

    private fun formatStamp(at: Long): String = stampFmt.get().format(Date(at))

    private fun topicStateTime(atMs: Long): String = when {
        atMs <= 0L -> ""
        TopicStore.isToday(atMs) -> formatTime(atMs).take(5)
        else -> formatStamp(atMs)
    }

    // ========================= 生命周期入口 =========================

    fun onShown() {
        syncFromStore()
        render()
        settleBusy()
        backfillMissingNames()

        var autoCheckinStarted = false
        if (!autoHandled && PanelSettings.autoOnOpen(ctx) && AccountStore.load(ctx).isNotEmpty()
            && System.currentTimeMillis() - PanelSettings.lastAutoAt(ctx) > 6 * 3600_000L
        ) {
            val started = runAll(true)
            if (started) {
                autoHandled = true
                PanelSettings.setLastAutoAt(ctx, System.currentTimeMillis())
                autoCheckinStarted = true
            }
        }
        if (!autoCheckinStarted) autoRefreshTopicsOnOpen()
    }

    fun onResume() {
        syncFromStore()
        render()
        settleBusy()
    }

    fun setLogExpanded(expanded: Boolean) {
        logExpanded = expanded
        pushLogState()
    }

    // ========================= 渲染 =========================

    private fun render() {
        renderRunState()
        val accounts = AccountStore.load(ctx)
        val panelReady = PanelSettings.panelUrl(ctx).isNotEmpty() &&
            PanelSettings.panelToken(ctx).isNotEmpty()

        _header.value = HomeHeaderUi(
            greeting = greeting(),
            today = if (accounts.isEmpty()) {
                ctx.getString(R.string.home_today_no_account_fmt, dateLabel())
            } else {
                ctx.getString(R.string.home_today_fmt, dateLabel(), accounts.size)
            },
            accountsTitle = if (accounts.isEmpty()) {
                ctx.getString(R.string.accounts_label)
            } else {
                ctx.getString(R.string.accounts_count_fmt, accounts.size)
            },
        )
        renderCookieValidity(accounts, panelReady)
        _accounts.value = accounts.map(::buildAccountRow)
        renderTopicProgress(accounts, panelReady)
        pushLogState()
    }

    private fun syncFromStore() {
        TopicStore.ensureLoaded(ctx)
        TopicStore.all(ctx).forEach { (uid, snap) ->
            if (topicStates.value[uid]?.loading == true) return@forEach
            if (snap.topics.isEmpty() && snap.error.isEmpty()) return@forEach
            setTopicState(
                uid,
                TopicState(topics = snap.topics, atMs = snap.at, error = snap.error, loading = false),
            )
        }
    }

    private fun settleBusy() {
        if (topicBusy && topicJob?.isActive != true) topicBusy = false
        if (busy && taskJob?.isActive != true) busy = false
        renderRunState()
    }

    private fun renderRunState() {
        _run.value = HomeRunUi(busy = busy, syncing = busy && busySyncing)
    }

    // ========================= Cookie 卡 =========================

    private fun renderCookieValidity(accounts: List<Account>, panelReady: Boolean) {
        val hint = ctx.getString(
            if (panelReady) R.string.sync_card_hint else R.string.sync_card_hint_off,
        )
        if (accounts.isEmpty()) {
            _cookie.value = CookieCardUi(
                pillText = ctx.getString(R.string.sync_pill_unknown),
                pillTone = PillTone.NEUTRAL,
                hint = hint,
                showConfig = !panelReady,
                emptyText = ctx.getString(R.string.sync_empty),
                rows = emptyList(),
            )
            return
        }

        val now = System.currentTimeMillis()
        val rows = accounts
            .map { it to CookieLife.describe(it, now) }
            .sortedBy { (acc, life) ->
                when {
                    acc.cookieDead -> 0
                    life.state == CookieLife.State.EXPIRED -> 1
                    life.state == CookieLife.State.EXPIRING -> 2
                    !life.known -> 3
                    else -> 4
                }
            }
        val dead = rows.count { it.first.cookieDead }
        val expired = rows.count { !it.first.cookieDead && it.second.state == CookieLife.State.EXPIRED }
        val expiring = rows.count { !it.first.cookieDead && it.second.state == CookieLife.State.EXPIRING }
        val unsynced = rows.count { !it.first.cookieDead && !it.second.known }

        val (pillText, pillTone) = when {
            !panelReady -> ctx.getString(R.string.sync_pill_off) to PillTone.NEUTRAL
            dead > 0 -> ctx.getString(R.string.sync_pill_dead_fmt, dead) to PillTone.DANGER
            expired > 0 -> ctx.getString(R.string.sync_pill_expired_fmt, expired) to PillTone.DANGER
            expiring > 0 -> ctx.getString(R.string.sync_pill_warn_fmt, expiring) to PillTone.DANGER
            unsynced == accounts.size -> ctx.getString(R.string.sync_pill_unknown) to PillTone.NEUTRAL
            unsynced > 0 -> ctx.getString(R.string.sync_pill_unsynced_fmt, unsynced) to PillTone.WARN
            else -> ctx.getString(R.string.sync_pill_ok) to PillTone.OK
        }

        _cookie.value = CookieCardUi(
            pillText = pillText,
            pillTone = pillTone,
            hint = hint,
            showConfig = !panelReady,
            emptyText = "",
            rows = rows.map { (acc, life) ->
                val danger = acc.cookieDead || life.alert
                val expiryText = if (acc.cookieDead) {
                    ctx.getString(R.string.cookie_dead_hint)
                } else {
                    val base = expiryLine(life, panelReady)
                    if (acc.cookieLastError.isNotEmpty()) {
                        base + "\n" + ctx.getString(R.string.cookie_last_error_fmt, acc.cookieLastError)
                    } else {
                        base
                    }
                }
                val (label, tone) = when {
                    acc.cookieDead -> ctx.getString(R.string.cookie_dead_pill) to PillTone.DANGER
                    life.state == CookieLife.State.EXPIRED ->
                        ctx.getString(R.string.cookie_expired) to PillTone.DANGER
                    life.state == CookieLife.State.EXPIRING ->
                        cookieLeftLabel(life) to PillTone.DANGER
                    life.state == CookieLife.State.OK -> cookieLeftLabel(life) to PillTone.OK
                    else -> ctx.getString(R.string.cookie_unsynced) to PillTone.NEUTRAL
                }
                CookieRowUi(
                    name = displayName(acc),
                    danger = danger,
                    expiryText = expiryText,
                    pillText = label,
                    pillTone = tone,
                )
            },
        )
    }

    private fun cookieLeftLabel(life: CookieLife.View): String {
        val days = life.daysLeft ?: return ctx.getString(R.string.cookie_unsynced)
        return when {
            days <= -1 -> ctx.getString(R.string.cookie_expired_days_fmt, -days.toInt())
            days <= 0 -> ctx.getString(R.string.cookie_expired)
            days < 1 -> ctx.getString(R.string.cookie_left_today)
            else -> ctx.getString(R.string.cookie_left_days_fmt, days.toInt())
        }
    }

    private fun expiryLine(life: CookieLife.View, panelReady: Boolean): String {
        if (!life.known) {
            return ctx.getString(
                if (panelReady) R.string.cookie_unknown_hint else R.string.cookie_unknown_hint_off,
            )
        }
        val login = if (life.loginAt > 0L) {
            ctx.getString(R.string.cookie_issued_at_fmt, formatStamp(life.loginAt))
        } else {
            ctx.getString(R.string.cookie_login_unknown)
        }
        val sync = if (life.syncedAt > 0L) {
            ctx.getString(R.string.cookie_synced_at_fmt, formatStamp(life.syncedAt))
        } else {
            ctx.getString(R.string.cookie_never_synced)
        }
        val server = if (life.serverExpiresAt > 0L) {
            ctx.getString(R.string.cookie_server_expiry_fmt, formatStamp(life.serverExpiresAt))
        } else {
            ctx.getString(R.string.cookie_server_expiry_unknown)
        }
        // 判定口径已改为「服务端声明优先」，此时再并列显示经验推算值会自相矛盾
        // （剩余 30 天，却写「推算到期 11/24」）。只在服务端到期未知时才展示推算值。
        val estimated = if (life.serverExpiresAt > 0L) {
            ""
        } else {
            " · " + ctx.getString(R.string.cookie_estimated_expiry_fmt, formatStamp(life.estimatedAt))
        }
        val legacy = if (life.fromPanel) "\n" + ctx.getString(R.string.cookie_legacy_anchor_note) else ""
        val base = "$login · $sync\n$server$estimated$legacy"
        return if (life.alert) base + "\n" + ctx.getString(R.string.cookie_renew_hint) else base
    }

    // ========================= Hero / 账号行 =========================

    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return ctx.getString(
            when (hour) {
                in 5..10 -> R.string.greeting_morning
                in 11..12 -> R.string.greeting_noon
                in 13..17 -> R.string.greeting_afternoon
                else -> R.string.greeting_evening
            },
        )
    }

    private fun dateLabel(): String =
        SimpleDateFormat("M月d日 · EEE", Locale.CHINA).format(Date())

    private fun renderTopicProgress(accounts: List<Account>, panelReady: Boolean) {
        val known = topicStates.value.values.filter {
            !it.loading && it.error.isEmpty() && it.total > 0 && it.freshToday
        }
        val total = known.sumOf { it.total }
        val done = known.sumOf { it.done }
        val zero = total <= 0
        val summary = if (accounts.isEmpty()) {
            ctx.getString(R.string.home_summary_empty)
        } else {
            ctx.getString(
                R.string.home_summary_fmt,
                accounts.size,
                if (panelReady) ctx.getString(R.string.home_panel_ok)
                else ctx.getString(R.string.home_panel_off),
            )
        }
        _hero.value = HomeHeroUiState(
            ringValue = if (zero) ctx.getString(R.string.home_ring_value_empty)
            else ctx.getString(R.string.home_ring_value_fmt, done, total),
            pendingValue = if (zero) ctx.getString(R.string.home_metric_empty)
            else (total - done).toString(),
            pillAccounts = ctx.getString(R.string.home_pill_accounts_fmt, accounts.size),
            pillPanel = ctx.getString(
                if (panelReady) R.string.home_pill_panel_on else R.string.home_pill_panel_off,
            ),
            summary = summary,
            progress = if (zero) 0f else done.toFloat() / total,
        )
        _badge.value = if (zero) null else total - done
    }

    private fun avatarInitial(acc: Account): String {
        if (AccountStore.needsNameResolve(acc)) return "微"
        val name = acc.name.trim().trimStart('@', '＠')
        if (name.isEmpty()) return "微"
        return String(Character.toChars(name.codePointAt(0)))
    }

    private fun displayName(acc: Account): String =
        if (AccountStore.needsNameResolve(acc)) ctx.getString(R.string.home_name_unknown) else acc.name

    private fun buildAccountRow(acc: Account): AccountRowUi {
        val st = topicStates.value[acc.uid]
        val loaded = st != null && !st.loading && st.error.isEmpty() && st.total > 0 && st.freshToday
        val chips = if (st != null && loaded) {
            val maxLevel = st.topics.maxOfOrNull { it.level } ?: 0
            AccountChipsUi(
                level = if (maxLevel > 0) ctx.getString(R.string.account_chip_level_fmt, maxLevel) else null,
                done = ctx.getString(R.string.account_chip_done_fmt, st.done),
                pending = ctx.getString(R.string.account_chip_pending_fmt, st.total - st.done),
            )
        } else {
            null
        }
        val note = when {
            st == null -> ctx.getString(R.string.home_topics_idle)
            st.loading -> ctx.getString(R.string.home_topics_loading)
            st.error.isNotEmpty() -> ctx.getString(R.string.home_topics_failed, st.error)
            st.total == 0 -> ctx.getString(R.string.home_topic_none)
            !st.freshToday -> ctx.getString(R.string.home_topics_stale_fmt, topicStateTime(st.atMs))
            else -> ctx.getString(R.string.home_topics_updated_fmt, topicStateTime(st.atMs))
        }
        val head = AccountStatusHead.of(
            loaded = loaded,
            done = st?.done ?: 0,
            total = st?.total ?: 0,
            lastResult = acc.lastResult,
            lastResultToday = TopicStore.isToday(acc.lastResultAt),
            lastResultAt = acc.lastResultAt,
        )
        val headText = when (head) {
            is AccountStatusHead.Head.TodayAll ->
                ctx.getString(R.string.home_status_today_all_fmt, head.done, head.total)
            is AccountStatusHead.Head.TodayLeft ->
                ctx.getString(
                    R.string.home_status_today_left_fmt,
                    head.done, head.total, head.total - head.done,
                )
            is AccountStatusHead.Head.LastToday -> head.result
            is AccountStatusHead.Head.LastEarlier -> if (head.atMs > 0L) {
                ctx.getString(R.string.home_status_last_fmt, head.result, topicStateTime(head.atMs))
            } else {
                ctx.getString(R.string.home_status_last_plain_fmt, head.result)
            }
            AccountStatusHead.Head.Idle -> ctx.getString(R.string.home_status_idle)
        }
        val tone = when {
            loaded && st != null && st.done >= st.total -> AvatarTone.OK
            loaded && st != null && st.done < st.total -> AvatarTone.WARN
            else -> AvatarTone.BRAND
        }
        return AccountRowUi(
            uid = acc.uid,
            name = displayName(acc),
            uidText = ctx.getString(R.string.home_uid_fmt, acc.uid),
            avatarInitial = avatarInitial(acc),
            avatarTone = tone,
            statusText = headText + "\n" + note,
            chips = chips,
        )
    }

    // ========================= 日志 =========================

    fun log(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > LOG_MAX_LINES) logLines.removeFirst()
        }
        pushLogState()
    }

    private fun pushLogState() {
        val lines = synchronized(logLines) { logLines.toList() }
        _log.value = LogCardUi(
            expanded = logExpanded,
            lines = lines,
            summary = lines.lastOrNull { it.isNotBlank() } ?: ctx.getString(R.string.log_empty),
        )
    }

    private fun now(): String = formatTime(System.currentTimeMillis())

    // ========================= 账号操作 =========================

    fun openLogin() = _events.tryEmit(HomeEvent.OpenLogin)
    fun openSettings() = _events.tryEmit(HomeEvent.OpenSettings)

    fun onHeroClick() {
        val uid = topicStates.value.entries
            .filter {
                it.value.error.isEmpty() && !it.value.loading && it.value.total > it.value.done
            }
            .maxByOrNull { it.value.total - it.value.done }?.key
        _events.tryEmit(HomeEvent.OpenTopicsTab(uid))
    }

    fun deleteAccount(uid: String) {
        AccountStore.remove(ctx, uid)
        removeTopicState(uid)
        TopicStore.clear(ctx, uid)
        render()
    }

    fun showRelogin(uid: String) {
        val acc = AccountStore.load(ctx).firstOrNull { it.uid == uid } ?: return
        val hasCredential = CredentialVault.has(ctx, acc.uid)
        val hasAlc = AlcVault.has(ctx, acc.uid)
        val state = AccountStore.reloginState(acc)
        val status = buildString {
            when {
                hasCredential -> append(
                    ctx.getString(
                        R.string.relogin_status_saved_fmt,
                        if (acc.credentialAt > 0L) formatStamp(acc.credentialAt) else "时间未知",
                    ),
                )
                hasAlc -> append(
                    ctx.getString(
                        R.string.relogin_status_alc_fmt,
                        if (acc.alcAt > 0L) formatStamp(acc.alcAt) else "时间未知",
                    ),
                )
                else -> append(ctx.getString(R.string.relogin_status_none))
            }
            when {
                state.blocked -> {
                    append("\n")
                    append(
                        ctx.getString(
                            R.string.relogin_status_blocked_fmt,
                            state.lastReason.ifEmpty { ReloginPolicy.reasonText(state.lastCode) },
                        ),
                    )
                }
                state.nextAt > 0L -> {
                    append("\n")
                    append(
                        ctx.getString(
                            R.string.relogin_status_next_fmt,
                            state.failStreak,
                            formatStamp(state.nextAt),
                        ),
                    )
                }
                hasCredential || hasAlc -> {
                    append("\n")
                    append(ctx.getString(R.string.relogin_status_ready))
                }
            }
        }
        _reloginDialog.value = ReloginDialogUi(acc.uid, status, hasCredential, hasAlc)
    }

    fun dismissRelogin() {
        _reloginDialog.value = null
    }

    /**
     * 保存自动重登凭证。
     *
     * 支持三种组合：只存密码、只存 ALC、两者都存。**只要有一项可用**就算成功——
     * 路线 E（ALC 重签）的意义正是"不想交密码也能自动恢复"。
     */
    fun saveReloginCredentials(
        uid: String,
        username: String,
        password: String,
        alcRaw: String = "",
    ): Boolean {
        val hasPassword = username.isNotEmpty() && password.isNotEmpty()
        val alc = WeiboAlcLogin.normalizeAlc(alcRaw)
        if (!hasPassword && alc.isEmpty()) {
            toast(ctx.getString(R.string.relogin_need_both))
            return false
        }
        if (username.isNotEmpty() != password.isNotEmpty()) {
            toast(ctx.getString(R.string.relogin_need_both))
            return false
        }
        try {
            if (hasPassword) {
                CredentialVault.save(ctx, uid, username, password)
                AccountStore.setCredentialAt(ctx, uid, System.currentTimeMillis())
            }
            if (alc.isNotEmpty()) {
                AlcVault.save(ctx, uid, alc)
                AccountStore.setAlcAt(ctx, uid, System.currentTimeMillis())
            }
            AccountStore.setAccountAutoRelogin(ctx, uid, true)
            // 重新保存凭证 = 用户给了一次新授权：把历史失败/硬停清掉。
            // 不清的话，只要曾经被「密码错」或「需要二次验证」硬停过一次，
            // 重新填一遍凭证也永远点不动「立即重登」（shouldAttempt 对硬停码直接拒绝）。
            AccountStore.saveReloginState(ctx, uid, ReloginPolicy.State())
        } catch (e: Exception) {
            toast(ctx.getString(R.string.relogin_failure_fmt, "凭证加密保存失败"))
            return false
        }
        toast(ctx.getString(if (hasPassword) R.string.relogin_saved else R.string.relogin_alc_only_saved))
        _reloginDialog.value = null
        AccountStore.load(ctx).firstOrNull { it.uid == uid }?.let { runManualRelogin(it) }
        return true
    }

    fun deleteReloginCredentials(uid: String) {
        CredentialVault.clear(ctx, uid)
        AlcVault.clear(ctx, uid)
        AccountStore.setAlcAt(ctx, uid, 0L)
        AccountStore.saveReloginState(ctx, uid, ReloginPolicy.State(credentialAt = 0L))
        AccountStore.setAccountAutoRelogin(ctx, uid, false)
        toast(ctx.getString(R.string.relogin_deleted))
        _reloginDialog.value = null
        render()
    }

    private fun runManualRelogin(acc: Account) {
        toast(ctx.getString(R.string.relogin_running))
        AppScope.launch {
            val outcome = autoRelogin.tryAutoRelogin(ctx, acc, "manual", force = true)
            withContext(Dispatchers.Main + NonCancellable) {
                toast(
                    if (outcome.ok) ctx.getString(R.string.relogin_success)
                    else ctx.getString(R.string.relogin_failure_fmt, outcome.message),
                )
                render()
            }
        }
    }

    // ========================= 昵称兜底 =========================

    private fun backfillMissingNames() {
        val pending = AccountStore.load(ctx).filter { AccountStore.needsNameResolve(it) }
        if (pending.isEmpty()) return
        val marker = pending.map { it.uid }.sorted().joinToString(",")
        if (marker == nameBackfillFor) return
        nameBackfillFor = marker

        AppScope.launch {
            var fixed = 0
            pending.forEach { acc ->
                try {
                    val verify = maintenance.probeAndPersist(ctx, acc)
                    if (!verify.login) return@forEach
                    val name = maintenance.resolveNameIfNeeded(acc, verify.cookie)
                    if (name.isEmpty()) return@forEach
                    maintenance.persistVerified(
                        ctx, acc, verify.cookie, name = name,
                    )
                    fixed++
                } catch (e: Exception) {
                    // 单账号失败不影响其它账号；下次进页面还会再试
                }
            }
            if (fixed > 0) withContext(Dispatchers.Main) { render() }
        }
    }

    private fun updateCookieAndName(uid: String, cookie: String) {
        val current = AccountStore.load(ctx).firstOrNull { it.uid == uid } ?: return
        val name = maintenance.resolveNameIfNeeded(current, cookie)
        maintenance.persistVerified(ctx, current, cookie, name = name)
    }

    // ========================= 超话状态刷新 =========================

    private fun autoRefreshTopicsOnOpen() {
        if (topicsAutoHandled) return
        topicsAutoHandled = true
        if (AccountStore.load(ctx).isEmpty()) return
        if (busy || topicBusy) return
        refreshTopics(manual = false)
    }

    fun refreshTopics(manual: Boolean) {
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) {
            if (manual) toast(ctx.getString(R.string.home_no_account))
            return
        }
        if (busy || topicBusy) {
            if (manual) toast(ctx.getString(R.string.busy)) else pendingAutoRefresh = true
            return
        }
        topicBusy = true
        if (manual) setLogExpanded(true)
        accounts.forEach { acc ->
            setTopicState(acc.uid, (topicStates.value[acc.uid] ?: TopicState()).copy(loading = true, error = ""))
        }
        render()
        log("==== 刷新超话状态 · ${now()} ====")

        topicJob = AppScope.launch {
            try {
                accounts.forEachIndexed { index, acc ->
                    if (index > 0) delay(800)
                    val label = acc.name.ifEmpty { acc.uid }
                    val verify = try {
                        WeiboAuthApi.verify(acc.cookie)
                    } catch (e: Exception) {
                        null
                    }
                    if (verify == null || !verify.login) {
                        if (verify != null) AccountStore.markDead(ctx, acc.uid)
                        setTopicState(
                            acc.uid,
                            (topicStates.value[acc.uid] ?: TopicState()).copy(
                                atMs = System.currentTimeMillis(),
                                error = ctx.getString(R.string.home_cookie_dead),
                                loading = false,
                            ),
                        )
                        TopicStore.saveError(ctx, acc.uid, ctx.getString(R.string.home_cookie_dead))
                        log("  ✗ $label：${ctx.getString(R.string.home_cookie_dead)}")
                        postRender()
                        return@forEachIndexed
                    }
                    updateCookieAndName(acc.uid, verify.cookie)
                    val topics = try {
                        WeiboTopicApi.listTopics(verify.cookie)
                    } catch (e: Exception) {
                        setTopicState(
                            acc.uid,
                            (topicStates.value[acc.uid] ?: TopicState()).copy(
                                atMs = System.currentTimeMillis(),
                                error = e.message ?: "超话列表失败",
                                loading = false,
                            ),
                        )
                        TopicStore.saveError(ctx, acc.uid, e.message ?: "超话列表失败")
                        log("  ✗ $label：${e.message}")
                        postRender()
                        return@forEachIndexed
                    }
                    setTopicState(acc.uid, TopicState(topics = topics, atMs = System.currentTimeMillis()))
                    TopicStore.save(ctx, acc.uid, topics)
                    log(
                        "  $label：${topics.size} 个超话，已签 ${topics.count { it.done }}，" +
                            "待签 ${topics.count { !it.done }}",
                    )
                    postRender()
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    topicBusy = false
                    render()
                    if (pendingAutoRefresh) {
                        pendingAutoRefresh = false
                        topicsAutoHandled = true
                        if (!busy) refreshTopics(manual = false)
                    }
                }
            }
        }
    }

    private fun postRender() {
        viewModelScope.launch(Dispatchers.Main) { render() }
    }

    // ========================= 签到 / 同步 =========================

    /** @return 是否真的启动了（被 busy 拦下或没有账号时返回 false，供自动签到判定）。 */
    fun runAll(auto: Boolean): Boolean {
        if (busy) {
            if (taskJob?.isActive == true) {
                toast(ctx.getString(R.string.busy))
                return false
            }
            busy = false
        }
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) {
            setLogExpanded(true)
            log(ctx.getString(R.string.home_no_account))
            toast(ctx.getString(R.string.home_no_account))
            return false
        }
        busy = true
        setLogExpanded(true)
        render()
        taskJob = AppScope.launch {
            try {
                log("==== ${if (auto) "打开自动" else "手动"}签到 · ${now()} ====")
                checkinService.runAll(
                    ctx = ctx,
                    accounts = accounts,
                    log = ::log,
                    cookieDeadText = ctx.getString(R.string.home_cookie_dead),
                    onTopics = { uid, topics ->
                        setTopicState(uid, TopicState(topics = topics, atMs = System.currentTimeMillis()))
                        postRender()
                    },
                    onAccountDone = { postRender() },
                )
                log("==== 全部完成 · ${now()} ====")
                if (CrawlSettings.crawlAuto(ctx)) {
                    try {
                        if (crawlService.runAll(ctx, ::log, auto = true) == CrawlService.RunResult.Busy) {
                            log("自动抓图跳过：已有抓取任务在运行")
                        }
                    } catch (e: Exception) {
                        log("自动抓图中断：${e.message}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("签到异常终止：${e.message ?: e.javaClass.simpleName}")
            } finally {
                try {
                    AccountStore.mergeSave(ctx, accounts)
                } catch (e: Exception) {
                    log("保存账号失败：${e.message}")
                }
                withContext(Dispatchers.Main + NonCancellable) {
                    busy = false
                    render()
                    if (pendingAutoRefresh) {
                        pendingAutoRefresh = false
                        if (!topicBusy) refreshTopics(manual = false)
                    }
                }
            }
        }
        return true
    }

    private suspend fun syncToPanel(acc: Account): Boolean {
        val r = maintenance.syncToPanel(ctx, acc)
        if (r.ok) {
            log("  📲 已同步面板：${r.detail}")
            return true
        }
        if (r.detail == "未配置面板") {
            log("  （未配置面板，跳过同步）")
        } else {
            log("  📲 面板拒绝同步：${r.detail}")
        }
        return false
    }

    fun syncOnly() {
        if (busy) {
            if (taskJob?.isActive == true) {
                toast(ctx.getString(R.string.busy))
                return
            }
            busy = false
        }
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) {
            setLogExpanded(true)
            log(ctx.getString(R.string.home_no_account))
            toast(ctx.getString(R.string.home_no_account))
            return
        }
        busySyncing = true
        busy = true
        setLogExpanded(true)
        render()
        taskJob = AppScope.launch {
            try {
                log("==== Cookie 同步 · ${now()} ====")
                var ok = 0
                for (acc in accounts) {
                    log("── ${acc.name.ifEmpty { acc.uid }} ──")
                    try {
                        val verify = maintenance.probeAndPersist(ctx, acc)
                        if (!verify.login) {
                            checkinService.noteResult(
                                ctx, acc, ctx.getString(R.string.home_cookie_dead),
                            )
                            AccountStore.markDead(ctx, acc.uid)
                            log("  ✗ ${ctx.getString(R.string.home_cookie_dead)}")
                            continue
                        }
                        if (syncToPanel(acc)) ok++
                    } catch (e: Exception) {
                        log("  ✗ ${e.message}")
                    }
                }
                log("==== 同步结束：成功 $ok/${accounts.size} · ${now()} ====")
            } finally {
                try {
                    AccountStore.mergeSave(ctx, accounts)
                } catch (e: Exception) {
                    log("保存账号失败：${e.message}")
                }
                withContext(Dispatchers.Main + NonCancellable) {
                    busySyncing = false
                    busy = false
                    render()
                }
            }
        }
    }

    // ========================= 超话清单 / 补签 =========================

    fun showTopics(uid: String) {
        if (busy || topicBusy) {
            toast(ctx.getString(R.string.busy))
            return
        }
        val acc = AccountStore.load(ctx).firstOrNull { it.uid == uid } ?: return
        topicBusy = true
        setLogExpanded(true)
        log("── 查询 ${acc.name.ifEmpty { acc.uid }} 的超话 ──")
        topicJob = AppScope.launch {
            try {
                val verify = try {
                    WeiboAuthApi.verify(acc.cookie)
                } catch (e: Exception) {
                    null
                }
                if (verify == null || !verify.login) {
                    if (verify != null) AccountStore.markDead(ctx, uid)
                    log("  ✗ ${ctx.getString(R.string.home_cookie_dead)}")
                    withContext(Dispatchers.Main + NonCancellable) {
                        _events.tryEmit(
                            HomeEvent.ShowMessage(
                                ctx.getString(R.string.home_topic_title, acc.name.ifEmpty { acc.uid }),
                                ctx.getString(R.string.home_cookie_dead),
                            ),
                        )
                    }
                    return@launch
                }
                updateCookieAndName(uid, verify.cookie)
                val topics = WeiboTopicApi.listTopics(verify.cookie)
                setTopicState(uid, TopicState(topics = topics, atMs = System.currentTimeMillis()))
                TopicStore.save(ctx, uid, topics)
                log("  ${topics.size} 个超话，已签 ${topics.count { it.done }}")
                withContext(Dispatchers.Main + NonCancellable) {
                    render()
                    presentTopics(uid, verify.st, topics)
                }
            } catch (e: Exception) {
                log("  ✗ 超话查询失败：${e.message}")
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    topicBusy = false
                }
            }
        }
    }

    private fun presentTopics(uid: String, st: String?, topics: List<WeiboTopicApi.Topic>) {
        val name = AccountStore.load(ctx).firstOrNull { it.uid == uid }
            ?.let { displayName(it) } ?: uid
        dialogTopicMap = topics.associateBy { it.key }
        _topicsDialog.value = TopicsDialogUi(
            uid = uid,
            st = st,
            name = name,
            topics = topics.map { TopicChoiceUi(it.key, it.name, it.done) },
        )
    }

    fun dismissTopicsDialog() {
        _topicsDialog.value = null
    }

    fun resignTopics(dialog: TopicsDialogUi, pickedKeys: List<String>) {
        val picked = pickedKeys.mapNotNull { dialogTopicMap[it] }
        if (picked.isEmpty()) {
            toast(ctx.getString(R.string.home_topic_none_selected))
            return
        }
        if (busy) {
            if (taskJob?.isActive == true) {
                toast(ctx.getString(R.string.busy))
                return
            }
            busy = false
        }
        busy = true
        setLogExpanded(true)
        render()
        log("── 补签 ${picked.size} 个超话 ──")
        val uid = dialog.uid
        val st = dialog.st
        taskJob = AppScope.launch {
            try {
                val acc = AccountStore.load(ctx).firstOrNull { it.uid == uid }
                if (acc == null) {
                    log("  ✗ 账号已不存在")
                    return@launch
                }
                var cookie = acc.cookie
                var freshSt = st
                try {
                    val v = maintenance.probeAndPersist(ctx, acc)
                    if (v.login) {
                        cookie = v.cookie
                        if (!v.st.isNullOrEmpty()) freshSt = v.st
                    } else {
                        log("  ✗ 登录状态已失效，请到超话页重新登录后再补签")
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("  ! 探活失败，沿用弹窗里的登录态：${e.message}")
                }
                var ok = 0
                var fail = 0
                for (topic in picked) {
                    delay(Random.nextLong(3000, 8000))
                    try {
                        if (WeiboTopicApi.sign(cookie, topic.scheme ?: "", freshSt)) {
                            ok++; log("  ✓ ${topic.name}")
                        } else {
                            fail++; log("  ✗ ${topic.name}")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fail++; log("  ✗ ${topic.name}：${e.message}")
                    }
                }
                log("  " + ctx.getString(R.string.home_resign_done_fmt, ok, fail))
                val summary = "补签 $ok/${picked.size}" +
                    (if (fail > 0) "（失败 $fail）" else "") + " · ${now()}"
                AccountStore.setLastResult(ctx, uid, summary)
                log("  小结：$summary")
                setTopicState(uid, (topicStates.value[uid] ?: TopicState()).copy(loading = true))
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    busy = false
                    render()
                    refreshTopics(manual = false)
                }
            }
        }
    }
}