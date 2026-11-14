package com.wcm.checkin

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * 抓取游标：记录「已完整覆盖的时间区间」与「历史方向的断点锚点」。
 *
 * 关键设计：
 * 1. 游标按 **(相册目录 treeTag, uid)** 双键存放 —— 换目录后新 tag 天然是空游标，
 *    不会继承旧目录的进度（旧实现 `crawl_done` 与目录无关，换到新空目录后第 1 页
 *    就判「连续 10 条已完成」直接退出，一张都抓不到）。
 * 2. 断点**不存页码**，存 `(anchorAt, anchorPostId)`。新帖插入会让页码整体下移、
 *    删帖会让页码上移，页码只能当搜索起点提示，永远不能当「已完成到第几页」的断言。
 * 3. `newestAt` / `oldestCoveredAt` 单调外扩，只增不减；任何失败都不推进边界。
 */
data class CrawlCursor(
    val uid: String = "",
    val newestAt: Long = 0L,          // 已覆盖区间上端（最新），epoch 秒
    val oldestCoveredAt: Long = 0L,   // 已覆盖区间下端（最老），0 = 尚无覆盖
    val anchorPostId: String = "",    // 历史方向断点帖 id（会被删帖打掉，仅用于快速定位）
    val anchorAt: Long = 0L,          // 历史方向断点时间（真正的语义锚点）
    val anchorPageHint: Int = 1,      // 上次所在页码，仅作搜索起点
    val reachedBottom: Boolean = false,
    val historyParked: Boolean = false,
    /**
     * 全量扫描（v0.9.12）的续跑页码，0 表示「没有进行中的全量扫描」。
     *
     * 与 [anchorPageHint] 分开存：增量方向的锚点是「时间锚点 + 搜索起点」，
     * 全量扫描是一次从最新往最老的**连续下行**，它的断点就是「下一次该翻第几页」。
     * 复用同一个字段会让两条方向的进度互相覆盖 —— 全量扫到第 40 页后
     * 再跑一轮增量，下一次全量就得从增量留下的锚点重新猜位置。
     */
    val fullScanPageHint: Int = 0,
) {
    val hasCoverage: Boolean get() = newestAt > 0L || oldestCoveredAt > 0L

    /** 是否有未完成的全量扫描（UI 展示「全量已扫到第 N 页」）。 */
    val fullScanInProgress: Boolean get() = fullScanPageHint > 0

    fun toJson(): JSONObject = JSONObject()
        .put("uid", uid)
        .put("newestAt", newestAt)
        .put("oldest", oldestCoveredAt)
        .put("anchorId", anchorPostId)
        .put("anchorAt", anchorAt)
        .put("hint", anchorPageHint)
        .put("bottom", reachedBottom)
        .put("parked", historyParked)
        .put("scanPage", fullScanPageHint)

    fun applyJson(o: JSONObject): CrawlCursor = copy(
        uid = o.optString("uid", uid),
        newestAt = o.optLong("newestAt", newestAt),
        oldestCoveredAt = o.optLong("oldest", oldestCoveredAt),
        anchorPostId = o.optString("anchorId", anchorPostId),
        anchorAt = o.optLong("anchorAt", anchorAt),
        anchorPageHint = o.optInt("hint", anchorPageHint).coerceAtLeast(1),
        reachedBottom = o.optBoolean("bottom", reachedBottom),
        historyParked = o.optBoolean("parked", historyParked),
        fullScanPageHint = o.optInt("scanPage", fullScanPageHint).coerceAtLeast(0),
    )

    companion object {
        fun fromJson(o: JSONObject): CrawlCursor = CrawlCursor().applyJson(o)
    }
}

/** 已覆盖区间。只有能证明「连续无洞」的区间才允许写入。 */
data class Coverage(val oldestAt: Long, val newestAt: Long) {
    val isEmpty: Boolean get() = newestAt <= 0L && oldestAt <= 0L

    /** 起始页与既有区间是否重叠（防上方删帖造成的空洞）。 */
    fun overlaps(at: Long): Boolean = !isEmpty && at >= oldestAt && at <= newestAt
}

