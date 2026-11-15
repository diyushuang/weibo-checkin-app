package com.wcm.checkin

import android.content.Context

/**
 * 账号维护：探活 → 落库（含昵称补拉）→ 面板同步。
 *
 * 首页「Cookie 同步」与后台 [CheckInWorker] 共用这一份实现：
 * 以前两边各写一套 markAlive，补昵称/同步/内存快照的细节已经开始分叉。
 */
object AccountMaintenanceService {

    /**
     * 探活成功后的统一收尾：更新内存快照 → 落库。
     *
     * 不发起网络探活，调用方已经把 verify 结果拿到手。`name` 非空时顺带补昵称，
     * 并同步到内存里的 [Account]（面板同步会用到最新昵称）。
     */
    fun persistVerified(
        ctx: Context,
        acc: Account,
        cookie: String,
        loginAt: Long = 0L,
        serverExpiresAt: Long = 0L,
        name: String = "",
        at: Long = System.currentTimeMillis(),
    ) {
        acc.cookie = cookie
        acc.cookieSeenAt = at
        acc.cookieLastCheckAt = at
        acc.cookieDead = false
        acc.cookieLastError = ""
        if (loginAt > 0L) acc.cookieLoginAt = loginAt
        if (serverExpiresAt > 0L) acc.cookieServerExpiresAt = serverExpiresAt
        AccountStore.markVerified(
            ctx, acc.uid, cookie,
            name = name,
            at = at,
            serverExpiresAt = serverExpiresAt,
            loginAt = loginAt,
        )
    }

    /** 昵称仍是占位值时补拉；拿不到返回空串，不影响主流程。 */
    fun resolveNameIfNeeded(acc: Account, cookie: String): String {
        if (!AccountStore.needsNameResolve(acc)) return ""
        return try {
            WeiboAuthApi.fetchProfile(cookie, acc.uid).first
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 探活 + 落库 + 按需补昵称。
     *
     * @return 探活结果；网络异常会抛出，由调用方决定记失败还是继续。
     *   明确 `login=false` 时不落库 —— 由调用方走 [AccountStore.markDead]。
     */
    fun probeAndPersist(
        ctx: Context,
        acc: Account,
        force: Boolean = false,
        resolveName: Boolean = false,
    ): WeiboAuthApi.Verify {
        val verify = WeiboAuthApi.verify(acc.cookie, force = force)
        if (!verify.login) return verify
        val name = if (resolveName) resolveNameIfNeeded(acc, verify.cookie) else ""
        persistVerified(
            ctx, acc, verify.cookie,
            loginAt = verify.loginAt,
            serverExpiresAt = verify.serverExpiresAt,
            name = name,
        )
        return verify
    }

    data class SyncResult(val ok: Boolean, val detail: String = "")

    /**
     * 把一个账号的 cookie 推送到面板；成功后记 [AccountStore.markSynced]。
     *
     * 未配置面板时返回 `ok=false, detail="未配置面板"`（调用方按需静默或写日志）。
     */
    fun syncToPanel(
        ctx: Context,
        acc: Account,
        name: String = "",
    ): SyncResult {
        val url = PanelSettings.panelUrl(ctx)
        val token = PanelSettings.panelToken(ctx)
        if (url.isEmpty() || token.isEmpty()) return SyncResult(false, "未配置面板")
        val displayName = name.ifEmpty { acc.name }
        return try {
            val r = PanelApi.sync(url, token, acc.cookie, acc.uid, displayName)
            if (r.ok) {
                AccountStore.markSynced(ctx, acc.uid)
                acc.cookieSyncedAt = System.currentTimeMillis()
                SyncResult(true, if (r.updated) "覆盖 ${r.name}" else "新建 ${r.name}")
            } else {
                SyncResult(false, r.detail)
            }
        } catch (e: Exception) {
            SyncResult(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
