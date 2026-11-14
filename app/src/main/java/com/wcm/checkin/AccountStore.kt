package com.wcm.checkin

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** 账号与配置存储：全部放应用私有 SharedPreferences（无任何存储权限）。 */
object AccountStore {

    data class Account(
        val uid: String,
        val name: String,
        var cookie: String,
        var lastResult: String = "",
        /**
         * [lastResult] 的写入时刻（epoch ms；0 = 旧版本写的 / 时间未知）。
         *
         * 签到状态是**按天**的：只有知道小结是哪天写的，才能判断它能不能当「今天」的
         * 结论。没有这个时间戳时，昨天 22:00 的「签到 7/7 · 22:00」会被当成今天已签。
         */
        var lastResultAt: Long = 0L,
        /** 本机首次存下这份 cookie 的时刻（epoch ms；0 = 没有记录）。 */
        var cookieIssuedAt: Long = 0L,
        /** 最近一次观察到微博**真的换发**登录关键字段（SUB/SUBP/SCF/SSOLoginState/ALF）的时刻。 */
        var cookieRotatedAt: Long = 0L,
        /** 最近一次探活确认「登录有效」的时刻。只证明还活着，**不作为寿命基准**。 */
        var cookieSeenAt: Long = 0L,
        /** 最近一次成功推送到面板的时刻。同步只影响面板，**不作为本机寿命基准**。 */
        var cookieSyncedAt: Long = 0L,
        /**
         * 探活已明确判定登录态失效（`login=false`）。
         *
         * 有效期是**估算**：一个已经掉登录的账号照样能算出「还剩 5 天」，
         * 这比不给结论更糟。所以探活失败要单独记一笔，界面上直接显示「已失效」，
         * 让用户去重新登录，而不是等那个算出来的倒计时走完。
         * 任何一次探活成功（[markVerified]）都会清掉它。
         */
        var cookieDead: Boolean = false,
        /**
         * 真实登录时刻（来自 cookie 的 `SSOLoginState`，缺失时用本次账密登录时刻）。
         *
         * 与 [cookieSyncedAt] 严格分开：同步不是登录，不能把同步时刻当寿命起点。
         */
        var cookieLoginAt: Long = 0L,
        /** 服务端**声明/名义**到期时间（Set-Cookie 的 Expires/Max-Age 或 ALF；0 = 未知）。 */
        var cookieServerExpiresAt: Long = 0L,
        /** 最近一次让寿命起算的关键字段列表（逗号分隔）；仅用于界面说明「为什么续期」。 */
        var cookieRotateKeys: String = "",
        /** 最近一次探测失败原因（网络/风控/失效），给用户看，成功时清空。 */
        var cookieLastError: String = "",
        /** 最近一次探测（无论成败）的时刻。 */
        var cookieLastCheckAt: Long = 0L,
        // ---- 账号密码自动重登（凭证本身在 CredentialVault 加密保存，不落在这里）----
        var autoReloginEnabled: Boolean = true,
        /** 最近一次保存/更新凭证的时刻。 */
        var credentialAt: Long = 0L,
        var reloginLastAttemptAt: Long = 0L,
        var reloginNextAt: Long = 0L,
        var reloginFailStreak: Int = 0,
        /** 24 小时窗口内的尝试时刻（epoch ms）。 */
        var reloginAttempts: List<Long> = emptyList(),
        var reloginBlocked: Boolean = false,
        var reloginLastReason: String = "",
        var reloginLastCode: String = "",
    )

    private const val PREFS = "wcm"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_PANEL_URL = "panel_url"
    private const val KEY_PANEL_TOKEN = "panel_token"
    private const val KEY_AUTO_ON_OPEN = "auto_on_open"
    private const val KEY_LAST_AUTO_AT = "last_auto_at"

    // ---- 账号密码自动重登（全局策略）----
    private const val KEY_AUTO_RELOGIN = "auto_relogin_enabled"
    private const val KEY_AUTO_RELOGIN_MAX = "auto_relogin_max_per_day"
    private const val KEY_AUTO_RELOGIN_BACKOFF = "auto_relogin_backoff_hours"

    // ---- 图片抓取（v0.2.0）----
    private const val KEY_CRAWL_TARGETS = "crawl_targets"   // [{"uid","name"}]
    private const val KEY_CRAWL_TREE = "crawl_tree_uri"     // SAF 相册目录持久授权
    private const val KEY_CRAWL_DONE_LEGACY = "crawl_done"  // 旧版进度，仅用于一次性迁移
    private const val KEY_CRAWL_CURSORS = "crawl_cursors_v2"// {treeTag: {uid: cursor}}
    private const val KEY_CRAWL_SINCE = "crawl_since"       // {uid: "yyyy-MM-dd"}
    private const val KEY_CRAWL_AUTO = "crawl_auto"         // 签到后自动增量抓图
    private const val KEY_CRAWL_GIF = "crawl_gif"           // Live Photo 转 GIF（默认开）
    private const val KEY_CRAWL_MODE = "crawl_mode"         // album（默认）/ timeline
    private const val KEY_ALBUM_SINCE = "crawl_album_since" // {treeTag: {uid: since_id}}
    private const val KEY_ALBUM_LAST = "crawl_album_last" // {treeTag: {uid: "mid|yyyy-MM"}}

    /** 相册抓图模式（游客身份，默认）。 */
    const val MODE_ALBUM = "album"

