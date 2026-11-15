package com.wcm.checkin

/**
 * 首页账号行第一行（「今天签了吗」）的口径决策。
 *
 * 这一行以前直接显示 [Account.lastResult]，空串就写「尚未签到」。
 * 问题在于 `lastResult` **只在「本机真的发起过一次签到」时才会写**：打开 App 时的
 * 自动刷新超话（`HomeFragment.refreshTopics`）只更新超话快照，从不碰它。于是
 * 「今天 7 个超话一个不剩地签完、但本机没签过这个号」会出现自相矛盾的一行：
 * chip 上是「已签 7 / 待签 0」，下面却写着「尚未签到」。
 *
 * 现在按「谁能回答今天」的顺序取证据：
 * 1. 今日的超话快照（直接来自微博，最权威）→ [Head.TodayAll] / [Head.TodayLeft]
 * 2. 快照不可用时退回本机小结；**跨天的小结不能冒充今天** → [Head.LastEarlier]
 * 3. 什么证据都没有 → [Head.Idle]
 *
 * 纯逻辑，不依赖 Android（JVM 单测覆盖）。
 */
object AccountStatusHead {

    sealed interface Head {
        /** 今日快照已知，全部签完。 */
        data class TodayAll(val done: Int, val total: Int) : Head

        /** 今日快照已知，还有未签（`left = total - done`）。 */
        data class TodayLeft(val done: Int, val total: Int) : Head

        /** 快照不可用，但本机小结是今天写的 —— 可以当今日结论。 */
        data class LastToday(val result: String) : Head

        /** 快照不可用，本机小结是更早的（或时间未知）：只能标明「上次」。 */
        data class LastEarlier(val result: String, val atMs: Long) : Head

        /** 既没有今日快照，也没有任何小结。 */
        object Idle : Head
    }

    /**
     * @param loaded 今日超话快照是否可用（刷新完成、无错误、有超话、且属于今天）
     * @param lastResultToday 这条 `lastResult` 是否**今天**写下（本地小结，没有时间戳就当未知）
     * @param lastResultAt `lastResult` 的写入时刻（0 = 旧版本写的，时间未知）
     */
    fun of(
        loaded: Boolean,
        done: Int,
        total: Int,
        lastResult: String,
        lastResultToday: Boolean,
        lastResultAt: Long = 0L,
    ): Head = when {
        loaded && total > 0 && done >= total -> Head.TodayAll(done.clampTo(total), total)
        loaded && total > 0 -> Head.TodayLeft(done.clampTo(total), total)
        lastResult.isEmpty() -> Head.Idle
        lastResultToday -> Head.LastToday(lastResult)
        else -> Head.LastEarlier(lastResult, lastResultAt)
    }

    /** 接口偶尔会回 `done > total`（关注数变了、翻页多了）—— 别让界面显示「已签 9/7」。 */
    private fun Int.clampTo(total: Int): Int = coerceIn(0, total)
}
