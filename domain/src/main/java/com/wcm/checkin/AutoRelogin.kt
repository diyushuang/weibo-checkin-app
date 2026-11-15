package com.wcm.checkin

import android.content.Context
import android.util.Log

/**
 * 账号密码自动重登编排：策略判断 → 先记账 → 解密凭证 → SSO 登录 → 落库 / 记失败。
 *
 * 调用方只需要 [tryAutoRelogin]；它自己保证：
 * - 只在 cookie 失效 / 过期 / 临期（或用户 force）时真的发起网络请求；
 * - 24 小时内最多 `auto_relogin_max_per_day` 次（默认 3），失败按指数退避；
 * - 密码错 / 验证码 / 二次验证 / 凭证读不出 → 直接硬停，等人工；
 * - 成功整份替换 cookie、写真实登录时间与服务端到期；失败写 `cookieLastError`（原因可见）。
 *
 * 日志只记 uid / 触发点 / 结果码，不记账号、密码、cookie。
 */
class AutoRelogin(
    /** 账号表读写（含重登状态机落库）。 */
    private val account: AccountPort,
    /** Keystore 加密凭证。 */
    private val vault: CredentialPort,
) {

    private val TAG = "AutoRelogin"
    private val DAY_MS = 86_400_000L

    data class Outcome(
        val ok: Boolean,
        val attempted: Boolean,
        val code: String = "",
        val message: String = "",
    )

    /** cookie 是否仍「可用」：非空、没被判失效、且有效期状态不是 EXPIRED/EXPIRING。 */
    fun cookieUsable(acc: Account, now: Long = System.currentTimeMillis()): Boolean {
        if (acc.cookie.isEmpty() || acc.cookieDead) return false
        return CookieLife.describe(acc, now).state == CookieLife.State.OK
    }

    fun hasCredential(ctx: Context, uid: String): Boolean = vault.has(ctx, uid)

    /**
     * 尝试自动重登。
     *
     * @param trigger 触发点（`keepalive_expiring` / `keepalive_invalid` / `manual`），只用于日志
     * @param force 用户显式点「立即重登」：越过退避与次数上限，硬停仍拒绝
     */
    fun tryAutoRelogin(
        ctx: Context,
        acc: Account,
        trigger: String,
        force: Boolean = false,
    ): Outcome {
        // 重新读库：调用方手里的对象可能已经过期（后台线程/前台动作并发）
        val fresh = account.load(ctx).firstOrNull { it.uid == acc.uid } ?: acc
        val now = System.currentTimeMillis()
        val globalEnabled = account.autoReloginEnabled(ctx)
        val hasCred = vault.has(ctx, fresh.uid)
        val usable = cookieUsable(fresh, now)
        val state = account.reloginState(fresh)
        val maxPerDay = account.autoReloginMaxPerDay(ctx)
        val baseHours = account.autoReloginBackoffHours(ctx)

        val decision = ReloginPolicy.shouldAttempt(
            globalEnabled = globalEnabled,
            accountEnabled = fresh.autoReloginEnabled,
            hasCredential = hasCred,
            cookieUsable = usable,
            state = state,
            now = now,
            force = force,
            maxPerDay = maxPerDay,
        )
        if (!decision.allowed) {
            // 次数用尽：把下次允许时间排到窗口内最早一次尝试之后，避免每轮都重复判断
            if (decision.skipCode == "daily_cap" && !force) {
                val recent = ReloginPolicy.recentAttempts(state, now)
                if (recent.isNotEmpty()) {
                    account.saveReloginState(
                        ctx, fresh.uid, state.copy(nextAt = recent.first() + DAY_MS),
                    )
                }
            }
            Log.i(TAG, "跳过自动重登 uid=${fresh.uid} trigger=$trigger reason=${decision.skipCode}")
            return Outcome(false, false, decision.skipCode, decision.message)
        }

        // 先记账再登录：进程在登录途中被杀，这次尝试也计入 24 小时上限
        val attempted = ReloginPolicy.recordAttempt(state, now)
        account.saveReloginState(ctx, fresh.uid, attempted)

        val credentials = try {
            vault.load(ctx, fresh.uid)
        } catch (e: CredentialUnreadableException) {
            null
        }
        if (credentials == null) {
            val failure = ReloginPolicy.onFailure(
                attempted, ReloginPolicy.CODE_CREDENTIAL_UNREADABLE, "",
                System.currentTimeMillis(), maxPerDay, baseHours,
            )
            account.markReloginFailure(
                ctx, fresh.uid, failure.state, failure.message, at = System.currentTimeMillis(), dead = fresh.cookieDead,
            )
            Log.w(TAG, "凭证不可用 uid=${fresh.uid}")
            return Outcome(false, true, ReloginPolicy.CODE_CREDENTIAL_UNREADABLE, failure.message)
        }

        val (username, password) = credentials
        val result = WeiboPasswordLogin.login(username, password)
        if (result.ok) {
            account.markReloginSuccess(
                ctx, fresh.uid, result.cookie, result.loginAt, result.serverExpiresAt, at = System.currentTimeMillis(),
            )
            Log.i(TAG, "自动重登成功 uid=${fresh.uid} trigger=$trigger")
            return Outcome(true, true, message = "账密重登成功，已更新 Cookie 与真实登录时间")
        }

        val failure = ReloginPolicy.onFailure(
            attempted, result.code, result.retcode,
            System.currentTimeMillis(), maxPerDay, baseHours,
        )
        // 本来就失效的账号继续保持失效；只是临期但还活着的，不因为一次重登失败就判死 ——
        // 注意不能用 `!usable`：它把「临期（EXPIRING，cookie 还有效）」和「过期」算在一起，
        // 会让一个还能正常用的账号因为一次网络抖动就被标成「已失效」。
        val expired = CookieLife.describe(fresh, System.currentTimeMillis()).state ==
            CookieLife.State.EXPIRED
        account.markReloginFailure(
            ctx, fresh.uid, failure.state, failure.message, at = System.currentTimeMillis(),
            dead = fresh.cookieDead || expired,
        )
        Log.w(TAG, "自动重登失败 uid=${fresh.uid} trigger=$trigger code=${result.code}")
        return Outcome(false, true, result.code, failure.message)
    }
}
