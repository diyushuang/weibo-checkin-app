package com.wcm.checkin

import android.content.Context

/**
 * 账号维护：探活 → 落库（含昵称补拉）→ 面板同步。
 *
 * 首页「Cookie 同步」与后台 [CheckInWorker] 共用这一份实现：
 * 以前两边各写一套 markAlive，补昵称/同步/内存快照的细节已经开始分叉。
 */
class AccountMaintenanceService(
    /** 账号表的定点写回（探活结果/同步时刻）。 */
    private val account: AccountPort,
    /** 面板地址与令牌。 */
    private val panel: PanelPort,
    /** 凭证库：只用来「把 cookie 里自带的 ALC 收进来」，不读密码。 */
    private val vault: CredentialPort,
) {

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
        account.markVerified(
            ctx, acc.uid, cookie,
            name = name,
            at = at,
            serverExpiresAt = serverExpiresAt,
            loginAt = loginAt,
        )
        harvestAlc(ctx, acc, cookie)
    }

    /**
     * 把 cookie 里自带的 `ALC` 收进票据库。
     *
     * 为什么值得单独做：`ALC` 是**免密码重签**的唯一凭据，而它本来就在登录/探活返回的
     * cookie 串里；此前只有「用户去浏览器开发者工具里复制再粘贴」这一条路，
     * 扫码登录拿到的 ALC 直接白丢（实测账号 `alcAt=0` 就是这个原因）。
     *
     * 只在**还没有 ALC**时写：不覆盖用户手动填的那份（手动填的可能是更新的）。
     * 提取失败/为空一律静默——这条链路是纯增益，不能影响探活主流程。
     */
    private fun harvestAlc(ctx: Context, acc: Account, cookie: String) {
        runCatching {
            if (vault.hasAlc(ctx, acc.uid)) return
            val alc = WeiboAlcLogin.alcFromCookie(cookie)
            if (alc.isEmpty()) return
            // 写票据库 + 记录保存时刻，两步都在适配器里完成
            vault.saveAlc(ctx, acc.uid, alc)
            acc.alcAt = System.currentTimeMillis()
        }
    }

    /** 昵称仍是占位值时补拉；拿不到返回空串，不影响主流程。 */
    fun resolveNameIfNeeded(acc: Account, cookie: String): String {
        if (!account.needsNameResolve(acc)) return ""
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
        val url = panel.panelUrl(ctx)
        val token = panel.panelToken(ctx)
        if (url.isEmpty() || token.isEmpty()) return SyncResult(false, "未配置面板")
        val displayName = name.ifEmpty { acc.name }
        return try {
            val r = PanelApi.sync(url, token, acc.cookie, acc.uid, displayName)
            if (r.ok) {
                account.markSynced(ctx, acc.uid, System.currentTimeMillis())
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
