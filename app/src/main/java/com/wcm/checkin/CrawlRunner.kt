package com.wcm.checkin

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 抓取单闸门：全应用同一时刻只允许一个抓取任务（增量 / 单条也算同一个）。
 *
 * 为什么必须有：首页签到后的自动抓图与图库页的手动抓取原本各跑各的，
 * 两个 [Crawler.TreeStore] 各自缓存目录名快照，同时写同一目录会撞名产生
 * `xxx (1).jpg` 副本；旧版进度是全量覆盖写，后结束的一轮会把先结束的进度盖回去。
 *
 * 采用**非阻塞**闸门（`tryLock`）：拿不到锁立即返回 [RunResult.Busy]，
 * 绝不排队 —— 排两轮全量只会更慢，用户也会困惑。
 *
 * v0.9.0：
 * - [state] 从「在跑什么」升级成结构化 [CrawlProgress]（阶段 / 计数 / 进度），
 *   图片页订阅它就能画出进度条，不再只能靠读日志猜；
 * - 新增 [requestStop]：停止信号由闸门本身持有，**首页自动触发的抓取也能停**
 *   （旧版只取消当前页面的协程，自动抓取根本停不下来）。
 */
object CrawlRunner {

    sealed interface RunState {
        data object Idle : RunState
        data class Running(val progress: CrawlProgress) : RunState
    }

    sealed interface RunResult {
        data object Done : RunResult
        data object Busy : RunResult
    }

    private val gate = Mutex()
    private val stopFlag = AtomicBoolean(false)
    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value is RunState.Running

    /**
     * 全量增量抓取（签到后自动 / 图库页手动共用）。
     *
     * 按 [AccountStore.crawlMode] 分派：
     * - `album`（默认）：游客身份走相册墙，只抓有图微博；拿不到游客身份时
     *   **自动回退**到时间线模式，用户不会因为风控/接口变化而「点了没反应」。
     * - `timeline`：原实现。
     */
    suspend fun runAll(
        ctx: Context,
        log: (String) -> Unit,
        auto: Boolean = false,
    ): RunResult {
        val album = AccountStore.crawlMode(ctx) == AccountStore.MODE_ALBUM
        return guarded(if (album) "相册抓取" else "增量抓取") { emit, stopped ->
            if (!album) {
                Crawler.runIncremental(
                    ctx = ctx,
                    log = log,
                    autoMode = auto,
                    onEvent = emit,
                    isStopped = stopped,
                )
                return@guarded
            }
            val ok = Crawler.runAlbum(
                ctx = ctx,
                log = log,
                autoMode = auto,
                onEvent = emit,
                isStopped = stopped,
            )
            if (!ok) {
                log("相册模式没跑起来（游客身份不可用或相册为空），回退到时间线模式")
                Crawler.runIncremental(
                    ctx = ctx,
                    log = log,
                    autoMode = auto,
                    onEvent = emit,
                    isStopped = stopped,
                )
            }
        }
    }

    /** 只跑失败重试（跳过 A/B 两个翻页阶段），用于「待重试 N 帖 → 立即重试」。 */
    suspend fun runRetryOnly(
        ctx: Context,
        log: (String) -> Unit,
    ): RunResult = guarded("补抓失败") { emit, stopped ->
        Crawler.runIncremental(
            ctx = ctx,
            log = log,
            retryOnly = true,
            onEvent = emit,
            isStopped = stopped,
        )
    }

    /** 单条抓取。它写同一个目录与同一份索引，必须共用闸门。 */
    suspend fun runSingle(
        ctx: Context,
        bid: String,
        log: (String) -> Unit,
    ): RunResult = guarded("单条抓取") { _, _ ->
        Crawler.crawlSinglePost(ctx, bid, log)
    }

    /**
     * 勾选抓取（浏览页 / 选图页提交，v0.9.11）。
     *
     * 与单条抓取同一个闸门、同一份索引、同一个停止信号 —— 浏览页只读接口
     * （列表 / 缩略图）不占闸门，但只要开始下载就按同一规则串行。
     *
     * 返回 null 表示闸门被占用（增量任务在跑）；否则返回落盘汇总。
     * 与 [guarded] 同构而不复用它：guarded 只回 Done/Busy，而勾选抓取的
     * 调用方（无日志面板的页面）需要拿 saved/failed 计数做结果 toast。
     */
    suspend fun runPicked(
        ctx: Context,
        tasks: List<Crawler.PickTask>,
        log: (String) -> Unit,
    ): Crawler.PickedSummary? {
        if (!gate.tryLock()) return null
        stopFlag.set(false)
        _state.value = RunState.Running(
            CrawlProgress(
                phase = CrawlPhase.PREPARE,
                what = "勾选抓取",
                since = System.currentTimeMillis(),
            )
        )
        try {
            return Crawler.crawlPicked(ctx, tasks, log, isStopped = { stopFlag.get() })
        } finally {
            _state.value = RunState.Idle
            stopFlag.set(false)
            gate.unlock()
        }
    }

    /** 请求停止当前任务：Crawler 在每帖 / 每阶段之间检查这个标志。 */
    fun requestStop() {
        stopFlag.set(true)
    }

    private suspend fun guarded(
        what: String,
        block: suspend (emit: (CrawlEvent) -> Unit, stopped: () -> Boolean) -> Unit,
    ): RunResult {
        if (!gate.tryLock()) return RunResult.Busy
        stopFlag.set(false)
        _state.value = RunState.Running(
            CrawlProgress(
                phase = CrawlPhase.PREPARE,
                what = what,
                since = System.currentTimeMillis(),
            )
        )
        // 事件在 IO 线程发；StateFlow 的赋值本身是线程安全的
        val emit: (CrawlEvent) -> Unit = { event ->
            val current = _state.value
            if (current is RunState.Running) {
                _state.value = RunState.Running(reduce(current.progress, event))
            }
        }
        try {
            block(emit) { stopFlag.get() }
            return RunResult.Done
        } finally {
            _state.value = RunState.Idle
            stopFlag.set(false)
            gate.unlock()
        }
    }
}
