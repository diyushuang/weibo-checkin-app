package com.wcm.checkin

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 互动任务的本地存储。
 *
 * **独立 prefs 文件 `wcm_engage`**——绝不写进 `wcm`。架构文档的不变量 3 要求
 * `wcm` 的文件名与既有键不得改，新功能另开文件是最省事的守法方式。
 *
 * 所有读改写都在 [LOCK] 内完成：预占是「读-判断-写」三步，一旦被并发插进来，
 * 同一个槽会被扣两次，直接后果就是重复发帖。
 *
 * 序列化交给 [EngageCodec]（纯函数、有单测），这里只负责落盘与淘汰。
 */
class EngageStore(context: Context) : EngageConfigPort, EngageRunPort {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 配置与文案 ----------

    override fun loadConfig(): EngageConfig =
        EngageCodec.configFromJson(prefs.getString(KEY_CFG, null)) ?: EngageConfig()

    override fun saveConfig(config: EngageConfig) {
        prefs.edit().putString(KEY_CFG, EngageCodec.configToJson(config)).apply()
    }

    override fun loadTexts(kind: TextKind): List<EngageText> = synchronized(LOCK) {
        ensureTextsMigrated()
        EngageCodec.textsFromJson(prefs.getString(keyTexts(kind), null))
    }

    override fun saveTexts(kind: TextKind, texts: List<EngageText>) = synchronized(LOCK) {
        writeTexts(kind, texts)
    }

    /**
     * 写某一套库。
     *
     * 两道防线：① 只允许写入 kind 匹配的条目（把评论列表写进发帖键不会崩，
     * 只会静默串库，是最难查的一类错）；② 每库独立上限。
     */
    private fun writeTexts(kind: TextKind, texts: List<EngageText>) {
        val sameKind = texts.filter { it.kind == kind }
        val capped = if (sameKind.size <= MAX_TEXTS) sameKind else sameKind.takeLast(MAX_TEXTS)
        prefs.edit().putString(keyTexts(kind), EngageCodec.textsToJson(capped)).apply()
    }

    override fun appendTexts(kind: TextKind, newTexts: List<EngageText>): List<EngageText> =
        synchronized(LOCK) {
            val incoming = newTexts.filter { it.kind == kind }
            val merged = loadTexts(kind) + incoming
            if (merged.size <= MAX_TEXTS) {
                writeTexts(kind, merged)
                return@synchronized merged
            }
            // 超上限时的丢弃顺序：**本次新增优先保住**（用户刚导入的东西不能悄悄消失），
            // 其余位置先给有使用统计的、再按导入时间从新到旧补满。
            val keep = ArrayList<EngageText>(MAX_TEXTS)
            incoming.forEach { if (keep.size < MAX_TEXTS) keep.add(it) }
            val older = loadTexts(kind)
                .filter { it !in incoming }
                .sortedWith(compareByDescending<EngageText> { it.usedCount > 0 }.thenByDescending { it.createdAt })
            for (t in older) {
                if (keep.size >= MAX_TEXTS) break
                keep.add(t)
            }
            writeTexts(kind, keep)
            keep
        }

    override fun mutateTexts(
        kind: TextKind,
        transform: (List<EngageText>) -> List<EngageText>,
    ): List<EngageText> = synchronized(LOCK) {
        val next = transform(loadTexts(kind))
        writeTexts(kind, next)
        next
    }

    /**
     * 老单池（`texts_v1`）→ 三套库的一次性拆分。
     *
     * 幂等靠三件事：① [KEY_TEXTS_SPLIT] 标记；② 发帖键已存在就只补标记（标记丢了的兜底）；
     * ③ 整批写用 `commit()`，标记与新键同批落盘——中途被杀时「标记也没落」，下次整体重做。
     * 派生 id 是固定后缀（`~c` / `~r`），所以最坏情况是重复覆盖同样内容，不会长出 `~c~c`。
     */
    private fun ensureTextsMigrated() { // 调用方已持 LOCK
        if (prefs.getBoolean(KEY_TEXTS_SPLIT, false)) return
        if (prefs.contains(keyTexts(TextKind.POST))) {
            prefs.edit().putBoolean(KEY_TEXTS_SPLIT, true).commit()
            return
        }
        val legacy = EngageCodec.textsFromJson(prefs.getString(KEY_TEXTS_LEGACY, null))
        val split = EngageTextPool.splitLegacy(legacy)
        prefs.edit()
            .putString(keyTexts(TextKind.POST), EngageCodec.textsToJson(split.post))
            .putString(keyTexts(TextKind.COMMENT), EngageCodec.textsToJson(split.comment))
            .putString(keyTexts(TextKind.REPOST), EngageCodec.textsToJson(split.repost))
            .putBoolean(KEY_TEXTS_SPLIT, true)
            .commit()
    }

