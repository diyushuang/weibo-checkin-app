package com.wcm.checkin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图片页：相册目录 / 抓取目标 / 一键增量抓取（含 Live→GIF）。
 *
 * v0.6（UI 重排，只动呈现层）：
 * - 运行卡升为页面唯一主卡：「开始抓取」是唯一 Filled 主行动，运行中换成「停止抓取」；
 * - 图库入口与存储位置合并为弱化的入口行，把首屏留给「目标 + 开始」；
 * - 每个抓取目标的「起始日期 / 移除」收进行尾溢出菜单；
 * - 日志卡默认收起，运行开始时自动展开。
 */
class GalleryFragment : Fragment() {

    /**
     * 运行态与日志放进程级：Fragment 在旋转 / 深浅色切换时会换实例，
     * 实例字段会让「正在抓取」凭空消失（用户可以再点一次，两轮并发写同一目录）。
     */
    private companion object {
        const val LOG_MAX_LINES = 500

        @Volatile private var busy = false
        @Volatile private var crawlJob: Job? = null

        /** 日志行缓冲：进程级，旋转后不丢。 */
        private val logLines = ArrayDeque<String>()
    }

    private var logExpanded = false

    /** 日志面板（与首页共用实现，见 [LogPanel]）。 */
    private var panel: LogPanel? = null

