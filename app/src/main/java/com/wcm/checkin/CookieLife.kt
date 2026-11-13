package com.wcm.checkin

/**
 * Cookie 有效期估算（与桌面端 `app/cookie_lifecycle.py` 同口径）。
 *
 * 关键修正（相对旧实现）：
 *
 * 1. **寿命只从「真实登录 / 凭证换发」起算**，不是从「同步到面板」起算；
 *    同步、探活成功都只是观察，不能让寿命重新计时。
 * 2. **服务端到期与推算到期分开**：`serverExpiresAt` 来自 Set-Cookie 的
 *    `Expires`/`Max-Age` 或 cookie 里的 ALF；`estimatedAt` 是按经验寿命推算的。
 *    两者取更早者作为最终判定，但界面上分别展示。
 * 3. 真实登录时间 [View.loginAt] 与同步时间 [View.syncedAt] 分开，互不冒充。
 *
 * 基准优先级：`loginAt`（SSOLoginState / 本次登录时刻）与 `rotatedAt`（微博真换发了
 * SUB 等关键字段）取更晚者，再退回本机首次录入时刻。只有旧版本数据（三者都为 0）
 * 才退化为「同步时刻」估算，并在 [View.fromPanel] 标记出来。
 *
 * 纯计算，不碰 Android / 网络，JVM 单测直接覆盖。
 */
object CookieLife {

    /** 经验 session 寿命（天）。与面板设置项 `cookie_session_ttl_days` 的默认值一致。 */
    const val TTL_DAYS = 7

    /** 临期预警阈值（天）。与面板设置项 `cookie_expiry_warn_days` 的默认值一致。 */
    const val WARN_DAYS = 2

    private const val DAY_MS = 86_400_000L

    /** 到期状态；[UNKNOWN] 表示没有任何可用的时间锚点。 */
    enum class State { OK, EXPIRING, EXPIRED, UNKNOWN }

    data class View(
        val state: State,
        /** 最终判定用的到期时刻（服务端与推算取更早；epoch ms；0 = 无法估算）。 */
        val deadlineAt: Long,
        /** 按经验寿命推算的到期时刻（0 = 无）。 */
        val estimatedAt: Long,
        /** 服务端声明/名义到期（0 = 未知）。 */
        val serverExpiresAt: Long,
        /** 真实登录时刻（0 = 未知）。 */
        val loginAt: Long,
        /** 寿命起算的凭据基准（登录 / 换发 / 首次录入）。 */
        val anchorAt: Long,
        /** 最近一次成功同步到面板的时刻（只用于展示，不参与寿命计算）。 */
        val syncedAt: Long,
        /** 最近一次探活成功时刻（只用于展示）。 */
        val seenAt: Long,
        /** 最近一次关键字段换发时刻（0 = 还没观察到换发）。 */
        val rotateAt: Long,
        val daysLeft: Double?,
        val alfAt: Long,
        /** 估算基准是否退化为「同步时刻」（仅旧数据，界面需要注明）。 */
        val fromPanel: Boolean,
    ) {
        val alert: Boolean get() = state == State.EXPIRING || state == State.EXPIRED
        val known: Boolean get() = state != State.UNKNOWN
        /** 最终判定是否来自服务端声明（否则就是经验推算）。 */
        val serverAuthoritative: Boolean get() = serverExpiresAt > 0 &&
            (estimatedAt <= 0 || serverExpiresAt <= estimatedAt)
    }

    /**
     * 解析 cookie 里的 ALF（account login expire time）→ epoch ms，取不到返回 0。
     */
    fun parseAlf(cookie: String): Long = parseCookieStamp(cookie, "ALF")

    /** 服务端声明/名义到期（当前只有 ALF 这个来源；探活 Set-Cookie 由调用方另传）。 */
    fun serverExpiryOf(cookie: String): Long = parseAlf(cookie)

