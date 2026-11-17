package com.wcm.checkin

/**
 * 熔断器：把「这次失败该怎么处置」收在一处，避免每个调用点各写一套 if。
 *
 * 两级 streak 的意义不同：
 * - **动作级**：同一个动作连续失败到阈值，只放弃这个动作（例如评论一直失败，
 *   但转发/点赞还是好的）；
 * - **整轮级**：跨动作累计失败到阈值，说明环境已经不对了，整轮停下。
 *
 * 刻意**不**把「跳过类」计入 streak：目标帖子被删（[EngageErrorKind.TARGET_GONE]）
 * 是正常情况，一条帖子没了就计一次失败，会让多目标场景很快熔断。
 *
 * 非线程安全——一轮运行一个实例，由 `EngageService` 持有，不要跨轮复用。
 */
class EngageGuard(private val failStreakAbort: Int = 3) {

    private var actionStreak = 0
    private var globalStreak = 0
    private var stopReason: String = ""

    val isStopped: Boolean get() = stopReason.isNotEmpty()
    val currentStopReason: String get() = stopReason
    val currentActionStreak: Int get() = actionStreak

    /** 换到下一个动作时清掉动作级 streak（整轮级不清）。 */
    fun nextAction() {
        actionStreak = 0
    }

    /**
     * 记录一次结果并给出处置。
     *
     * 顺序很关键：**硬停类先判**，否则「连续 2 次网络失败后遇到验证码」会先
     * 触发动作级放弃，把需要人工的信号吞掉。
     */
    fun onResult(kind: EngageErrorKind): Disposition {
        if (isStopped) return Disposition.ABORT_RUN

        when (kind) {
            EngageErrorKind.NONE -> {
                actionStreak = 0
                globalStreak = 0
                return Disposition.CONTINUE
            }

            // 必须人工：登录失效、需要验证码。当日不再自动重试。
            EngageErrorKind.LOGIN_WALL,
            EngageErrorKind.NOT_LOGGED_IN -> {
                stopReason = "登录态失效，需要重新登录"
                return Disposition.REQUIRE_HUMAN
            }

            EngageErrorKind.NEED_VERIFY -> {
                stopReason = "需要身份验证，请在微博 App 内手动完成后再运行"
                return Disposition.REQUIRE_HUMAN
            }

            // 环境性问题：整轮没意义了。
            EngageErrorKind.TOPIC_BLOCKED -> {
                stopReason = "被超话屏蔽或禁言"
                return Disposition.ABORT_RUN
            }

            EngageErrorKind.TOPIC_NOT_FOLLOWED -> {
                stopReason = "未关注该超话，请先在微博内关注"
                return Disposition.ABORT_RUN
            }

            EngageErrorKind.ENDPOINT_INVALID -> {
                stopReason = "接口已变更，请升级 App"
                return Disposition.ABORT_RUN
            }

            EngageErrorKind.CONFIG_INVALID -> {
                stopReason = "配置非法"
                return Disposition.ABORT_RUN
            }

            EngageErrorKind.RATE_LIMITED -> {
                stopReason = "操作频繁，已退避"
                return Disposition.RETRY_LATER
            }

            EngageErrorKind.GUARD_TRIPPED -> {
                stopReason = "已熔断"
                return Disposition.ABORT_RUN
            }

            // 只挡当前动作。
            EngageErrorKind.LEVEL_TOO_LOW -> return Disposition.ABORT_ACTION
            EngageErrorKind.NO_TEXT -> return Disposition.ABORT_ACTION
            EngageErrorKind.CONTENT_REJECTED,
            EngageErrorKind.PARAM_INVALID -> return Disposition.ABORT_ACTION

            // 跳过类：不计失败，换下一个目标继续。
            EngageErrorKind.TARGET_GONE,
            EngageErrorKind.DUPLICATE_ACTION -> return Disposition.CONTINUE

            // 结果未知或不稳定的失败：两级各记一次，动作级先判——先把「这一个动作不行」
            // 的结论表达出来；等到换动作后仍继续失败（全局计数早已越过阈值），才整轮停。
            EngageErrorKind.NETWORK,
            EngageErrorKind.SERVER_ERROR -> {
                actionStreak++
                globalStreak++
                if (actionStreak >= failStreakAbort) return Disposition.ABORT_ACTION
                if (globalStreak >= failStreakAbort) {
                    stopReason = "累计失败 $globalStreak 次，已熔断"
                    return Disposition.ABORT_RUN
                }
                return Disposition.CONTINUE
            }
        }
    }

    /** 显式熔断（例如时间预算耗尽、外部要求停止）。 */
    fun trip(reason: String) {
        if (stopReason.isEmpty()) stopReason = reason
    }
}
