package com.wcm.checkin

/**
 * Cookie 有效期估算（口径对齐面板端 `app/weibo_client.describe_cookie_lifespan`）。
 *
 * 面板端怎么算：
 *
 * ```python
 * alf_at = parse_cookie_expiry(cookie)            # cookie 里 ALF 的"名义到期"
 * observed_at = min(issued_at, last_rotated_at, last_seen_valid_at) + ttl_days
 * deadline = min(alf_at, observed_at)             # 多把尺子取最早的
 * ```
 *
 * 三条锚点里 `cookie_issued_at` 是**唯一不会退休的那把尺**：`last_seen_valid_at` 每轮
 * 保活都会归零（于是永远报不出"即将失效"），`last_rotated_at` 只在微博真换发时推进。
 * 微博 ALF 常写着一年，而服务端 session 实测约 **7 天**就被吊销，所以经验寿命才是主判据。
 *
 * App 侧的两点等价映射：
 *
 * - 面板的 `cookie_issued_at` 在**每次手机同步时被重置**（`database.update_account`
 *   会自动把生命周期基准打成同步时刻），所以已同步账号的锚点用 [AccountStore.Account.cookieSyncedAt]；
 * - 还没同步过的账号，锚点退回本机的录入 / 换发 / 探活三个时刻里最早的那个。
 *
 * 这里只做纯计算（不碰 Android、不触网），JVM 单测直接覆盖。
 */
object CookieLife {

    /** 经验 session 寿命（天）。与面板设置项 `cookie_session_ttl_days` 的默认值一致。 */
    const val TTL_DAYS = 7

    /** 临期预警阈值（天）。与面板设置项 `cookie_expiry_warn_days` 的默认值一致。 */
    const val WARN_DAYS = 2

    private const val DAY_MS = 86_400_000L

    /** 到期状态；[UNKNOWN] 表示没有任何可用的时间锚点（还没同步过）。 */
    enum class State { OK, EXPIRING, EXPIRED, UNKNOWN }

    /**
     * 一次估算的结果。
     *
     * @param deadlineAt 预计失效时刻（epoch ms；0 = 无法估算）
     * @param anchorAt 使用的基准时刻（同步时刻，或本机最早锚点；0 = 无基准）
     * @param daysLeft 剩余天数（可为负；null = 无法估算）
     * @param alfAt cookie 里 ALF 的名义到期（0 = 没有 / 判定为旧值）
     * @param fromPanel 基准是否来自面板同步
     */
    data class View(
        val state: State,
        val deadlineAt: Long,
        val anchorAt: Long,
        val daysLeft: Double?,
        val alfAt: Long,
        val fromPanel: Boolean,
    ) {
        /** 是否需要用红色标出来提醒用户续期。 */
        val alert: Boolean get() = state == State.EXPIRING || state == State.EXPIRED

        /** 是否已经能给出结论（false = 未同步过，界面上应引导用户点「Cookie 同步」）。 */
        val known: Boolean get() = state != State.UNKNOWN
    }

    /**
     * 解析 cookie 里的 ALF（account login expire time）→ epoch ms，取不到返回 0。
     *
     * 与面板 `parse_cookie_expiry` 同口径：秒级时间戳为主，兼容毫秒与脏值。
     */
    fun parseAlf(cookie: String): Long {
        val raw = WeiboApi.parseCookieString(cookie)["ALF"]?.trim().orEmpty()
        if (raw.isEmpty() || !raw.all { it.isDigit() }) return 0L
        var value = raw.toLongOrNull() ?: return 0L
        if (value > 10_000_000_000L) value /= 1000        // 毫秒时间戳
        if (value < 1_000_000_000L) return 0L             // 2001 年前 → 脏值
        return value * 1000
    }

    /**
     * 估算一个账号的 cookie 有效期。
     *
     * @param syncedAt 最近一次成功推送到面板的时刻（>0 时即为面板侧基准）
     * @param ttlDays 经验寿命（天），默认 7
     * @param warnDays 临期阈值（天），默认 2
     */
    fun describe(
        cookie: String,
        issuedAt: Long = 0L,
        rotatedAt: Long = 0L,
        seenAt: Long = 0L,
        syncedAt: Long = 0L,
        now: Long = System.currentTimeMillis(),
        ttlDays: Int = TTL_DAYS,
        warnDays: Double = WARN_DAYS.toDouble(),
    ): View {
        val localAnchors = listOf(issuedAt, rotatedAt, seenAt).filter { it > 0 }
        val fromPanel = syncedAt > 0
        val anchor = when {
            fromPanel -> syncedAt
            localAnchors.isNotEmpty() -> localAnchors.min()
            else -> 0L
        }
        val ttl = ttlDays.coerceAtLeast(1) * DAY_MS
        val estimated = if (anchor > 0) anchor + ttl else 0L

        // ALF 早于基准 = 上一轮登录残留的旧值（合并 cookie 时很常见，面板侧靠实时探活
        // 抵消它）。这里直接忽略，否则刚同步成功的账号会立刻显示「已过期」。
        val alfRaw = parseAlf(cookie)
        val alf = if (alfRaw > anchor) alfRaw else 0L

        val deadline = listOf(estimated, alf).filter { it > 0 }.minOrNull() ?: 0L
        if (deadline == 0L) {
            return View(State.UNKNOWN, 0L, anchor, null, alf, fromPanel)
        }
        val daysLeft = (deadline - now).toDouble() / DAY_MS
        val state = when {
            daysLeft <= 0.0 -> State.EXPIRED
            daysLeft <= warnDays -> State.EXPIRING
            else -> State.OK
        }
        return View(state, deadline, anchor, daysLeft, alf, fromPanel)
    }

    /** 便捷重载：直接按账号行算（界面与日志共用同一口径）。 */
    fun describe(acc: AccountStore.Account, now: Long = System.currentTimeMillis()): View =
        describe(
            cookie = acc.cookie,
            issuedAt = acc.cookieIssuedAt,
            rotatedAt = acc.cookieRotatedAt,
            seenAt = acc.cookieSeenAt,
            syncedAt = acc.cookieSyncedAt,
            now = now,
        )
}