    // ---------- 当日计数 ----------

    override fun loadCounter(uid: String, day: String): EngageDayCounter =
        EngageCodec.counterFromJson(prefs.getString(counterKey(uid, day), null))
            ?: EngageDayCounter(accountUid = uid, day = day)

    private fun saveCounter(counter: EngageDayCounter) {
        prefs.edit()
            .putString(counterKey(counter.accountUid, counter.day), EngageCodec.counterToJson(counter))
            .apply()
    }

    /**
     * 预占。
     *
     * 判断顺序：同 key 已存在 → 拒绝（防同轮重放）；今日已完成数已到上限 → 拒绝。
     * 上限由调用方通过 [EngageConfig] 判断更合适，但这里也做一次兜底，
     * 因为并发下两次运行各自的 in-memory 计数会同时认为「还有额度」。
     */
    override fun reserveSlot(
        uid: String,
        day: String,
        action: EngageAction,
        seq: Int,
        textId: String,
        now: Long,
    ): Boolean = synchronized(LOCK) {
        val counter = loadCounter(uid, day)
        val key = slotKey(action, seq)
        if (counter.pending.any { it.key == key }) return@synchronized false
        val updated = counter.copy(
            pending = counter.pending + PendingSlot(key, action, now, textId),
        )
        saveCounter(updated)
        true
    }

    override fun commitSlot(
        uid: String,
        day: String,
        action: EngageAction,
        seq: Int,
        now: Long,
    ): EngageDayCounter = synchronized(LOCK) {
        val counter = loadCounter(uid, day)
        val key = slotKey(action, seq)
        val updated = counter.copy(
            done = counter.done + (action to counter.doneOf(action) + 1),
            lastPostAt = if (action == EngageAction.POST) now else counter.lastPostAt,
            pending = counter.pending.filterNot { it.key == key },
        )
        saveCounter(updated)
        updated
    }

    override fun releaseSlot(
        uid: String,
        day: String,
        action: EngageAction,
        seq: Int,
    ): EngageDayCounter = synchronized(LOCK) {
        val counter = loadCounter(uid, day)
        val key = slotKey(action, seq)
        val updated = counter.copy(pending = counter.pending.filterNot { it.key == key })
        saveCounter(updated)
        updated
    }

    /** 结果未知：**保留** pending，用于锁死当日发帖。 */
    override fun markUncertain(
        uid: String,
        day: String,
        action: EngageAction,
        seq: Int,
        now: Long,
    ): EngageDayCounter = synchronized(LOCK) {
        val counter = loadCounter(uid, day)
        val key = slotKey(action, seq)
        val exists = counter.pending.any { it.key == key }
        val updated = if (exists) counter else counter.copy(
            pending = counter.pending + PendingSlot(key, action, now),
        )
        saveCounter(updated)
        updated
    }

    // ---------- 运行记录 ----------

    override fun saveRun(run: EngageRun) {
        prefs.edit().putString(runKey(run.accountUid, run.runId), EngageCodec.runToJson(run)).apply()
    }

    override fun loadRuns(uid: String, limit: Int): List<EngageRun> {
        val prefix = "${RUN_PREFIX}${uid}_"
        val runs = prefs.all.entries
            .filter { it.key.startsWith(prefix) && it.value is String }
            .mapNotNull { EngageCodec.runFromJson(it.value as String) }
        return runs.sortedByDescending { it.startedAt }.take(limit)
    }

    // ---------- 发帖台账 ----------

    override fun loadPosts(uid: String, day: String): List<EngagePostRecord> =
        EngageCodec.postsFromJson(prefs.getString(postKey(uid, day), null))

    override fun savePost(record: EngagePostRecord) = synchronized(LOCK) {
        val key = postKey(record.accountUid, EngageSchedule.dayKey(record.at))
        val list = EngageCodec.postsFromJson(prefs.getString(key, null)).toMutableList()
        val idx = list.indexOfFirst { it.id == record.id }
        if (idx >= 0) list[idx] = record else list.add(record)
        // 单日上限 50 条，超出丢最旧的（正常配置下不可能触达，纯属兜底）
        val capped = if (list.size <= MAX_POSTS_PER_DAY) list else list.takeLast(MAX_POSTS_PER_DAY)
        prefs.edit().putString(key, EngageCodec.postsToJson(capped)).apply()
    }

