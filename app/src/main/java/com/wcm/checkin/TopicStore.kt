package com.wcm.checkin

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 超话快照与详情缓存（v0.9.0）。
 *
 * 设计要点：
 * - **独立的 prefs 文件 `wcm_topics`**：与 `AccountStore` 的 `wcm` 完全隔离，
 *   账号 / 抓取游标 / 目录授权这些老键一个字节都不动，升级零迁移风险；
 * - 快照按 uid 存（`snap_v1_<uid>`），详情按超话 ID 存（`detail_v1_<topicId>`）；
 * - **不持久化签到链接**：`scheme` 是带 st 的短期 URL，落盘会过期误用，
 *   缓存项读出后 `scheme = null`，用户点签到前会先刷新一次；
 * - 解析失败的坏数据一律返回 null（宁缺不崩），与 AccountStore 的口径一致。
 */
object TopicStore {

    /** 一个账号的最近一次超话快照。 */
    data class Snapshot(
        val uid: String,
        val at: Long = 0L,
        val topics: List<WeiboApi.Topic> = emptyList(),
        val error: String = "",
    ) {
        val total: Int get() = topics.size
        val done: Int get() = topics.count { it.done }
        val pending: Int get() = total - done
        val maxLevel: Int get() = topics.maxOfOrNull { it.level } ?: 0
    }

    private const val PREFS = "wcm_topics"
    private const val SNAP_PREFIX = "snap_v1_"
    private const val DETAIL_PREFIX = "detail_v1_"
    private const val VERSION = 1

    /** 详情缓存的默认新鲜期：超过就重新拉（用户主动点开时才会触发）。 */
    const val DETAIL_TTL_MS = 30 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- 进程内共享状态：首页与超话页读同一份，避免两边各读一次 prefs ----

    private val _state = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val state: StateFlow<Map<String, Snapshot>> = _state.asStateFlow()

    @Volatile
    private var loaded = false

