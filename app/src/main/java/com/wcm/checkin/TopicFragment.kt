package com.wcm.checkin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 超话页（v0.9.0）：把接口能读到的超话信息集中展示。
 *
 * 一屏讲三件事：
 * 1. **概览**：关注多少、今天签了多少、还差多少（Hero 三列 + 占比进度条）；
 * 2. **逐条**：每条超话的等级徽章、名称、签到状态，可按状态/等级筛选、按等级排序；
 * 3. **详情**：点开某条才去拉简介、关注数、帖子数 —— 列表页因此只需要 1~2 次请求。
 *
 * 数据来源与缓存策略：
 * - 列表来自 `listTopics`（`100803_-_followsuper`），一次拉全量后落地到 [TopicStore]，
 *   断网/冷启动时先显示缓存（副标题标「离线」），再后台刷新；
 * - 详情来自 `100808<hash>` 主页接口，按超话 ID 缓存 30 分钟，且只在用户点开时请求；
 * - 缓存里的签到链接恒为空（会过期），所以签到前会重新拉一次列表取新链接。
 */
class TopicFragment : Fragment() {

    // ---------------- 状态 ----------------

    /** "" = 全部账号；否则为某个账号的 uid。 */
    private var currentUid = ""
    private var filter = TopicFilter.ALL
    private var sort = TopicSort.PENDING_FIRST
    private var loading = false

    /**
     * 展开态 / 详情加载态，键是 `uid + \u0000 + topic.key`。
     *
     * 只按超话 key 存会在「全部账号」模式下串号：同一个超话被两个账号关注时，
     * 展开一条会把另一条也展开，详情加载态也会互相覆盖。
     */
    private val expanded = HashSet<String>()
    private val detailBusy = HashSet<String>()
    private val detailErrors = HashMap<String, String>()

    private fun rowKey(uid: String, key: String): String = uid + "\u0000" + key

    /** 从首页跳进来时暂存的账号；视图还没建好就先记着。 */
    private var pendingUid: String? = null

    private var lastAutoRefreshAt = 0L

    /** 「全部账号」且账号数 > 1：列表副行需要标出这条属于哪个账号（不重复读 prefs）。 */
    private var multiAccountMode = false

    /** Compose 整屏状态；[render] 是唯一写入点。 */
    private val uiState = MutableStateFlow(TopicUiState())

    /** 当前屏幕上的行，回调按 `uid + \u0000 + key` 找回原始数据。 */
    private var rowsByKey: Map<String, TopicRow> = emptyMap()

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    /** 跨天用的格式器：SimpleDateFormat 构造不便宜，不要每次渲染都 new 一个。 */
    private val dateFmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

    /** 当前打开的只读弹窗（原始 JSON / 字段说明），视图销毁时手动关掉。 */
    private var textDialog: androidx.appcompat.app.AlertDialog? = null

    /** 列表行：带上账号，全部账号模式下签到才知道该用谁的 cookie。 */
    data class TopicRow(
        val uid: String,
        val accountName: String,
        val topic: WeiboTopicApi.Topic,
    )

    companion object {
        /** 缓存超过这个时间就自动刷新一次（打开页面时判断，不是定时任务）。 */
        private const val AUTO_REFRESH_AFTER_MS = 10 * 60_000L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = ComposeView(requireContext())
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent {
            val state by uiState.collectAsState()
            WcmTheme {
                TopicScreen(
                    state = state,
                    onRefresh = { refresh(manual = true) },
                    onOverflowField = {
                        showTextDialog(
                            getString(R.string.topic_field_title),
                            getString(R.string.topic_field_body),
                        )
                    },
                    onOverflowRefresh = { refresh(manual = true) },
                    onSelectAccount = {
                        currentUid = it
                        render()
                    },
                    onFilter = {
                        filter = it
                        render()
                    },
                    onSort = {
                        sort = it
                        render()
                    },
                    onRowClick = { ui -> rowFor(ui)?.let { toggleDetail(it) } },
                    onRowCheckin = { ui -> rowFor(ui)?.let { checkin(it) } },
                    onRowCopy = { ui -> rowFor(ui)?.let { copySignLink(it) } },
                    onRowOpen = { ui -> rowFor(ui)?.let { openInWeibo(it) } },
                    onRowJson = { ui -> rowFor(ui)?.let { showRawJson(it) } },
                    onEmptyAction = { (activity as? MainActivity)?.selectHomeTab() },
                    onRetry = { refresh(manual = true) },
                )
            }
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        TopicStore.ensureLoaded(requireContext())
        pendingUid?.let {
            currentUid = it
            pendingUid = null
        }
        render()
        maybeAutoRefresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            render()
            maybeAutoRefresh()
        }
    }