    /** 时间线抓图模式（原实现，兜底）。 */
    const val MODE_TIMELINE = "timeline"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 进程级写锁：所有「读 → 改 → 整表写回」的方法都必须持锁。
     *
     * 账号表是全量 JSON，同一时刻可能有四个写入方：签到收尾的 [mergeSave]、超话刷新里的
     * 昵称/cookie 补写（[upsert] 语义）、超话页的 cookie 轮换、以及 WorkManager 保活。
     * 各自「load 一份旧表 → 改 → save 覆盖」会在并发时互相吞掉对方的写入 ——
     * 最典型的现象是刚轮换到的新 cookie 被旧快照写回，下一轮直接掉登录。
     *
     * `synchronized` 可重入，内层调用 [load]/[save] 是安全的。
     */
    private val LOCK = Any()

    /** [load] 的解析缓存：prefs 原文没变就不重复做一次 JSON 解析。 */
    private var cacheRaw: String? = null
    private var cacheList: List<Account> = emptyList()

    /**
     * 读账号表。
     *
     * 调用点很多都在 UI 线程（首页每次 render、列表 bind），而这里是「取 prefs 字符串 +
     * 全量 JSON 解析」。SharedPreferences 本身读的是内存快照，真正的开销在解析上，
     * 所以按原文做一层缓存：只有真正写盘后原文变了才重新解析。
     * [Account] 是不可变数据类，返回的 MutableList 每次都是新壳，调用方随便改。
     */
    fun load(ctx: Context): MutableList<Account> {
        val raw = SafePrefs.string(prefs(ctx), KEY_ACCOUNTS, "[]")
        val cached = synchronized(LOCK) {
            if (raw == cacheRaw) {
                cacheList
            } else {
                val parsed = deserializeAccounts(raw)
                cacheRaw = raw
                cacheList = parsed
                parsed
            }
        }
        return cached.toMutableList()
    }

    fun save(ctx: Context, accounts: List<Account>) {
        synchronized(LOCK) {
            val raw = serializeAccounts(accounts)
            prefs(ctx).edit { putString(KEY_ACCOUNTS, raw) }
            // 顺手把缓存对齐，省掉紧随其后的那次重新解析
            cacheRaw = raw
            cacheList = accounts
        }
    }

    // ---- 纯序列化与合并（不依赖 Android，JVM 单测覆盖） ----

    /** 昵称占位值：登录时 fetchProfile 没取到用户名会写成「微博 <uid>」。 */
    fun placeholderName(uid: String): String = "微博 $uid"

    /** 是否还需要去拉一次真实昵称（列表里不能只显示 UID）。 */
    fun needsNameResolve(acc: Account): Boolean =
        acc.name.isEmpty() || acc.name == acc.uid || acc.name == placeholderName(acc.uid)

    fun serializeAccounts(accounts: List<Account>): String {
        val arr = JSONArray()
        accounts.forEach {
            arr.put(
                JSONObject()
                    .put("uid", it.uid)
                    .put("name", it.name)
                    .put("cookie", it.cookie)
                    .put("lastResult", it.lastResult)
                    .put("lastResultAt", it.lastResultAt)
                    .put("issuedAt", it.cookieIssuedAt)
                    .put("rotatedAt", it.cookieRotatedAt)
                    .put("seenAt", it.cookieSeenAt)
                    .put("syncedAt", it.cookieSyncedAt)
                    .put("dead", it.cookieDead)
                    .put("loginAt", it.cookieLoginAt)
                    .put("serverExpiresAt", it.cookieServerExpiresAt)
                    .put("rotateKeys", it.cookieRotateKeys)
                    .put("lastError", it.cookieLastError)
                    .put("lastCheckAt", it.cookieLastCheckAt)
                    .put("autoRelogin", it.autoReloginEnabled)
                    .put("credentialAt", it.credentialAt)
                    .put("rlLast", it.reloginLastAttemptAt)
                    .put("rlNext", it.reloginNextAt)
                    .put("rlStreak", it.reloginFailStreak)
                    .put("rlAttempts", JSONArray(it.reloginAttempts))
                    .put("rlBlocked", it.reloginBlocked)
                    .put("rlReason", it.reloginLastReason)
                    .put("rlCode", it.reloginLastCode)
            )
        }
        return arr.toString()
    }

