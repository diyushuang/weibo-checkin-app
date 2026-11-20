package com.wcm.checkin

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 时间与日期口径的唯一出口。
 *
 * 为什么单独拎出来：项目里此前散落着多处 `SimpleDateFormat`，同一天在不同地方
 * 可能算出不同的键（时区、Locale、是否补零），而「当日计数」「同日不复用文案」
 * 「今天跑没跑过」全都依赖这个键一致。这里统一成 `yyyy-MM-dd`。
 *
 * 边界：**业务键**（当日计数、同日去重、免打扰判定）必须走这里；
 * 纯展示用的 `HH:mm:ss` / `M月d日 HH:mm` 仍可用 `SimpleDateFormat`（它们不参与
 * 任何键计算，且很多是主线程高频格式化，`DateTimeFormatter` 反而更慢）。
 */
object EngageSchedule {

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DAY_CN = DateTimeFormatter.ofPattern("M月d日")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    private val TOPIC_ID_RE = Regex("^[0-9A-Za-z]{8,64}$")

    /** 超话 containerid 前缀。写接口 `topic_id=1022:<containerid>` 与帖子流 `flowId` 都用它。 */
    const val TOPIC_CONTAINER_PREFIX = "100808"

    /** 超话 tid 合法性：只允许字母数字，长度 8..64，杜绝拼进查询串时被注入。 */
    fun isValidTopicId(tid: String): Boolean = tid.isNotEmpty() && tid.matches(TOPIC_ID_RE)

    /**
     * 归一成完整 containerid。
     *
     * `WeiboTopicApi.extractTopicId` 返回的是**去掉 `100808` 的 hash**，而写接口与帖子流
     * 要求的是**带前缀的完整 containerid**（实测文档 §2.1/§2.6）。两边口径不同，
     * 所以只在这里做一次归一，别在各调用点各拼一次。
     */
    fun topicContainerId(tid: String): String =
        if (tid.startsWith(TOPIC_CONTAINER_PREFIX)) tid else TOPIC_CONTAINER_PREFIX + tid

    /** 是否已经是完整 containerid（配置校验用，避免存半截 ID 发错对象）。 */
    fun isContainerTopicId(tid: String): Boolean =
        isValidTopicId(tid) && tid.startsWith(TOPIC_CONTAINER_PREFIX)

    fun dayKey(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(nowMillis).atZone(zone).format(DAY)

    fun dateLabel(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(nowMillis).atZone(zone).format(DAY)

    fun dateCnLabel(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(nowMillis).atZone(zone).format(DAY_CN)

    fun clockLabel(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(nowMillis).atZone(zone).format(CLOCK)

    /**
     * 文案禁用的日期集合。
     *
     * `strictTextGapDays <= 0` 时只有今天（同一天不用同一条）；
     * `= N` 时是「今天 + 前 N-1 天」，也就是同一条文案至少隔 N 天才复用。
     *
     * 入参是**已经归一好的**日期键（`yyyy-MM-dd`），时区在 [dayKey] 那一步就定下来了，
     * 所以这里不接收 `zone`——早期版本收了一个从不使用的 `ZoneId`，会让调用方误以为
     * 传进去能改变「禁用哪些天」的口径。
     */
    fun forbiddenTextDays(today: String, strictTextGapDays: Int): Set<String> {
        val base = java.time.LocalDate.parse(today, DAY)
        val span = if (strictTextGapDays <= 1) 1 else strictTextGapDays
        return (0 until span).map { base.minusDays(it.toLong()).format(DAY) }.toSet()
    }

    /** 某个小时是否落在免打扰时段（支持跨夜，例如 23..7）。 */
    fun isQuietHour(hour: Int, config: EngageConfig): Boolean {
        val start = config.quietStartHour.coerceIn(0, 23)
        val end = config.quietEndHour.coerceIn(0, 24)
        if (start == end) return false
        return if (start < end) hour in start until end else hour >= start || hour < end
    }

    /** 现在是否处于免打扰时段。 */
    fun isQuietNow(nowMillis: Long, config: EngageConfig, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        isQuietHour(Instant.ofEpochMilli(nowMillis).atZone(zone).hour, config)

    /** 今天是否已经跑过（用 dayKey 比较，不依赖时刻）。 */
    fun ranToday(lastRunAt: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (lastRunAt <= 0L) return false
        return dayKey(lastRunAt, zone) == dayKey(nowMillis, zone)
    }
}