    override fun onDestroyView() {
        // 视图没了就把自建弹窗关掉，否则窗口挂着已 detach 的 Activity → WindowLeaked
        textDialog?.dismiss()
        textDialog = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) render()
    }

    /** 首页点账号行 / 进度环时调用：切到指定账号。 */
    fun selectAccount(uid: String) {
        currentUid = uid
        if (view != null) render() else pendingUid = uid
    }

    private fun rowFor(ui: TopicRowUi): TopicRow? = rowsByKey[rowKey(ui.uid, ui.key)]

    // ========================= 刷新 =========================

    private fun maybeAutoRefresh() {
        if (!isAdded || loading) return
        // 本页是「全部 add + hide/show」式 tab：隐藏时 onViewCreated 照样执行，
        // 于是每建一次超话页都会先打一轮接口，和首页 runAll 的自动刷新撞车。
        if (isHidden) return
        val ctx = requireContext()
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

    private fun refresh(manual: Boolean) {
        if (!isAdded) return
        if (loading) {
            if (manual) toast(getString(R.string.busy))
            return
        }
        val ctx = requireContext()
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) {
            render()
            return
        }
        loading = true
        lastAutoRefreshAt = System.currentTimeMillis()
        render()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                accounts.forEachIndexed { index, acc ->
                    if (index > 0) delay(800)   // 多账号之间留间隔，避免被风控
                    loadAccount(ctx, acc)
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    if (isAdded) {
                        loading = false
                        render()
                    } else {
                        loading = false
                    }
                }
            }
        }
    }

    /** 拉一个账号的超话列表并落缓存；失败只写错误，不清掉上次的数据。 */
    private suspend fun loadAccount(ctx: Context, acc: AccountStore.Account) {
        try {
            val verify = WeiboAuthApi.verify(acc.cookie)
            if (!verify.login) {
                TopicStore.saveError(ctx, acc.uid, ctx.getString(R.string.home_cookie_dead))
                return
            }
            rotateCookie(ctx, acc.uid, verify.cookie)
            val topics = WeiboTopicApi.listTopics(verify.cookie)
            TopicStore.save(ctx, acc.uid, topics)
        } catch (e: CancellationException) {
            // 取消不是失败：不拦住就会被写成「Job was cancelled」并落盘，
            // 重启后这条假错误还挂在快照上，用户以为账号出问题了。
            throw e
        } catch (e: Exception) {
            TopicStore.saveError(ctx, acc.uid, e.message ?: "超话列表失败")
        }
        withContext(Dispatchers.Main) { if (isAdded) render() }
    }

    /** 顺带把轮换后的 cookie 写回账号表（否则下一轮会用到过期的 SUB）。 */
    private fun rotateCookie(ctx: Context, uid: String, cookie: String) {
        if (cookie.isEmpty()) return
        // 必须走 patchAccount：只改 cookie 会漏掉 cookieDead = false 与各时间锚点，
        // 失效标记会一直挂着，CookieLife 的倒计时也会停在旧值上；而且整表 save()
        // 没有加 AccountStore.LOCK，会与首页/Worker 的 patchAccount 互相吞写
        AccountStore.markVerified(ctx, uid, cookie)
    }

    /**
     * 状态时间标签：今天只给 `HH:mm`，跨天补日期。
     *
     * 签到状态是按天的，只显示时分会让「昨天 20:15」和「今天 20:15」看起来一样，
     * 用户无法判断这份「已签」到底是哪天的。
     */
    private fun topicStateTime(at: Long): String = when {
        at <= 0L -> ""
        TopicStore.isToday(at) -> timeFmt.format(Date(at))
        else -> dateFmt.format(Date(at))
    }

    // ========================= 渲染 =========================

    private fun render() {
        if (!isAdded) return
        val ctx = requireContext()
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
        val dash = getString(R.string.topic_metric_empty)

        // 副标题：加载中 > 错误 > 跨天失效 > 更新时间
        val subtitle = when {
            loading -> getString(R.string.topic_subtitle_loading)
            error.isNotEmpty() -> getString(R.string.topic_subtitle_failed, error)
            staleDay -> getString(R.string.topic_subtitle_stale, atLabel)
            at > 0L -> getString(R.string.topic_subtitle_updated, atLabel)
            else -> getString(R.string.topic_subtitle_idle)
        }
        val status = when {
            accounts.isEmpty() -> getString(R.string.topic_empty_no_account)
            error.isNotEmpty() -> getString(R.string.topic_subtitle_failed, error)
            staleDay -> getString(R.string.topic_subtitle_stale, atLabel)
            at > 0L -> getString(R.string.topic_subtitle_updated, atLabel)
            else -> getString(R.string.topic_subtitle_idle)
        }

        // 空态 / 错误态：错误优先（否则会把"失败"伪装成"没有超话"）
        //
        // 判空必须按**筛选后**的 shown，不能按 rows：筛选命中 0 条时 rows 非空，
        // 于是既不显示空态、列表又显示空表 —— 整屏一片空白，而且
        // topic_empty_filter 那段文案永远走不到。
        val showError = shown.isEmpty() && error.isNotEmpty() && !loading
        val showEmpty = shown.isEmpty() && !showError && !loading
        val emptyText = when {
            accounts.isEmpty() -> getString(R.string.topic_empty_no_account)
            rows.isEmpty() -> getString(R.string.topic_empty_no_topic)
            else -> getString(R.string.topic_empty_filter)
        }

        val pills = if (accounts.size >= 2) {
            buildList {
                add(TopicPillUi("", getString(R.string.topic_account_all), currentUid.isEmpty()))
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
            TopicFilterUi(value, getString(labelRes), value == filter)
        }

        uiState.value = TopicUiState(
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
            rows = shown.map { buildRowUi(it, ctx) },
            showError = showError,
            errorText = getString(R.string.topic_subtitle_failed, error),
            showEmpty = showEmpty,
            emptyText = emptyText,
            emptyActionVisible = accounts.isEmpty(),
        )
    }

    private fun buildRowUi(row: TopicRow, ctx: Context): TopicRowUi {
        val topic = row.topic
        val stateKey = rowKey(row.uid, topic.key)
        val tail = when {
            topic.desc2.isNotEmpty() -> topic.desc2
            topic.topicId.isNotEmpty() -> getString(R.string.topic_meta_hint)
            else -> getString(R.string.topic_detail_none)
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
                    hint = getString(R.string.topic_detail_loading)
                }
                detail != null -> {
                    intro = detail.intro
                    stats = detail.stats.take(3).joinToString(" · ")
                    hint = buildString {
                        if (topic.topicId.isNotEmpty()) {
                            append(getString(R.string.topic_id_fmt, topic.topicId))
                            if (detail.fetchedAt > 0) append(" · ")
                        }
                        if (detail.fetchedAt > 0) {
                            append(
                                getString(
                                    R.string.topic_detail_fetched,
                                    timeFmt.format(Date(detail.fetchedAt)),
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
                        detailErrors[stateKey] != null -> getString(R.string.topic_detail_failed)
                        topic.topicId.isEmpty() -> getString(R.string.topic_detail_none)
                        else -> getString(R.string.topic_detail_failed)
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

    /**
     * 当前选择里是否有「不是今天」的快照。
     *
     * 不能只看最新的那份：全部账号模式下 A 是今天的、B 是昨天的，B 那几行显示的
     * 「已签」其实是昨天的结论 —— 只要有一份跨天就得提示，否则错的那半看不出来。
     */
    private fun anyStaleDay(
        accounts: List<AccountStore.Account>,
        snaps: Map<String, TopicStore.Snapshot>,
    ): Boolean {
        val picked = if (currentUid.isNotEmpty()) listOf(currentUid) else accounts.map { it.uid }
        return picked.any { uid ->
            val at = snaps[uid]?.at ?: 0L
            at > 0L && !TopicStore.isToday(at)
        }
    }

    private fun buildRows(
        accounts: List<AccountStore.Account>,
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

    // ========================= 展开详情 =========================

    private fun toggleDetail(row: TopicRow) {
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

        val ctx = requireContext()
        val cached = TopicStore.detail(ctx, topicId)
        if (cached != null && TopicStore.isFresh(cached.fetchedAt)) return
        loadDetail(ctx, row.uid, topicId, key)
    }

    /** 懒加载详情：只有用户点开时才发这一次请求（自带 1.2s 节流在 API 层）。 */
    private fun loadDetail(ctx: Context, uid: String, topicId: String, key: String) {
        val stateKey = rowKey(uid, key)
        if (!detailBusy.add(stateKey)) return
        detailErrors.remove(stateKey)
        render()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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
                if (isAdded) render()
            }
        }
    }

    // ========================= 签到 / 行操作 =========================

    private fun copySignLink(row: TopicRow) {
        val scheme = row.topic.scheme
        if (scheme.isNullOrEmpty()) {
            toast(getString(R.string.topic_copied_none))
            return
        }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("weibo", scheme))
        toast(getString(R.string.topic_copied))
    }

    private fun openInWeibo(row: TopicRow) {
        val id = row.topic.topicId
        val web = if (id.isNotEmpty()) {
            "https://m.weibo.cn/p/100808$id/super_index"
        } else {
            row.topic.scheme ?: return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, web.toUri()))
        } catch (e: Exception) {
            toast(getString(R.string.topic_open_fail))
        }
    }

    private fun showRawJson(row: TopicRow) {
        val body = row.topic.raw.ifEmpty { getString(R.string.topic_json_empty) }
        showTextDialog(getString(R.string.topic_json_title, row.topic.name), body)
    }

    /** 正在签到的超话（key = uid + 超话 key），防连点并发打同一批接口。 */
    private val checkinBusy = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 签到一个超话：缓存里的链接会过期，先按需重新取一次最新列表。 */
    private fun checkin(row: TopicRow) {
        if (!isAdded) return
        // 连点会起 N 个协程同时 verify + sign：多花风控额度，还会叠 N 个 Toast。
        // 详情拉取那边早就有 detailBusy，签到这条路径漏了。
        if (!checkinBusy.add(row.uid + "|" + row.topic.key)) {
            toast(getString(R.string.busy))
            return
        }
        val key = row.uid + "|" + row.topic.key
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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
                        rotateCookie(ctx, acc.uid, verify.cookie)
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
                                markDone(ctx, acc.uid, row.topic.key)
                                noteCheckin(ctx, acc.uid)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = ctx.getString(R.string.topic_checkin_fail_fmt, e.message ?: "异常")
            } finally {
                // 解闸必须无条件执行：checkinBusy 是 Fragment 级字段、onDestroyView 不清，
                // 取消（重抛 CancellationException）走不到下面的 withContext 就会永久泄漏，
                // 该超话之后每次点都只弹「正忙」。
                checkinBusy.remove(key)
            }
            withContext(Dispatchers.Main + NonCancellable) {
                if (isAdded) {
                    toast(message)
                    render()
                }
            }
        }
    }

    private fun markDone(ctx: Context, uid: String, key: String) {
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
    private fun noteCheckin(ctx: Context, uid: String) {
        val snap = TopicStore.snapshot(ctx, uid) ?: return
        if (snap.topics.isEmpty()) return
        // 单独 new 一个格式器：这里的调用点在 IO 线程，与 render() 的主线程共用
        // Fragment 级 timeFmt 会有并发风险（SimpleDateFormat 非线程安全）
        val at = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
        AccountStore.setLastResult(
            ctx, uid,
            ctx.getString(R.string.home_status_signed_fmt, snap.done, snap.total, at),
        )
    }

    // ========================= 弹窗 =========================

    /** 等宽只读弹窗：原始 JSON 与字段说明共用。 */
    private fun showTextDialog(title: String, body: String) {
        if (!isAdded) return
        val ctx = requireContext()
        // 用 requireContext 建的 Dialog 不归 Fragment 管：视图销毁（旋转/切页）时
        // 窗口还挂在已经 detach 的 Activity 上 → WindowLeaked。留个引用手动 dismiss。
        textDialog?.dismiss()
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val text = TextView(ctx).apply {
            this.text = body
            setTextIsSelectable(true)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.ink_2))
            setPadding(pad, pad / 2, pad, pad / 2)
            movementMethod = LinkMovementMethod.getInstance()
            gravity = Gravity.START
        }
        val scroll = ScrollView(ctx).apply {
            addView(text)
            setPadding(0, 0, 0, 0)
        }
        val height = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        scroll.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height,
        )
        textDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(text: String) {
        if (isAdded) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }
}