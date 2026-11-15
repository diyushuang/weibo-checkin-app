package com.wcm.checkin

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** 账号与配置存储：全部放应用私有 SharedPreferences（无任何存储权限）。 */
object AccountStore {

    private const val PREFS = "wcm"
    private const val KEY_ACCOUNTS = "accounts"

    // ---- 账号密码自动重登（全局策略）----
    private const val KEY_AUTO_RELOGIN = "auto_relogin_enabled"
    private const val KEY_AUTO_RELOGIN_MAX = "auto_relogin_max_per_day"
    private const val KEY_AUTO_RELOGIN_BACKOFF = "auto_relogin_backoff_hours"

    /** 存量明文 cookie 是否已全量改写成密文（`migrateToSealed` 的一次性标记）。 */
    private const val KEY_SEALED = "cookie_sealed_v1"


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
                val parsed = deserializeAccounts(raw) { token ->
                    // 解不开就当没有：单条坏掉只影响这一个账号，不能让整表读失败
                    SecretBox.openOrNull(token) ?: ""
                }
                cacheRaw = raw
                cacheList = parsed
                parsed
            }
        }
        return cached.toMutableList()
    }

    fun save(ctx: Context, accounts: List<Account>) {
        synchronized(LOCK) {
            // cookie 落盘前逐条加密（失败降级明文，见 SecretBox.sealOrPlain）
            val raw = serializeAccounts(accounts) { SecretBox.sealOrPlain(it) }
            prefs(ctx).edit { putString(KEY_ACCOUNTS, raw) }
            // 顺手把缓存对齐，省掉紧随其后的那次重新解析
            cacheRaw = raw
            cacheList = accounts
        }
    }

    /**
     * 一次性把存量明文 cookie 全量改写成密文（升级后首次启动跑一次）。
     *
     * 为什么是"启动时全量"而不是"下次写入时渐进"：明文 cookie 在设备上停留的每一天
     * 都是纯暴露面，而重写只是一次 JSON 序列化 + N 次 AES 加密（N ≤ 账号数），
     * 冷启动多花不到 10ms。代价是**写失败会连累整表**，所以：
     *
     * - 只在 `sealed_v1` 标记未置位时跑；
     * - 任何一条加密失败（[SecretBox.seal] 抛异常）就整体放弃，保持原文件不动；
     * - **先校验再落盘**：把重写结果解析回来逐行比对（原本是明文的行必须变成可解密的密文、
     *   原本解不开的行必须原样保留），全部通过才写入，避免"写进去才发现坏了"。
     *
     * @return true = 真的改写并落盘了
     */
    fun migrateToSealed(ctx: Context): Boolean = synchronized(LOCK) {
        val p = prefs(ctx)
        if (SafePrefs.bool(p, KEY_SEALED, false)) return false
        val raw = SafePrefs.string(p, KEY_ACCOUNTS, "[]")

        // 逐行看原始 cookie 串：只迁移"还是明文"的行；已经解不开的密文行不动，
        // 否则会拿空串覆盖掉一条本来还有救的记录。
        val before = rawCookies(raw)
        if (before.isEmpty() || before.none { SecretBox.needsSeal(it.value) }) {
            // 空表 / 已全部加密：置位，省掉以后每次启动的重复解析
            p.edit { putBoolean(KEY_SEALED, true) }
            return false
        }

        val accounts = deserializeAccounts(raw) { SecretBox.openOrNull(it) ?: "" }
        val sealed = try {
            serializeAccounts(accounts) { SecretBox.seal(it) }
        } catch (e: Exception) {
            // 加密不可用（极少数 ROM）：保持明文，下次启动再试，但不阻塞使用
            return false
        }

        val after = rawCookies(sealed)
        val ok = before.keys == after.keys && before.all { (uid, old) ->
            val now = after[uid].orEmpty()
            if (SecretBox.needsSeal(old)) {
                SecretBox.isSealed(now) && SecretBox.openOrNull(now) == old
            } else {
                now == old
            }
        }
        if (!ok) {
            // 校验不过：一个字节都不写，原文件保持原样
            return false
        }

        // 数据与标记一次提交：避免"写完了但标记没置位"导致的重复迁移窗口
        p.edit {
            putString(KEY_ACCOUNTS, sealed)
            putBoolean(KEY_SEALED, true)
        }
        cacheRaw = sealed
        cacheList = deserializeAccounts(sealed) { SecretBox.openOrNull(it) ?: "" }
        true
    }

    /** 从账号表 JSON 里取 `uid → 原始 cookie 串`（不解密，用于迁移前后比对）。 */
    private fun rawCookies(json: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uid = o.optString("uid", "")
                if (uid.isNotEmpty()) out[uid] = o.optString("cookie", "")
            }
        } catch (e: Exception) {
            // 解析失败返回空表：调用方会按"没有需要迁移的行"处理，不写盘
        }
        return out
    }

    // ---- 纯序列化与合并（不依赖 Android，JVM 单测覆盖） ----

    /** 昵称占位值：登录时 fetchProfile 没取到用户名会写成「微博 <uid>」。 */
    fun placeholderName(uid: String): String = "微博 $uid"

    /** 是否还需要去拉一次真实昵称（列表里不能只显示 UID）。 */
    fun needsNameResolve(acc: Account): Boolean =
        acc.name.isEmpty() || acc.name == acc.uid || acc.name == placeholderName(acc.uid)

    /**
     * 序列化账号表。
     *
     * @param sealCookie cookie 字段的加密函数。默认恒等（纯函数口径，JVM 单测直接用）；
     *   生产路径由 [save] 传入 [SecretBox.sealOrPlain]，因此**磁盘上见不到明文 cookie**。
     *   加密放在序列化这一刻而不是更上层，是为了保证"只要落盘就一定过加密"，
     *   不依赖每个调用方记得处理。
     */
    fun serializeAccounts(
        accounts: List<Account>,
        sealCookie: (String) -> String = { it },
    ): String {
        val arr = JSONArray()
        accounts.forEach {
            arr.put(
                JSONObject()
                    .put("uid", it.uid)
                    .put("name", it.name)
                    .put("cookie", sealCookie(it.cookie))
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
                    .put("cookieDomains", it.cookieDomains)
                    .put("alcAt", it.alcAt)
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

    /**
     * 反序列化账号表。
     *
     * @param openCookie cookie 字段的解密函数。默认恒等（兼容明文与单测）；
     *   生产路径由 [load] 传入 [SecretBox.openOrNull]，非密文原样返回、坏值降级为空串。
     */
    fun deserializeAccounts(
        raw: String,
        openCookie: (String) -> String = { it },
    ): List<Account> {
        val list = ArrayList<Account>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Account(
                        uid = o.optString("uid", ""),
                        name = o.optString("name", "未命名账号"),
                        cookie = openCookie(o.optString("cookie", "")),
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
                        cookieDomains = o.optString("cookieDomains", ""),
                        alcAt = o.optLong("alcAt", 0L),
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
    fun newestAnchor(a: Account): Long =
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
        /** 本次从 Set-Cookie 学到的域映射（`SUB=weibo.cn;…`）；空串表示这次没有新信息。 */
        domainHints: String = "",
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
            if (domainHints.isNotEmpty() && domainHints != acc.cookieDomains) {
                acc = acc.copy(cookieDomains = domainHints)
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
    val LOGIN_CRITICAL_KEYS = listOf("SUB", "SUBP", "SCF", "SSOLoginState", "ALF")

    /** 比较新旧 cookie，返回真正变化的登录关键字段。 */
    fun criticalChangedKeys(oldCookie: String, newCookie: String): List<String> {
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
        // 删账号同时销毁本机保存的两类凭证，不给残留：
        // 账号密码（cred_）与 ALC（alc_）都在这一个 prefs 里，必须一起清。
        CredentialVault.clear(ctx, uid)
        AlcVault.clear(ctx, uid)
    }

    /** 记录一次 ALC 凭证的保存时刻（ALC 值本身在 [AlcVault] 里加密存）。 */
    fun setAlcAt(ctx: Context, uid: String, at: Long) {
        synchronized(LOCK) {
            val list = load(ctx)
            val index = list.indexOfFirst { it.uid == uid }
            if (index < 0) return
            list[index] = list[index].copy(alcAt = at)
            save(ctx, list)
        }
    }

}
