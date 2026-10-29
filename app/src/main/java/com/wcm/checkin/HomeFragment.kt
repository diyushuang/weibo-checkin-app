package com.wcm.checkin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 */
class HomeFragment : Fragment() {

    private var busy = false
    private var topicBusy = false
    private var pendingAutoRefresh = false
    private var lastAutoRefreshAt = 0L

    /** 日志卡是否展开（默认收起，把首屏让给签到主行动）。 */
    private var logExpanded = false

    private val logLines = ArrayDeque<String>()

    /**
     * 时间格式化：`SimpleDateFormat` **不是线程安全的**。
     *
     * 这个字段同时被 UI 线程（[syncFromStore] 里格式化快照时间）和 IO 线程
     * （签到/刷新日志里的 [now]）使用，共用一个实例会出现串位、甚至抛
     * `ArrayIndexOutOfBoundsException`。按线程各持一个实例最省事也最稳。
     */
    private val timeFmt: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }

    /** 线程安全的时间格式化：每个线程用自己的 [SimpleDateFormat]。 */
    private fun formatTime(at: Long): String {
        val fmt = timeFmt.get() ?: SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        return fmt.format(Date(at))
    }

    /** 各账号最近一次超话状态（key = uid）。打开页面会自动重拉覆盖。 */
    private val topicStates = HashMap<String, TopicState>()

    private data class TopicState(
        val topics: List<WeiboApi.Topic> = emptyList(),
        val at: String = "",
        val error: String = "",
        val loading: Boolean = false,
    ) {
        val total: Int get() = topics.size
        val done: Int get() = topics.count { it.done }
    }

    companion object {
        /** 本次进程只自动签到一次（切 Tab 不重复触发）。 */
        private var autoHandled = false
        private const val LOG_MAX_LINES = 500
        private const val AUTO_REFRESH_DEBOUNCE_MS = 3_000L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnCheckin).setOnClickListener { runAll(false) }
        view.findViewById<View>(R.id.btnSync).setOnClickListener { syncOnly() }
        view.findViewById<View>(R.id.btnAdd).setOnClickListener {
            startActivity(Intent(requireContext(), LoginWebViewActivity::class.java))
        }
        view.findViewById<View>(R.id.btnRefreshTopics).setOnClickListener {
            refreshTopics(manual = true)
        }
        // Hero 卡整块可点：直接进「超话」页，默认选中待签最多的账号
        view.findViewById<View>(R.id.heroCard)?.setOnClickListener {
            val uid = topicStates.entries
                .filter { it.value.error.isEmpty() && !it.value.loading && it.value.total > it.value.done }
                .maxByOrNull { it.value.total - it.value.done }?.key
            jumpToTopics(uid)
        }
        // 日志卡：卡头整行可点，收起时只显示一行摘要
        view.findViewById<View>(R.id.logHeader).setOnClickListener {
            setLogExpanded(!logExpanded)
        }
        setLogExpanded(false)
        renderLog()
        syncFromStore(requireContext())
        render()

        val ctx = requireContext()
        if (!autoHandled) {
            autoHandled = true
            val lastAuto = AccountStore.lastAutoAt(ctx)
            if (AccountStore.autoOnOpen(ctx) && AccountStore.load(ctx).isNotEmpty()
                && System.currentTimeMillis() - lastAuto > 6 * 3600_000L
            ) {
                AccountStore.setLastAutoAt(ctx, System.currentTimeMillis())
                runAll(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncFromStore(requireContext())
        render()
        maybeAutoRefreshTopics()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            syncFromStore(requireContext())
            render()
            maybeAutoRefreshTopics()
        }
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
            topicStates[uid] = TopicState(
                topics = snap.topics,
                at = if (snap.at > 0L) formatTime(snap.at) else "",
                error = snap.error,
                loading = false,
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
        val ctx = requireContext()
        val accounts = AccountStore.load(ctx)
        val panelReady = AccountStore.panelUrl(ctx).isNotEmpty() &&
            AccountStore.panelToken(ctx).isNotEmpty()

        // 应用栏（v2.0）：问候语 + 「日期 · 账号数」。
        // 每次 render 都重算，跨天回前台、增删账号都会自动更新。
        view?.findViewById<TextView>(R.id.tvGreeting)?.text = greeting()
        view?.findViewById<TextView>(R.id.tvToday)?.text = if (accounts.isEmpty()) {
            getString(R.string.home_today_no_account_fmt, dateLabel())
        } else {
            getString(R.string.home_today_fmt, dateLabel(), accounts.size)
        }

        // Hero 的两个 pill：账号数、面板状态。用 pill 而不是句子，
        // 是为了让它们在视觉上"可以被扫过"，不占阅读路径。
        view?.findViewById<TextView>(R.id.tvPillAccounts)?.text =
            getString(R.string.home_pill_accounts_fmt, accounts.size)
        view?.findViewById<TextView>(R.id.tvPillPanel)?.text =
            getString(if (panelReady) R.string.home_pill_panel_on else R.string.home_pill_panel_off)

        view?.findViewById<TextView>(R.id.tvSummary)?.text = when {
            accounts.isEmpty() -> getString(R.string.home_summary_empty)
            else -> getString(
                R.string.home_summary_fmt,
                accounts.size,
                if (panelReady) getString(R.string.home_panel_ok) else getString(R.string.home_panel_off),
            )
        }

        // 标题带账号数量（原来只有「账号」两个字，还得自己数）
        view?.findViewById<TextView>(R.id.tvAccountsTitle)?.text =
            if (accounts.isEmpty()) {
                getString(R.string.accounts_label)
            } else {
                getString(R.string.accounts_count_fmt, accounts.size)
            }

        val container = view?.findViewById<ViewGroup>(R.id.accountList) ?: return
        container.removeAllViews()
        if (accounts.isEmpty()) {
            container.addView(buildEmptyHint(getString(R.string.home_account_empty)))
            // 空账号时进度环也要回到占位态，否则会留着上一次的百分比
            renderTopicProgress()
            return
        }
        val inflater = LayoutInflater.from(ctx)
        accounts.forEach { acc ->
            val row = inflater.inflate(R.layout.item_account, container, false)
            val avatar = row.findViewById<TextView>(R.id.tvAvatar)
            avatar.text = avatarInitial(acc)
            paintAvatar(avatar, topicStates[acc.uid])
            row.findViewById<TextView>(R.id.tvName).text = displayName(acc)
            row.findViewById<TextView>(R.id.tvUid).text = getString(R.string.home_uid_fmt, acc.uid)
            bindAccountStatus(row, acc)
            // 整行点按仍然进入超话清单（行为不变）
            row.setOnClickListener { showTopics(acc.uid) }
            val more = row.findViewById<View>(R.id.btnAccountMore)
            more.setOnClickListener { showAccountMenu(it, acc) }
            // 长按同一入口，习惯长按的用户也不必先去找右上角的点
            row.setOnLongClickListener {
                showAccountMenu(more, acc)
                true
            }
            container.addView(row)
        }
        renderTopicProgress()
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
    private fun renderTopicProgress() {
        val known = topicStates.values.filter { !it.loading && it.error.isEmpty() && it.total > 0 }
        val total = known.sumOf { it.total }
        val done = known.sumOf { it.done }

        val host = view ?: return
        val ring = host.findViewById<CircularProgressIndicator>(R.id.ringProgress) ?: return
        val value = host.findViewById<TextView>(R.id.tvRingValue)
        val pending = host.findViewById<TextView>(R.id.tvPendingMetric)

        if (total <= 0) {
            ring.setProgressCompat(0, true)
            value.text = getString(R.string.home_ring_value_empty)
            pending.text = getString(R.string.home_metric_empty)
            (activity as? MainActivity)?.setTopicBadge(null)
            return
        }
        ring.setProgressCompat(done * 100 / total, true)
        value.text = getString(R.string.home_ring_value_fmt, done, total)
        pending.text = (total - done).toString()
        // 底栏「超话」上的待签角标：不进那一页也能看到还有多少没签
        (activity as? MainActivity)?.setTopicBadge(total - done)
    }

    /**
     * 头像配色跟随账号状态：全部签完 = 绿，部分/全未签 = 琥珀，状态未知 = 品牌色。
     * 这是列表里成本最低的信号 —— 不用读文字，扫一眼颜色就知道哪个号要处理。
     */
    private fun paintAvatar(avatar: TextView, state: TopicState?) {
        val ctx = requireContext()
        val doneAll = state != null && !state.loading && state.error.isEmpty() &&
            state.total > 0 && state.done >= state.total
        val hasPending = state != null && !state.loading && state.error.isEmpty() && state.done < state.total
        val (bg, fg) = when {
            doneAll -> R.drawable.bg_avatar_ok to R.color.ok_text
            hasPending -> R.drawable.bg_avatar_warn to R.color.warn_text
            else -> R.drawable.bg_avatar to R.color.brand_strong
        }
        avatar.setBackgroundResource(bg)
        avatar.setTextColor(ContextCompat.getColor(ctx, fg))
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

    /** 空态提示：套统一文本样式，避免代码里写死字号/颜色（深色主题下同样可读）。 */
    private fun buildEmptyHint(text: String): TextView = TextView(requireContext()).apply {
        setTextAppearance(R.style.Widget_Wcm_Text_Empty)
        this.text = text
    }

    /** 账号行溢出菜单：查看超话清单 / 删除账号（删除不再常驻在行尾）。 */
    private fun showAccountMenu(anchor: View, acc: AccountStore.Account) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.item_account_actions, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_view_topics -> {
                    showTopics(acc.uid)
                    true
                }
                R.id.action_delete_account -> {
                    confirmDeleteAccount(acc)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** 删除账号确认：与改造前完全一致（只删本机登录态，不影响面板）。 */
    private fun confirmDeleteAccount(acc: AccountStore.Account) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.home_delete_title, displayName(acc)))
            .setMessage(getString(R.string.home_delete_msg))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AccountStore.remove(ctx, acc.uid)
                topicStates.remove(acc.uid)
                TopicStore.clear(ctx, acc.uid)
                render()
            }
            .show()
    }

    /** 昵称优先；拿不到真实昵称时明确提示，而不是把 UID 当名字显示。 */
    private fun displayName(acc: AccountStore.Account): String =
        if (AccountStore.needsNameResolve(acc)) getString(R.string.home_name_unknown) else acc.name

    /**
     * 账号行的状态可视化。
     *
     * 超话状态已知时用「已签 / 待签」两个 chip 表示（成功=绿、待签=琥珀），
     * 状态未知（未刷新 / 刷新中 / 刷新失败 / 没关注超话）时退化成一行说明文字，
     * 不再把所有信息拼成一条逗号长句。
     */
    private fun bindAccountStatus(row: View, acc: AccountStore.Account) {
        val st = topicStates[acc.uid]
        val chipRow = row.findViewById<View>(R.id.chipRow)
        val loaded = st != null && !st.loading && st.error.isEmpty() && st.total > 0
        chipRow.visibility = if (loaded) View.VISIBLE else View.GONE
        if (st != null && loaded) {
            row.findViewById<TextView>(R.id.chipDone).text =
                getString(R.string.account_chip_done_fmt, st.done)
            row.findViewById<TextView>(R.id.chipPending).text =
                getString(R.string.account_chip_pending_fmt, st.total - st.done)
        }
        // 最高超话等级：一眼看出这个号在超话里的分量（没有等级字段的账号直接隐藏）
        val maxLevel = if (loaded) st?.topics?.maxOfOrNull { it.level } ?: 0 else 0
        val levelChip = row.findViewById<TextView>(R.id.chipLevel)
        if (maxLevel > 0) {
            levelChip.visibility = View.VISIBLE
            levelChip.text = getString(R.string.account_chip_level_fmt, maxLevel)
        } else {
            levelChip.visibility = View.GONE
        }
        val note = when {
            st == null -> getString(R.string.home_topics_idle)
            st.loading -> getString(R.string.home_topics_loading)
            st.error.isNotEmpty() -> getString(R.string.home_topics_failed, st.error)
            st.total == 0 -> getString(R.string.home_topic_none)
            else -> getString(R.string.home_topics_updated_fmt, st.at)
        }
        row.findViewById<TextView>(R.id.tvStatus).text = buildString {
            append(acc.lastResult.ifEmpty { getString(R.string.home_status_idle) })
            append('\n')
            append(note)
        }
    }

    // ========================= 日志（可滚动，不打断阅读） =========================

    /**
     * 追加一行日志。**只有用户本来就贴着底部时才自动滚到底**，
     * 这样往上翻看旧日志时不会被新日志拽回去。
     */
    private fun log(line: String) {
        val host = view ?: return
        host.post {
            synchronized(logLines) {
                logLines.addLast(line)
                while (logLines.size > LOG_MAX_LINES) logLines.removeFirst()
            }
            renderLog()
        }
    }

    private fun renderLog() {
        val host = view ?: return
        val tv = host.findViewById<TextView>(R.id.logView) ?: return
        val scroll = host.findViewById<ScrollView>(R.id.logScroll) ?: return
        // 先取「是否贴底」，再赋新文本 —— 赋值会 requestLayout，若反过来读，
        // 结果就依赖"此刻还没重新测量"这一时序巧合，日后插入任何同步测量都会反转语义。
        val wasAtBottom = !scroll.canScrollVertically(1)
        tv.text = synchronized(logLines) { logLines.joinToString("\n") }
            .ifEmpty { getString(R.string.log_empty) }
        // 收起状态下也要能看到「最近发生了什么」：卡头下方保持一行摘要
        host.findViewById<TextView>(R.id.tvLogSummary)?.text = synchronized(logLines) {
            logLines.lastOrNull { it.isNotBlank() } ?: getString(R.string.log_empty)
        }
        // 收起时窗口高度为 0，canScrollVertically 恒为 false，此时不要滚（等展开时再滚到底）；
        // 展开时仍只在「原本就贴着底部」才自动滚，不打断用户翻看旧日志。
        if (scroll.visibility != View.VISIBLE) return
        if (wasAtBottom) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * 展开 / 收起日志区。
     *
     * 默认收起：日志是排查用信息，不该在首屏和签到行动抢位置；
     * 运行任务开始时自动展开，让用户看得到进度。
     *
     * 无障碍：展开/收起的状态文案挂在**可点的卡头容器**上（`logHeader`），
     * 而不是右边的箭头 —— 箭头设了 `importantForAccessibility="no"`，
     * 写在它身上的 contentDescription 读屏根本不会播报。
     */
    private fun setLogExpanded(expanded: Boolean) {
        logExpanded = expanded
        val host = view ?: return
        val scroll = host.findViewById<ScrollView>(R.id.logScroll) ?: return
        scroll.visibility = if (expanded) View.VISIBLE else View.GONE
        host.findViewById<View>(R.id.tvLogSummary)?.visibility =
            if (expanded) View.GONE else View.VISIBLE
        val arrow = host.findViewById<View>(R.id.btnLogToggle)
        arrow?.rotation = if (expanded) 180f else 0f
        host.findViewById<View>(R.id.logHeader)?.contentDescription = getString(
            if (expanded) R.string.cd_log_expanded else R.string.cd_log_collapsed
        )
        if (expanded) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun now(): String = formatTime(System.currentTimeMillis())

    private fun toast(text: String) =
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()

    private fun setBusy(value: Boolean) {
        busy = value
        view?.findViewById<View>(R.id.btnCheckin)?.isEnabled = !value
        view?.findViewById<View>(R.id.btnSync)?.isEnabled = !value
        view?.findViewById<View>(R.id.btnAdd)?.isEnabled = !value
        view?.findViewById<View>(R.id.btnRefreshTopics)?.isEnabled = !value && !topicBusy
    }

    private fun setTopicBusy(value: Boolean) {
        topicBusy = value
        view?.findViewById<View>(R.id.btnRefreshTopics)?.isEnabled = !value && !busy
    }

    // ========================= 超话状态刷新 =========================

    /** 页面打开时自动调用；[force] 用于签到刚结束时补一次。 */
    private fun maybeAutoRefreshTopics(force: Boolean = false) {
        if (!isAdded) return
        val ctx = requireContext()
        if (AccountStore.load(ctx).isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAutoRefreshAt < AUTO_REFRESH_DEBOUNCE_MS) return
        if (busy || topicBusy) {
            pendingAutoRefresh = true
            return
        }
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
        if (busy || topicBusy) {
            if (manual) toast(getString(R.string.busy)) else pendingAutoRefresh = true
            return
        }
        setTopicBusy(true)
        // 手动刷新才展开日志；页面打开时的自动刷新不打扰首屏
        if (manual) setLogExpanded(true)
        lastAutoRefreshAt = System.currentTimeMillis()
        accounts.forEach { acc ->
            topicStates[acc.uid] = (topicStates[acc.uid] ?: TopicState()).copy(loading = true, error = "")
        }
        render()
        log("==== 刷新超话状态 · ${now()} ====")

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                accounts.forEachIndexed { index, acc ->
                    if (index > 0) delay(800)
                    val label = acc.name.ifEmpty { acc.uid }
                    val verify = try {
                        WeiboApi.verify(acc.cookie)
                    } catch (e: Exception) {
                        null
                    }
                    if (verify == null || !verify.login) {
                        topicStates[acc.uid] = TopicState(at = now(), error = ctx.getString(R.string.home_cookie_dead))
                        TopicStore.saveError(ctx, acc.uid, ctx.getString(R.string.home_cookie_dead))
                        log("  ✗ $label：${ctx.getString(R.string.home_cookie_dead)}")
                        postRender()
                        return@forEachIndexed
                    }
                    updateCookieAndName(ctx, acc.uid, verify.cookie)
                    val topics = try {
                        WeiboApi.listTopics(verify.cookie)
                    } catch (e: Exception) {
                        topicStates[acc.uid] = TopicState(at = now(), error = e.message ?: "超话列表失败")
                        TopicStore.saveError(ctx, acc.uid, e.message ?: "超话列表失败")
                        log("  ✗ $label：${e.message}")
                        postRender()
                        return@forEachIndexed
                    }
                    topicStates[acc.uid] = TopicState(topics = topics, at = now())
                    TopicStore.save(ctx, acc.uid, topics)
                    log(
                        "  $label：${topics.size} 个超话，已签 ${topics.count { it.done }}，" +
                            "待签 ${topics.count { !it.done }}"
                    )
                    postRender()
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    if (isAdded) {
                        setTopicBusy(false)
                        render()
                        // 刷新期间又被要求刷新（例如签到刚结束）→ 补一轮
                        if (pendingAutoRefresh) {
                            pendingAutoRefresh = false
                            maybeAutoRefreshTopics(force = true)
                        }
                    } else {
                        topicBusy = false
                    }
                }
            }
        }
    }

    /** 视图可能已销毁，统一切回主线程再渲染。 */
    private fun postRender() {
        view?.post { if (isAdded) render() }
    }

    /** 顺手把轮换后的 cookie 写回，并在昵称仍是占位值时补拉真实用户名。 */
    private fun updateCookieAndName(ctx: Context, uid: String, cookie: String) {
        val list = AccountStore.load(ctx)
        val index = list.indexOfFirst { it.uid == uid }
        if (index < 0) return
        var acc = list[index]
        var changed = false
        if (cookie.isNotEmpty() && cookie != acc.cookie) {
            acc = acc.copy(cookie = cookie)      // name/uid 是 val，只能 copy
            changed = true
        }
        if (AccountStore.needsNameResolve(acc)) {
            val (name, _) = WeiboApi.fetchProfile(cookie)
            if (name.isNotEmpty()) {
                acc = acc.copy(name = name)
                changed = true
            }
        }
        if (changed) {
            list[index] = acc
            AccountStore.save(ctx, list)
        }
    }

    // ========================= 签到 / 同步 =========================

    private fun runAll(auto: Boolean) {
        if (busy) {
            toast(getString(R.string.busy))
            return
        }
        val ctx = requireContext()
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) {
            log(getString(R.string.home_no_account))
            return
        }
        setBusy(true)
        setLogExpanded(true)            // 运行时自动展开日志，进度可见
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("==== ${if (auto) "打开自动" else "手动"}签到 · ${now()} ====")
                accounts.forEachIndexed { index, acc ->
                    if (index > 0) delay(Random.nextLong(2000, 5000))
                    runAccount(ctx, acc)
                }
                log("==== 全部完成 · ${now()} ====")
                if (AccountStore.crawlAuto(ctx)) {
                    try {
                        // 与图库页手动抓取共用闸门：正跑着就静默跳过，自动流程不该弹提示
                        if (CrawlRunner.runAll(ctx, ::log, auto = true) == CrawlRunner.RunResult.Busy) {
                            log("自动抓图跳过：已有抓取任务在运行")
                        }
                    } catch (e: Exception) {
                        log("自动抓图中断：${e.message}")
                    }
                }
            } finally {
                AccountStore.mergeSave(ctx, accounts)
                withContext(Dispatchers.Main + NonCancellable) {
                    setBusy(false)
                    render()
                    // 签到过程中被推迟的状态刷新，现在补上
                    if (pendingAutoRefresh) {
                        pendingAutoRefresh = false
                        maybeAutoRefreshTopics(force = true)
                    }
                }
            }
        }
    }

    /**
     * 跑一个账号的完整签到。
     *
     * [ctx] 由调用方在启动协程前取好（`requireContext()`）：签约一轮要几分钟，
     * 期间用户完全可能退出页面，届时 `requireContext()` 会抛
     * IllegalStateException，把整轮签到（乃至进程）带崩。
     */
    private suspend fun runAccount(ctx: Context, acc: AccountStore.Account) {
        val name = acc.name.ifEmpty { acc.uid }
        log("── $name ──")
        val verify = try {
            WeiboApi.verify(acc.cookie)
        } catch (e: Exception) {
            acc.lastResult = "探活失败：${e.message}"
            log("  ✗ 探活失败：${e.message}")
            return
        }
        if (!verify.login) {
            acc.lastResult = ctx.getString(R.string.home_cookie_dead)
            log("  ✗ ${ctx.getString(R.string.home_cookie_dead)}")
            return
        }
        acc.cookie = verify.cookie
        try {
            val topics = WeiboApi.listTopics(acc.cookie)
            val pending = topics.filter { !it.done }
            log("  ${topics.size} 个超话，${topics.size - pending.size} 已签，待签 ${pending.size}")
            topicStates[acc.uid] = TopicState(topics = topics, at = now())
            TopicStore.save(ctx, acc.uid, topics)
            postRender()
            var ok = 0
            var fail = 0
            for (topic in pending) {
                delay(Random.nextLong(3000, 8000))
                try {
                    if (WeiboApi.sign(acc.cookie, topic.scheme ?: "", verify.st)) {
                        ok++; log("  ✓ ${topic.name}")
                    } else {
                        fail++; log("  ✗ ${topic.name}")
                    }
                } catch (e: Exception) {
                    fail++; log("  ✗ ${topic.name}：${e.message}")
                }
            }
            acc.lastResult = "签到 $ok/${pending.size}" +
                (if (fail > 0) "（失败 $fail）" else "") + " · ${now()}"
            log("  小结：${acc.lastResult}")
        } catch (e: Exception) {
            acc.lastResult = "超话列表失败：${e.message}"
            log("  ✗ ${e.message}")
        }
        syncToPanel(ctx, acc)
    }

    private suspend fun syncToPanel(ctx: Context, acc: AccountStore.Account) {
        val url = AccountStore.panelUrl(ctx)
        val token = AccountStore.panelToken(ctx)
        if (url.isEmpty() || token.isEmpty()) {
            log("  （未配置面板，跳过同步）")
            return
        }
        try {
            val r = PanelApi.sync(url, token, acc.cookie, acc.uid)
            if (r.ok) {
                log("  📲 已同步面板：${if (r.updated) "覆盖" else "新建"} ${r.name}")
            } else {
                log("  📲 面板拒绝同步：${r.detail}")
            }
        } catch (e: Exception) {
            log("  📲 同步面板失败：${e.message}")
        }
    }

    // ========================= 超话清单（完整展示 + 逐条状态） =========================

    /** 点按账号行：重新拉一次该账号的超话，然后弹出完整清单。 */
    private fun showTopics(uid: String) {
        if (busy || topicBusy) {
            toast(getString(R.string.busy))
            return
        }
        val ctx = requireContext()
        val acc = AccountStore.load(ctx).firstOrNull { it.uid == uid } ?: return
        setTopicBusy(true)
        setLogExpanded(true)
        log("── 查询 ${acc.name.ifEmpty { acc.uid }} 的超话 ──")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val verify = try {
                    WeiboApi.verify(acc.cookie)
                } catch (e: Exception) {
                    null
                }
                if (verify == null || !verify.login) {
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
                val topics = WeiboApi.listTopics(verify.cookie)
                topicStates[uid] = TopicState(topics = topics, at = now())
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
                    if (isAdded) setTopicBusy(false) else topicBusy = false
                }
            }
        }
    }

    /**
     * 超话清单：**全部超话逐条列出**并标注已签/待签。
     * 已签项勾选且不可再选（防重复签到），待签项可勾选后一次性补签。
     */
    private fun presentTopics(uid: String, st: String?, topics: List<WeiboApi.Topic>) {
        if (!isAdded || topics.isEmpty()) {
            if (isAdded && topics.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.home_topic_title, uid))
                    .setMessage(R.string.home_topic_none)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            return
        }
        val ctx = requireContext()
        val name = AccountStore.load(ctx).firstOrNull { it.uid == uid }?.let { displayName(it) } ?: uid
        val done = topics.count { it.done }

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_topic_list, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.topicContainer)
        dialogView.findViewById<TextView>(R.id.tvTopicSummary).text = getString(
            R.string.home_topic_list_summary, topics.size, done, topics.size - done,
        )

        val pendingBoxes = ArrayList<Pair<WeiboApi.Topic, MaterialCheckBox>>()
        topics.forEach { topic ->
            val box = MaterialCheckBox(ctx)
            if (topic.done) {
                box.text = getString(R.string.home_topic_item_done, topic.name)
                box.isChecked = true
                box.isEnabled = false          // 已签不可再选
            } else {
                box.text = getString(R.string.home_topic_item_pending, topic.name)
                box.isChecked = false
                pendingBoxes.add(topic to box)
            }
            container.addView(box)
        }

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.home_topic_title, name))
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            // 弹窗只做「快速补签」；想看等级/详情/全部字段，跳到超话页
            .setNeutralButton(R.string.topic_view_all) { _, _ -> jumpToTopics(uid) }

        if (pendingBoxes.isEmpty()) {
            builder.setPositiveButton(android.R.string.ok, null)
        } else {
            builder.setPositiveButton(R.string.home_topic_resign) { _, _ ->
                val picked = pendingBoxes.filter { it.second.isChecked }.map { it.first }
                if (picked.isEmpty()) {
                    toast(getString(R.string.home_topic_none_selected))
                } else {
                    resignTopics(uid, st, picked)
                }
            }
        }
        builder.show()
    }

    /** 勾选补签：与主签到同节奏（3-8 秒随机间隔），完成后同步面板并刷新状态。 */
    private fun resignTopics(uid: String, st: String?, topics: List<WeiboApi.Topic>) {
        if (topics.isEmpty()) return
        if (busy) {
            toast(getString(R.string.busy))
            return
        }
        val ctx = requireContext()
        setBusy(true)
        setLogExpanded(true)
        log("── 补签 ${topics.size} 个超话 ──")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val acc = AccountStore.load(ctx).firstOrNull { it.uid == uid }
                if (acc == null) {
                    log("  ✗ 账号已不存在")
                    return@launch
                }
                var ok = 0
                var fail = 0
                for (topic in topics) {
                    delay(Random.nextLong(3000, 8000))
                    try {
                        if (WeiboApi.sign(acc.cookie, topic.scheme ?: "", st)) {
                            ok++; log("  ✓ ${topic.name}")
                        } else {
                            fail++; log("  ✗ ${topic.name}")
                        }
                    } catch (e: Exception) {
                        fail++; log("  ✗ ${topic.name}：${e.message}")
                    }
                }
                log("  " + ctx.getString(R.string.home_resign_done_fmt, ok, fail))
                val summary = "补签 $ok/${topics.size}" +
                    (if (fail > 0) "（失败 $fail）" else "") + " · ${now()}"
                saveLastResult(ctx, uid, summary)
                log("  小结：$summary")
                acc.lastResult = summary
                syncToPanel(ctx, acc)
                // 补签后立刻重拉一次状态，让清单不再显示旧的「待签」
                topicStates[uid] = (topicStates[uid] ?: TopicState()).copy(loading = true)
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    if (isAdded) {
                        setBusy(false)
                        render()
                        refreshTopics(manual = false)
                    } else {
                        busy = false
                    }
                }
            }
        }
    }

    private fun saveLastResult(ctx: Context, uid: String, result: String) {
        val list = AccountStore.load(ctx)
        list.firstOrNull { it.uid == uid }?.let { it.lastResult = result }
        AccountStore.save(ctx, list)
    }

    private fun syncOnly() {
        if (busy) {
            toast(getString(R.string.busy))
            return
        }
        val ctx = requireContext()
        val accounts = AccountStore.load(ctx)
        if (accounts.isEmpty()) {
            log(getString(R.string.home_no_account))
            return
        }
        setBusy(true)
        setLogExpanded(true)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("==== 仅同步面板 · ${now()} ====")
                for (acc in accounts) {
                    log("── ${acc.name.ifEmpty { acc.uid }} ──")
                    try {
                        val verify = WeiboApi.verify(acc.cookie)
                        if (!verify.login) {
                            acc.lastResult = ctx.getString(R.string.home_cookie_dead)
                            log("  ✗ ${ctx.getString(R.string.home_cookie_dead)}")
                            continue
                        }
                        acc.cookie = verify.cookie
                        syncToPanel(ctx, acc)
                    } catch (e: Exception) {
                        log("  ✗ ${e.message}")
                    }
                }
            } finally {
                AccountStore.mergeSave(ctx, accounts)
                withContext(Dispatchers.Main + NonCancellable) {
                    setBusy(false)
                    render()
                }
            }
        }
    }
}
