package com.wcm.checkin

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

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

    /** 详情缓存的保留上限：超过 7 天没人看就淘汰，最多留 400 条。 */
    private const val DETAIL_KEEP_MS = 7 * 24 * 60 * 60 * 1000L
    private const val DETAIL_MAX = 400

    /** 每写 N 次详情做一次淘汰（写路径上不做全量扫描）。 */
    private const val DETAIL_PRUNE_EVERY = 32

    /** 详情内存缓存条数上限（大约覆盖十来个超话的来回切换）。 */
    private const val DETAIL_CACHE_MAX = 64

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- 进程内共享状态：首页与超话页读同一份，避免两边各读一次 prefs ----

    private val _state = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val state: StateFlow<Map<String, Snapshot>> = _state.asStateFlow()

    @Volatile
    private var loaded = false

    /** 预热入口：放到 IO 线程调，避免首页/超话页在主线程遍历 prefs。 */
    fun warm(ctx: Context) {
        runCatching {
            ensureLoaded(ctx)
            pruneDetails(ctx)
        }
    }

    /**
     * 幂等：首次调用把磁盘上的全部快照读进内存。
     *
     * 必须持类锁：首页与超话页会并发首载，两边各解析一遍 prefs.all 还只是浪费，
     * 更糟的是后完成的那次 `_state.value = all` 会把另一线程刚 save 进去的
     * 新快照覆盖掉 —— 用户看到的是「刚刷出来的超话列表又变回旧的」。
     */
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
        // 读改写必须原子：并发 save / saveError 用 value + ... 会互相丢更新
        _state.update { it + (uid to snap) }
    }

    /** 记录一次失败：保留上一次的 topic 列表与时间戳，只把错误写进去。 */
    fun saveError(ctx: Context, uid: String, message: String) {
        if (uid.isEmpty()) return
        ensureLoaded(ctx)
        // 同一个临界区里读旧值、写新值：分开写会让并发的 save() 结果被覆盖
        var snap = Snapshot(uid = uid)
        _state.update { cur ->
            snap = (cur[uid] ?: Snapshot(uid = uid)).copy(error = message)
            cur + (uid to snap)
        }
        prefs(ctx).edit { putString(SNAP_PREFIX + uid, serializeSnapshot(snap)) }
    }

    fun clear(ctx: Context, uid: String) {
        if (uid.isEmpty()) return
        ensureLoaded(ctx)
        prefs(ctx).edit { remove(SNAP_PREFIX + uid) }
        // 同样要 update 而不是 value - uid：两次 .value 之间可能插入别的 uid 的 save()
        _state.update { it - uid }
    }

    // ---- 超话详情 ----

    /**
     * 详情的内存缓存（进程级）。
     *
     * 列表 bind 时每行都要读一次详情：原先每次都走 prefs 取串 + JSON 解析，
     * 滚动几屏就是几百次解析，全在 UI 线程上。缓存有上限，超出按写入顺序丢最旧的。
     */
    private val detailCache = object : LinkedHashMap<String, WeiboApi.TopicDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WeiboApi.TopicDetail>?): Boolean =
            size > DETAIL_CACHE_MAX
    }

    fun detail(ctx: Context, topicId: String): WeiboApi.TopicDetail? {
        if (topicId.isEmpty()) return null
        synchronized(detailCache) { detailCache[topicId] }?.let { return it }
        val raw = SafePrefs.string(prefs(ctx), DETAIL_PREFIX + topicId)
        if (raw.isEmpty()) return null
        val parsed = deserializeDetail(topicId, raw) ?: return null
        synchronized(detailCache) { detailCache[topicId] = parsed }
        return parsed
    }

    fun saveDetail(ctx: Context, d: WeiboApi.TopicDetail) {
        if (d.topicId.isEmpty()) return
        synchronized(detailCache) { detailCache[d.topicId] = d }
        val p = prefs(ctx)
        p.edit { putString(DETAIL_PREFIX + d.topicId, serializeDetail(d)) }
        // 详情缓存是「只增不减」的：每次点开超话就多一个键，一年下来几百上千条，
        // 全都压在 prefs 的 XML 里（ensureLoaded 的 prefs.all 会把它整份读进内存）。
        // 这里按次数抽样淘汰，写路径上不做全量扫描。
        if (++detailSaves % DETAIL_PRUNE_EVERY == 0) pruneDetails(ctx)
    }

    @Volatile
    private var detailSaves = 0

    /**
     * 淘汰过期详情缓存。
     *
     * 只删「解析得出来且明显过期」的项；解析失败的坏数据一并清掉（本来也读不出来）。
     * 顺带保底：条目数超过 [DETAIL_MAX] 时按 fetchedAt 从旧到新删到上限以内，
     * 防止用户在短时间内翻遍大量超话把 XML 撑大。
     */
    fun pruneDetails(ctx: Context, now: Long = System.currentTimeMillis()) {
        val p = prefs(ctx)
        runCatching {
            val entries = p.all.entries
                .filter { it.key.startsWith(DETAIL_PREFIX) && it.value is String }
                .map { it.key to (it.value as String) }
            if (entries.isEmpty()) return
            val stale = ArrayList<String>()
            val alive = ArrayList<Pair<String, Long>>()
            entries.forEach { (key, raw) ->
                val at = runCatching { JSONObject(raw).optLong("at", 0L) }.getOrDefault(0L)
                if (at <= 0L || now - at > DETAIL_KEEP_MS) stale.add(key) else alive.add(key to at)
            }
            if (alive.size > DETAIL_MAX) {
                alive.sortedBy { it.second }
                    .take(alive.size - DETAIL_MAX)
                    .forEach { stale.add(it.first) }
            }
            if (stale.isEmpty()) return
            p.edit {
                stale.forEach { remove(it) }
            }
            synchronized(detailCache) { stale.forEach { detailCache.remove(it.removePrefix(DETAIL_PREFIX)) } }
        }
    }

    fun clearDetail(ctx: Context, topicId: String) {
        if (topicId.isEmpty()) return
        synchronized(detailCache) { detailCache.remove(topicId) }
        prefs(ctx).edit { remove(DETAIL_PREFIX + topicId) }
    }

    /** 详情是否还在新鲜期内（[now] 可注入，便于单测）。 */
    fun isFresh(at: Long, now: Long = System.currentTimeMillis(), ttlMs: Long = DETAIL_TTL_MS): Boolean =
        at > 0L && now - at in 0 until ttlMs

    /**
     * 快照是否属于「今天」。
     *
     * **签到状态是按天的**：微博每天重置「已签」，昨天的 `done=true` 今天完全不成立。
     * 快照没有跨天失效时，界面会把昨天的「已签」当成今天的结论 —— 既显示错状态，
     * 又让待签数变成 0（于是「明明没签，却一个都不签」）。
     * 按本地时区比较日历日，而不是用时间差（24 小时 ≠ 一天）。
     */
    fun isToday(at: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (at <= 0L) return false
        val zone = ZoneId.systemDefault()
        val snapshotDay = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return snapshotDay == today
    }

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

    /**
     * 有快照但账号已不存在 → 该删的 uid 集合。
     *
     * 纯函数，供「清理孤儿快照」的场景使用（账号删除本身走精确的 [clear]）。
     */
    fun orphanKeys(existing: Set<String>, validUids: Set<String>): Set<String> =
        existing.filter { it.isNotEmpty() && it !in validUids }.toSet()
}
