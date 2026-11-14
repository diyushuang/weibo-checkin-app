package com.wcm.checkin

import com.wcm.checkin.databinding.FragmentHomeBinding
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 首页：签到 / 同步 / 账号管理 / 超话状态 / 运行日志。
 *
 * v0.5.1 起：
 * - 账号行分三行显示「用户名 / UID / 状态」，昵称缺失时会自动补拉；
 * - **页面一打开就自动刷新各账号的超话签到状态**，另有「刷新超话」按钮手动重拉；
 * - 超话清单弹窗**完整列出全部超话**并逐条标注已签/待签；
 * - 日志按时间顺序追加，用户上翻查看旧日志时不会被新日志强行拉回底部。
 *
 * v0.6（UI 刷新，只动呈现层，不改业务语义）：
 * - 「仅同步面板」「刷新超话」降级到概览卡的次级操作行，主行动只剩「立即签到」；
 * - 账号卡标题行只留「账号 (N) + 添加」，删除收进行尾溢出菜单；
 * - 账号行的超话状态改用「已签 / 待签」两个状态 chip；
 * - 日志卡默认收起（标题 + 最近一行摘要），运行任务开始时自动展开。
 *
 * v0.9.10：
 * - 「仅同步面板」升级为与「立即签到」**等宽并列**的「Cookie 同步」按钮 ——
 *   同步是每 7 天必须做一次的动作，藏在文字按钮里会被忘掉；
 * - 新增「Cookie 同步」卡：逐账号展示面板端 cookie 还剩几天、何时到期
 *   （口径见 [CookieLife]，对齐面板 `describe_cookie_lifespan`），临期/过期整行标红；
 * - 昵称补拉：占位名账号在进入首页时后台补一次，不再长期停在「（未获取到昵称）」。
 */
@AndroidEntryPoint
class HomeFragment : Fragment() {

    @Inject lateinit var crawlService: CrawlService

    private val viewModel: HomeViewModel by viewModels()

    /** Hero 卡（Compose）的渲染状态；每次 render 重建。 */
    private val heroState = MutableStateFlow(HomeHeroUiState())

    /** 账号列表（Compose）的渲染状态；每次 render 重建。 */
    private val accountState = MutableStateFlow<List<AccountRowUi>>(emptyList())

    /** Cookie 卡（Compose）的渲染状态；每次 render 重建。 */
    private val cookieState = MutableStateFlow(CookieCardUi())

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * 签到 / 同步的运行态与日志缓冲。
     *
     * **刻意放在 companion（进程级）而不是实例字段**：Fragment 会在旋转、切深浅色、
     * 页面重建时换一个实例，实例字段会把「正在跑」这件事丢掉 —— 新页面看起来空闲，
     * 用户再点一次就成了同一批账号被并发签到两轮。
     */
    private companion object {
        private const val LOG_MAX_LINES = 500
    }

    private var pendingAutoRefresh = false


    /** 日志卡是否展开（默认收起，把首屏让给签到主行动）。 */
    private var logExpanded = false

    /** 日志卡（Compose）的渲染状态；每次 render / 追加日志时重建。 */
    private val logState = MutableStateFlow(LogCardUi())

    /** 超话清单弹窗状态（Compose AlertDialog）。 */
    private val topicsDialogState = MutableStateFlow<TopicsDialogUi?>(null)

    /** 自动重登凭证弹窗状态（Compose AlertDialog）。 */
    private val reloginDialogState = MutableStateFlow<ReloginDialogUi?>(null)