    override fun settlePost(
        uid: String,
        day: String,
        postId: String,
        state: ActionState,
        manualConfirmed: Boolean,
    ): EngagePostRecord? = synchronized(LOCK) {
        val key = postKey(uid, day)
        val list = EngageCodec.postsFromJson(prefs.getString(key, null)).toMutableList()
        val idx = list.indexOfFirst { it.id == postId }
        if (idx < 0) return@synchronized null
        val updated = list[idx].copy(state = state, manualConfirmed = manualConfirmed)
        list[idx] = updated
        prefs.edit().putString(key, EngageCodec.postsToJson(list)).apply()
        updated
    }

    // ---------- 淘汰 ----------

    override fun prune(uid: String, today: String) = synchronized(LOCK) {
        val todayDate = runCatching { LocalDate.parse(today, DAY) }.getOrNull() ?: return@synchronized
        val counterCutoff = todayDate.minusDays(KEEP_COUNTER_DAYS).format(DAY)
        val postCutoff = todayDate.minusDays(KEEP_POST_DAYS).format(DAY)

        val toRemove = prefs.all.keys.filter { key ->
            when {
                key.startsWith("${COUNTER_PREFIX}${uid}_") -> {
                    val day = key.removePrefix("${COUNTER_PREFIX}${uid}_")
                    day.isNotEmpty() && day < counterCutoff
                }
                key.startsWith("${POST_PREFIX}${uid}_") -> {
                    val day = key.removePrefix("${POST_PREFIX}${uid}_")
                    day.isNotEmpty() && day < postCutoff
                }
                else -> false
            }
        }
        if (toRemove.isNotEmpty()) {
            val editor = prefs.edit()
            toRemove.forEach { editor.remove(it) }
            editor.apply()
        }

        // 运行记录：每账号只留最近 KEEP_RUNS 条
        val runKeys = prefs.all.keys
            .filter { it.startsWith("${RUN_PREFIX}${uid}_") }
            .mapNotNull { key -> EngageCodec.runFromJson(prefs.getString(key, null))?.let { it to key } }
            .sortedByDescending { it.first.startedAt }
        if (runKeys.size > KEEP_RUNS) {
            val editor = prefs.edit()
            runKeys.drop(KEEP_RUNS).forEach { editor.remove(it.second) }
            editor.apply()
        }
    }

    override fun clearAll() {
        prefs.edit()
            .remove(KEY_CFG)
            .remove(keyTexts(TextKind.POST))
            .remove(keyTexts(TextKind.COMMENT))
            .remove(keyTexts(TextKind.REPOST))
            .remove(KEY_TEXTS_LEGACY)
            .remove(KEY_TEXTS_SPLIT)
            .apply()
    }

    override fun clearAll(uid: String) = synchronized(LOCK) {
        val prefixes = listOf(
            "${COUNTER_PREFIX}${uid}_",
            "${POST_PREFIX}${uid}_",
            "${RUN_PREFIX}${uid}_",
        )
        val keys = prefs.all.keys.filter { key -> prefixes.any { key.startsWith(it) } }
        if (keys.isNotEmpty()) {
            val editor = prefs.edit()
            keys.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    // ---------- 键 ----------

    private fun counterKey(uid: String, day: String) = "$COUNTER_PREFIX${uid}_$day"

    /** 三套库各自的键。后缀用 [TextKind.key]，与路由参数同一套名字。 */
    private fun keyTexts(kind: TextKind) = "$KEY_TEXTS_PREFIX${kind.key}_v1"

    private fun postKey(uid: String, day: String) = "$POST_PREFIX${uid}_$day"

    private fun runKey(uid: String, runId: String) = "$RUN_PREFIX${uid}_$runId"

    private fun slotKey(action: EngageAction, seq: Int) = "${action.name}#$seq"

    companion object {
        /** 独立文件，绝不写进 `wcm`（架构不变量 3）。 */
        const val PREFS = "wcm_engage"

        const val KEY_CFG = "cfg_v1"

        /** 三套文案库：`texts_post_v1` / `texts_comment_v1` / `texts_repost_v1`。 */
        const val KEY_TEXTS_PREFIX = "texts_"

        /** 0.13.x 及以前的单池键。**只读不删**（留作回滚余量），由 [KEY_TEXTS_SPLIT] 保证只拆一次。 */
        const val KEY_TEXTS_LEGACY = "texts_v1"

        /** 拆分完成标记。 */
        const val KEY_TEXTS_SPLIT = "texts_split_v1"

        const val COUNTER_PREFIX = "counter_v1_"
        const val POST_PREFIX = "post_v1_"
        const val RUN_PREFIX = "run_v1_"

        /** 每套库的条数上限（不是三库合计）。 */
        const val MAX_TEXTS = 500
        const val MAX_POSTS_PER_DAY = 50
        const val KEEP_RUNS = 20
        const val KEEP_COUNTER_DAYS = 30L
        const val KEEP_POST_DAYS = 90L

        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val LOCK = Any()
    }
}