    /**
     * 真实登录时刻：cookie 里的 `SSOLoginState`（秒/毫秒时间戳）→ epoch ms；0 = 未知。
     */
    fun loginTimeOf(cookie: String): Long = parseCookieStamp(cookie, "SSOLoginState")

    private fun parseCookieStamp(cookie: String, key: String): Long {
        val raw = WeiboApi.parseCookieString(cookie)[key]?.trim().orEmpty()
        if (raw.isEmpty() || !raw.all { it.isDigit() }) return 0L
        var value = raw.toLongOrNull() ?: return 0L
        if (value > 10_000_000_000L) value /= 1000        // 毫秒时间戳
        if (value < 1_000_000_000L) return 0L             // 2001 年前 → 脏值
        return value * 1000
    }

    /**
     * 估算一个账号的 cookie 有效期。
     *
     * @param loginAt 真实登录时刻（>0 时优先作为寿命基准）
     * @param serverExpiresAt 服务端声明到期（>0 时与推算到期取更早）
     * @param syncedAt 最近一次同步到面板的时刻（**不参与寿命计算**，仅旧数据兜底）
     */
    fun describe(
        cookie: String,
        issuedAt: Long = 0L,
        rotatedAt: Long = 0L,
        seenAt: Long = 0L,
        syncedAt: Long = 0L,
        loginAt: Long = 0L,
        serverExpiresAt: Long = 0L,
        now: Long = System.currentTimeMillis(),
        ttlDays: Int = TTL_DAYS,
        warnDays: Double = WARN_DAYS.toDouble(),
    ): View {
        // 真正的续期凭证：登录时刻 / 关键字段换发时刻 / 首次录入时刻，取最晚者
        val credentialAnchor = listOf(loginAt, rotatedAt, issuedAt)
            .filter { it > 0L }
            .maxOrNull() ?: 0L
        // 只有旧数据（三个锚点全缺失）才退回同步时刻，并明确标记
        val fromPanel = credentialAnchor == 0L && syncedAt > 0L
        val anchor = when {
            credentialAnchor > 0L -> credentialAnchor
            syncedAt > 0L -> syncedAt
            else -> 0L
        }

        val ttl = ttlDays.coerceAtLeast(1) * DAY_MS
        val estimated = if (anchor > 0L) anchor + ttl else 0L

        // ALF 早于基准 = 上一轮登录残留的旧值，忽略；否则视作服务端名义到期
        val alfRaw = parseAlf(cookie)
        val alf = when {
            alfRaw <= 0L -> 0L
            anchor > 0L && alfRaw <= anchor -> 0L
            else -> alfRaw
        }
        val server = listOf(serverExpiresAt, alf)
            .filter { it > 0L && (anchor <= 0L || it > anchor) }
            .minOrNull() ?: 0L

        val deadline = listOf(estimated, server).filter { it > 0L }.minOrNull() ?: 0L
        if (deadline == 0L) {
            return View(
                State.UNKNOWN, 0L, estimated, server, loginAt, anchor,
                syncedAt, seenAt, rotatedAt, null, alf, fromPanel,
            )
        }
        val daysLeft = (deadline - now).toDouble() / DAY_MS
        val state = when {
            daysLeft <= 0.0 -> State.EXPIRED
            daysLeft <= warnDays -> State.EXPIRING
            else -> State.OK
        }
        return View(
            state, deadline, estimated, server, loginAt, anchor,
            syncedAt, seenAt, rotatedAt, daysLeft, alf, fromPanel,
        )
    }

    /** 便捷重载：直接按账号行算（界面与日志共用同一口径）。 */
    fun describe(acc: AccountStore.Account, now: Long = System.currentTimeMillis()): View =
        describe(
            cookie = acc.cookie,
            issuedAt = acc.cookieIssuedAt,
            rotatedAt = acc.cookieRotatedAt,
            seenAt = acc.cookieSeenAt,
            syncedAt = acc.cookieSyncedAt,
            loginAt = acc.cookieLoginAt,
            serverExpiresAt = acc.cookieServerExpiresAt,
            now = now,
        )
}
