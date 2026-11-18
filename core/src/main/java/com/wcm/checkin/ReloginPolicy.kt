package com.wcm.checkin

/**
 * 账号密码自动重登策略（纯逻辑，不碰 Android / 网络，JVM 单测直接覆盖）。
 *
 * 口径与桌面端 `app/relogin.py` 一致：
 *
 * - 只有 cookie 被判定失效 / 已过期 / 临期时才触发（由调用方传入 `cookieUsable`）；
 * - 24 小时滑动窗口内最多 [maxPerDay] 次尝试；
 * - 失败基础退避 [baseBackoffHours] 小时，按 2 倍递增、封顶 24 小时；
 * - 账号密码错误 / 验证码或二次验证 / 凭证读不出 → 立即暂停（硬停），等人工处理；
 * - 连续失败达到上限也暂停，避免把账号反复撞进微博风控。
 */
object ReloginPolicy {

    // 失败分类（与服务端 `cookie_last_error` / `relogin_last_code` 同字符串）
    const val CODE_BAD_CREDENTIALS = "bad_credentials"
    const val CODE_NEEDS_MANUAL = "needs_manual"
    const val CODE_RISK_CONTROL = "risk_control"
    const val CODE_NETWORK = "network"
    const val CODE_VERIFY_FAILED = "verify_failed"
    const val CODE_UNKNOWN = "unknown"
    const val CODE_CREDENTIAL_UNREADABLE = "credential_unreadable"

    /** 硬停：重试没有任何意义，必须人工扫码 / 更新凭证。 */
    val HARD_STOP_CODES = setOf(
        CODE_BAD_CREDENTIALS, CODE_NEEDS_MANUAL, CODE_CREDENTIAL_UNREADABLE,
    )

    private const val HOUR_MS = 3_600_000L
    private const val WINDOW_MS = 24 * HOUR_MS

    /** 硬停后用户手动点「立即重登」的最短间隔（风控类暂停允许手动试一次）。 */
    private const val MANUAL_MIN_GAP_MS = 10 * 60_000L

    /** 自动重登状态（持久化在账号行里；不含任何密码信息）。 */
    data class State(
        val lastAttemptAt: Long = 0L,
        val nextAt: Long = 0L,
        val failStreak: Int = 0,
        val attempts: List<Long> = emptyList(),
        val blocked: Boolean = false,
        val lastReason: String = "",
        val lastCode: String = "",
        val credentialAt: Long = 0L,
    )

    /** 一次策略判断的结果：允许则 [skipCode] 为空。 */
    data class Decision(val allowed: Boolean, val skipCode: String = "", val message: String = "")

    /** 一次失败后的新状态与给用户看的文案。 */
    data class Failure(val state: State, val message: String)

    /**
     * 已知 `retcode` 的补充说明。
     *
     * 只放「码本身看不出原因、但用户必须知道该干什么」的那几个；其余仍按
     * `（retcode xxx）` 原样带上，方便定位。
     */
    private val RETCODE_DETAILS = mapOf(
        // 微博对账密登录的通用拒绝：实测与账号是否存在无关，换任意账号都是它。
        // 新设备 / 异地登录时它几乎等同于「请先完成短信或私信二次验证」。
        "1117" to "多为该账号需短信/私信二次验证或登录过于频繁，请用「网页登录」完成一次验证",
    )

    /** 失败原因的固定文案；`retcode` 只用于补充定位，绝不回显表单/密码。 */
    fun reasonText(code: String, retcode: String = ""): String {
        val base = when (code) {
            CODE_BAD_CREDENTIALS -> "账号或密码错误"
            CODE_NEEDS_MANUAL -> "需要验证码或二次验证（需人工处理）：账密自动重登过不去，请用「网页登录」完成一次验证，或填写 ALC 票据"
            CODE_RISK_CONTROL -> "被微博风控拦截"
            CODE_NETWORK -> "网络异常"
            CODE_VERIFY_FAILED -> "登录返回成功，但新 Cookie 未通过校验"
            CODE_CREDENTIAL_UNREADABLE -> "本地凭证无法解密，请重新保存账号密码"
            else -> "微博登录返回未知错误"
        }
        val rc = retcode.trim()
        return when {
            rc.isEmpty() -> base
            RETCODE_DETAILS[rc] != null -> "$base（retcode $rc：${RETCODE_DETAILS[rc]}）"
            else -> "$base（retcode $rc）"
        }
    }

