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
        val hasPwd = vault.has(ctx, fresh.uid)
        // 没有密码但有 ALC 也允许自动恢复（路线 E）。两者都没有才算「无凭证」。
        val hasAlc = vault.hasAlc(ctx, fresh.uid)
        val hasCred = hasPwd || hasAlc
        val usable = cookieUsable(fresh, now)
        val state = account.reloginState(fresh)
        val maxPerDay = account.autoReloginMaxPerDay(ctx)
        val baseHours = account.autoReloginBackoffHours(ctx)

        val decision = ReloginPolicy.shouldAttempt(
            facts = ReloginPolicy.Facts(
                globalEnabled = globalEnabled,
                accountEnabled = fresh.autoReloginEnabled,
                hasCredential = hasCred,
                cookieUsable = usable,
            ),
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

        val result = attemptLogin(ctx, fresh.uid, hasPwd)
        if (result == null) {
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
        if (result.ok) {
            account.markReloginSuccess(
                ctx, fresh.uid, result.cookie, result.loginAt, result.serverExpiresAt, at = System.currentTimeMillis(),
            )
            Log.i(TAG, "自动重登成功 uid=${fresh.uid} trigger=$trigger via=${result.via}")
            return Outcome(true, true, message = result.okText)
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
        Log.w(TAG, "自动重登失败 uid=${fresh.uid} trigger=$trigger code=${result.code} via=${result.via}")
        return Outcome(false, true, result.code, failure.message)
    }

    /** 一次登录尝试的统一结果（账密与 ALC 两条路线收敛成同一个形状）。 */
    private data class LoginResult(
        val ok: Boolean,
        val code: String = "",
        val retcode: String = "",
        val cookie: String = "",
        val loginAt: Long = 0L,
        val serverExpiresAt: Long = 0L,
        val via: String = "",
        val okText: String = "",
    )

    /**
     * 按可用凭证路由登录方式。
     *
     * - **有密码 → 账密 SSO（路线 D）**：能力最全，能处理"SSO 要求重新验证"之外的绝大多数情况；
     * - **没密码但有 ALC → 票据重签（路线 E）**：不碰密码，凭 `ALC` 走一遍 `sso/login.php`
     *   换一套新凭据。见 `WeiboAlcLogin` 的类注释——这**不是续期**，是重新登录。
     *
     * 返回 null = 凭证存在但读不出来（Keystore 异常），调用方按 `credential_unreadable` 硬停。
     */
    private fun attemptLogin(ctx: Context, uid: String, hasPwd: Boolean): LoginResult? {
        if (hasPwd) {
            val credentials = try {
                vault.load(ctx, uid)
            } catch (e: CredentialUnreadableException) {
                null
            } ?: return null
            val (username, password) = credentials
            val r = WeiboPasswordLogin.login(username, password)
            return LoginResult(
                ok = r.ok,
                code = r.code,
                retcode = r.retcode,
                cookie = r.cookie,
                loginAt = r.loginAt,
                serverExpiresAt = r.serverExpiresAt,
                via = "password",
                okText = "账密重登成功，已更新 Cookie 与真实登录时间",
            )
        }
        val alc = try {
            vault.loadAlc(ctx, uid)
        } catch (e: CredentialUnreadableException) {
            null
        } ?: return null
        val r = WeiboAlcLogin.login(alc)
        return LoginResult(
            ok = r.ok,
            code = r.code,
            cookie = r.cookie,
            loginAt = r.loginAt,
            serverExpiresAt = r.serverExpiresAt,
            via = "alc",
            okText = "ALC 重签成功，已更新 Cookie 与真实登录时间（未使用账号密码）",
        )
    }
}
