package com.wcm.checkin

/**
 * 抓取进度的可计算模型（v0.9.0）。
 *
 * 把「抓取跑到哪了」从字符串日志里解耦出来：Crawler 只发事件，
 * 这里用纯函数 [reduce] 累加成一份可渲染的快照，UI 订阅 [CrawlRunner.state] 即可。
 *
 * 为什么要做成纯函数：进度算错的表现是「进度条倒退 / 数字乱跳」，
 * 这类问题靠人眼在真机上盯日志几乎发现不了，必须能被 JVM 单测钉住。
 */
enum class CrawlPhase { IDLE, PREPARE, RETRY, NEW, HISTORY, DONE }

data class CrawlProgress(
    val phase: CrawlPhase = CrawlPhase.IDLE,
    val what: String = "",
    val since: Long = 0L,
    val targetLabel: String = "",
    val targetIndex: Int = 0,
    val targetTotal: Int = 0,
    /** 本轮计划处理的帖数（RETRY + A + B 三阶段累加），进度条的分母。 */
    val discovered: Int = 0,
    val processed: Int = 0,
    val downloaded: Int = 0,
    val skipped: Int = 0,
    val failedSlots: Int = 0,
    val message: String = "",
) {
    val running: Boolean get() = phase != CrawlPhase.IDLE && phase != CrawlPhase.DONE
}

sealed interface CrawlEvent {
    data class Begin(val what: String, val targets: Int) : CrawlEvent
    data class Target(val label: String, val index: Int, val total: Int) : CrawlEvent
    data class Phase(val phase: CrawlPhase, val planned: Int, val skipped: Int) : CrawlEvent
    data class Post(val saved: Int, val failed: Int) : CrawlEvent
    data object Finish : CrawlEvent
}

/** 事件 → 新快照。所有计数只增不减，保证进度条单调。 */
fun reduce(prev: CrawlProgress, event: CrawlEvent, now: Long = System.currentTimeMillis()): CrawlProgress =
    when (event) {
        is CrawlEvent.Begin -> CrawlProgress(
            phase = CrawlPhase.PREPARE,
            what = event.what,
            since = now,
            targetTotal = event.targets,
        )
        is CrawlEvent.Target -> prev.copy(
            targetLabel = event.label,
            targetIndex = event.index,
            targetTotal = event.total,
        )
        is CrawlEvent.Phase -> prev.copy(
            phase = event.phase,
            discovered = prev.discovered + event.planned.coerceAtLeast(0),
            skipped = prev.skipped + event.skipped.coerceAtLeast(0),
        )
        is CrawlEvent.Post -> prev.copy(
            processed = prev.processed + 1,
            downloaded = prev.downloaded + event.saved.coerceAtLeast(0),
            failedSlots = prev.failedSlots + event.failed.coerceAtLeast(0),
        )
        CrawlEvent.Finish -> prev.copy(phase = CrawlPhase.DONE)
    }

/** 进度百分比：分母还没算出来时返回 0（不显示一个假的满格）。 */
fun percent(progress: CrawlProgress): Int {
    if (progress.discovered <= 0) return 0
    return (progress.processed * 100 / progress.discovered).coerceIn(0, 100)
}

/** 失败清单的可读摘要：`postId · 缺 N 张`。 */
fun describeFailures(records: List<CrawlIndex.PostRec>): List<String> =
    records.map { rec ->
        val missing = rec.failed.size
        if (missing > 0) "${rec.postId} · 缺 $missing 张" else rec.postId
    }
