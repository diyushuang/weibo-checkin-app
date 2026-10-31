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
         * 本机首次存下这份 cookie 的时刻（epoch ms；0 = 没有记录）。
         *
         * 四个时间戳共同构成 cookie 有效期的估算依据（口径见 [CookieLife]）：
         * 面板端每次同步都会把基准重置成同步时刻，所以已同步账号以 [cookieSyncedAt]
         * 为准，未同步账号才退回本机这三把尺子。
         */
        var cookieIssuedAt: Long = 0L,
        /** 最近一次观察到微博换发 Set-Cookie（SUB 等被替换）的时刻。 */
        var cookieRotatedAt: Long = 0L,
        /** 最近一次探活确认「登录有效」的时刻。 */
        var cookieSeenAt: Long = 0L,
        /** 最近一次成功推送到面板的时刻（面板侧生命周期基准）。 */
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
    )

    private const val PREFS = "wcm"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_PANEL_URL = "panel_url"
    private const val KEY_PANEL_TOKEN = "panel_token"
    private const val KEY_AUTO_ON_OPEN = "auto_on_open"
    private const val KEY_LAST_AUTO_AT = "last_auto_at"

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

    fun load(ctx: Context): MutableList<Account> {
        val raw = SafePrefs.string(prefs(ctx), KEY_ACCOUNTS, "[]")
        return deserializeAccounts(raw).toMutableList()
    }

    fun save(ctx: Context, accounts: List<Account>) {
        synchronized(LOCK) {
            prefs(ctx).edit { putString(KEY_ACCOUNTS, serializeAccounts(accounts)) }
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
                    .put("issuedAt", it.cookieIssuedAt)
                    .put("rotatedAt", it.cookieRotatedAt)
                    .put("seenAt", it.cookieSeenAt)
                    .put("syncedAt", it.cookieSyncedAt)
                    .put("dead", it.cookieDead)
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
                        // 旧版本没有这些键：时间戳落 0 = 没有锚点（界面显示「未同步」），
                        // 失效标记落 false = 还没被探活判过
                        cookieIssuedAt = o.optLong("issuedAt", 0L),
                        cookieRotatedAt = o.optLong("rotatedAt", 0L),
                        cookieSeenAt = o.optLong("seenAt", 0L),
                        cookieSyncedAt = o.optLong("syncedAt", 0L),
                        cookieDead = o.optBoolean("dead", false),
                    )
                )
            }
        } catch (e: Exception) {
            // 存储损坏时静默降级（返回已解析的部分），宁可丢账号也不能让 App 起不来
        }
        return list
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
     */
    fun mergeAccounts(current: List<Account>, snapshot: List<Account>): List<Account> =
        current.map { cur ->
            snapshot.firstOrNull { snap -> snap.uid.isNotEmpty() && snap.uid == cur.uid }
                ?.let { snap ->
                    cur.copy(
                        cookie = snap.cookie,
                        lastResult = snap.lastResult,
                        name = if (needsNameResolve(cur) && !needsNameResolve(snap)) {
                            snap.name
                        } else {
                            cur.name
                        },
                        cookieIssuedAt = maxOf(cur.cookieIssuedAt, snap.cookieIssuedAt),
                        cookieRotatedAt = maxOf(cur.cookieRotatedAt, snap.cookieRotatedAt),
                        cookieSeenAt = maxOf(cur.cookieSeenAt, snap.cookieSeenAt),
                        cookieSyncedAt = maxOf(cur.cookieSyncedAt, snap.cookieSyncedAt),
                    )
                }
                ?: cur
        }

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
     * - cookie **真的换了** → 首次录入写 `issuedAt`，之后写 `rotatedAt`（微博换发）；
     * - [seenAt] / [syncedAt] 只前进不后退，传 0 表示这次不更新。
     *
     * @return 是否真的写了盘（没变化就不写，减少 IO 与冲突面）。
     */
    fun patchAccount(
        ctx: Context,
        uid: String,
        cookie: String = "",
        name: String = "",
        seenAt: Long = 0L,
        syncedAt: Long = 0L,
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
                acc = acc.copy(
                    cookie = cookie,
                    cookieIssuedAt = if (firstTime) now else acc.cookieIssuedAt,
                    cookieRotatedAt = if (firstTime) acc.cookieRotatedAt else now,
                )
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
     */
    fun markVerified(
        ctx: Context,
        uid: String,
        cookie: String,
        name: String = "",
        at: Long = System.currentTimeMillis(),
    ): Boolean = patchAccount(ctx, uid, cookie = cookie, name = name, seenAt = at, dead = false)

    /**
     * 记录一次「探活明确判定登录态失效」。
     *
     * 只由 `login == false` 触发，**网络异常不写**：断网、风控、超时都不代表登录态没了，
     * 把它们记成失效会让整块 Cookie 卡变成一片红报警。
     */
    fun markDead(ctx: Context, uid: String): Boolean = patchAccount(ctx, uid, dead = true)

    /** 记录一次成功推送到面板：面板侧 cookie 生命周期从这里重新计时（约 7 天）。 */
    fun markSynced(ctx: Context, uid: String, at: Long = System.currentTimeMillis()): Boolean =
        patchAccount(ctx, uid, syncedAt = at)

    /** 只写一行 `lastResult`（签到小结），不影响其它字段。 */
    fun setLastResult(ctx: Context, uid: String, result: String) {
        synchronized(LOCK) {
            val list = load(ctx)
            val index = list.indexOfFirst { it.uid == uid }
            if (index < 0) return
            list[index] = list[index].copy(lastResult = result)
            save(ctx, list)
        }
    }

    fun remove(ctx: Context, uid: String) {
        synchronized(LOCK) {
            val list = load(ctx)
            list.removeAll { it.uid == uid }
            save(ctx, list)
        }
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
            runCatching {
                CrawlIndex.prune(
                    ctx.filesDir,
                    setOf(CrawlIndex.treeTagOf(uri), CrawlIndex.treeTagOf(old)),
                )
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
}