/** 单轮工作量上限：避免一次跑几小时，预算耗尽不是错误。 */
data class Budget(
    val maxPages: Int = 6,
    val maxDetails: Int = 200,
    val maxFiles: Int = 400,
    val maxWallClockMs: Long = 25 * 60_000L,
) {
    companion object {
        val NEW = Budget(maxPages = 6)
        val HISTORY = Budget(maxPages = 20)
        val RETRY = Budget(maxPages = 0, maxDetails = 50, maxFiles = 150)

        /**
         * 全量（单博主 / 全部目标，v0.9.12）单轮预算。
         *
         * 只放宽**翻页**上限到 20 页：全量意味着「从头重新枚举一遍」，
         * 已抓过的帖在索引里是终态，只花列表请求、不拉详情也不重复下载，
         * 所以多翻几页很便宜；而下载/详情/时间三项上限保持与增量一致，
         * 避免一次全量把手机跑几个小时（预算耗尽可再点一次接着走）。
         */
        val FULL = Budget(maxPages = 20)
    }
}

enum class Phase { NEW, HISTORY, RETRY, SCAN }

enum class StopReason {
    /** 列表返回空页：真到底了 */
    PAGE_EXHAUSTED,
    /** 已推到用户设定的起始日期 */
    SINCE_DATE,
    /** 单轮预算耗尽（下轮从锚点接上，不重走） */
    BUDGET,
    /** 某页请求失败：边界只推进到失败页之前 */
    PAGE_ERROR,
    /** 已回到既有覆盖区间，本方向无事可做 */
    ALL_COVERED,
    /** MAX_PAGES 硬墙 */
    HARD_CAP,
    /** 还没开始 */
    NONE,
}

/**
 * 日期工具。微博 created_at 固定 +0800，全部换算统一按 +0800，
 * 而 MaterialDatePicker 返回的是 **UTC 当日 0 点** —— 两者必须分开处理，
 * 否则东八区用户选 1 号会显示成 31 号。
 */
object CrawlDate {

    private val WEIBO_ZONE: ZoneOffset = ZoneOffset.ofHours(8)

    /** "2024-01-01" → 该日 00:00:00(+0800) 的 epoch 秒。非法返回 null。 */
    fun dayStartSec(date: String): Long? = try {
        LocalDate.parse(date).atStartOfDay(WEIBO_ZONE).toEpochSecond()
    } catch (e: Exception) {
        null
    }

    /** ("2026-10-22", "15:27:12") → epoch 秒（+0800）。 */
    fun epochSecOf(date: String, time: String): Long? = try {
        LocalDate.parse(date).atTime(LocalTime.parse(time)).toEpochSecond(WEIBO_ZONE)
    } catch (e: Exception) {
        null
    }

    fun isValid(date: String): Boolean =
        date.isEmpty() || runCatching { LocalDate.parse(date) }.isSuccess

