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
    /**
     * **当前目标**本轮累计计划处理的帖数（RETRY + A + B 三阶段累加），进度条的分母。
     *
     * 换目标（[CrawlEvent.Target]）时归零：进度条回答的是「这个博主还剩多少」，
     * 而不是把三个博主的工作量加在一起 —— 加起来会让比例在每个新目标处腰斩，
     * 看起来像进度倒退。`downloaded / skipped / failedSlots` 仍是整轮累计。
     */
    val discovered: Int = 0,
    val processed: Int = 0,
    val downloaded: Int = 0,
    val skipped: Int = 0,
    val failedSlots: Int = 0,
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

/**
 * 事件 → 新快照。
 *
 * 三条不变量：
 * 1. **同一轮内不整体重置**：相册模式回退到时间线时会再发一次 [CrawlEvent.Begin]，
 *    旧实现直接 `CrawlProgress(...)` 归零，用户看到进度条从 30% 掉回 0；
 * 2. **换目标时才重置进度**：`discovered / processed` 描述「当前这个博主还剩多少」，
 *    跨目标累加会让比例在每个新目标处腰斩（详见 [CrawlProgress.discovered]）；
 * 3. **`processed` 永不超过 `discovered`**：新阶段的分母在已处理数之上累加，
 *    否则会出现 120% 这种值。
 */
fun reduce(prev: CrawlProgress, event: CrawlEvent, now: Long = System.currentTimeMillis()): CrawlProgress =
    when (event) {
        is CrawlEvent.Begin -> if (prev.running) {
            // 同一轮里的第二段（相册 → 时间线回退）：保留计数，只更新当前在跑什么
            prev.copy(what = event.what, targetTotal = event.targets)
        } else {
            CrawlProgress(
                phase = CrawlPhase.PREPARE,
                what = event.what,
                since = now,
                targetTotal = event.targets,
            )
        }
        is CrawlEvent.Target -> prev.copy(
            targetLabel = event.label,
            targetIndex = event.index,
            targetTotal = event.total,
            // 新目标：进度按「当前目标」重新计（下载/跳过/失败仍是整轮累计）
            discovered = 0,
            processed = 0,
        )
        is CrawlEvent.Phase -> prev.copy(
            phase = event.phase,
            discovered = maxOf(prev.discovered, prev.processed) + event.planned.coerceAtLeast(0),
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
