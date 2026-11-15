package com.wcm.checkin

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** 抓取目标、目录、游标、模式与 Live/GIF 开关。 */
object CrawlSettings {

    /** 相册抓图模式（游客身份，默认）。 */
    const val MODE_ALBUM = "album"

    /** 时间线抓图模式（原实现，兜底）。 */
    const val MODE_TIMELINE = "timeline"

    private const val KEY_CRAWL_TARGETS = "crawl_targets"   // [{"uid","name"}]
    private const val KEY_CRAWL_TREE = "crawl_tree_uri"     // SAF 相册目录持久授权
    private const val KEY_CRAWL_DONE_LEGACY = "crawl_done"  // 旧版进度，仅用于一次性迁移
    private const val KEY_CRAWL_CURSORS = "crawl_cursors_v2"// {treeTag: {uid: cursor}}
    private const val KEY_CRAWL_SINCE = "crawl_since"       // {uid: "yyyy-MM-dd"}
    private const val KEY_CRAWL_AUTO = "crawl_auto"         // 签到后自动增量抓图
    private const val KEY_CRAWL_GIF = "crawl_gif"           // Live Photo 转 GIF（默认开）
    private const val KEY_CRAWL_MODE = "crawl_mode"         // album（默认）/ timeline
    private const val KEY_ALBUM_SINCE = "crawl_album_since" // {treeTag: {uid: since_id}}
    private const val KEY_ALBUM_LAST = "crawl_album_last"   // {treeTag: {uid: "mid|yyyy-MM"}}

    private val LOCK = Any()