    /**
     * 时间格式化：`SimpleDateFormat` **不是线程安全的**。
     *
     * 这个字段同时被 UI 线程（[syncFromStore] 里格式化快照时间）和 IO 线程
     * （签到/刷新日志里的 [now]）使用，共用一个实例会出现串位、甚至抛
     * `ArrayIndexOutOfBoundsException`。按线程各持一个实例最省事也最稳。
     */
    private val timeFmt: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }

    /** cookie 有效期的日期戳（`10月30日 21:40`）：只到分钟，不占宽度也不啰嗦。 */
    private val stampFmt: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA) }

    private fun formatStamp(at: Long): String {
        val fmt = stampFmt.get() ?: SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        return fmt.format(Date(at))
    }

    /** 线程安全的时间格式化：每个线程用自己的 [SimpleDateFormat]。 */
    private fun formatTime(at: Long): String {
        val fmt = timeFmt.get() ?: SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        return fmt.format(Date(at))
    }

    /**
     * 超话状态时间的人类可读标签：今天只给 `HH:mm`，跨天补上日期。
     *
     * 只给时分时，昨天 20:15 与今天 20:15 长得一模一样，用户没法判断这份状态是不是
     * 昨天的（而签到状态恰恰是按天的）。
     */
    private fun topicStateTime(atMs: Long): String = when {
        atMs <= 0L -> ""
        TopicStore.isToday(atMs) -> formatTime(atMs).take(5)
        else -> SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(atMs))
    }

    /** 各账号最近一次超话状态：由 [HomeViewModel] 持有，读到的始终是一份完整快照。 */
    private val topicStates: Map<String, TopicState> get() = viewModel.topicStates.value

    /** 单条更新：StateFlow + 新建 map，不会打断正在被遍历的旧快照。 */
    private fun setTopicState(uid: String, state: TopicState) = viewModel.setTopicState(uid, state)

    private fun removeTopicState(uid: String) = viewModel.removeTopicState(uid)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCheckin.setOnClickListener { runAll(false) }
        binding.btnSync.setOnClickListener { syncOnly() }
        binding.btnAdd.setOnClickListener {
            startActivity(Intent(requireContext(), LoginWebViewActivity::class.java))
        }
        binding.btnRefreshTopics.setOnClickListener {
            refreshTopics(manual = true)
        }
        // Hero 卡（Compose）：整块可点，直接进「超话」页并选中待签最多的账号
        binding.heroCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.heroCompose.setContent {
            val hero by heroState.collectAsState()
            WcmTheme {
                HomeHeroCard(
                    state = hero,
                    onClick = {
                        val uid = topicStates.entries
                            .filter {
                                it.value.error.isEmpty() && !it.value.loading &&
                                    it.value.total > it.value.done
                            }
                            .maxByOrNull { it.value.total - it.value.done }?.key
                        jumpToTopics(uid)
                    },
                )
            }
        }
        binding.accountCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.accountCompose.setContent {
            val rows by accountState.collectAsState()
            val topicsDialog by topicsDialogState.collectAsState()
            val reloginDialog by reloginDialogState.collectAsState()
            WcmTheme {
                Box {
                    AccountList(
                        rows = rows,
                        onRowClick = ::showTopics,
                        onViewTopics = ::showTopics,
                        onRelogin = { uid ->
                            AccountStore.load(requireContext()).firstOrNull { it.uid == uid }
                                ?.let { showReloginDialog(it) }
                        },
                        onDelete = { uid ->
                            AccountStore.load(requireContext()).firstOrNull { it.uid == uid }
                                ?.let { deleteAccount(it) }
                        },
                    )
                    topicsDialog?.let { dlg ->
                        TopicsDialog(
                            state = dlg,
                            onDismiss = { topicsDialogState.value = null },
                            onResign = { picked ->
                                topicsDialogState.value = null
                                if (picked.isEmpty()) {
                                    toast(getString(R.string.home_topic_none_selected))
                                } else {
                                    resignTopics(dlg.uid, dlg.st, picked)
                                }
                            },
                            onViewAll = {
                                topicsDialogState.value = null
                                jumpToTopics(dlg.uid)
                            },
                        )
                    }
                    reloginDialog?.let { dlg ->
                        ReloginDialog(
                            state = dlg,
                            onDismiss = { reloginDialogState.value = null },
                            onSave = { user, pass ->
                                saveReloginCredentials(dlg.uid, user, pass)
                            },
                            onDelete = { deleteReloginCredentials(dlg.uid) },
                        )
                    }
                }
            }
        }
        binding.cookieCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.cookieCompose.setContent {
            val state by cookieState.collectAsState()
            WcmTheme {
                CookieCard(
                    state = state,
                    onConfig = { (activity as? MainActivity)?.selectSettingsTab() },
                )
            }
        }
        // 日志卡：卡头整行可点，收起时只显示一行摘要
        binding.logCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.logCompose.setContent {
            val state by logState.collectAsState()
            WcmTheme {
                LogCard(state, onToggle = { setLogExpanded(!logExpanded) })
            }
        }
        // 视图重建时恢复上一次的展开态（默认收起），并把历史日志重新贴上去
        pushLogState()
        syncFromStore(requireContext())
        render()
        // 视图是新建的（刚进页面 / 旋转重建）：上一次的 busy latch 不代表现在真在跑，
        // 按 Job 的真实状态校正一次，避免按钮一进来就是「已置灰」的假运行态。
        settleBusy()
        // 昵称兜底：占位名（登录时资料接口没回昵称）在这里补一次，补到就落盘
        backfillMissingNames()

        val ctx = requireContext()
        var autoCheckinStarted = false
        // autoHandled 是「本进程只自动签一次」的闩；但如果这次没真的跑起来
        // （没账号 / 已关自动 / runAll 因 busy 早退），闩住就等于把整次开机机会吞掉，
        // 6 小时内都不会再试。所以：条件成立才置位，时间戳等确实启动后再落。
        if (!viewModel.autoHandled && PanelSettings.autoOnOpen(ctx) && AccountStore.load(ctx).isNotEmpty()
            && System.currentTimeMillis() - PanelSettings.lastAutoAt(ctx) > 6 * 3600_000L
        ) {
            val started = runAll(true)
            if (started) {
                viewModel.autoHandled = true
                PanelSettings.setLastAutoAt(ctx, System.currentTimeMillis())
                autoCheckinStarted = true
            }
        }
        // 签到本身就会把每个账号的超话列表拉一遍并写入 TopicStore，这时不必再单独刷新一次
        if (!autoCheckinStarted) autoRefreshTopicsOnOpen()
    }

    override fun onResume() {
        super.onResume()
        syncFromStore(requireContext())
        render()
        settleBusy()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // 只做内存重绘（从超话页签到后切回来能立刻看到新状态），**不触发网络刷新** ——
            // 超话状态的自动刷新只发生在打开 App 那一次，见 [autoRefreshTopicsOnOpen]。
            syncFromStore(requireContext())
            render()
            settleBusy()
        }
    }

    /**
     * 视图销毁时收干净视图相关的东西。
     *
     * **不取消 [taskJob] / [topicJob]**：签到与抓取是「不能半途而废」的长任务，旋转屏幕
     * 或进出页面就砍掉一半，用户既看不到结果也无从补救。它们跑在 [AppScope] 上，
     * 进度通过 [AccountStore] 与 `TopicStore` 落盘，回到页面重新读即可。
     */
    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** 只在「latch 说在跑、但没有活着的 Job」时把界面态归位；真在跑就保持不动。 */
    private fun settleBusy() {
        if (viewModel.topicBusy && viewModel.topicJob?.isActive != true) {
            viewModel.topicBusy = false
        }
        if (viewModel.busy && viewModel.taskJob?.isActive != true) {
            setBusy(false)
            return
        }
        setBusy(viewModel.busy)
    }

    /**
     * 把 [TopicStore] 里持久化的快照回灌到页面内存态。
     *
     * 解决两件事：冷启动时首屏就能看到上次的超话状态（不必等一次网络请求）；
     * 从「超话」页签到后切回来，账号行的 chip 与 Hero 进度立刻是新值。
     * 正在刷新的账号跳过 —— 否则会被旧快照覆盖掉 loading 态。
     */
    private fun syncFromStore(ctx: Context) {
        TopicStore.ensureLoaded(ctx)
        TopicStore.all(ctx).forEach { (uid, snap) ->
            if (topicStates[uid]?.loading == true) return@forEach
            if (snap.topics.isEmpty() && snap.error.isEmpty()) return@forEach
            setTopicState(
                uid,
                TopicState(
                    topics = snap.topics,
                    atMs = snap.at,
                    error = snap.error,
                    loading = false,
                ),
            )
        }
    }

    /** 跳到「超话」页；[uid] 为空时保持该页当前选择。 */
    private fun jumpToTopics(uid: String?) {
        val host = activity as? MainActivity ?: return
        if (uid.isNullOrEmpty()) {
            host.selectTopicTab("")
        } else {
            host.selectTopicTab(uid)
        }
    }

    // ========================= 渲染 =========================

    private fun render() {
        if (!isAdded) return
        // 视图重建后按真实运行态回填按钮文案（见 renderRunButtons 的说明）
        renderRunButtons()
        val ctx = requireContext()
        val accounts = AccountStore.load(ctx)
        val panelReady = PanelSettings.panelUrl(ctx).isNotEmpty() &&
            PanelSettings.panelToken(ctx).isNotEmpty()

        // 应用栏（v2.0）：问候语 + 「日期 · 账号数」。
        // 每次 render 都重算，跨天回前台、增删账号都会自动更新。
        _binding?.tvGreeting?.text = greeting()
        _binding?.tvToday?.text = if (accounts.isEmpty()) {
            getString(R.string.home_today_no_account_fmt, dateLabel())
        } else {
            getString(R.string.home_today_fmt, dateLabel(), accounts.size)
        }

        // 标题带账号数量（原来只有「账号」两个字，还得自己数）
        _binding?.tvAccountsTitle?.text =
            if (accounts.isEmpty()) {
                getString(R.string.accounts_label)
            } else {
                getString(R.string.accounts_count_fmt, accounts.size)
            }

        // Cookie 有效期面板：账号为空时它也要渲染（显示空态并引导去添加账号）
        renderCookieValidity(accounts, panelReady)
        accountState.value = accounts.map(::buildAccountRow)
        renderTopicProgress(accounts, panelReady)
    }

    // ========================= Cookie 有效期面板 =========================

    /**
     * 逐账号展示面板端 cookie 的有效期。
     *
     * 数据来源：`AccountStore` 的真实登录/换发/首次录入锚点、服务端声明到期（ALF）
     * 与 cookie 本身，算法在 [CookieLife]。同步时刻、探活时刻只展示，不参与寿命计算；
     * 这里只负责把结论翻译成「登录/同步/服务端到期/推算到期」，并在失效/临期/过期时标红。
     *
     * **为什么红色要铺到昵称和副行，而不只是那个小 pill**：用户扫的是「用户名那一行
     * 还剩几天」，只有 pill 变色时，眼睛会把它当成状态标签跳过；整行红字才会拉住视线。
     */
    private fun renderCookieValidity(accounts: List<AccountStore.Account>, panelReady: Boolean) {
        val hint = getString(
            if (panelReady) R.string.sync_card_hint else R.string.sync_card_hint_off
        )
        if (accounts.isEmpty()) {
            cookieState.value = CookieCardUi(
                pillText = getString(R.string.sync_pill_unknown),
                pillTone = PillTone.NEUTRAL,
                hint = hint,
                showConfig = !panelReady,
                emptyText = getString(R.string.sync_empty),
                rows = emptyList(),
            )
            return
        }

        val now = System.currentTimeMillis()
        // 需要处理的排前面（失效 → 已过期 → 临期 → 未同步 → 正常），同档保持账号原顺序：
        // 这块卡存在的意义是「告诉我现在该动谁」，需要动手的不该埋在列表末尾
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

        // 卡头只讲「最坏的那一个」：一个号过期，面板上那个号就已经掉登录了
        val (pillText, pillTone) = when {
            !panelReady -> getString(R.string.sync_pill_off) to PillTone.NEUTRAL
            dead > 0 -> getString(R.string.sync_pill_dead_fmt, dead) to PillTone.DANGER
            expired > 0 -> getString(R.string.sync_pill_expired_fmt, expired) to PillTone.DANGER
            expiring > 0 -> getString(R.string.sync_pill_warn_fmt, expiring) to PillTone.DANGER
            unsynced == accounts.size -> getString(R.string.sync_pill_unknown) to PillTone.NEUTRAL
            unsynced > 0 -> getString(R.string.sync_pill_unsynced_fmt, unsynced) to PillTone.WARN
            else -> getString(R.string.sync_pill_ok) to PillTone.OK
        }

        cookieState.value = CookieCardUi(
            pillText = pillText,
            pillTone = pillTone,
            hint = hint,
            showConfig = !panelReady,
            emptyText = "",
            rows = rows.map { (acc, life) ->
                val danger = acc.cookieDead || life.alert
                val expiryText = if (acc.cookieDead) {
                    getString(R.string.cookie_dead_hint)
                } else {
                    val base = expiryLine(life, panelReady)
                    if (acc.cookieLastError.isNotEmpty()) {
                        base + "\n" + getString(R.string.cookie_last_error_fmt, acc.cookieLastError)
                    } else {
                        base
                    }
                }
                val (label, tone) = when {
                    acc.cookieDead -> getString(R.string.cookie_dead_pill) to PillTone.DANGER
                    life.state == CookieLife.State.EXPIRED ->
                        getString(R.string.cookie_expired) to PillTone.DANGER
                    life.state == CookieLife.State.EXPIRING ->
                        cookieLeftLabel(life) to PillTone.DANGER
                    life.state == CookieLife.State.OK -> cookieLeftLabel(life) to PillTone.OK
                    else -> getString(R.string.cookie_unsynced) to PillTone.NEUTRAL
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

    /** 剩余天数文案：满 1 天给整数天，不足 1 天单独说，别显示「剩余 0 天」。 */
    private fun cookieLeftLabel(life: CookieLife.View): String {
        val days = life.daysLeft ?: return getString(R.string.cookie_unsynced)
        return when {
            // 已过期要说清过了多久：过期 1 天和过期 20 天的处置紧迫度完全不同
            days <= -1 -> getString(R.string.cookie_expired_days_fmt, -days.toInt())
            days <= 0 -> getString(R.string.cookie_expired)
            days < 1 -> getString(R.string.cookie_left_today)
            else -> getString(R.string.cookie_left_days_fmt, days.toInt())
        }
    }

    /**
     * 副行文案：真实登录时间与同步时间分开，服务端到期与推算到期分开展示。
     *
     * 「登录于 10月30日 21:40 · 同步于 11月2日 09:00
     *   服务端到期 11月6日 21:40 · 推算到期 11月6日 21:40」
     *
     * 旧数据（没有真实登录/换发锚点）会注明「按同步时间兜底」，不再让同步时间冒充登录时间。
     */
    private fun expiryLine(life: CookieLife.View, panelReady: Boolean): String {
        if (!life.known) {
            return getString(
                if (panelReady) R.string.cookie_unknown_hint else R.string.cookie_unknown_hint_off
            )
        }
        val login = if (life.loginAt > 0L) {
            getString(R.string.cookie_issued_at_fmt, formatStamp(life.loginAt))
        } else {
            getString(R.string.cookie_login_unknown)
        }
        val sync = if (life.syncedAt > 0L) {
            getString(R.string.cookie_synced_at_fmt, formatStamp(life.syncedAt))
        } else {
            getString(R.string.cookie_never_synced)
        }
        val server = if (life.serverExpiresAt > 0L) {
            getString(R.string.cookie_server_expiry_fmt, formatStamp(life.serverExpiresAt))
        } else {
            getString(R.string.cookie_server_expiry_unknown)
        }
        val estimated = getString(R.string.cookie_estimated_expiry_fmt, formatStamp(life.estimatedAt))
        val legacy = if (life.fromPanel) {
            "\n" + getString(R.string.cookie_legacy_anchor_note)
        } else {
            ""
        }
        val base = "$login · $sync\n$server · $estimated$legacy"
        return if (life.alert) base + "\n" + getString(R.string.cookie_renew_hint) else base
    }

    
    /**
     * 昵称兜底补拉：登录时资料接口没回昵称的账号，进首页后台补一次。
     *
     * 旧实现的补拉只挂在「超话状态刷新」里，于是从不刷新超话、也不签到的账号会
     * 长期停在「（未获取到昵称）」。现在与刷新解耦：只要列表里还有占位名，
     * 打开首页就补一次（同一批账号每进程只补一次，见 [nameBackfillFor]）。
     */
    private fun backfillMissingNames() {
        val ctx = context ?: return
        val pending = AccountStore.load(ctx).filter { AccountStore.needsNameResolve(it) }
        if (pending.isEmpty()) return
        val marker = pending.map { it.uid }.sorted().joinToString(",")
        if (marker == viewModel.nameBackfillFor) return
        viewModel.nameBackfillFor = marker

        AppScope.launch {
            var fixed = 0
            pending.forEach { acc ->
                try {
                    val verify = AccountMaintenanceService.probeAndPersist(ctx, acc)
                    if (!verify.login) return@forEach
                    val name = AccountMaintenanceService.resolveNameIfNeeded(acc, verify.cookie)
                    if (name.isEmpty()) return@forEach
                    AccountMaintenanceService.persistVerified(
                        ctx, acc, verify.cookie, name = name,
                    )
                    fixed++
                } catch (e: Exception) {
                    // 单账号失败不影响其它账号；下次进页面（或刷新超话）还会再试
                }
            }
            if (fixed > 0) postRender()
        }
    }

    /** 问候语：按本机时间分四档，只读系统时钟，不请求任何权限。 */
    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return getString(
            when (hour) {
                in 5..10 -> R.string.greeting_morning
                in 11..12 -> R.string.greeting_noon
                in 13..17 -> R.string.greeting_afternoon
                else -> R.string.greeting_evening
            }
        )
    }

    /** 应用栏日期，如「10月28日 · 周三」。 */
    private fun dateLabel(): String =
        SimpleDateFormat("M月d日 · EEE", Locale.CHINA).format(Date())

    /**
     * Hero 进度环：把所有已知账号的超话状态汇总成「已签 / 总数」。
     *
     * 只统计**状态已知**的账号（加载中或取失败的不计入），否则分母会在刷新过程中忽大忽小。
     * 一个都没拿到时显示占位值 —— 宁可显示「—」，也不显示一个假 0%。
     */
    private fun renderTopicProgress(accounts: List<AccountStore.Account>, panelReady: Boolean) {
        // 只统计**今天**取得的状态：跨天快照里的「已签」是昨天的结论，算进来会
        // 让进度环与待签角标都失真（最坏情况是待签 0，看起来"今天已经全签完了"）
        val known = topicStates.values.filter {
            !it.loading && it.error.isEmpty() && it.total > 0 && it.freshToday
        }
        val total = known.sumOf { it.total }
        val done = known.sumOf { it.done }
        val zero = total <= 0
        val summary = if (accounts.isEmpty()) {
            getString(R.string.home_summary_empty)
        } else {
            getString(
                R.string.home_summary_fmt,
                accounts.size,
                if (panelReady) getString(R.string.home_panel_ok)
                else getString(R.string.home_panel_off),
            )
        }
        heroState.value = HomeHeroUiState(
            ringValue = if (zero) getString(R.string.home_ring_value_empty)
            else getString(R.string.home_ring_value_fmt, done, total),
            pendingValue = if (zero) getString(R.string.home_metric_empty)
            else (total - done).toString(),
            pillAccounts = getString(R.string.home_pill_accounts_fmt, accounts.size),
            pillPanel = getString(
                if (panelReady) R.string.home_pill_panel_on else R.string.home_pill_panel_off,
            ),
            summary = summary,
            progress = if (zero) 0f else done.toFloat() / total,
        )
        // 底栏「超话」上的待签角标：不进那一页也能看到还有多少没签
        (activity as? MainActivity)?.setTopicBadge(if (zero) null else total - done)
    }

    /**
     * 头像首字（v0.7）：取昵称的第一个码点。
     *
     * 微博图文接口不返回头像，用「首字 + 品牌容器色圆底」代替，
     * 让多账号列表里每一行都有独立锚点，扫视时不必逐行读昵称。
     * 昵称还没补拉到时给一个中性占位字，避免显示成空白圆点。
     */
    private fun avatarInitial(acc: AccountStore.Account): String {
        if (AccountStore.needsNameResolve(acc)) return "微"
        val name = acc.name.trim().trimStart('@', '＠')
        if (name.isEmpty()) return "微"
        val first = name.codePointAt(0)
        return String(Character.toChars(first))
    }

    /** 删除账号（确认已由 Compose 账号行完成；只删本机登录态，不影响面板）。 */
    private fun deleteAccount(acc: AccountStore.Account) {
        val ctx = requireContext()
        AccountStore.remove(ctx, acc.uid)
        removeTopicState(acc.uid)
        TopicStore.clear(ctx, acc.uid)
        render()
    }

    /** 昵称优先；拿不到真实昵称时明确提示，而不是把 UID 当名字显示。 */
    private fun displayName(acc: AccountStore.Account): String =
        if (AccountStore.needsNameResolve(acc)) getString(R.string.home_name_unknown) else acc.name

    /**
     * 账号密码自动重登设置：录凭证 / 查看暂停与退避状态 / 手动立即重登 / 删除凭证。
     *
     * 密码输入后立即加密落盘，之后再打开弹窗也不回填密码；状态区会明确告诉用户
     * 上次失败原因、是否已暂停、下次允许重登的时间。
     */
    private fun showReloginDialog(acc: AccountStore.Account) {
        val ctx = requireContext()
        val hasCredential = CredentialVault.has(ctx, acc.uid)
        val state = AccountStore.reloginState(acc)
        val status = buildString {
            if (hasCredential) {
                append(
                    getString(
                        R.string.relogin_status_saved_fmt,
                        if (acc.credentialAt > 0L) formatStamp(acc.credentialAt) else "时间未知",
                    )
                )
            } else {
                append(getString(R.string.relogin_status_none))
            }
            when {
                state.blocked -> {
                    append("\n")
                    append(
                        getString(
                            R.string.relogin_status_blocked_fmt,
                            state.lastReason.ifEmpty { ReloginPolicy.reasonText(state.lastCode) },
                        )
                    )
                }
                state.nextAt > 0L -> {
                    append("\n")
                    append(
                        getString(
                            R.string.relogin_status_next_fmt,
                            state.failStreak,
                            formatStamp(state.nextAt),
                        )
                    )
                }
                hasCredential -> {
                    append("\n")
                    append(getString(R.string.relogin_status_ready))
                }
            }
        }
        reloginDialogState.value = ReloginDialogUi(acc.uid, status, hasCredential)
    }

    /** 保存凭证并立即尝试一次手动重登；返回是否保存成功（Compose 弹窗据此决定是否关闭）。 */
    private fun saveReloginCredentials(uid: String, username: String, password: String): Boolean {
        val ctx = requireContext()
        if (username.isEmpty() || password.isEmpty()) {
            toast(getString(R.string.relogin_need_both))
            return false
        }
        try {
            // 明文只在内存里停留这一次；落盘即密文
            CredentialVault.save(ctx, uid, username, password)
            AccountStore.setCredentialAt(ctx, uid, System.currentTimeMillis())
            AccountStore.setAccountAutoRelogin(ctx, uid, true)
        } catch (e: Exception) {
            toast(getString(R.string.relogin_failure_fmt, "凭证加密保存失败"))
            return false
        }
        toast(getString(R.string.relogin_saved))
        AccountStore.load(ctx).firstOrNull { it.uid == uid }?.let { runManualRelogin(it) }
        return true
    }

    private fun deleteReloginCredentials(uid: String) {
        val ctx = requireContext()
        CredentialVault.clear(ctx, uid)
        AccountStore.saveReloginState(ctx, uid, ReloginPolicy.State(credentialAt = 0L))
        AccountStore.setAccountAutoRelogin(ctx, uid, false)
        toast(getString(R.string.relogin_deleted))
        render()
    }

    /** 前台手动重登：force 越过退避/次数上限，但密码错误、验证码等硬停仍会被策略拒绝。 */
    private fun runManualRelogin(acc: AccountStore.Account) {
        val ctx = requireContext().applicationContext
        Toast.makeText(ctx, R.string.relogin_running, Toast.LENGTH_SHORT).show()
        AppScope.launch {
            val outcome = AutoRelogin.tryAutoRelogin(ctx, acc, "manual", force = true)
            withContext(Dispatchers.Main + NonCancellable) {
                val text = if (outcome.ok) {
                    ctx.getString(R.string.relogin_success)
                } else {
                    ctx.getString(R.string.relogin_failure_fmt, outcome.message)
                }
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
                if (isAdded) render()
            }
        }
    }

    /**
     * 账号行的状态可视化。
     *
     * 超话状态已知时用「已签 / 待签」两个 chip 表示（成功=绿、待签=琥珀），
     * 状态未知（未刷新 / 刷新中 / 刷新失败 / 没关注超话）时退化成一行说明文字，
     * 不再把所有信息拼成一条逗号长句。
     *
     * 第一行答的是「**今天**签了吗」，证据按 [AccountStatusHead] 的顺序取：
     * 今日超话快照 → 今天写的小结 → 更早的小结（标明「上次」）→ 尚未签到。
     * 不再无条件拿 `lastResult` 当今日结论 —— 那个字段只在**本机发起过签到**时才写，
     * 打开 App 自动刷新出来的「已签 7 / 待签 0」不会写它，于是同一行里
     * chip 说签完、文字说「尚未签到」。
     */
    /** 把一个账号的当前状态翻译成 Compose 行模型（头像/状态 chip/说明）。 */
    private fun buildAccountRow(acc: AccountStore.Account): AccountRowUi {
        val st = topicStates[acc.uid]
        // 「已签 / 待签」是**今天**的结论：跨天的快照不参与
        val loaded = st != null && !st.loading && st.error.isEmpty() && st.total > 0 && st.freshToday
        val chips = if (st != null && loaded) {
            val maxLevel = st.topics.maxOfOrNull { it.level } ?: 0
            AccountChipsUi(
                level = if (maxLevel > 0) {
                    getString(R.string.account_chip_level_fmt, maxLevel)
                } else {
                    null
                },
                done = getString(R.string.account_chip_done_fmt, st.done),
                pending = getString(R.string.account_chip_pending_fmt, st.total - st.done),
            )
        } else {
            null
        }
        val note = when {
            st == null -> getString(R.string.home_topics_idle)
            st.loading -> getString(R.string.home_topics_loading)
            st.error.isNotEmpty() -> getString(R.string.home_topics_failed, st.error)
            st.total == 0 -> getString(R.string.home_topic_none)
            !st.freshToday -> getString(R.string.home_topics_stale_fmt, topicStateTime(st.atMs))
            else -> getString(R.string.home_topics_updated_fmt, topicStateTime(st.atMs))
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
                getString(R.string.home_status_today_all_fmt, head.done, head.total)
            is AccountStatusHead.Head.TodayLeft ->
                getString(
                    R.string.home_status_today_left_fmt,
                    head.done, head.total, head.total - head.done,
                )
            is AccountStatusHead.Head.LastToday -> head.result
            is AccountStatusHead.Head.LastEarlier -> if (head.atMs > 0L) {
                getString(R.string.home_status_last_fmt, head.result, topicStateTime(head.atMs))
            } else {
                getString(R.string.home_status_last_plain_fmt, head.result)
            }
            AccountStatusHead.Head.Idle -> getString(R.string.home_status_idle)
        }
        val tone = when {
            loaded && st != null && st.done >= st.total -> AvatarTone.OK
            loaded && st != null && st.done < st.total -> AvatarTone.WARN
            else -> AvatarTone.BRAND
        }
        return AccountRowUi(
            uid = acc.uid,
            name = displayName(acc),
            uidText = getString(R.string.home_uid_fmt, acc.uid),
            avatarInitial = avatarInitial(acc),
            avatarTone = tone,
            statusText = headText + "\n" + note,
            chips = chips,
        )
    }

    // ========================= 日志（可滚动，不打断阅读） =========================

    /**
     * 追加一行日志（渲染由 Compose 日志卡负责：只有原本贴底时才跟随滚动）。
     *
     * 缓冲 [logLines] 在 ViewModel 里，**不随视图销毁**：旋转/切页期间签到还在跑，
     * 历史和当前日志都不能丢。
     */
    private fun log(line: String) {
        synchronized(viewModel.logLines) {
            viewModel.logLines.addLast(line)
            while (viewModel.logLines.size > LOG_MAX_LINES) viewModel.logLines.removeFirst()
        }
        // 日志大多从 AppScope(IO) 追加，统一切主线程再推状态
        view?.post { pushLogState() }
    }

    /** 展开 / 收起日志区；[logExpanded] 记住用户选择，视图重建后恢复。 */
    private fun setLogExpanded(expanded: Boolean) {
        logExpanded = expanded
        pushLogState()
    }

    private fun pushLogState() {
        val lines = synchronized(viewModel.logLines) { viewModel.logLines.toList() }
        logState.value = LogCardUi(
            expanded = logExpanded,
            lines = lines,
            summary = lines.lastOrNull { it.isNotBlank() } ?: getString(R.string.log_empty),
        )
    }

    private fun now(): String = formatTime(System.currentTimeMillis())

    private fun toast(text: String) = toastShort(text)

    /**
     * 运行态切换。
     *
     * **刻意不置灰「立即签到」**：MaterialButton 被 `isEnabled=false` 后连点击事件都不派发，
     * 用户点下去不弹 toast、不展开日志、什么都不发生 —— 正是「点了没反应」的观感，
     * 而且看不出是被禁用还是 App 卡住了。现在主按钮始终可点，按钮文案直接改成
     * 「正在签到…」说明状态；重复点按由 busy 闸门 + toast 兜住。
     * 其余按钮同理不置灰：它们的入口自带闸门与新账号合并逻辑，无需靠禁用防重入。
     */
    private fun setBusy(value: Boolean) {
        viewModel.busy = value
        renderRunButtons()
    }

    /**
     * 按当前 busy / busySyncing 重设两枚运行按钮的文案。
     *
     * 必须能脱离 setBusy 单独调用：setBusy 在 view == null 时会提前返回，
     * 而此时 busy 已经改了 —— 旋转屏幕 / 切页回来重建视图后，按钮还显示
     * 「立即签到」，真实状态却是在跑（点了只弹「正忙」）。
     * 所以 render 里也要调一次，按真实状态回填。
     */
    private fun renderRunButtons() {
        val host = _binding ?: return
        val running = viewModel.busy
        // 两枚按钮各自说自己正在干什么：同步时把「立即签到」改成「正在签到…」会误导
        val syncing = running && viewModel.busySyncing
        host.btnCheckin.apply {
            text = getString(
                if (running && !syncing) R.string.btn_checkin_running else R.string.btn_checkin
            )
            isEnabled = true
        }
        host.btnSync.apply {
            text = getString(if (syncing) R.string.btn_sync_running else R.string.btn_sync)
            isEnabled = true
        }
    }

    private fun setTopicBusy(value: Boolean) {
        viewModel.topicBusy = value
    }

    // ========================= 超话状态刷新 =========================

    /**
     * 打开 App 时自动刷新一次超话状态。
     *
     * **只在进程内第一次进入首页时触发**：以前每次 `onResume` / 切回 Tab 都会重拉，
     * 一个账号一次列表请求（关注 60+ 超话要翻 6 页），来回切几次就是几十个请求，
     * 既慢又白白消耗账号频次。现在切 Tab 只重绘内存快照，需要最新状态时点「刷新超话」。
     *
     * 跨天场景仍会被覆盖：进程重启后这里的闸门归零，会重新拉一次。
     */
    private fun autoRefreshTopicsOnOpen() {
        if (viewModel.topicsAutoHandled) return
        viewModel.topicsAutoHandled = true
        if (AccountStore.load(requireContext()).isEmpty()) return
        // 有任务在跑就不必再刷：签到会顺带把超话列表拉一遍（见 CheckinService.runAccount）
        if (viewModel.busy || viewModel.topicBusy) return
        refreshTopics(manual = false)
    }

    /**
     * 拉取全部账号的超话签到状态。逐个账号串行（每个账号内部再按页限速），
     * 每拉完一个就刷新一次列表，用户能立刻看到先完成的账号。
     */
    private fun refreshTopics(manual: Boolean) {
        if (!isAdded) return
        val ctx = requireContext()
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) {
            if (manual) toast(getString(R.string.home_no_account))
            return
        }
        if (viewModel.busy || viewModel.topicBusy) {
            if (manual) toast(getString(R.string.busy)) else pendingAutoRefresh = true
            return
        }
        setTopicBusy(true)
        // 手动刷新才展开日志；页面打开时的自动刷新不打扰首屏
        if (manual) setLogExpanded(true)
        accounts.forEach { acc ->
            setTopicState(acc.uid, (topicStates[acc.uid] ?: TopicState()).copy(loading = true, error = ""))
        }
        render()
        log("==== 刷新超话状态 · ${now()} ====")

        viewModel.topicJob = AppScope.launch {
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
                        // 只有「探活明确判未登录」才记失效；抛异常（断网/风控）不代表号掉了
                        if (verify != null) AccountStore.markDead(ctx, acc.uid)
                        // 用旧快照 copy：整条 TopicState 新建会把 topics 冲成空表，
                        // 首页「待签 N」角标会在一次失败刷新后瞬间清零
                        setTopicState(
                            acc.uid,
                            (topicStates[acc.uid] ?: TopicState()).copy(
                                atMs = System.currentTimeMillis(),
                                error = ctx.getString(R.string.home_cookie_dead),
                                loading = false,
                            )
                        )
                        TopicStore.saveError(ctx, acc.uid, ctx.getString(R.string.home_cookie_dead))
                        log("  ✗ $label：${ctx.getString(R.string.home_cookie_dead)}")
                        postRender()
                        return@forEachIndexed
                    }
                    updateCookieAndName(ctx, acc.uid, verify.cookie)
                    val topics = try {
                        WeiboTopicApi.listTopics(verify.cookie)
                    } catch (e: Exception) {
                        setTopicState(
                            acc.uid,
                            (topicStates[acc.uid] ?: TopicState()).copy(
                                atMs = System.currentTimeMillis(),
                                error = e.message ?: "超话列表失败",
                                loading = false,
                            )
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
                            "待签 ${topics.count { !it.done }}"
                    )
                    postRender()
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    setTopicBusy(false)
                    if (isAdded) {
                        render()
                        // 刷新期间被推迟的请求（例如自动刷新撞上签到）→ 补一轮，仍只补一次
                        if (pendingAutoRefresh) {
                            pendingAutoRefresh = false
                            viewModel.topicsAutoHandled = true
                            if (!viewModel.busy) refreshTopics(manual = false)
                        }
                    }
                }
            }
        }
    }

    /** 视图可能已销毁，统一切回主线程再渲染。 */
    private fun postRender() {
        view?.post { if (isAdded) render() }
    }

    /**
     * 顺手把轮换后的 cookie 写回，并在昵称仍是占位值时补拉真实用户名。
     *
     * 写回走 [AccountStore.markVerified]（内部持锁，且顺带推进「最近一次探活有效」
     * 时间锚点）：这里跑在 IO 线程，两步之间随时可能被签到收尾的 mergeSave 插进来，
     * 「load 一份旧表 → 改 → save 覆盖」的旧写法会把刚轮换的 cookie 又写回去。
     */
    private fun updateCookieAndName(ctx: Context, uid: String, cookie: String) {
        val current = AccountStore.load(ctx).firstOrNull { it.uid == uid } ?: return
        val name = AccountMaintenanceService.resolveNameIfNeeded(current, cookie)
        AccountMaintenanceService.persistVerified(ctx, current, cookie, name = name)
    }

    // ========================= 签到 / 同步 =========================

    /** @return 是否真的启动了（被 viewModel.busy 拦下或没有账号时返回 false，供自动签到判定）。 */
    private fun runAll(auto: Boolean): Boolean {
        if (viewModel.busy) {
            // busy 只是界面态：真在跑才拦，否则（协程被取消后残留的 latch）归位后照常开跑
            if (viewModel.taskJob?.isActive == true) {
                toast(getString(R.string.busy))
                return false
            }
            setBusy(false)
        }
        val ctx = requireContext()
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) {
            // 空账号是「点了没反应」的另一条静默出口：日志卡默认收起，
            // 只写日志等于什么都没说 —— 展开日志并弹一句可读的提示。
            setLogExpanded(true)
            log(getString(R.string.home_no_account))
            toast(getString(R.string.home_no_account))
            return false
        }
        setBusy(true)
        setLogExpanded(true)            // 运行时自动展开日志，进度可见
        // 挂在 AppScope：一整轮签到可能几分钟，旋转 / 切页都不该把它砍断
        viewModel.taskJob = AppScope.launch {
            try {
                log("==== ${if (auto) "打开自动" else "手动"}签到 · ${now()} ====")
                CheckinService.runAll(
                    ctx = ctx,
                    accounts = accounts,
                    log = ::log,
                    cookieDeadText = getString(R.string.home_cookie_dead),
                    onTopics = { uid, topics ->
                        setTopicState(
                            uid,
                            TopicState(topics = topics, atMs = System.currentTimeMillis()),
                        )
                        postRender()
                    },
                    onAccountDone = { postRender() },
                )
                log("==== 全部完成 · ${now()} ====")
                if (CrawlSettings.crawlAuto(ctx)) {
                    try {
                        // 与图库页手动抓取共用闸门：正跑着就静默跳过，自动流程不该弹提示
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
                // 这里以前没有 catch：markVerified / log 抛出的异常会直接冒到线程默认
                // 处理器 —— 进程崩溃，日志区连一行痕迹都没有。现在至少留下原因。
                log("签到异常终止：${e.message ?: e.javaClass.simpleName}")
            } finally {
                // finally 里先做「必须完成」的事：落盘失败也绝不能连带跳过下面的状态复位，
                // 否则 busy 会永久为 true，按钮从此对点击无反应。
                try {
                    AccountStore.mergeSave(ctx, accounts)
                } catch (e: Exception) {
                    log("保存账号失败：${e.message}")
                }
                withContext(Dispatchers.Main + NonCancellable) {
                    setBusy(false)
                    if (isAdded) {
                        render()
                        if (pendingAutoRefresh) {
                            pendingAutoRefresh = false
                            if (!viewModel.topicBusy) refreshTopics(manual = false)
                        }
                    }
                }
            }
        }
        return true
    }

    /**
     * 把一个账号的 cookie 推送到面板（由「Cookie 同步」与后台保活触发）。
     *
     * 成功后记一笔 [AccountStore.markSynced]：面板端每次同步都会把 cookie 的生命周期
     * 基准重置成此刻（`database.update_account` 的行为），首页那张卡的「还剩几天」
     * 就是从这个时刻往后数 7 天。**不记这一笔，界面会继续显示旧的到期时间**。
     *
     * @return 是否同步成功
     */
    private suspend fun syncToPanel(ctx: Context, acc: AccountStore.Account): Boolean {
        val r = AccountMaintenanceService.syncToPanel(ctx, acc)
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

    // ========================= 超话清单（完整展示 + 逐条状态） =========================

    /** 点按账号行：重新拉一次该账号的超话，然后弹出完整清单。 */
    private fun showTopics(uid: String) {
        if (viewModel.busy || viewModel.topicBusy) {
            toast(getString(R.string.busy))
            return
        }
        val ctx = requireContext()
        val acc = AccountStore.load(ctx).firstOrNull { it.uid == uid } ?: return
        setTopicBusy(true)
        setLogExpanded(true)
        log("── 查询 ${acc.name.ifEmpty { acc.uid }} 的超话 ──")
        viewModel.topicJob = AppScope.launch {
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
                        if (isAdded) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(getString(R.string.home_topic_title, acc.name.ifEmpty { acc.uid }))
                                .setMessage(R.string.home_cookie_dead)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    return@launch
                }
                updateCookieAndName(ctx, uid, verify.cookie)
                val topics = WeiboTopicApi.listTopics(verify.cookie)
                setTopicState(uid, TopicState(topics = topics, atMs = System.currentTimeMillis()))
                TopicStore.save(ctx, uid, topics)
                log("  ${topics.size} 个超话，已签 ${topics.count { it.done }}")
                withContext(Dispatchers.Main + NonCancellable) {
                    if (isAdded) {
                        render()
                        presentTopics(uid, verify.st, topics)
                    }
                }
            } catch (e: Exception) {
                log("  ✗ 超话查询失败：${e.message}")
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    if (isAdded) setTopicBusy(false) else viewModel.topicBusy = false
                }
            }
        }
    }

    /**
     * 超话清单：**全部超话逐条列出**并标注已签/待签。
     * 已签项勾选且不可再选（防重复签到），待签项可勾选后一次性补签。
     */
    private fun presentTopics(uid: String, st: String?, topics: List<WeiboTopicApi.Topic>) {
        if (!isAdded) return
        val name = AccountStore.load(requireContext()).firstOrNull { it.uid == uid }
            ?.let { displayName(it) } ?: uid
        topicsDialogState.value = TopicsDialogUi(uid, st, name, topics)
    }

    /**
     * 勾选补签：与主签到同节奏（3-8 秒随机间隔）。补完只重拉超话状态，
     * **不同步面板**（同步是独立动作，见 [syncOnly]）。
     */
    private fun resignTopics(uid: String, st: String?, topics: List<WeiboTopicApi.Topic>) {
        if (topics.isEmpty()) return
        if (viewModel.busy) {
            if (viewModel.taskJob?.isActive == true) {
                toast(getString(R.string.busy))
                return
            }
            setBusy(false)
        }
        val ctx = requireContext()
        setBusy(true)
        setLogExpanded(true)
        log("── 补签 ${topics.size} 个超话 ──")
        viewModel.taskJob = AppScope.launch {
            try {
                val acc = AccountStore.load(ctx).firstOrNull { it.uid == uid }
                if (acc == null) {
                    log("  ✗ 账号已不存在")
                    return@launch
                }
                // 弹窗打开时抓的 st / scheme 有时效：用户会犹豫、会切后台，可能已经过期。
                // 补签前先 verify 一次换新 cookie + 新 st（与 TopicFragment.checkin 同口径），
                // 否则整批都会失败在「登录状态异常」上，还要白等风控间隔。
                var cookie = acc.cookie
                var freshSt = st
                try {
                    val v = AccountMaintenanceService.probeAndPersist(ctx, acc)
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
                for (topic in topics) {
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
                val summary = "补签 $ok/${topics.size}" +
                    (if (fail > 0) "（失败 $fail）" else "") + " · ${now()}"
                saveLastResult(ctx, uid, summary)
                log("  小结：$summary")
                // 补签后立刻重拉一次状态，让清单不再显示旧的「待签」
                setTopicState(uid, (topicStates[uid] ?: TopicState()).copy(loading = true))
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    setBusy(false)
                    if (isAdded) {
                        render()
                        refreshTopics(manual = false)
                    }
                }
            }
        }
    }

    /** 只写一行「上次结果」，不整表覆盖（整表写会把并发轮换的 cookie 顶掉）。 */
    private fun saveLastResult(ctx: Context, uid: String, result: String) {
        AccountStore.setLastResult(ctx, uid, result)
    }

    private fun syncOnly() {
        if (viewModel.busy) {
            if (viewModel.taskJob?.isActive == true) {
                toast(getString(R.string.busy))
                return
            }
            setBusy(false)
        }
        val ctx = requireContext()
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) {
            setLogExpanded(true)
            log(getString(R.string.home_no_account))
            toast(getString(R.string.home_no_account))
            return
        }
        viewModel.busySyncing = true
        setBusy(true)
        setLogExpanded(true)
        viewModel.taskJob = AppScope.launch {
            try {
                log("==== Cookie 同步 · ${now()} ====")
                var ok = 0
                for (acc in accounts) {
                    log("── ${acc.name.ifEmpty { acc.uid }} ──")
                    try {
                        val verify = AccountMaintenanceService.probeAndPersist(ctx, acc)
                        if (!verify.login) {
                            CheckinService.noteResult(
                                ctx, acc, ctx.getString(R.string.home_cookie_dead),
                            )
                            AccountStore.markDead(ctx, acc.uid)
                            log("  ✗ ${ctx.getString(R.string.home_cookie_dead)}")
                            continue
                        }
                        // 探活成功也走一次落盘：同步失败的账号至少把「最近有效」锚点更新掉，
                        // 否则它会一直停在过期的红字上，看起来像没点过同步
                        if (syncToPanel(ctx, acc)) ok++
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
                    viewModel.busySyncing = false
                    setBusy(false)
                    if (isAdded) render()
                }
            }
        }
    }
}