    /** "2024-01-01" → MaterialDatePicker 需要的 UTC 毫秒。 */
    fun toPickerMillis(date: String): Long? = try {
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    /** MaterialDatePicker 回调的 UTC 毫秒 → "yyyy-MM-dd"。 */
    fun fromPickerMillis(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** epoch 秒 → (+0800) "yyyy-MM-dd"，用于日志与 UI 回显。 */
    fun dateOf(epochSec: Long): String = try {
        Instant.ofEpochSecond(epochSec).atZone(WEIBO_ZONE).toLocalDate().toString()
    } catch (e: Exception) {
        // 异常 epoch（历史脏数据 / 时区缺失的设备）此前会在这里抛 DateTimeException，
        // 而调用点都在 UI 渲染路径上 —— 一次脏游标就能让页面崩掉。
        ""
    }
}

/** 游标演算（纯函数，JVM 单测覆盖）。 */
object CursorLogic {

    /**
     * 用户改了起始日期。只解除「历史方向停靠」，**绝不动 newestAt / oldestCoveredAt** ——
     * 那是物理事实（哪些帖真的抓过），不是配置。
     */
    fun onSinceDateChanged(c: CrawlCursor, picked: String): CrawlCursor {
        val pickedSec = CrawlDate.dayStartSec(picked)
        val parkImmediately = picked.isNotEmpty() && pickedSec != null &&
            c.oldestCoveredAt > 0L && pickedSec >= c.oldestCoveredAt
        return c.copy(
            // 日期往后收（只留更新的部分）→ 历史方向直接停靠，不再白翻页
            historyParked = parkImmediately || (picked.isNotEmpty() && c.reachedBottom),
        )
    }

    /** Phase A 结束：覆盖区间上端只增不减。 */
    fun afterNewPhase(c: CrawlCursor, coverageHigh: Long, coverageLow: Long): CrawlCursor {
        if (coverageHigh <= 0L) return c
        return c.copy(
            newestAt = maxOf(c.newestAt, coverageHigh),
            oldestCoveredAt = if (c.oldestCoveredAt <= 0L) coverageLow else minOf(c.oldestCoveredAt, coverageLow),
        )
    }

    /** Phase B 结束：覆盖区间下端只减不增；同时更新锚点。 */
    fun afterHistoryPhase(
        c: CrawlCursor,
        coverageLow: Long,
        anchorPostId: String,
        anchorAt: Long,
        hintPage: Int,
        reachedBottom: Boolean = false,
        parked: Boolean = false,
    ): CrawlCursor = c.copy(
        // coverageLow <= 0 表示本轮没走到任何可解析时间的帖：边界保持不动
        oldestCoveredAt = when {
            coverageLow <= 0L -> c.oldestCoveredAt
            c.oldestCoveredAt <= 0L -> coverageLow
            else -> minOf(c.oldestCoveredAt, coverageLow)
        },
        anchorPostId = anchorPostId.ifEmpty { c.anchorPostId },
        // 锚点只能变老：历史阶段是在往更早的方向推进，回退会让同一段反复重扫
        anchorAt = when {
            anchorAt <= 0L -> c.anchorAt
            c.anchorAt <= 0L -> anchorAt
            else -> minOf(anchorAt, c.anchorAt)
        },
        anchorPageHint = if (hintPage >= 1) hintPage else c.anchorPageHint,
        reachedBottom = c.reachedBottom || reachedBottom,
        historyParked = c.historyParked || parked,
    )
}

/**
 * 相册模式的「上次抓到哪条」记忆（v0.9.17）：值形如 `mid|yyyy-MM`。
 *
 * 为什么需要它：相册模式的「补新」每轮都从墙顶开始翻（`ALBUM_NEW_PAGES` 页），
 * 逐条查索引判断是否抓过 —— 没新帖时也一样，等于每轮都把已经抓过的几条
 * 重新枚举一遍、重新查一遍索引。记住上一轮走到的那条微博后，翻墙碰到它就不再
 * 往更旧的页翻：「它前面都抓过，后面还没（下一轮的活）」—— 当前页仍会走完，
 * 因为相册模式没有独立的 RETRY 阶段，本页上次失败的帖只能靠这一遍补。
 *
 * 只存 mid 不够、还要带上「年-月」：日志里要能说出「从哪天的那条之后接着抓」，
 * 而 mid 是一串数字，用户认不出来。
 *
 * 纯字符串编解码，JVM 单测覆盖（见 `CrawlCursorTest`）。
 */
object AlbumMemo {

    /** `(mid, ym)` → 记忆值。mid 为空时返回空串（= 没有记忆）。 */
    fun encode(mid: String, ym: String): String {
        val m = mid.trim()
        if (m.isEmpty()) return ""
        val y = ym.trim()
        return if (y.isEmpty()) m else "$m|$y"
    }

    /** 记忆值 → mid；兼容只存了 mid 的旧值。 */
    fun midOf(value: String): String = value.substringBefore('|').trim()

    /** 记忆值 → `yyyy-MM`；没有这一段时返回空串。 */
    fun ymOf(value: String): String =
        if (value.contains('|')) value.substringAfter('|').trim() else ""
}
