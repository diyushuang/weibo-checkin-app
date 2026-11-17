package com.wcm.checkin

import android.content.Context

/**
 * `:core` 里各端口的实现：**只做转发**，不缓存、不加锁、不改变语义。
 *
 * 为什么用适配器而不是让 `AccountStore` 之类直接 `: AccountPort`：
 * 那些对象的方法带默认参数（`at: Long = System.currentTimeMillis()`），而 Kotlin 不允许
 * 重写函数再声明默认值。让它们实现端口就得把所有默认值搬到接口上，进而改动 app 侧几十处
 * 「省略参数」的调用点 —— 为了一层抽象去改动调用点，是拿行为风险换形式。
 * 适配器只增加一层方法调用，零语义变化。
 *
 * 注意：适配器**无状态**，可以安全地做成 `@Singleton`。
 */

class AccountStoreAdapter : AccountPort {
    override fun load(ctx: Context): MutableList<Account> = AccountStore.load(ctx)

    override fun markVerified(
        ctx: Context,
        uid: String,
        cookie: String,
        name: String,
        at: Long,
        serverExpiresAt: Long,
        loginAt: Long,
    ): Boolean = AccountStore.markVerified(ctx, uid, cookie, name, at, serverExpiresAt, loginAt)

    override fun markDead(ctx: Context, uid: String, reason: String, at: Long): Boolean =
        AccountStore.markDead(ctx, uid, reason, at)

    override fun markSynced(ctx: Context, uid: String, at: Long): Boolean =
        AccountStore.markSynced(ctx, uid, at)

    override fun updateCookie(
        ctx: Context,
        uid: String,
        cookie: String,
        domainHints: String,
    ): Boolean = AccountStore.patchAccount(
        ctx, uid, cookie = cookie, domainHints = domainHints,
    )

    override fun setLastResult(ctx: Context, uid: String, result: String, at: Long) =
        AccountStore.setLastResult(ctx, uid, result, at)

    override fun needsNameResolve(acc: Account): Boolean = AccountStore.needsNameResolve(acc)

    override fun reloginState(acc: Account): ReloginPolicy.State = AccountStore.reloginState(acc)

    override fun saveReloginState(
        ctx: Context,
        uid: String,
        state: ReloginPolicy.State,
    ): Boolean = AccountStore.saveReloginState(ctx, uid, state)

    override fun markReloginSuccess(
        ctx: Context,
        uid: String,
        cookie: String,
        loginAt: Long,
        serverExpiresAt: Long,
        at: Long,
    ): Boolean = AccountStore.markReloginSuccess(ctx, uid, cookie, loginAt, serverExpiresAt, at)

    override fun markReloginFailure(
        ctx: Context,
        uid: String,
        state: ReloginPolicy.State,
        message: String,
        at: Long,
        dead: Boolean,
    ): Boolean = AccountStore.markReloginFailure(ctx, uid, state, message, at, dead)

    override fun autoReloginEnabled(ctx: Context): Boolean = AccountStore.autoReloginEnabled(ctx)

    override fun autoReloginMaxPerDay(ctx: Context): Int = AccountStore.autoReloginMaxPerDay(ctx)

    override fun autoReloginBackoffHours(ctx: Context): Double =
        AccountStore.autoReloginBackoffHours(ctx)
}

class CrawlSettingsAdapter : CrawlSettingsPort {
    override fun crawlTargets(ctx: Context): MutableList<CrawlTarget> =
        CrawlSettings.crawlTargets(ctx)

    override fun crawlTreeUri(ctx: Context): String = CrawlSettings.crawlTreeUri(ctx)

    override fun crawlSince(ctx: Context, uid: String): String = CrawlSettings.crawlSince(ctx, uid)

    override fun crawlCursor(ctx: Context, treeTag: String, uid: String): CrawlCursor =
        CrawlSettings.crawlCursor(ctx, treeTag, uid)

    override fun saveCrawlCursor(ctx: Context, treeTag: String, cursor: CrawlCursor) =
        CrawlSettings.saveCrawlCursor(ctx, treeTag, cursor)

    override fun crawlDoneLegacy(ctx: Context): Map<String, Map<String, Int>> =
        CrawlSettings.crawlDoneLegacy(ctx)

    override fun clearCrawlDoneLegacy(ctx: Context) = CrawlSettings.clearCrawlDoneLegacy(ctx)

    override fun crawlMode(ctx: Context): String = CrawlSettings.crawlMode(ctx)

    override fun crawlGif(ctx: Context): Boolean = CrawlSettings.crawlGif(ctx)

    override fun albumSince(ctx: Context, treeTag: String, uid: String): String =
        CrawlSettings.albumSince(ctx, treeTag, uid)

    override fun setAlbumSince(ctx: Context, treeTag: String, uid: String, sinceId: String) =
        CrawlSettings.setAlbumSince(ctx, treeTag, uid, sinceId)

    override fun albumLastPost(ctx: Context, treeTag: String, uid: String): String =
        CrawlSettings.albumLastPost(ctx, treeTag, uid)

    override fun setAlbumLastPost(ctx: Context, treeTag: String, uid: String, value: String) =
        CrawlSettings.setAlbumLastPost(ctx, treeTag, uid, value)

    override fun selectTargets(list: List<CrawlTarget>, uids: Set<String>): List<CrawlTarget> =
        CrawlSettings.selectTargets(list, uids)
}

class TopicCacheAdapter : TopicCachePort {
    override fun save(ctx: Context, uid: String, topics: List<WeiboTopicApi.Topic>, at: Long) =
        TopicStore.save(ctx, uid, topics, at)
}

class PanelSettingsAdapter : PanelPort {
    override fun panelUrl(ctx: Context): String = PanelSettings.panelUrl(ctx)

    override fun panelToken(ctx: Context): String = PanelSettings.panelToken(ctx)
}

class CredentialVaultAdapter : CredentialPort {
    override fun has(ctx: Context, uid: String): Boolean = CredentialVault.has(ctx, uid)

    override fun load(ctx: Context, uid: String): Pair<String, String>? =
        CredentialVault.load(ctx, uid)

    override fun hasAlc(ctx: Context, uid: String): Boolean = AlcVault.has(ctx, uid)

    override fun loadAlc(ctx: Context, uid: String): String? = AlcVault.load(ctx, uid)

    override fun saveAlc(ctx: Context, uid: String, alc: String) {
        if (alc.isBlank()) return
        AlcVault.save(ctx, uid, alc)
        // 顺手记「什么时候存过 ALC」：界面用它显示「已保存 ALC（时间）」，
        // `Account.alcAt` 存在账号表里，由这里统一维护，调用方不必再单独写一次。
        AccountStore.setAlcAt(ctx, uid, System.currentTimeMillis())
    }
}