    /** 幂等：首次调用把磁盘上的全部快照读进内存。 */
    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        val all = HashMap<String, Snapshot>()
        runCatching {
            prefs(ctx).all.forEach { (key, value) ->
                if (!key.startsWith(SNAP_PREFIX)) return@forEach
                val uid = key.removePrefix(SNAP_PREFIX)
                val raw = value as? String ?: return@forEach
                deserializeSnapshot(uid, raw)?.let { all[uid] = it }
            }
        }
        _state.value = all
        loaded = true
    }

    fun snapshot(ctx: Context, uid: String): Snapshot? {
        ensureLoaded(ctx)
        return _state.value[uid]
    }

    fun all(ctx: Context): Map<String, Snapshot> {
        ensureLoaded(ctx)
        return _state.value
    }

    /** 写入一次成功拉取的结果。 */
    fun save(ctx: Context, uid: String, topics: List<WeiboApi.Topic>, at: Long = System.currentTimeMillis()) {
        if (uid.isEmpty()) return
        ensureLoaded(ctx)
        val snap = Snapshot(uid = uid, at = at, topics = topics, error = "")
        prefs(ctx).edit { putString(SNAP_PREFIX + uid, serializeSnapshot(snap)) }
        _state.value = _state.value + (uid to snap)
    }

    /** 记录一次失败：保留上一次的 topic 列表与时间戳，只把错误写进去。 */
    fun saveError(ctx: Context, uid: String, message: String) {
        if (uid.isEmpty()) return
        ensureLoaded(ctx)
        val prev = _state.value[uid] ?: Snapshot(uid = uid)
        val snap = prev.copy(error = message)
        prefs(ctx).edit { putString(SNAP_PREFIX + uid, serializeSnapshot(snap)) }
        _state.value = _state.value + (uid to snap)
    }

    fun clear(ctx: Context, uid: String) {
        if (uid.isEmpty()) return
        ensureLoaded(ctx)
        prefs(ctx).edit { remove(SNAP_PREFIX + uid) }
        _state.value = _state.value - uid
    }

    /** 账号被删后清掉孤儿快照。 */
    fun prune(ctx: Context, validUids: Set<String>) {
        ensureLoaded(ctx)
        val orphans = orphanKeys(_state.value.keys, validUids)
        if (orphans.isEmpty()) return
        prefs(ctx).edit { orphans.forEach { remove(SNAP_PREFIX + it) } }
        _state.value = _state.value - orphans
    }

    // ---- 超话详情 ----

    fun detail(ctx: Context, topicId: String): WeiboApi.TopicDetail? {
        if (topicId.isEmpty()) return null
        val raw = SafePrefs.string(prefs(ctx), DETAIL_PREFIX + topicId)
        if (raw.isEmpty()) return null
        return deserializeDetail(topicId, raw)
    }

    fun saveDetail(ctx: Context, d: WeiboApi.TopicDetail) {
        if (d.topicId.isEmpty()) return
        prefs(ctx).edit { putString(DETAIL_PREFIX + d.topicId, serializeDetail(d)) }
    }

    fun clearDetail(ctx: Context, topicId: String) {
        if (topicId.isEmpty()) return
        prefs(ctx).edit { remove(DETAIL_PREFIX + topicId) }
    }

    /** 详情是否还在新鲜期内（[now] 可注入，便于单测）。 */
    fun isFresh(at: Long, now: Long = System.currentTimeMillis(), ttlMs: Long = DETAIL_TTL_MS): Boolean =
        at > 0L && now - at in 0 until ttlMs

    // ---- 纯函数（不依赖 Android，JVM 单测覆盖） ----

    fun serializeSnapshot(s: Snapshot): String {
        val topics = JSONArray()
        s.topics.forEach { t ->
            topics.put(
                JSONObject()
                    .put("id", t.topicId)
                    .put("name", t.name)
                    .put("lv", t.level)
                    .put("d2", t.desc2)
                    .put("dn", if (t.done) 1 else 0)
            )
        }
        return JSONObject()
            .put("v", VERSION)
            .put("at", s.at)
            .put("error", s.error)
            .put("topics", topics)
            .toString()
    }

    fun deserializeSnapshot(uid: String, raw: String): Snapshot? {
        if (raw.isEmpty()) return null
        return try {
            val o = JSONObject(raw)
            if (o.optInt("v", 0) != VERSION) return null
            val arr = o.optJSONArray("topics") ?: JSONArray()
            val topics = ArrayList<WeiboApi.Topic>(arr.length())
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val name = t.optString("name", "")
                if (name.isEmpty()) continue
                topics.add(
                    WeiboApi.Topic(
                        name = name,
                        scheme = null,               // 签到链接不落盘，必须刷新后才有
                        done = t.optInt("dn", 0) == 1,
                        topicId = t.optString("id", ""),
                        level = t.optInt("lv", 0),
                        desc2 = t.optString("d2", ""),
                    )
                )
            }
            Snapshot(uid = uid, at = o.optLong("at", 0L), topics = topics, error = o.optString("error", ""))
        } catch (e: Exception) {
            null
        }
    }

    fun serializeDetail(d: WeiboApi.TopicDetail): String {
        val stats = JSONArray()
        d.stats.forEach { stats.put(it) }
        return JSONObject()
            .put("v", VERSION)
            .put("at", d.fetchedAt)
            .put("oid", d.oid)
            .put("t", d.title)
            .put("intro", d.intro)
            .put("pt", d.portraitText)
            .put("st", stats)
            .toString()
    }

    fun deserializeDetail(topicId: String, raw: String): WeiboApi.TopicDetail? {
        if (raw.isEmpty()) return null
        return try {
            val o = JSONObject(raw)
            if (o.optInt("v", 0) != VERSION) return null
            val arr = o.optJSONArray("st") ?: JSONArray()
            val stats = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotEmpty()) stats.add(s)
            }
            WeiboApi.TopicDetail(
                topicId = topicId,
                title = o.optString("t", ""),
                intro = o.optString("intro", ""),
                stats = stats,
                portraitText = o.optString("pt", ""),
                oid = o.optString("oid", ""),
                fetchedAt = o.optLong("at", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 有快照但账号已不存在 → 该删的 uid 集合。 */
    fun orphanKeys(existing: Set<String>, validUids: Set<String>): Set<String> =
        existing.filter { it.isNotEmpty() && it !in validUids }.toSet()
}