    fun crawlTargets(ctx: Context): MutableList<CrawlTarget> {
        val raw = SafePrefs.string(StorePrefs.get(ctx), KEY_CRAWL_TARGETS, "[]")
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
            StorePrefs.get(ctx).edit { putString(KEY_CRAWL_TARGETS, arr.toString()) }
        }
    }

    fun removeCrawlTarget(ctx: Context, uid: String) {
        synchronized(LOCK) {
            val list = crawlTargets(ctx)
            list.removeAll { it.uid == uid }
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("uid", it.uid).put("name", it.name)) }
            StorePrefs.get(ctx).edit { putString(KEY_CRAWL_TARGETS, arr.toString()) }
        }
    }

    fun crawlTreeUri(ctx: Context): String =
        SafePrefs.string(StorePrefs.get(ctx), KEY_CRAWL_TREE)

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
        StorePrefs.get(ctx).edit { putString(KEY_CRAWL_TREE, uri) }
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
        val raw = SafePrefs.string(StorePrefs.get(ctx), KEY_CRAWL_SINCE, "{}")
        return try {
            JSONObject(raw).optString(uid, "")
        } catch (e: Exception) {
            ""
        }
    }

    fun setCrawlSince(ctx: Context, uid: String, date: String) {
        synchronized(LOCK) {
            val raw = SafePrefs.string(StorePrefs.get(ctx), KEY_CRAWL_SINCE, "{}")
            val obj = try {
                JSONObject(raw)
            } catch (e: Exception) {
                JSONObject()
            }
            obj.put(uid, date)
            StorePrefs.get(ctx).edit { putString(KEY_CRAWL_SINCE, obj.toString()) }
        }
    }

    fun crawlCursor(ctx: Context, treeTag: String, uid: String): CrawlCursor {
        val raw = SafePrefs.string(StorePrefs.get(ctx), KEY_CRAWL_CURSORS, "{}")
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
            val raw = SafePrefs.string(StorePrefs.get(ctx), KEY_CRAWL_CURSORS, "{}")
            val root = try {
                JSONObject(raw)
            } catch (e: Exception) {
                JSONObject()
            }
            val perTree = root.optJSONObject(treeTag) ?: JSONObject().also { root.put(treeTag, it) }
            perTree.put(cursor.uid, cursor.toJson())
            StorePrefs.get(ctx).edit { putString(KEY_CRAWL_CURSORS, root.toString()) }
        }
    }

    /** 全部目标的游标（UI 展示已覆盖区间用）。 */
    fun allCursors(ctx: Context, treeTag: String): Map<String, CrawlCursor> {
        val raw = SafePrefs.string(StorePrefs.get(ctx), KEY_CRAWL_CURSORS, "{}")
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
        val raw = SafePrefs.string(StorePrefs.get(ctx), KEY_CRAWL_DONE_LEGACY)
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
        StorePrefs.get(ctx).edit { remove(KEY_CRAWL_DONE_LEGACY) }
    }

    fun crawlAuto(ctx: Context): Boolean =
        SafePrefs.bool(StorePrefs.get(ctx), KEY_CRAWL_AUTO, false)

    fun setCrawlAuto(ctx: Context, value: Boolean) {
        StorePrefs.get(ctx).edit { putBoolean(KEY_CRAWL_AUTO, value) }
    }

    fun crawlGif(ctx: Context): Boolean =
        SafePrefs.bool(StorePrefs.get(ctx), KEY_CRAWL_GIF, true)

    fun setCrawlGif(ctx: Context, value: Boolean) {
        StorePrefs.get(ctx).edit { putBoolean(KEY_CRAWL_GIF, value) }
    }

    // ---------- 抓取模式（v0.9.8） ----------

    /**
     * 抓取模式：`album` = 相册（游客身份，默认）/ `timeline` = 时间线（原实现，兜底）。
     *
     * 存在全局而不是按目标：用户切的是「这次怎么抓」，不是「这个博主怎么抓」，
     * 每个目标各记一份反而会让切换变成半生效。
     */
    fun crawlMode(ctx: Context): String =
        SafePrefs.string(StorePrefs.get(ctx), KEY_CRAWL_MODE, MODE_ALBUM)

    fun setCrawlMode(ctx: Context, mode: String) {
        StorePrefs.get(ctx).edit {
            putString(KEY_CRAWL_MODE, if (mode == MODE_TIMELINE) MODE_TIMELINE else MODE_ALBUM)
        }
    }

    /**
     * 相册墙游标：`{treeTag: {uid: "since_id"}}`。
     *
     * 按 treeTag 隔离，与 [crawlCursor] 同一口径：换目录天然从头开始，切回旧目录仍能续跑。
     */
    fun albumSince(ctx: Context, treeTag: String, uid: String): String {
        val raw = SafePrefs.string(StorePrefs.get(ctx), KEY_ALBUM_SINCE, "{}")
        return try {
            JSONObject(raw).optJSONObject(treeTag)?.optString(uid, "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun setAlbumSince(ctx: Context, treeTag: String, uid: String, sinceId: String) {
        synchronized(LOCK) {
            val raw = SafePrefs.string(StorePrefs.get(ctx), KEY_ALBUM_SINCE, "{}")
            val root = try {
                JSONObject(raw)
            } catch (e: Exception) {
                JSONObject()
            }
            val perTree = root.optJSONObject(treeTag) ?: JSONObject().also { root.put(treeTag, it) }
            perTree.put(uid, sinceId)
            StorePrefs.get(ctx).edit { putString(KEY_ALBUM_SINCE, root.toString()) }
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
        val raw = SafePrefs.string(StorePrefs.get(ctx), KEY_ALBUM_LAST, "{}")
        return try {
            JSONObject(raw).optJSONObject(treeTag)?.optString(uid, "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun setAlbumLastPost(ctx: Context, treeTag: String, uid: String, value: String) {
        synchronized(LOCK) {
            val raw = SafePrefs.string(StorePrefs.get(ctx), KEY_ALBUM_LAST, "{}")
            val root = try {
                JSONObject(raw)
            } catch (e: Exception) {
                JSONObject()
            }
            val perTree = root.optJSONObject(treeTag) ?: JSONObject().also { root.put(treeTag, it) }
            perTree.put(uid, value)
            StorePrefs.get(ctx).edit { putString(KEY_ALBUM_LAST, root.toString()) }
        }
    }
}
