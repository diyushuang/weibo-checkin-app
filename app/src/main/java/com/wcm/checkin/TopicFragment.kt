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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
    private var filter = Filter.ALL
    private var sort = Sort.PENDING_FIRST
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

    /** 「全部账号」且账号数 > 1：列表副行需要标出这条属于哪个账号（bind 时不重复读 prefs）。 */
    private var multiAccountMode = false

    private lateinit var adapter: TopicAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyBox: View
    private lateinit var errorBox: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var pillsRow: LinearLayout
    private lateinit var filtersRow: LinearLayout

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    private enum class Filter { ALL, PENDING, DONE, LEVEL7 }
    private enum class Sort { PENDING_FIRST, LEVEL_DESC, NAME }

    /** 列表行：带上账号，全部账号模式下签到才知道该用谁的 cookie。 */
    data class TopicRow(
        val uid: String,
        val accountName: String,
        val topic: WeiboApi.Topic,
    )

    companion object {
        /** 缓存超过这个时间就自动刷新一次（打开页面时判断，不是定时任务）。 */
        private const val AUTO_REFRESH_AFTER_MS = 10 * 60_000L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_topic, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.topicList)
        emptyBox = view.findViewById(R.id.topicEmpty)
        errorBox = view.findViewById(R.id.topicError)
        progress = view.findViewById(R.id.prTopic)
        pillsRow = view.findViewById(R.id.topicAccountPills)
        filtersRow = view.findViewById(R.id.topicFilters)

        adapter = TopicAdapter()
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        view.findViewById<View>(R.id.btnTopicRefresh).setOnClickListener { refresh(manual = true) }
        view.findViewById<View>(R.id.btnTopicEmptyAction).setOnClickListener {
            (activity as? MainActivity)?.selectHomeTab()
        }
        view.findViewById<View>(R.id.btnTopicRetry).setOnClickListener { refresh(manual = true) }
        view.findViewById<View>(R.id.btnTopicSort).setOnClickListener { showSortMenu(it) }
        view.findViewById<View>(R.id.btnTopicMore).setOnClickListener { showOverflow(it) }

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

    override fun onResume() {
        super.onResume()
        if (!isHidden) render()
    }

    /** 首页点账号行 / 进度环时调用：切到指定账号。 */
    fun selectAccount(uid: String) {
        currentUid = uid
        if (view != null) render() else pendingUid = uid
    }

    // ========================= 刷新 =========================

    private fun maybeAutoRefresh() {
        if (!isAdded || loading) return
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
        setRefreshing(true)
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
                        setRefreshing(false)
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
            val verify = WeiboApi.verify(acc.cookie)
            if (!verify.login) {
                TopicStore.saveError(ctx, acc.uid, ctx.getString(R.string.home_cookie_dead))
                return
            }
            rotateCookie(ctx, acc.uid, verify.cookie)
            val topics = WeiboApi.listTopics(verify.cookie)
            TopicStore.save(ctx, acc.uid, topics)
        } catch (e: Exception) {
            TopicStore.saveError(ctx, acc.uid, e.message ?: "超话列表失败")
        }
        withContext(Dispatchers.Main) { if (isAdded) render() }
    }

    /** 顺带把轮换后的 cookie 写回账号表（否则下一轮会用到过期的 SUB）。 */
    private fun rotateCookie(ctx: Context, uid: String, cookie: String) {
        if (cookie.isEmpty()) return
        val accounts = AccountStore.load(ctx)
        val index = accounts.indexOfFirst { it.uid == uid }
        if (index < 0 || accounts[index].cookie == cookie) return
        accounts[index] = accounts[index].copy(cookie = cookie)
        AccountStore.save(ctx, accounts)
    }

    /**
     * 刷新中的视觉反馈。
     *
     * **不置灰刷新按钮**：禁用的按钮连点击事件都不派发，用户点下去既没有 toast
     * 也看不到任何变化（首页「立即签到」就踩过这个坑）。重入由 [refresh] 里的
     * `loading` 闸门 + toast「正在运行中…」兜住。
     */
    private fun setRefreshing(on: Boolean) {
        // 与 render() 里 `progress.visibility = if (loading) …` 保持同一口径：
        // 两处都按「是否正在刷新」决定可见性，谁先跑都不会留下一条卡住的进度条。
        progress.visibility = if (on) View.VISIBLE else View.GONE
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
        else -> SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(at))
    }

    // ========================= 渲染 =========================

    private fun render() {
        if (!isAdded) return
        val host = view ?: return
        val ctx = requireContext()
        val accounts = AccountStore.load(ctx)
        val snaps = TopicStore.all(ctx)

        // 账号被删后，选择器里可能还停在一个不存在的 uid 上
        if (currentUid.isNotEmpty() && accounts.none { it.uid == currentUid }) currentUid = ""
        multiAccountMode = currentUid.isEmpty() && accounts.size > 1

        renderAccountPills(accounts)
        renderFilters()

        val rows = buildRows(accounts, snaps)
        val shown = applySort(rows.filter { matchFilter(it.topic) })

        adapter.submit(shown)

        // 概览永远统计「全部已加载的超话」，不受筛选影响 —— 筛选是看的方式，不是事实。
        // 但「今日已签 / 待签」是按天的结论：快照跨天时这里必须显示占位符，
        // 否则会把昨天的已签数当成今天的结果（待签还会变成 0）。
        val error = errorOf(snaps)
        val at = updatedAt(snaps)
        val atLabel = topicStateTime(at)
        val staleDay = anyStaleDay(accounts, snaps)

        val total = rows.size
        val done = rows.count { it.topic.done }
        val dash = getString(R.string.topic_metric_empty)
        host.findViewById<TextView>(R.id.tvMetricTotal).text =
            if (total > 0) total.toString() else dash
        host.findViewById<TextView>(R.id.tvMetricDone).text =
            if (staleDay || total == 0) dash else done.toString()
        host.findViewById<TextView>(R.id.tvMetricPending).text =
            if (staleDay || total == 0) dash else (total - done).toString()
        progress.setProgressCompat(if (total > 0 && !staleDay) done * 100 / total else 0, true)
        progress.visibility = if (loading) View.VISIBLE else View.GONE

        // 副标题：加载中 > 错误 > 跨天失效 > 更新时间
        host.findViewById<TextView>(R.id.tvTopicSubtitle).text = when {
            loading -> getString(R.string.topic_subtitle_loading)
            error.isNotEmpty() -> getString(R.string.topic_subtitle_failed, error)
            staleDay -> getString(R.string.topic_subtitle_stale, atLabel)
            at > 0L -> getString(R.string.topic_subtitle_updated, atLabel)
            else -> getString(R.string.topic_subtitle_idle)
        }
        host.findViewById<TextView>(R.id.tvTopicStatus).text = when {
            accounts.isEmpty() -> getString(R.string.topic_empty_no_account)
            error.isNotEmpty() -> getString(R.string.topic_subtitle_failed, error)
            staleDay -> getString(R.string.topic_subtitle_stale, atLabel)
            at > 0L -> getString(R.string.topic_subtitle_updated, atLabel)
            else -> getString(R.string.topic_subtitle_idle)
        }

        // 空态 / 错误态：错误优先（否则会把"失败"伪装成"没有超话"）
        val showError = rows.isEmpty() && error.isNotEmpty() && !loading
        val showEmpty = rows.isEmpty() && !showError && !loading
        errorBox.visibility = if (showError) View.VISIBLE else View.GONE
        host.findViewById<TextView>(R.id.tvTopicError).text =
            getString(R.string.topic_subtitle_failed, error)
        emptyBox.visibility = if (showEmpty) View.VISIBLE else View.GONE
        host.findViewById<TextView>(R.id.tvTopicEmpty).text = when {
            accounts.isEmpty() -> getString(R.string.topic_empty_no_account)
            rows.isEmpty() && shown.isEmpty() && filter == Filter.ALL -> getString(R.string.topic_empty_no_topic)
            else -> getString(R.string.topic_empty_filter)
        }
        host.findViewById<View>(R.id.btnTopicEmptyAction).visibility =
            if (accounts.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
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
        // 行上的 uid 也决定了点签到用谁的 cookie。适配器的身份判定同样是 (uid, key)。
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

    private fun matchFilter(topic: WeiboApi.Topic): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.PENDING -> !topic.done
        Filter.DONE -> topic.done
        Filter.LEVEL7 -> topic.level >= 7
    }

    private fun applySort(rows: List<TopicRow>): List<TopicRow> = when (sort) {
        Sort.PENDING_FIRST -> rows.sortedWith(
            compareBy<TopicRow> { it.topic.done }.thenByDescending { it.topic.level }
        )
        Sort.LEVEL_DESC -> rows.sortedByDescending { it.topic.level }
        Sort.NAME -> rows.sortedBy { it.topic.name }
    }

    private fun renderAccountPills(accounts: List<AccountStore.Account>) {
        pillsRow.removeAllViews()
        if (accounts.size < 2) return   // 只有一个账号时选择器没有意义
        val inflater = LayoutInflater.from(requireContext())
        val items = buildList {
            add("" to getString(R.string.topic_account_all))
            accounts.forEach { add(it.uid to (it.name.ifEmpty { it.uid })) }
        }
        items.forEach { (uid, label) ->
            val chip = inflater.inflate(R.layout.item_chip, pillsRow, false) as TextView
            chip.text = label
            paintChip(chip, uid == currentUid)
            chip.setOnClickListener {
                currentUid = uid
                render()
            }
            pillsRow.addView(chip)
        }
    }

    private fun renderFilters() {
        filtersRow.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val entries = listOf(
            Filter.ALL to getString(R.string.topic_filter_all),
            Filter.PENDING to getString(R.string.topic_filter_pending),
            Filter.DONE to getString(R.string.topic_filter_done),
            Filter.LEVEL7 to getString(R.string.topic_filter_level),
        )
        entries.forEach { (value, label) ->
            val chip = inflater.inflate(R.layout.item_chip, filtersRow, false) as TextView
            chip.text = label
            paintChip(chip, value == filter)
            chip.setOnClickListener {
                filter = value
                render()
            }
            filtersRow.addView(chip)
        }
    }

    private fun paintChip(chip: TextView, selected: Boolean) = chip.applyChipStyle(selected)

    // ========================= 展开详情 =========================

    private fun toggleDetail(row: TopicRow) {
        val key = row.topic.key
        val stateKey = rowKey(row.uid, key)
        if (expanded.contains(stateKey)) {
            expanded.remove(stateKey)
            adapter.notifyKeyChanged(row.uid, key)
            return
        }
        expanded.add(stateKey)
        adapter.notifyKeyChanged(row.uid, key)
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
        adapter.notifyKeyChanged(uid, key)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var error = ""
            try {
                val acc = AccountStore.load(ctx).firstOrNull { it.uid == uid }
                if (acc == null) {
                    error = "账号已不存在"
                } else {
                    val verify = WeiboApi.verify(acc.cookie)
                    if (!verify.login) {
                        error = ctx.getString(R.string.home_cookie_dead)
                    } else {
                        val detail = WeiboApi.fetchTopicDetail(verify.cookie, topicId)
                        TopicStore.saveDetail(ctx, detail)
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "详情加载失败"
            }
            withContext(Dispatchers.Main + NonCancellable) {
                detailBusy.remove(stateKey)
                if (error.isNotEmpty()) detailErrors[stateKey] = error
                if (isAdded) adapter.notifyKeyChanged(uid, key)
            }
        }
    }

    // ========================= 签到 / 行操作 =========================

    private fun showRowMenu(anchor: View, row: TopicRow) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.item_topic_actions, popup.menu)
        popup.menu.findItem(R.id.action_topic_checkin).isEnabled = !row.topic.done
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_topic_checkin -> { checkin(row); true }
                R.id.action_topic_copy -> { copySignLink(row); true }
                R.id.action_topic_open -> { openInWeibo(row); true }
                R.id.action_topic_json -> { showRawJson(row); true }
                else -> false
            }
        }
        popup.show()
    }

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

    /** 签到一个超话：缓存里的链接会过期，先按需重新取一次最新列表。 */
    private fun checkin(row: TopicRow) {
        if (!isAdded) return
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var message: String
            try {
                val acc = AccountStore.load(ctx).firstOrNull { it.uid == row.uid }
                if (acc == null) {
                    message = "账号已不存在"
                } else {
                    val verify = WeiboApi.verify(acc.cookie)
                    if (!verify.login) {
                        message = ctx.getString(R.string.home_cookie_dead)
                    } else {
                        rotateCookie(ctx, acc.uid, verify.cookie)
                        var scheme = row.topic.scheme
                        if (scheme.isNullOrEmpty()) {
                            val fresh = WeiboApi.listTopics(verify.cookie)
                            TopicStore.save(ctx, acc.uid, fresh)
                            scheme = fresh.firstOrNull { it.key == row.topic.key }?.scheme
                        }
                        if (scheme.isNullOrEmpty()) {
                            message = ctx.getString(R.string.topic_copied_none)
                        } else {
                            val result = WeiboApi.signDetailed(verify.cookie, scheme, verify.st)
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
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                message = ctx.getString(R.string.topic_checkin_fail_fmt, e.message ?: "异常")
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

    // ========================= 菜单 / 弹窗 =========================

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, getString(R.string.topic_sort_default))
        popup.menu.add(0, 2, 1, getString(R.string.topic_sort_level))
        popup.menu.add(0, 3, 2, getString(R.string.topic_sort_name))
        popup.setOnMenuItemClickListener { item ->
            sort = when (item.itemId) {
                2 -> Sort.LEVEL_DESC
                3 -> Sort.NAME
                else -> Sort.PENDING_FIRST
            }
            render()
            true
        }
        popup.show()
    }

    private fun showOverflow(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.topic_overflow, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_topic_field -> {
                    showTextDialog(
                        getString(R.string.topic_field_title),
                        getString(R.string.topic_field_body),
                    )
                    true
                }
                R.id.action_topic_refresh_all -> {
                    refresh(manual = true)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** 等宽只读弹窗：原始 JSON 与字段说明共用。 */
    private fun showTextDialog(title: String, body: String) {
        if (!isAdded) return
        val ctx = requireContext()
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
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(text: String) {
        if (isAdded) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    // ========================= 适配器 =========================

    private inner class TopicAdapter : RecyclerView.Adapter<TopicAdapter.Holder>() {

        private val items = ArrayList<TopicRow>()

        /**
         * 提交列表。
         *
         * 换账号 / 换筛选 / 换排序都会重建整份列表，但绝大多数行其实没变。
         * 原先 `clear + addAll + notifyDataSetChanged()` 会让整屏重绑：展开的详情块要重画、
         * 列表滚动位置被推回顶部。DiffUtil 只通知真正变化的位置（O(n) 比较，n 是超话条数）。
         */
        fun submit(rows: List<TopicRow>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = rows.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val a = items[oldPos]
                    val b = rows[newPos]
                    return a.uid == b.uid && a.topic.key == b.topic.key
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    items[oldPos] == rows[newPos]
            })
            items.clear()
            items.addAll(rows)
            diff.dispatchUpdatesTo(this)
        }

        /** 按 key 定位后局部刷新（展开/收起、详情回填都只动一行）。 */
        fun notifyKeyChanged(uid: String, key: String) {
            val index = items.indexOfFirst { it.uid == uid && it.topic.key == key }
            if (index >= 0) notifyItemChanged(index)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_topic, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val card = view
            private val badge = view.findViewById<TextView>(R.id.tvLevelBadge)
            private val name = view.findViewById<TextView>(R.id.tvTopicName)
            private val meta = view.findViewById<TextView>(R.id.tvTopicMeta)
            private val state = view.findViewById<TextView>(R.id.tvTopicState)
            private val more = view.findViewById<ImageButton>(R.id.btnTopicMore)
            private val detailBox = view.findViewById<View>(R.id.topicDetailBox)
            private val intro = view.findViewById<TextView>(R.id.tvTopicIntro)
            private val stats = view.findViewById<TextView>(R.id.tvTopicStats)
            private val detailHint = view.findViewById<TextView>(R.id.tvTopicDetailHint)

            fun bind(row: TopicRow) {
                val ctx = itemView.context
                val topic = row.topic
                val key = topic.key

                name.text = topic.name
                bindBadge(ctx, topic)
                bindState(ctx, topic.done)

                val multiAccount = multiAccountMode
                val tail = when {
                    topic.desc2.isNotEmpty() -> topic.desc2
                    topic.topicId.isNotEmpty() -> getString(R.string.topic_meta_hint)
                    else -> getString(R.string.topic_detail_none)
                }
                meta.text = if (multiAccount) "${row.accountName} · $tail" else tail

                card.setOnClickListener { toggleDetail(row) }
                more.setOnClickListener { showRowMenu(it, row) }

                val stateKey = rowKey(row.uid, key)
                val isExpanded = expanded.contains(stateKey)
                detailBox.visibility = if (isExpanded) View.VISIBLE else View.GONE
                // 整卡一句话播报：名称 + 等级 + 状态 + 可执行动作，
                // 比让读屏把 4 个 TextView 逐条念一遍更容易理解
                card.contentDescription = buildString {
                    append(topic.name)
                    if (topic.level > 0) append(" · ").append(topic.levelLabel)
                    append(" · ")
                    append(getString(if (topic.done) R.string.topic_state_done else R.string.topic_state_pending))
                    append(" · ")
                    append(getString(if (isExpanded) R.string.cd_topic_collapse else R.string.cd_topic_expand))
                }
                if (!isExpanded) return

                val detail = if (topic.topicId.isEmpty()) null else TopicStore.detail(ctx, topic.topicId)
                val busy = detailBusy.contains(stateKey)
                when {
                    busy -> {
                        intro.visibility = View.GONE
                        stats.visibility = View.GONE
                        detailHint.visibility = View.VISIBLE
                        detailHint.text = getString(R.string.topic_detail_loading)
                    }
                    detail != null -> {
                        intro.visibility = if (detail.intro.isEmpty()) View.GONE else View.VISIBLE
                        intro.text = detail.intro
                        stats.visibility = if (detail.stats.isEmpty()) View.GONE else View.VISIBLE
                        stats.text = detail.stats.take(3).joinToString(" · ")
                        detailHint.visibility = View.VISIBLE
                        detailHint.text = buildString {
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
                            if (detail.portraitText.isNotEmpty()) append(" · ").append(detail.portraitText)
                        }
                    }
                    else -> {
                        intro.visibility = View.GONE
                        stats.visibility = View.GONE
                        detailHint.visibility = View.VISIBLE
                        detailHint.text = when {
                            detailErrors[stateKey] != null -> getString(R.string.topic_detail_failed)
                            topic.topicId.isEmpty() -> getString(R.string.topic_detail_none)
                            else -> getString(R.string.topic_detail_failed)
                        }
                    }
                }
            }

            private fun bindBadge(ctx: Context, topic: WeiboApi.Topic) {
                val tier = WeiboApi.topicLevelTier(topic.level)
                if (tier == 0) {
                    badge.visibility = View.GONE
                    return
                }
                badge.visibility = View.VISIBLE
                badge.text = topic.levelLabel
                val (bg, fg) = when (tier) {
                    1 -> R.color.level_neutral_bg to R.color.level_neutral_fg
                    2 -> R.color.level_blue_bg to R.color.level_blue_fg
                    3 -> R.color.level_purple_bg to R.color.level_purple_fg
                    else -> R.color.level_gold_bg to R.color.level_gold_fg
                }
                badge.setBackgroundResource(R.drawable.bg_topic_badge)
                badge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, bg))
                badge.setTextColor(ContextCompat.getColor(ctx, fg))
                badge.contentDescription = getString(R.string.cd_level_badge, topic.level)
            }

            private fun bindState(ctx: Context, done: Boolean) {
                if (done) {
                    state.setText(R.string.topic_state_done)
                    state.setBackgroundResource(R.drawable.bg_pill_ok)
                    state.setTextColor(ContextCompat.getColor(ctx, R.color.ok_text))
                } else {
                    state.setText(R.string.topic_state_pending)
                    state.setBackgroundResource(R.drawable.bg_pill_warn)
                    state.setTextColor(ContextCompat.getColor(ctx, R.color.warn_text))
                }
            }
        }
    }
}