    /** 日志时间戳格式化：`SimpleDateFormat` 非线程安全，按线程各持一份。 */
    private val timeFmt: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }

    /** 最近一次的进度快照：任务结束后仍留着，让用户看得到「这轮干了什么」。 */
    private var lastProgress: CrawlProgress? = null

    private fun stamp(line: String): String =
        "[${timeFmt.get()?.format(Date()) ?: ""}] $line"

    private val pickDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val ctx = context ?: return@registerForActivityResult
            if (uri != null) {
                // 部分第三方 provider 不支持持久化写权限，直接抛 SecurityException；
                // 这里降级为「能拿到的权限」而不是崩掉整个页面。
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.onFailure {
                    runCatching {
                        ctx.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                AccountStore.setCrawlTreeUri(ctx, uri.toString())
                renderDir()
                refreshGalleryCount()
                toastShort(getString(R.string.crawl_dir_saved))
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_gallery, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnPickDir).setOnClickListener { pickDir.launch(null) }
        view.findViewById<View>(R.id.btnAddTarget).setOnClickListener { addTarget() }
        view.findViewById<View>(R.id.btnSinglePost).setOnClickListener { pickSinglePost() }
        view.findViewById<View>(R.id.btnCrawlStart).setOnClickListener { start() }
        view.findViewById<View>(R.id.btnCrawlStop).setOnClickListener {
            // 先置停止标志（首页自动触发的抓取没有本页协程可取消），再取消本页任务
            CrawlRunner.requestStop()
            crawlJob?.cancel()
            log("⏹ 正在停止…（进度已保存）")
        }
        view.findViewById<View>(R.id.btnRetryFailed).setOnClickListener { showRetryDialog() }
        view.findViewById<View>(R.id.tvDirStatus).setOnClickListener { pickDir.launch(null) }
        view.findViewById<View>(R.id.btnOpenGallery).setOnClickListener { openGallery() }

        val switchMode = view.findViewById<MaterialSwitch>(R.id.switchCrawlMode)
        val modeLabel = view.findViewById<TextView>(R.id.tvCrawlMode)
        // 先渲染初值再挂监听：否则 `isChecked = ...` 会立刻回调一次，把默认值写回存储
        switchMode.isChecked = AccountStore.crawlMode(requireContext()) == AccountStore.MODE_ALBUM
        modeLabel.text = getString(
            if (switchMode.isChecked) R.string.crawl_mode_album else R.string.crawl_mode_timeline
        )
        switchMode.setOnCheckedChangeListener { _, checked ->
            AccountStore.setCrawlMode(
                requireContext(),
                if (checked) AccountStore.MODE_ALBUM else AccountStore.MODE_TIMELINE,
            )
            modeLabel.text = getString(
                if (checked) R.string.crawl_mode_album else R.string.crawl_mode_timeline
            )
        }
        view.findViewById<View>(R.id.rowCrawlMode).setOnClickListener { switchMode.toggle() }

        val switchAuto = view.findViewById<MaterialSwitch>(R.id.switchCrawlAuto)
        switchAuto.isChecked = AccountStore.crawlAuto(requireContext())
        switchAuto.setOnCheckedChangeListener { _, checked ->
            AccountStore.setCrawlAuto(requireContext(), checked)
        }
        // 整行可点切换开关（点标题也算点开关，不必精确点到那个小滑块）
        view.findViewById<View>(R.id.rowCrawlAuto).setOnClickListener { switchAuto.toggle() }

        // 日志卡：卡头整行可点，收起时只显示一行摘要
        panel = LogPanel(
            host = view,
            lines = logLines,
            scrollId = R.id.crawlLogScroll,
            textId = R.id.crawlLog,
            summaryId = R.id.tvCrawlLogSummary,
            toggleId = R.id.btnCrawlLogToggle,
            headerId = R.id.crawlLogHeader,
            emptyTextRes = R.string.log_empty,
            cdExpandedRes = R.string.cd_crawl_log_expanded,
            cdCollapsedRes = R.string.cd_crawl_log_collapsed,
            maxLines = LOG_MAX_LINES,
        )
        view.findViewById<View>(R.id.crawlLogHeader).setOnClickListener {
            setLogExpanded(!logExpanded)
        }
        setLogExpanded(logExpanded)
        panel?.render()

        renderDir()
        renderTargets()
        refreshGalleryCount()
        refreshRetryCount()

        // 订阅抓取状态：首页自动抓图时本页也能看到进度，且能正确显示「停止」
        viewLifecycleOwner.lifecycleScope.launch {
            CrawlRunner.state.collect { state ->
                if (isAdded) renderRunState(state)
            }
        }
        renderRunState(CrawlRunner.state.value)
    }

    override fun onDestroyView() {
        panel = null
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) {
            renderDir()
            renderTargets()
            refreshGalleryCount()
            refreshRetryCount()
            renderRunState(CrawlRunner.state.value)
        }
    }

    private fun toast(text: String) = toastShort(text)

    private fun renderDir() {
        // 先取 view：本方法会从 onHiddenChanged / 授权回调里调用，页面可能已在销毁中。
        val host = view ?: return
        val ctx = host.context
        val raw = AccountStore.crawlTreeUri(ctx)
        val display = if (raw.isEmpty()) getString(R.string.crawl_dir_empty) else try {
            val segments = raw.toUri().pathSegments
            segments.lastOrNull { it.contains(':') }?.substringAfter(':')
                ?: segments.lastOrNull() ?: raw
        } catch (e: Exception) {
            raw
        }
        host.findViewById<TextView>(R.id.tvDirPath)?.text = display

        // 授权自检：被系统回收的授权要让用户当场看见，而不是等抓取全失败才发现。
        //
        // **必须放到后台线程**：check() 会走 ContentProvider 跨进程查询（目录可能是
        // 云盘 / U 盘），而本方法在冷启动时就会执行 —— 在主线程做，慢 provider 会把
        // 首屏直接卡住，严重的会被系统判 ANR。查完再回主线程点亮 pill。
        val pill = host.findViewById<TextView>(R.id.tvDirStatus) ?: return
        pill.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val status = SafAccess.check(ctx, raw)
            val readOnly = status == SafAccess.Status.Ok && raw.isNotEmpty() &&
                runCatching { SafAccess.readOnly(ctx, raw.toUri()) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                when (status) {
                    SafAccess.Status.Ok -> {
                        // 授权在，但有些第三方 provider 只给读权限：能列目录却写不进去
                        if (readOnly) {
                            pill.visibility = View.VISIBLE
                            pill.text = getString(R.string.crawl_saf_readonly)
                        } else {
                            pill.visibility = View.GONE
                        }
                    }
                    SafAccess.Status.Missing -> pill.visibility = View.GONE
                    SafAccess.Status.NoPermission -> {
                        pill.visibility = View.VISIBLE
                        pill.text = getString(R.string.crawl_saf_lost_action)
                    }
                    is SafAccess.Status.Unreadable -> {
                        pill.visibility = View.VISIBLE
                        pill.text = status.message
                    }
                }
            }
        }
    }

    // ---------- 抓取进度（v0.9.0） ----------

    private fun phaseLabel(phase: CrawlPhase): String = when (phase) {
        CrawlPhase.IDLE, CrawlPhase.PREPARE -> getString(R.string.crawl_phase_prepare)
        CrawlPhase.RETRY -> getString(R.string.crawl_phase_retry)
        CrawlPhase.NEW -> getString(R.string.crawl_phase_new)
        CrawlPhase.HISTORY -> getString(R.string.crawl_phase_history)
        CrawlPhase.DONE -> getString(R.string.crawl_phase_done)
    }

    /**
     * 把 [CrawlRunner.state] 画到运行卡上。
     *
     * 空闲时不清空进度块 —— 用最后一份快照显示「这轮抓了多少」，
     * 否则点完“停止”数字会整块消失，用户反而不知道刚才发生了什么。
     */
    private fun renderRunState(state: CrawlRunner.RunState) {
        val host = view ?: return
        val progress = (state as? CrawlRunner.RunState.Running)?.progress
        setRunningUi(progress != null)

        val box = host.findViewById<View>(R.id.crawlProgressBox)
        val bar = host.findViewById<LinearProgressIndicator>(R.id.prCrawl)
        val step = host.findViewById<TextView>(R.id.tvCrawlStep)
        val target = host.findViewById<TextView>(R.id.tvCrawlTarget)

        val shown = progress ?: lastProgress
        if (shown == null) {
            box.visibility = View.GONE
            return
        }
        if (progress != null) lastProgress = progress

        box.visibility = View.VISIBLE
        bar.setProgressCompat(if (progress == null) 100 else percent(shown), progress != null)
        step.text = if (shown.discovered > 0) {
            getString(R.string.crawl_stage_fmt, phaseLabel(shown.phase), shown.processed, shown.discovered)
        } else {
            phaseLabel(shown.phase)
        }
        val counts = getString(
            R.string.crawl_counts_fmt, shown.downloaded, shown.skipped, shown.failedSlots,
        )
        target.text = if (shown.targetTotal > 0 && shown.targetLabel.isNotEmpty()) {
            getString(
                R.string.crawl_target_step_fmt,
                shown.targetIndex, shown.targetTotal, shown.targetLabel,
            ) + " · " + counts
        } else {
            counts
        }
    }

    // ---------- 失败清单 / 重试（v0.9.0） ----------

    /**
     * 索引里有待重试的帖就把入口亮出来。
     *
     * 走索引旁的**计数提示文件**（几字节）：旧实现为此把整份 NDJSON 读进内存
     * （10 万张 ≈ 17MB 文件 / 25MB 内存），而本方法每次进页面都会跑。
     * 老版本还没有提示文件时才会解析一次，解析完 `close()` 会把它写出来。
     */
    private fun refreshRetryCount() {
        // 先取 view 再取 context：本方法会被「抓取结束 / 停止」的收尾块调用，
        // 那时用户可能已经退出页面，requireContext() 会抛 IllegalStateException。
        val host = view ?: return
        val ctx = host.context
        val raw = AccountStore.crawlTreeUri(ctx)
        val row = host.findViewById<View>(R.id.crawlRetryRow) ?: return
        if (raw.isEmpty()) {
            row.visibility = View.GONE
            return
        }
        val tag = CrawlIndex.treeTagOf(raw)
        AppScope.launch {
            var count = CrawlIndex.retryHintOf(ctx.filesDir, tag)
            if (count < 0) {
                count = try {
                    val idx = CrawlIndex.open(ctx.filesDir, tag)
                    try {
                        idx.load()
                        idx.pendingRetryCount()
                    } finally {
                        idx.close()
                    }
                } catch (e: Exception) {
                    0
                }
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val host2 = view ?: return@withContext
                row.visibility = if (count > 0) View.VISIBLE else View.GONE
                host2.findViewById<TextView>(R.id.tvRetryCount)?.text =
                    getString(R.string.crawl_retry_title, count)
            }
        }
    }

    /** 失败清单：列出「哪条帖子、缺几张」，底部一键只跑重试阶段。 */
    private fun showRetryDialog() {
        val ctx = requireContext()
        val raw = AccountStore.crawlTreeUri(ctx)
        if (raw.isEmpty()) {
            toast(getString(R.string.crawl_need_dir))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val items = try {
                val idx = CrawlIndex.open(ctx.filesDir, CrawlIndex.treeTagOf(raw))
                try {
                    idx.load()
                    describeFailures(idx.pendingRetries(System.currentTimeMillis(), 100))
                } finally {
                    idx.close()
                }
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (items.isEmpty()) {
                    toast(getString(R.string.crawl_retry_none))
                    refreshRetryCount()
                    return@withContext
                }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(getString(R.string.crawl_retry_title, items.size))
                    .setItems(items.toTypedArray(), null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.crawl_retry_action) { _, _ -> runRetryOnly() }
                    .show()
            }
        }
    }

    private fun runRetryOnly() {
        if (busy) {
            toast(getString(R.string.busy))
            return
        }
        val ctx = requireContext()
        busy = true
        setLogExpanded(true)
        log("==== ${getString(R.string.crawl_retry_running)} ====")
        crawlJob = AppScope.launch {
            try {
                if (CrawlRunner.runRetryOnly(ctx, ::log) == CrawlRunner.RunResult.Busy) {
                    log(ctx.getString(R.string.crawl_busy))
                }
            } catch (e: CancellationException) {
                log("⏹ 已停止")
            } catch (e: Exception) {
                log("✗ 重试异常：${e.message}")
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    busy = false
                    refreshGalleryCount()
                    refreshRetryCount()
                }
            }
        }
    }

    // ---------- 图库（v0.4.0） ----------

    private fun openGallery() {
        val ctx = requireContext()
        val raw = AccountStore.crawlTreeUri(ctx)
        if (raw.isEmpty()) {
            toast(getString(R.string.gallery_need_dir))
            return
        }
        GalleryBrowseActivity.start(ctx, raw)
    }

    /** 图库卡计数：目录不可读/为空显示空态文案。 */
    private fun refreshGalleryCount() {
        // 与 refreshRetryCount 同理：这是收尾块里会调用的函数，页面可能已经销毁。
        val host = view ?: return
        val ctx = host.context
        val raw = AccountStore.crawlTreeUri(ctx)
        val tv = host.findViewById<TextView>(R.id.tvGalleryCount) ?: return
        if (raw.isEmpty()) {
            tv.text = getString(R.string.gallery_count_none)
            return
        }
        val resolver = ctx.contentResolver
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val count = try {
                GalleryFiles.listImages(resolver, raw.toUri()).size
            } catch (e: Exception) {
                -1
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                tv.text = when {
                    count <= 0 -> getString(R.string.gallery_count_none)
                    else -> getString(R.string.gallery_count_fmt, count)
                }
            }
        }
    }

    private fun renderTargets() {
        val ctx = requireContext()
        val container = view?.findViewById<ViewGroup>(R.id.crawlTargetList) ?: return
        container.removeAllViews()
        val targets = AccountStore.crawlTargets(ctx)
        // 标题带数量：不用点进去就知道配了几个目标
        view?.findViewById<TextView>(R.id.tvTargetsTitle)?.text =
            if (targets.isEmpty()) {
                getString(R.string.crawl_targets_title)
            } else {
                getString(R.string.crawl_targets_count_fmt, targets.size)
            }
        if (targets.isEmpty()) {
            container.addView(buildEmptyHint(getString(R.string.crawl_target_empty)))
            return
        }
        val inflater = LayoutInflater.from(ctx)
        val treeTag = CrawlIndex.treeTagOf(AccountStore.crawlTreeUri(ctx))
        val cursors = AccountStore.allCursors(ctx, treeTag)
        targets.forEach { t ->
            val row = inflater.inflate(R.layout.item_crawl_target, container, false)
            row.findViewById<TextView>(R.id.tvTargetName).text =
                t.name.ifEmpty { "UID ${t.uid}" }
            row.findViewById<TextView>(R.id.tvTargetUid).text = t.uid
            row.findViewById<TextView>(R.id.tvTargetProgress).text =
                describeProgress(ctx, t, cursors[t.uid])
            val more = row.findViewById<View>(R.id.btnTargetMore)
            more.setOnClickListener { showTargetMenu(it, t) }
            // 整行也可点：样式里有 ripple，若不接监听就会变成"有反馈但没反应"的假可点，
            // 读屏还会把它念成可点击。点整行 = 打开同一个「起始日期 / 移除」菜单。
            row.setOnClickListener { showTargetMenu(more, t) }
            container.addView(row)
        }
    }

    /** 空态提示：套统一文本样式，避免代码里写死字号/颜色。 */
    private fun buildEmptyHint(text: String): TextView = requireContext().emptyHintView(text)

    /**
     * 目标行溢出菜单：起始日期 / 移除。
     * 原来这两个入口以文字按钮常驻在行尾，一行 3 个点击目标极易误触。
     */
    private fun showTargetMenu(anchor: View, target: AccountStore.CrawlTarget) {
        val ctx = requireContext()
        val popup = PopupMenu(ctx, anchor)
        popup.menuInflater.inflate(R.menu.item_crawl_target_actions, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_target_since -> {
                    pickSince(target)
                    true
                }
                R.id.action_target_delete -> {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(getString(R.string.crawl_remove_title, target.name))
                        .setMessage(getString(R.string.crawl_remove_msg))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            AccountStore.removeCrawlTarget(ctx, target.uid)
                            renderTargets()
                        }
                        .show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** 进度行：已覆盖区间 + 起始日期 + 是否到底，让用户看得出「抓到哪天」。 */
    private fun describeProgress(
        ctx: android.content.Context,
        target: AccountStore.CrawlTarget,
        cursor: CrawlCursor?,
    ): String {
        val sb = StringBuilder()
        if (cursor != null && cursor.oldestCoveredAt > 0L && cursor.newestAt > 0L) {
            sb.append(
                getString(
                    R.string.crawl_progress_fmt,
                    CrawlDate.dateOf(cursor.oldestCoveredAt),
                    CrawlDate.dateOf(cursor.newestAt),
                )
            )
        } else {
            sb.append(getString(R.string.crawl_progress_none))
        }
        val since = AccountStore.crawlSince(ctx, target.uid)
        if (since.isNotEmpty()) sb.append(" · ").append(getString(R.string.crawl_progress_from, since))
        if (cursor?.reachedBottom == true) sb.append(getString(R.string.crawl_progress_bottom))
        return sb.toString()
    }

    /** 选择「从哪天开始向新方向抓」。 */
    private fun pickSince(target: AccountStore.CrawlTarget) {
        val ctx = requireContext()
        val treeTag = CrawlIndex.treeTagOf(AccountStore.crawlTreeUri(ctx))
        val cursor = AccountStore.crawlCursor(ctx, treeTag, target.uid)
        val current = AccountStore.crawlSince(ctx, target.uid)

        val constraints = CalendarConstraints.Builder()
            .setValidator(DateValidatorPointBackward.now())   // 不允许未来
            .build()
        val builder = MaterialDatePicker.Builder.datePicker()
            .setTitleText(getString(R.string.crawl_since_title, target.name.ifEmpty { target.uid }))
            .setCalendarConstraints(constraints)
            .setInputMode(MaterialDatePicker.INPUT_MODE_CALENDAR)
        CrawlDate.toPickerMillis(current)?.let { builder.setSelection(it) }

        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { millis ->
            val picked = CrawlDate.fromPickerMillis(millis)
            AccountStore.setCrawlSince(ctx, target.uid, picked)
            AccountStore.saveCrawlCursor(ctx, treeTag, CursorLogic.onSinceDateChanged(cursor, picked))
            renderTargets()
            log(getString(R.string.crawl_since_saved, picked))
        }
        picker.show(childFragmentManager, "since_date")
    }

    private fun addTarget() {
        val ctx = requireContext()
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_target, null)
        val et = dialogView.findViewById<TextInputEditText>(R.id.etTargetInput)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.crawl_add_title))
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val input = et.text?.toString() ?: ""
                AppScope.launch {
                    val uid = WeiboApi.resolveUid(input)
                    if (uid.isEmpty()) {
                        log("✗ 无法解析 UID：$input")
                        return@launch
                    }
                    // 昵称走游客身份也能拿到：没有登录账号时依然能把博主名显示出来
                    val accounts = AccountStore.load(ctx)
                    val cookie = if (accounts.isEmpty()) null else Crawler.pickCrawlCookie(accounts, ::log)
                    val name = WeiboApi.fetchUserName(cookie, uid)
                    AccountStore.addCrawlTarget(ctx, AccountStore.CrawlTarget(uid, name))
                    withContext(Dispatchers.Main) { renderTargets() }
                    log("✓ 已添加：${name.ifEmpty { uid }}（$uid）")
                }
            }
            .show()
    }

    // ========================= 日志 =========================

    /** 追加一行日志（带时间戳）；实现见 [LogPanel]。 */
    private fun log(line: String) {
        panel?.append(stamp(line))
    }

    /** 展开 / 收起抓取日志（默认收起，运行时自动展开）。 */
    private fun setLogExpanded(expanded: Boolean) {
        logExpanded = expanded
        panel?.setExpanded(expanded)
    }

    /**
     * 运行态切换：空闲时只显示「开始抓取」，运行中只显示「停止抓取」。
     *
     * 两个按钮不再并排常驻 —— 任何时候用户面前只有一个明确的下一步动作，
     * 也不会出现「开始」灰着但「停止」亮着却不知道该点哪个的犹豫。
     */
    private fun setRunningUi(running: Boolean) {
        val host = view ?: return
        val startButton = host.findViewById<View>(R.id.btnCrawlStart)
        val stopButton = host.findViewById<View>(R.id.btnCrawlStop)
        startButton?.visibility = if (running) View.GONE else View.VISIBLE
        stopButton?.visibility = if (running) View.VISIBLE else View.GONE
        startButton?.isEnabled = !running
        host.findViewById<View>(R.id.btnAddTarget)?.isEnabled = !running
    }

    private fun start() {
        if (busy) {
            toast(getString(R.string.busy))
            return
        }
        val ctx = requireContext()
        if (AccountStore.crawlTargets(ctx).isEmpty()) {
            toast(getString(R.string.crawl_need_target))
            return
        }
        if (AccountStore.crawlTreeUri(ctx).isEmpty()) {
            toast(getString(R.string.crawl_need_dir))
            return
        }
        busy = true
        setRunningUi(true)
        setLogExpanded(true)            // 运行时自动展开日志
        crawlJob = AppScope.launch {
            try {
                val modeName = if (AccountStore.crawlMode(ctx) == AccountStore.MODE_ALBUM) "相册" else "时间线"
                log("==== 抓取开始（$modeName · Live→${if (AccountStore.crawlGif(ctx)) "GIF" else "MOV"}） ====")
                // 与首页自动抓图共用一个闸门：拿不到锁立即返回，不排队、不并发写同一目录
                if (CrawlRunner.runAll(ctx, ::log) == CrawlRunner.RunResult.Busy) {
                    log(ctx.getString(R.string.crawl_busy))
                } else {
                    log("==== 抓取结束 ====")
                }
            } catch (e: CancellationException) {
                log("⏹ 已停止（进度已保存，可续跑）")
            } catch (e: Exception) {
                log("✗ 抓取异常：${e.message}")
            } finally {
                // 用 NonCancellable：「停止」按钮会取消本协程，若这里跟着被取消，
                // busy 就永远回不到 false，开始抓取的按钮会一直灰着。
                withContext(Dispatchers.Main + NonCancellable) {
                    busy = false
                    setRunningUi(false)
                    refreshGalleryCount()
                }
            }
        }
    }

    // ---------- 单条抓取（v0.3.1） ----------

    private fun pickSinglePost() {
        if (busy) {
            toast(getString(R.string.busy))
            return
        }
        val ctx = requireContext()
        val dialogView = layoutInflater.inflate(R.layout.dialog_single_post, null)
        val et = dialogView.findViewById<TextInputEditText>(R.id.etSingleInput)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.single_title))
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val input = et.text?.toString() ?: ""
                AppScope.launch {
                    val bid = WeiboApi.parseBidFromInput(input)
                    if (bid.isNotEmpty()) {
                        startSingle(ctx, bid)
                        return@launch
                    }
                    // 不是微博链接：当博主 UID 处理，拉最近微博供选择
                    val uid = WeiboApi.resolveUid(input)
                    if (uid.isEmpty()) {
                        log("✗ 无法识别输入：$input")
                        return@launch
                    }
                    val cookie = Crawler.pickCrawlCookie(AccountStore.load(ctx), ::log)
                        ?: return@launch
                    log("正在拉取博主 $uid 的最近微博…")
                    val posts = ArrayList<WeiboApi.RawPost>()
                    for (page in 1..2) {
                        try {
                            val (_, list) = WeiboApi.fetchUserPosts(cookie, uid, page)
                            posts.addAll(list)
                            if (list.isEmpty()) break
                        } catch (e: Exception) {
                            break
                        }
                    }
                    if (posts.isEmpty()) {
                        log("✗ 没有拉到微博（UID 是否正确？）")
                        return@launch
                    }
                    withContext(Dispatchers.Main) { showPostPicker(posts) }
                }
            }
            .show()
    }

    /** 简单的微博选择器：时间 + 图数 + 置顶/转发标记 + 正文摘要，点选即抓。 */
    private fun showPostPicker(posts: List<WeiboApi.RawPost>) {
        val items = posts.map { p ->
            val t = WeiboApi.parseWeiboTime(p.createdAt)
            val whenStr = if (t != null) {
                "${t.first.substring(5)} ${t.second.substring(0, 5)}"
            } else {
                "未知时间"
            }
            val tags = buildString {
                if (p.pinned) append(" · 置顶")
                if (p.isRetweet) append(" · 转发")
                else if (p.picNum > 0) append(" · ${p.picNum}图")
                else append(" · 无图")
            }
            "$whenStr$tags\n${p.text.ifEmpty { "（无文字）" }}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.single_pick_title, posts.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setSingleChoiceItems(items, -1) { dialog, which ->
                dialog.dismiss()
                val bid = posts[which].bid
                val appCtx = requireContext().applicationContext
                AppScope.launch { startSingle(appCtx, bid) }
            }
            .show()
    }

    private suspend fun startSingle(ctx: Context, bid: String) {
        // busy 只在主线程读写，避免并发点击下双重启动
        val gate = withContext(Dispatchers.Main) {
            if (busy) return@withContext false
            busy = true
            setLogExpanded(true)
            view?.findViewById<View>(R.id.btnCrawlStart)?.isEnabled = false
            view?.findViewById<View>(R.id.btnSinglePost)?.isEnabled = false
            true
        }
        if (!gate) return
        try {
            if (CrawlRunner.runSingle(ctx, bid, ::log) == CrawlRunner.RunResult.Busy) {
                log(ctx.getString(R.string.crawl_busy))
            }
        } catch (e: CancellationException) {
            log("⏹ 单条抓取被取消")
        } catch (e: Exception) {
            log("✗ 单条抓取异常：${e.message}")
        } finally {
            withContext(Dispatchers.Main + NonCancellable) {
                busy = false
                view?.findViewById<View>(R.id.btnCrawlStart)?.isEnabled = true
                view?.findViewById<View>(R.id.btnSinglePost)?.isEnabled = true
                refreshGalleryCount()
            }
        }
    }
}