    fun deserializeAccounts(raw: String): List<Account> {
        val list = ArrayList<Account>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Account(
                        uid = o.optString("uid", ""),
                        name = o.optString("name", "未命名账号"),
                        cookie = o.optString("cookie", ""),
                        lastResult = o.optString("lastResult", ""),
                        lastResultAt = o.optLong("lastResultAt", 0L),
                        // 旧版本没有这些键：时间戳落 0 = 没有锚点（界面显示「未同步」），
                        // 失效标记落 false = 还没被探活判过
                        cookieIssuedAt = o.optLong("issuedAt", 0L),
                        cookieRotatedAt = o.optLong("rotatedAt", 0L),
                        cookieSeenAt = o.optLong("seenAt", 0L),
                        cookieSyncedAt = o.optLong("syncedAt", 0L),
                        cookieDead = o.optBoolean("dead", false),
                        cookieLoginAt = o.optLong("loginAt", 0L),
                        cookieServerExpiresAt = o.optLong("serverExpiresAt", 0L),
                        cookieRotateKeys = o.optString("rotateKeys", ""),
                        cookieLastError = o.optString("lastError", ""),
                        cookieLastCheckAt = o.optLong("lastCheckAt", 0L),
                        autoReloginEnabled = o.optBoolean("autoRelogin", true),
                        credentialAt = o.optLong("credentialAt", 0L),
                        reloginLastAttemptAt = o.optLong("rlLast", 0L),
                        reloginNextAt = o.optLong("rlNext", 0L),
                        reloginFailStreak = o.optInt("rlStreak", 0),
                        reloginAttempts = parseAttemptStamps(o.optJSONArray("rlAttempts")),
                        reloginBlocked = o.optBoolean("rlBlocked", false),
                        reloginLastReason = o.optString("rlReason", ""),
                        reloginLastCode = o.optString("rlCode", ""),
                    )
                )
            }
        } catch (e: Exception) {
            // 存储损坏时静默降级（返回已解析的部分），宁可丢账号也不能让 App 起不来
        }
        return list
    }

    /** 解析自动重登的 24 小时尝试记录（脏值静默丢弃）。 */
    private fun parseAttemptStamps(arr: JSONArray?): List<Long> {
        if (arr == null) return emptyList()
        val out = ArrayList<Long>()
        for (i in 0 until arr.length()) {
            val v = arr.optLong(i, 0L)
            if (v > 0L) out.add(v)
        }
        return out
    }

    /**
     * 合并两个账号列表：以 current 为准（成员不增不减），只把 snapshot 里同 uid
     * 账号的 cookie / lastResult / 时间锚点回填进来。运行期间用户新增 / 删除的账号不会被
     * 旧快照覆盖（Worker 与前台签到共用，防丢新账号 / 防删掉的账号复活）。
     *
     * 昵称单独处理：只有**当前还是占位名**、而快照里已经是真实昵称时才覆盖 ——
     * 后台保活顺手补拉的昵称能落地，又不会把用户已经看到的名字改回去
     * （面板侧的自定义名同理，见 AccountStorePureTest 的既有断言）。
     *
     * 失效标记（`cookieDead`）**不从快照合并**：它只由 [markDead] / [markVerified]
     * 直接写库，而快照是"任务开始前"的状态。按 OR 合并会让刚探活成功的账号被旧快照
     * 重新标成失效，按快照覆盖又会抹掉任务中途刚记下的失效 —— 保持库里那份最稳。
     *
     * cookie / lastResult 按「谁新用谁」：旧实现无条件用快照胜出，而时间锚点却用 maxOf，
     * 方向自相矛盾 —— 后台保活 Worker 跑着时用户前台重新扫码登录，Worker 结束时
     * 会把**任务开始前的旧 cookie** 写回去，用户立刻掉登录，界面还显示「刚刚验证」。
     *
     * 但 `lastResult` 多一条兜底：**空串不算结论** —— 只有一侧有值就用那一侧。
     * 这是「签到成功却仍显示尚未签到」的成因之一 —— 任务途中 [markVerified] 会把库里的
     * 锚点推进到快照之后，于是 `snapNewer=false`，快照里刚算出来的「签到 7/7」被
     * 库里那个空串顶掉；反过来，任务开始前的旧快照也不能拿空串把库里刚写好的结果抹掉。
     * 时间戳 [Account.lastResultAt] 跟着被采纳的那一份走，界面据此判断能否当今日结论。
     */
    fun mergeAccounts(current: List<Account>, snapshot: List<Account>): List<Account> =
        current.map { cur ->
            snapshot.firstOrNull { snap -> snap.uid.isNotEmpty() && snap.uid == cur.uid }
                ?.let { snap ->
                    // 最新锚点决定谁是「新的那一份」
                    val snapNewer = newestAnchor(snap) > newestAnchor(cur)
                    // lastResult 与它的时间戳**成对**选：两侧都有值时取锚点新的一侧，
                    // 空串不算结论 —— 否则「库里刚空、快照里刚算出签到 7/7」或反过来
                    // 「快照是任务开始前的空壳」都会把已有小结抹成空白。
                    val takeSnapResult = snap.lastResult.isNotEmpty() &&
                        (snapNewer || cur.lastResult.isEmpty())
                    cur.copy(
                        cookie = if (snapNewer) snap.cookie else cur.cookie,
                        lastResult = if (takeSnapResult) snap.lastResult else cur.lastResult,
                        lastResultAt = if (takeSnapResult) snap.lastResultAt else cur.lastResultAt,
                        name = if (needsNameResolve(cur) && !needsNameResolve(snap)) {
                            snap.name
                        } else {
                            cur.name
                        },
                        cookieIssuedAt = maxOf(cur.cookieIssuedAt, snap.cookieIssuedAt),
                        cookieRotatedAt = maxOf(cur.cookieRotatedAt, snap.cookieRotatedAt),
                        cookieSeenAt = maxOf(cur.cookieSeenAt, snap.cookieSeenAt),
                        cookieSyncedAt = maxOf(cur.cookieSyncedAt, snap.cookieSyncedAt),
                        // 真实登录时间取更晚的一次；服务端到期跟着那次登录走，避免时间与到期错配
                        cookieLoginAt = maxOf(cur.cookieLoginAt, snap.cookieLoginAt),
                        cookieServerExpiresAt = if (snap.cookieLoginAt > cur.cookieLoginAt) {
                            snap.cookieServerExpiresAt
                        } else {
                            cur.cookieServerExpiresAt
                        },
                        cookieRotateKeys = if (snapNewer) snap.cookieRotateKeys else cur.cookieRotateKeys,
                        // 失败原因属于「库里最新一次探测」：旧快照的报错不能盖掉刚成功的状态
                        cookieLastError = if (snapNewer) snap.cookieLastError else cur.cookieLastError,
                        cookieLastCheckAt = maxOf(cur.cookieLastCheckAt, snap.cookieLastCheckAt),
                        credentialAt = maxOf(cur.credentialAt, snap.credentialAt),
                        // 自动重登开关与退避状态只由用户/策略直接写库，不从任务快照合并
                    )
                }
                ?: cur
        }

    /** 一份账号「最新」的时间锚点：比较新旧时只看这个。 */
    internal fun newestAnchor(a: Account): Long =
        maxOf(
            a.cookieRotatedAt, a.cookieSeenAt, a.cookieSyncedAt,
            a.cookieIssuedAt, a.cookieLoginAt,
        )

    /** 合并式写回：先读当前库再按 [mergeAccounts] 合并，用于长任务结束时的落盘。 */
    fun mergeSave(ctx: Context, snapshot: List<Account>) {
        synchronized(LOCK) { save(ctx, mergeAccounts(load(ctx), snapshot)) }
    }

    /** 按 uid upsert（同微博号换 cookie 只更新一行）。 */
    fun upsert(ctx: Context, account: Account) {
        synchronized(LOCK) {
            val list = load(ctx)
            val idx = list.indexOfFirst { it.uid.isNotEmpty() && it.uid == account.uid }
            if (idx >= 0) {
                list[idx] = account.copy(lastResult = list[idx].lastResult)
            } else {
                list.add(account)
            }
            save(ctx, list)
        }
    }

    /**
     * 只补写 cookie / 昵称 / 时间锚点（超话刷新、签到探活、面板同步后都会用到）。
     *
     * 用「持锁的 load-modify-save」而不是让调用方自己 load 再 save：调用方在 IO 线程
     * 上跑，两步之间随时可能被别的写入方插进来。
     *
     * 时间锚点的写入口径（[CookieLife] 依赖它们，写错就会误报临期）：
     * - cookie **真的换了** → 首次录入写 `issuedAt`；
     * - **只有登录关键字段（SUB/SUBP/SCF/SSOLoginState/ALF）变化才写 `rotatedAt`** ——
     *   探活成功（seenAt）或推到面板（syncedAt）都只是观察，不是续期；
     * - [seenAt] / [syncedAt] / [checkedAt] 只前进不后退，传 0 表示这次不更新。
     *
     * @param loginAt 真实登录时刻（0 = 从 cookie 的 SSOLoginState 推断）
     * @param serverExpiresAt 服务端声明到期（0 = 从 ALF 推断 / 保持原值）
     * @param lastError 传 null 表示不动；传空串表示清除（探活成功）
     * @return 是否真的写了盘（没变化就不写，减少 IO 与冲突面）。
     */
    fun patchAccount(
        ctx: Context,
        uid: String,
        cookie: String = "",
        name: String = "",
        seenAt: Long = 0L,
        syncedAt: Long = 0L,
        loginAt: Long = 0L,
        serverExpiresAt: Long = 0L,
        checkedAt: Long = 0L,
        lastError: String? = null,
        dead: Boolean? = null,
    ): Boolean {
        synchronized(LOCK) {
            val list = load(ctx)
            val index = list.indexOfFirst { it.uid == uid }
            if (index < 0) return false
            var acc = list[index]
            var changed = false
            if (cookie.isNotEmpty() && cookie != acc.cookie) {
                val now = System.currentTimeMillis()
                val firstTime = acc.cookie.isEmpty() || acc.cookieIssuedAt <= 0L
                // 关键点：普通 cookie 字段（_T_WM 之类）变化不算续期
                val rotateKeys = criticalChangedKeys(acc.cookie, cookie)
                val renewed = firstTime || rotateKeys.isNotEmpty()
                acc = acc.copy(
                    cookie = cookie,
                    cookieIssuedAt = if (firstTime) now else acc.cookieIssuedAt,
                    cookieRotatedAt = if (renewed) now else acc.cookieRotatedAt,
                    cookieRotateKeys = when {
                        firstTime -> "login"
                        rotateKeys.isNotEmpty() -> rotateKeys.joinToString(",")
                        else -> acc.cookieRotateKeys
                    },
                    cookieLoginAt = when {
                        loginAt > 0L -> loginAt
                        "SSOLoginState" in rotateKeys -> CookieLife.loginTimeOf(cookie)
                        else -> acc.cookieLoginAt
                    },
                    cookieServerExpiresAt = when {
                        serverExpiresAt > 0L -> serverExpiresAt
                        "ALF" in rotateKeys -> CookieLife.serverExpiryOf(cookie)
                        else -> acc.cookieServerExpiresAt
                    },
                )
                changed = true
            }
            // cookie 没换但拿到了更准确的时间信息（例如探活响应带了 SSOLoginState）
            if (loginAt > acc.cookieLoginAt) {
                acc = acc.copy(cookieLoginAt = loginAt)
                changed = true
            }
            if (serverExpiresAt > 0L && serverExpiresAt != acc.cookieServerExpiresAt) {
                acc = acc.copy(cookieServerExpiresAt = serverExpiresAt)
                changed = true
            }
            if (name.isNotEmpty() && name != acc.name) {
                acc = acc.copy(name = name)
                changed = true
            }
            if (seenAt > acc.cookieSeenAt) {
                acc = acc.copy(cookieSeenAt = seenAt)
                changed = true
            }
            if (syncedAt > acc.cookieSyncedAt) {
                acc = acc.copy(cookieSyncedAt = syncedAt)
                changed = true
            }
            if (checkedAt > acc.cookieLastCheckAt) {
                acc = acc.copy(cookieLastCheckAt = checkedAt)
                changed = true
            }
            if (lastError != null && lastError != acc.cookieLastError) {
                acc = acc.copy(cookieLastError = lastError)
                changed = true
            }
            if (dead != null && dead != acc.cookieDead) {
                acc = acc.copy(cookieDead = dead)
                changed = true
            }
            if (changed) {
                list[index] = acc
                save(ctx, list)
            }
            return changed
        }
    }

    /**
     * 记录一次「探活确认登录有效」：同时收下换发后的 cookie。
     *
     * [name] 非空时顺带补昵称 —— 后台保活顺手把占位名换成真实昵称，用户下次打开
     * 就不再看到「（未获取到昵称）」。
     *
     * [serverExpiresAt] / [loginAt] 来自探活响应的 Set-Cookie / SSOLoginState；
     * 没有就是 0，由 cookie 自身兜底。
     */
    fun markVerified(
        ctx: Context,
        uid: String,
        cookie: String,
        name: String = "",
        at: Long = System.currentTimeMillis(),
        serverExpiresAt: Long = 0L,
        loginAt: Long = 0L,
    ): Boolean = patchAccount(
        ctx, uid,
        cookie = cookie,
        name = name,
        seenAt = at,
        loginAt = loginAt,
        serverExpiresAt = serverExpiresAt,
        checkedAt = at,
        lastError = "",
        dead = false,
    )

    /**
     * 记录一次「探活明确判定登录态失效」。
     *
     * 只由 `login == false` 触发，**网络异常不写**：断网、风控、超时都不代表登录态没了，
     * 把它们记成失效会让整块 Cookie 卡变成一片红报警。
     */
    fun markDead(
        ctx: Context,
        uid: String,
        reason: String = "",
        at: Long = System.currentTimeMillis(),
    ): Boolean = patchAccount(
        ctx, uid,
        checkedAt = at,
        lastError = reason.ifEmpty { "登录态已失效" },
        dead = true,
    )

    /** 记录一次「探测失败但不是失效」（网络 / 风控 / 接口异常）：原因要能被用户看到。 */
    fun markCheckError(
        ctx: Context,
        uid: String,
        reason: String,
        at: Long = System.currentTimeMillis(),
    ): Boolean = patchAccount(ctx, uid, checkedAt = at, lastError = reason.ifEmpty { "探测失败" })

    /** 记录一次成功推送到面板：只影响面板侧计时，**不是本机续期依据**。 */
    fun markSynced(ctx: Context, uid: String, at: Long = System.currentTimeMillis()): Boolean =
        patchAccount(ctx, uid, syncedAt = at)

    // ---- 账号密码自动重登 ----

    /** 登录关键字段：只有这些值真的变化，才说明微博换发了一套新凭据。 */
    internal val LOGIN_CRITICAL_KEYS = listOf("SUB", "SUBP", "SCF", "SSOLoginState", "ALF")

    /** 比较新旧 cookie，返回真正变化的登录关键字段。 */
    internal fun criticalChangedKeys(oldCookie: String, newCookie: String): List<String> {
        val old = WeiboHttp.parseCookieString(oldCookie)
        val fresh = WeiboHttp.parseCookieString(newCookie)
        return LOGIN_CRITICAL_KEYS.filter { old[it] != fresh[it] }
    }

    fun reloginState(acc: Account): ReloginPolicy.State = ReloginPolicy.State(
        lastAttemptAt = acc.reloginLastAttemptAt,
        nextAt = acc.reloginNextAt,
        failStreak = acc.reloginFailStreak,
        attempts = acc.reloginAttempts,
        blocked = acc.reloginBlocked,
        lastReason = acc.reloginLastReason,
        lastCode = acc.reloginLastCode,
        credentialAt = acc.credentialAt,
    )

    /** 只更新自动重登策略状态（不含 cookie / 凭证）。 */
    fun saveReloginState(ctx: Context, uid: String, state: ReloginPolicy.State): Boolean {
        synchronized(LOCK) {
            val list = load(ctx)
            val index = list.indexOfFirst { it.uid == uid }
            if (index < 0) return false
            val old = list[index]
            list[index] = old.copy(
                reloginLastAttemptAt = state.lastAttemptAt,
                reloginNextAt = state.nextAt,
                reloginFailStreak = state.failStreak,
                reloginAttempts = state.attempts,
                reloginBlocked = state.blocked,
                reloginLastReason = state.lastReason,
                reloginLastCode = state.lastCode,
                credentialAt = maxOf(old.credentialAt, state.credentialAt),
            )
            save(ctx, list)
            return true
        }
    }

    /**
     * 账密重登成功：整份替换 cookie（新的 SUB/SUBP/SCF 是一套，旧值不能留），
     * 同时把真实登录时间、服务端到期、轮换来源一次写准。
     */
    fun markReloginSuccess(
        ctx: Context,
        uid: String,
        cookie: String,
        loginAt: Long,
        serverExpiresAt: Long,
        at: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(LOCK) {
            val list = load(ctx)
            val index = list.indexOfFirst { it.uid == uid }
            if (index < 0) return false
            val old = list[index]
            list[index] = old.copy(
                cookie = cookie,
                cookieIssuedAt = if (old.cookieIssuedAt > 0L) old.cookieIssuedAt else at,
                cookieRotatedAt = at,
                cookieRotateKeys = "auto_relogin",
                cookieLoginAt = if (loginAt > 0L) loginAt else at,
                cookieServerExpiresAt = serverExpiresAt,
                cookieSeenAt = at,
                cookieLastCheckAt = at,
                cookieLastError = "",
                cookieDead = false,
                reloginLastAttemptAt = at,
                reloginNextAt = 0L,
                reloginFailStreak = 0,
                reloginAttempts = emptyList(),
                reloginBlocked = false,
                reloginLastReason = "",
                reloginLastCode = "",
            )
            save(ctx, list)
            return true
        }
    }

    /** 重登失败：原因落到 `cookieLastError`，策略状态一起保存；已判失效的继续标失效。 */
    fun markReloginFailure(
        ctx: Context,
        uid: String,
        state: ReloginPolicy.State,
        message: String,
        at: Long = System.currentTimeMillis(),
        dead: Boolean,
    ): Boolean = patchAccount(
        ctx, uid,
        checkedAt = at,
        lastError = message,
        dead = if (dead) true else null,
    ).also { saveReloginState(ctx, uid, state) }

    fun setCredentialAt(ctx: Context, uid: String, at: Long) {
        synchronized(LOCK) {
            val list = load(ctx)
            val index = list.indexOfFirst { it.uid == uid }
            if (index < 0) return
            list[index] = list[index].copy(credentialAt = at)
            save(ctx, list)
        }
    }

    fun setAccountAutoRelogin(ctx: Context, uid: String, enabled: Boolean) {
        synchronized(LOCK) {
            val list = load(ctx)
            val index = list.indexOfFirst { it.uid == uid }
            if (index < 0) return
            list[index] = list[index].copy(autoReloginEnabled = enabled)
            save(ctx, list)
        }
    }

    // ---- 自动重登全局策略（设置页）----

    fun autoReloginEnabled(ctx: Context): Boolean =
        SafePrefs.bool(prefs(ctx), KEY_AUTO_RELOGIN, true)

    fun setAutoReloginEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit { putBoolean(KEY_AUTO_RELOGIN, value) }
    }

    fun autoReloginMaxPerDay(ctx: Context): Int =
        SafePrefs.string(prefs(ctx), KEY_AUTO_RELOGIN_MAX, "3").toIntOrNull()?.coerceIn(1, 10) ?: 3

    fun autoReloginBackoffHours(ctx: Context): Double =
        SafePrefs.string(prefs(ctx), KEY_AUTO_RELOGIN_BACKOFF, "6")
            .toDoubleOrNull()?.coerceIn(0.5, 24.0) ?: 6.0

    /** 只写一行 `lastResult`（签到小结）与它的写入时刻，不影响其它字段。 */
    fun setLastResult(
        ctx: Context,
        uid: String,
        result: String,
        at: Long = System.currentTimeMillis(),
    ) {
        synchronized(LOCK) {
            val list = load(ctx)
            val index = list.indexOfFirst { it.uid == uid }
            if (index < 0) return
            list[index] = list[index].copy(lastResult = result, lastResultAt = at)
            save(ctx, list)
        }
    }

    fun remove(ctx: Context, uid: String) {
        synchronized(LOCK) {
            val list = load(ctx)
            list.removeAll { it.uid == uid }
            save(ctx, list)
        }
        // 删账号同时销毁本机保存的账号密码密文，不给残留
        CredentialVault.clear(ctx, uid)
    }

    fun panelUrl(ctx: Context): String = SafePrefs.string(prefs(ctx), KEY_PANEL_URL)
    fun panelToken(ctx: Context): String = SafePrefs.string(prefs(ctx), KEY_PANEL_TOKEN)

    fun savePanel(ctx: Context, url: String, token: String) {
        prefs(ctx).edit {
            putString(KEY_PANEL_URL, url.trim())
            putString(KEY_PANEL_TOKEN, token.trim())
        }
    }

    fun autoOnOpen(ctx: Context): Boolean = SafePrefs.bool(prefs(ctx), KEY_AUTO_ON_OPEN, true)
    fun setAutoOnOpen(ctx: Context, value: Boolean) {
        prefs(ctx).edit { putBoolean(KEY_AUTO_ON_OPEN, value) }
    }

    fun lastAutoAt(ctx: Context): Long = SafePrefs.long(prefs(ctx), KEY_LAST_AUTO_AT, 0L)
    fun setLastAutoAt(ctx: Context, at: Long) {
        prefs(ctx).edit { putLong(KEY_LAST_AUTO_AT, at) }
    }

    // ---- 图片抓取 ----

    data class CrawlTarget(val uid: String, val name: String)

    fun crawlTargets(ctx: Context): MutableList<CrawlTarget> {
        val raw = SafePrefs.string(prefs(ctx), KEY_CRAWL_TARGETS, "[]")
        val list = ArrayList<CrawlTarget>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(CrawlTarget(o.optString("uid", ""), o.optString("name", "")))
            }
        } catch (e: Exception) {
        }
        return list
    }

    /**
     * 按 UID 收窄抓取范围（v0.9.12：单博主全量 / 增量）。
     *
     * 纯函数，便于单测：`uids` 为空 = 全部目标（保持旧的「全部抓取」语义）；
     * 传了 UID 就**只**跑这些，顺序沿用列表顺序（用户看到的顺序就是抓取顺序），
     * 匹配不到的 UID 静默忽略 —— 目标可能在点菜单与真正开跑之间被删掉，
     * 这里不该抛异常，也不该把「已删除的博主」当成有效范围。
     */
    fun selectTargets(list: List<CrawlTarget>, uids: Set<String>): List<CrawlTarget> =
        if (uids.isEmpty()) list else list.filter { it.uid in uids }

    fun addCrawlTarget(ctx: Context, target: CrawlTarget) {
        synchronized(LOCK) {
            val list = crawlTargets(ctx)
            if (list.none { it.uid == target.uid }) list.add(target)
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("uid", it.uid).put("name", it.name)) }
            prefs(ctx).edit { putString(KEY_CRAWL_TARGETS, arr.toString()) }
        }
    }

    fun removeCrawlTarget(ctx: Context, uid: String) {
        synchronized(LOCK) {
            val list = crawlTargets(ctx)
            list.removeAll { it.uid == uid }
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("uid", it.uid).put("name", it.name)) }
            prefs(ctx).edit { putString(KEY_CRAWL_TARGETS, arr.toString()) }
        }
    }

    fun crawlTreeUri(ctx: Context): String = SafePrefs.string(prefs(ctx), KEY_CRAWL_TREE)

    /**
     * 换相册目录。
     *
     * **不清进度**：游标按 treeTag 分键，新目录天然是空游标，本就会从头抓；
     * 旧目录的游标与索引保留，切回去还能原地续跑。
     * 旧实现把进度存在与目录无关的单一键里，换到新空目录后继承旧进度，
     * 第 1 页就判「连续 10 条已完成」直接退出 —— 一张都抓不到。
     */
    fun setCrawlTreeUri(ctx: Context, uri: String) {
        val old = crawlTreeUri(ctx)
        prefs(ctx).edit { putString(KEY_CRAWL_TREE, uri) }
        if (old.isNotEmpty() && old != uri) {
            // prune 会遍历并删除旧目录的索引文件（NDJSON 可能几十 MB），
            // 而调用点是图库页选择目录的回调（主线程）—— 同步做就是一次 ANR 风险。
            // 挪到应用级 IO 作用域；目录键已先落盘，即使进程在删除中段被杀，
            // 下一次进入抓取也会重新剪枝（prune 是幂等的）。
            val app = ctx.applicationContext
            AppScope.launch {
                runCatching {
                    CrawlIndex.prune(
                        app.filesDir,
                        setOf(CrawlIndex.treeTagOf(uri), CrawlIndex.treeTagOf(old)),
                    )
                }
            }
        }
    }

    // ---- 增量游标（按 treeTag + uid 分键） ----

    fun crawlSince(ctx: Context, uid: String): String {
        val raw = SafePrefs.string(prefs(ctx), KEY_CRAWL_SINCE, "{}")
        return try {
            JSONObject(raw).optString(uid, "")
        } catch (e: Exception) {
            ""
        }
    }

    fun setCrawlSince(ctx: Context, uid: String, date: String) {
        synchronized(LOCK) {
            val raw = SafePrefs.string(prefs(ctx), KEY_CRAWL_SINCE, "{}")
            val obj = try {
                JSONObject(raw)
            } catch (e: Exception) {
                JSONObject()
            }
            obj.put(uid, date)
            prefs(ctx).edit { putString(KEY_CRAWL_SINCE, obj.toString()) }
        }
    }

    fun crawlCursor(ctx: Context, treeTag: String, uid: String): CrawlCursor {
        val raw = SafePrefs.string(prefs(ctx), KEY_CRAWL_CURSORS, "{}")
        return try {
            val perTree = JSONObject(raw).optJSONObject(treeTag) ?: return CrawlCursor(uid = uid)
            val o = perTree.optJSONObject(uid) ?: return CrawlCursor(uid = uid)
            CrawlCursor.fromJson(o).copy(uid = uid)
        } catch (e: Exception) {
            CrawlCursor(uid = uid)
        }
    }

    fun saveCrawlCursor(ctx: Context, treeTag: String, cursor: CrawlCursor) {
        synchronized(LOCK) {
            val raw = SafePrefs.string(prefs(ctx), KEY_CRAWL_CURSORS, "{}")
            val root = try {
                JSONObject(raw)
            } catch (e: Exception) {
                JSONObject()
            }
            val perTree = root.optJSONObject(treeTag) ?: JSONObject().also { root.put(treeTag, it) }
            perTree.put(cursor.uid, cursor.toJson())
            prefs(ctx).edit { putString(KEY_CRAWL_CURSORS, root.toString()) }
        }
    }

    /** 全部目标的游标（UI 展示已覆盖区间用）。 */
    fun allCursors(ctx: Context, treeTag: String): Map<String, CrawlCursor> {
        val raw = SafePrefs.string(prefs(ctx), KEY_CRAWL_CURSORS, "{}")
        val result = HashMap<String, CrawlCursor>()
        try {
            val perTree = JSONObject(raw).optJSONObject(treeTag) ?: return result
            perTree.keys().forEach { uid ->
                perTree.optJSONObject(uid)?.let { result[uid] = CrawlCursor.fromJson(it).copy(uid = uid) }
            }
        } catch (e: Exception) {
        }
        return result
    }

    /**
     * 旧版进度（`{uid: {post_id: 已尝试槽位数}}`），**只读**，供一次性迁移。
     * 注意其值是「尝试过」而非「成功」，迁移后无法自愈旧缺口 —— 见 README 的说明。
     */
    fun crawlDoneLegacy(ctx: Context): Map<String, Map<String, Int>> {
        val raw = SafePrefs.string(prefs(ctx), KEY_CRAWL_DONE_LEGACY)
        if (raw.isEmpty() || raw == "{}") return emptyMap()
        val result = HashMap<String, MutableMap<String, Int>>()
        try {
            val root = JSONObject(raw)
            root.keys().forEach { uid ->
                val inner = HashMap<String, Int>()
                val posts = root.optJSONObject(uid) ?: JSONObject()
                posts.keys().forEach { pid -> inner[pid] = posts.optInt(pid, 0) }
                result[uid] = inner
            }
        } catch (e: Exception) {
        }
        return result
    }

    fun clearCrawlDoneLegacy(ctx: Context) {
        prefs(ctx).edit { remove(KEY_CRAWL_DONE_LEGACY) }
    }

    fun crawlAuto(ctx: Context): Boolean = SafePrefs.bool(prefs(ctx), KEY_CRAWL_AUTO, false)
    fun setCrawlAuto(ctx: Context, value: Boolean) {
        prefs(ctx).edit { putBoolean(KEY_CRAWL_AUTO, value) }
    }

    fun crawlGif(ctx: Context): Boolean = SafePrefs.bool(prefs(ctx), KEY_CRAWL_GIF, true)
    fun setCrawlGif(ctx: Context, value: Boolean) {
        prefs(ctx).edit { putBoolean(KEY_CRAWL_GIF, value) }
    }

    // ---------- 抓取模式（v0.9.8） ----------

    /**
     * 抓取模式：`album` = 相册（游客身份，默认）/ `timeline` = 时间线（原实现，兜底）。
     *
     * 存在全局而不是按目标：用户切的是「这次怎么抓」，不是「这个博主怎么抓」，
     * 每个目标各记一份反而会让切换变成半生效。
     */
    fun crawlMode(ctx: Context): String =
        SafePrefs.string(prefs(ctx), KEY_CRAWL_MODE, MODE_ALBUM)

    fun setCrawlMode(ctx: Context, mode: String) {
        prefs(ctx).edit { putString(KEY_CRAWL_MODE, if (mode == MODE_TIMELINE) MODE_TIMELINE else MODE_ALBUM) }
    }

    /**
     * 相册墙游标：`{treeTag: {uid: "since_id"}}`。
     *
     * 按 treeTag 隔离，与 [crawlCursor] 同一口径：换目录天然从头开始，切回旧目录仍能续跑。
     */
    fun albumSince(ctx: Context, treeTag: String, uid: String): String {
        val raw = SafePrefs.string(prefs(ctx), KEY_ALBUM_SINCE, "{}")
        return try {
            JSONObject(raw).optJSONObject(treeTag)?.optString(uid, "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun setAlbumSince(ctx: Context, treeTag: String, uid: String, sinceId: String) {
        synchronized(LOCK) {
            val raw = SafePrefs.string(prefs(ctx), KEY_ALBUM_SINCE, "{}")
            val root = try {
                JSONObject(raw)
            } catch (e: Exception) {
                JSONObject()
            }
            val perTree = root.optJSONObject(treeTag) ?: JSONObject().also { root.put(treeTag, it) }
            perTree.put(uid, sinceId)
            prefs(ctx).edit { putString(KEY_ALBUM_SINCE, root.toString()) }
        }
    }

    /**
     * 相册模式「上次抓到哪条微博」的记忆（v0.9.17），值形如 `mid|yyyy-MM`。
     *
     * 按 (相册目录 treeTag, uid) 双键存：换目录后新 tag 天然没有记忆，
     * 不会拿旧目录的进度去跳过新目录里的帖（与 [crawlCursor] 同一口径）。
     * 空串 = 没有记忆（首次跑 / 清过数据），此时补新照旧从墙顶扫起。
     */
    fun albumLastPost(ctx: Context, treeTag: String, uid: String): String {
        val raw = SafePrefs.string(prefs(ctx), KEY_ALBUM_LAST, "{}")
        return try {
            JSONObject(raw).optJSONObject(treeTag)?.optString(uid, "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun setAlbumLastPost(ctx: Context, treeTag: String, uid: String, value: String) {
        synchronized(LOCK) {
            val raw = SafePrefs.string(prefs(ctx), KEY_ALBUM_LAST, "{}")
            val root = try {
                JSONObject(raw)
            } catch (e: Exception) {
                JSONObject()
            }
            val perTree = root.optJSONObject(treeTag) ?: JSONObject().also { root.put(treeTag, it) }
            perTree.put(uid, value)
            prefs(ctx).edit { putString(KEY_ALBUM_LAST, root.toString()) }
        }
    }
}
