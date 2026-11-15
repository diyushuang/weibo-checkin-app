package com.wcm.checkin

import android.content.Context

/**
 * 业务层（`:domain`）对本地状态的最小端口。
 *
 * 为什么要有这一层：`:domain` 里是签到、抓取、重登的编排逻辑，它不该知道状态存在
 * SharedPreferences 还是 SQLite，更不该直接依赖 `:data` 的实现。加入端口后，依赖方向是
 * `:domain -> :core <- :data`，实现类由 `:app` 的组合根（`DomainModule`）注入。
 *
 * 三条约定：
 * 1. **只暴露 domain 真正调用的方法**，不做 `AccountStore` 之类整对象镜像 ——
 *    镜像会让 domain 的契约退化成「data 的另一个名字」，倒置就白做了。
 * 2. 端口方法保留 `Context` 形参：`Context` 由调用方（服务层，最终来自 ViewModel /
 *    Worker）传入，端口自身不持有 Android 单例。这样既不用改任何调用点签名，
 *    也避免在 data 里藏一个静态 Context。
 * 3. 端口**不声明默认值**：Kotlin 不允许重写函数带默认值，默认值留在实现类里，
 *    端口调用方显式传全参。
 */

/** 账号表：读全表 + 签到/同步/重登的定点写回。 */
interface AccountPort {

    /** 读全表（返回的是新壳，调用方可随意改）。 */
    fun load(ctx: Context): MutableList<Account>

    /** 探活成功：刷新 cookie / 昵称 / 各类时刻，并清掉 cookieDead。 */
    fun markVerified(
        ctx: Context,
        uid: String,
        cookie: String,
        name: String,
        at: Long,
        serverExpiresAt: Long,
        loginAt: Long,
    ): Boolean

    /** 探活明确判定登录失效。 */
    fun markDead(ctx: Context, uid: String, reason: String, at: Long): Boolean

    /** 记录一次成功的面板同步（只影响面板，不作为 cookie 寿命基准）。 */
    fun markSynced(ctx: Context, uid: String, at: Long): Boolean

    /**
     * 把一次任务内累积的 cookie（含业务响应里的 `Set-Cookie` 换发）写回账号。
     *
     * 是否算"换发"由实现按 `LOGIN_CRITICAL_KEYS` 比对决定，调用方不判断——
     * 这样"什么时候推进寿命锚点"的口径只有一处（见 `CookieLife`）。
     *
     * @param domainHints 学到的 `name=domain` 映射；空串表示这次没有新信息
     */
    fun updateCookie(ctx: Context, uid: String, cookie: String, domainHints: String): Boolean

    /** 写「上次签到小结」——签到链路的唯一写入点。 */
    fun setLastResult(ctx: Context, uid: String, result: String, at: Long)

    /** 昵称是否还需要补拉（历史数据里可能存的是占位名）。 */
    fun needsNameResolve(acc: Account): Boolean

    fun reloginState(acc: Account): ReloginPolicy.State

    fun saveReloginState(ctx: Context, uid: String, state: ReloginPolicy.State): Boolean

    fun markReloginSuccess(
        ctx: Context,
        uid: String,
        cookie: String,
        loginAt: Long,
        serverExpiresAt: Long,
        at: Long,
    ): Boolean

    fun markReloginFailure(
        ctx: Context,
        uid: String,
        state: ReloginPolicy.State,
        message: String,
        at: Long,
        dead: Boolean,
    ): Boolean

    fun autoReloginEnabled(ctx: Context): Boolean

    fun autoReloginMaxPerDay(ctx: Context): Int

    fun autoReloginBackoffHours(ctx: Context): Double
}

/** 图片抓取设置：目标、SAF 目录、游标、模式与 Live/GIF 开关。 */
interface CrawlSettingsPort {

    fun crawlTargets(ctx: Context): MutableList<CrawlTarget>

    fun crawlTreeUri(ctx: Context): String

    fun crawlSince(ctx: Context, uid: String): String

    fun crawlCursor(ctx: Context, treeTag: String, uid: String): CrawlCursor

    fun saveCrawlCursor(ctx: Context, treeTag: String, cursor: CrawlCursor)

    /** 旧版进度（仅用于一次性迁移到 NDJSON 索引）。 */
    fun crawlDoneLegacy(ctx: Context): Map<String, Map<String, Int>>

    fun clearCrawlDoneLegacy(ctx: Context)

    fun crawlMode(ctx: Context): String

    fun crawlGif(ctx: Context): Boolean

    fun albumSince(ctx: Context, treeTag: String, uid: String): String

    fun setAlbumSince(ctx: Context, treeTag: String, uid: String, sinceId: String)

    fun albumLastPost(ctx: Context, treeTag: String, uid: String): String

    fun setAlbumLastPost(ctx: Context, treeTag: String, uid: String, value: String)

    /** 纯函数：从目标表里挑出指定 uid。 */
    fun selectTargets(list: List<CrawlTarget>, uids: Set<String>): List<CrawlTarget>
}

/** 超话快照缓存（按天失效）。domain 只需要写入能力，读取在 UI 层。 */
interface TopicCachePort {

    fun save(ctx: Context, uid: String, topics: List<WeiboTopicApi.Topic>, at: Long)
}

/** 面板地址与令牌。 */
interface PanelPort {

    fun panelUrl(ctx: Context): String

    fun panelToken(ctx: Context): String
}

/**
 * 账号凭证库（Keystore 加密）。
 *
 * 两类凭证分开存、分开判：
 * - **账号密码**：走 `sso/login.php` 表单登录（路线 D），要存两个字段，风险最高；
 * - **`ALC`**：SSO 长效票据，凭它重签一次即可拿到新 `SUB`（路线 E），
 *   不涉及密码，是"不想把密码交给 App"的用户唯一可用的自动恢复手段。
 *
 * 优先级由 domain 层决定：有密码先用密码，没有才用 ALC（见 `AutoRelogin`）。
 */
interface CredentialPort {

    fun has(ctx: Context, uid: String): Boolean

    /** 返回 (账号, 密码)；解不开时抛 `CredentialUnreadableException`。 */
    fun load(ctx: Context, uid: String): Pair<String, String>?

    /** 是否保存过 `ALC`（不解密，不抛异常）。 */
    fun hasAlc(ctx: Context, uid: String): Boolean

    /** 读取并解密 `ALC`；没存返回 null，解不开抛 `CredentialUnreadableException`。 */
    fun loadAlc(ctx: Context, uid: String): String?
}