    /** 24 小时窗口内的尝试时刻（升序）。 */
    fun recentAttempts(state: State, now: Long): List<Long> =
        state.attempts.filter { it > 0L && now - it <= WINDOW_MS }.sorted()

    /**
     * 判断这次要不要真的发起登录。
     *
     * @param cookieUsable 当前 cookie 是否仍被判为「可用」（[CookieLife] 的 OK 状态且未失效）。
     *   为 true 时自动模式直接跳过 —— 重登只在失效或临期时触发。
     * @param force 用户显式点「立即重登」：可越过退避与次数上限，但硬停仍拒绝。
     */
    fun shouldAttempt(
        globalEnabled: Boolean,
        accountEnabled: Boolean,
        hasCredential: Boolean,
        cookieUsable: Boolean,
        state: State,
        now: Long,
        force: Boolean,
        maxPerDay: Int,
    ): Decision {
        if (!globalEnabled) return Decision(false, "disabled", "自动重登总开关已关闭")
        if (!accountEnabled) return Decision(false, "disabled", "该账号未开启自动重登")
        if (!hasCredential) return Decision(false, "no_credentials", "未保存账号密码，无法自动重登")

        if (state.blocked) {
            if (state.lastCode in HARD_STOP_CODES) {
                val text = state.lastReason.ifEmpty { reasonText(state.lastCode) }
                return Decision(false, state.lastCode, "自动重登已暂停：$text")
            }
            if (!force) {
                val text = state.lastReason.ifEmpty { "连续失败达到上限" }
                return Decision(false, "blocked", "自动重登已暂停：$text")
            }
            // 风控 / 网络类暂停允许用户手动点一次，但仍要有最短间隔
            if (state.lastAttemptAt > 0L && now - state.lastAttemptAt < MANUAL_MIN_GAP_MS) {
                return Decision(false, "cooldown", "刚尝试过，请 10 分钟后再试")
            }
        }

        if (!force) {
            if (state.nextAt > 0L && now < state.nextAt) {
                return Decision(false, "backoff", "退避中，下次允许重登时间 ${ReloginTime.format(state.nextAt)}")
            }
            val recent = recentAttempts(state, now)
            val cap = maxPerDay.coerceAtLeast(1)
            if (recent.size >= cap) {
                return Decision(false, "daily_cap", "24 小时内已尝试 ${recent.size} 次，达到上限")
            }
            if (cookieUsable) return Decision(false, "not_needed", "Cookie 仍可用，无需自动重登")
        }
        return Decision(true)
    }

    /** 先记账再登录：即使进程中途崩溃，这次尝试也计入 24 小时上限。 */
    fun recordAttempt(state: State, now: Long): State {
        val recent = recentAttempts(state, now) + now
        return state.copy(lastAttemptAt = now, attempts = recent.takeLast(16))
    }

    /** 失败后：递增连续失败、写退避或硬停。 */
    fun onFailure(
        state: State,
        code: String,
        retcode: String,
        now: Long,
        maxPerDay: Int,
        baseBackoffHours: Double,
    ): Failure {
        val streak = state.failStreak + 1
        val cap = maxPerDay.coerceAtLeast(1)
        val blocked = code in HARD_STOP_CODES || streak >= cap
        val base = baseBackoffHours.coerceAtLeast(0.5)
        val nextAt = if (blocked) {
            0L
        } else {
            val hours = minOf(base * Math.pow(2.0, (streak - 1).toDouble()), 24.0)
            now + (hours * HOUR_MS).toLong()
        }
        var text = "自动重登失败：" + reasonText(code, retcode)
        if (blocked) text += "；已暂停自动重登，请手动处理"
        return Failure(
            state = state.copy(
                failStreak = streak,
                nextAt = nextAt,
                blocked = blocked,
                lastReason = text,
                lastCode = code,
            ),
            message = text,
        )
    }

    /** 成功后清空失败/暂停/尝试记录，避免历史失败拖累下一次故障。 */
    fun onSuccess(now: Long): State =
        State(lastAttemptAt = now, attempts = emptyList())
}

/** epoch ms 的极简格式化（纯 JVM 可测，不依赖 Android）。 */
internal object ReloginTime {
    fun format(epochMs: Long): String {
        if (epochMs <= 0L) return "未知"
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(epochMs))
    }
}
