package com.wcm.checkin

import org.json.JSONArray
import org.json.JSONObject

/**
 * 互动任务的本地序列化。
 *
 * 放在 `:core` 而不是 `:data`，是为了让「存下去的 JSON 能不能读回来」
 * 变成纯 JVM 单测——这类代码最容易在改字段时悄悄破坏老数据，
 * 而 `:data` 的 SharedPreferences 实现没法脱离 Android 测。
 *
 * 约定：
 * - 全部用短键（prefs 里存的是批量数据，键长直接乘条数）；
 * - 读的时候**缺字段一律用默认值**，这是老版本升级不丢配置的前提；
 * - 单条坏数据跳过，不因为一条脏 JSON 丢掉整池文案。
 */
object EngageCodec {

    private const val CONFIG_VERSION = 1

    // ---------- 配置 ----------

    fun configToJson(c: EngageConfig): String = JSONObject().apply {
        put("v", CONFIG_VERSION)
        put("e", c.enabled)
        put("ca", c.consentAt)
        put("cv", c.consentVersion)
        put("uid", c.accountUid)
        put("tid", c.topicId)
        put("tn", c.topicName)
        put("p", c.postEnabled)
        put("r", c.repostEnabled)
        put("c", c.commentEnabled)
        put("l", c.likeEnabled)
        put("pa", c.postAuto)
        put("ra", c.repostAuto)
        put("ca2", c.commentAuto)
        put("la", c.likeAuto)
        put("pc", c.postCount)
        put("rc", c.repostCount)
        put("cc", c.commentCount)
        put("lc", c.likeCount)
        put("qs", c.quietStartHour)
        put("qe", c.quietEndHour)
        put("aro", c.autoRunOnOpen)
        // 节奏代际标记：只有**没有**这个键的配置（0.13.0 之前写的）才走间隔迁移。
        // 否则「用户主动点了稳健档」存下来的数值会与旧默认值完全相同，
        // 下次读取又被迁移成均衡档 —— 表现就是「选了稳健，重启后自己变回均衡」。
        put("gv", GAP_VERSION)
        put("pg1", c.postGapMinMs)
        put("pg2", c.postGapMaxMs)
        put("rg1", c.repostGapMinMs)
        put("rg2", c.repostGapMaxMs)
        put("cg1", c.commentGapMinMs)
        put("cg2", c.commentGapMaxMs)
        put("lg1", c.likeGapMinMs)
        put("lg2", c.likeGapMaxMs)
        put("ag1", c.actionGapMinMs)
        put("ag2", c.actionGapMaxMs)
        put("fs", c.failStreakAbort)
        put("ma", c.maxActionsPerRun)
        put("rcp", c.requireConfirmBeforePost)
        put("pib", c.postInBackground)
        put("stl", c.syncToTimeline)
        put("vu", c.videoUrl)
        put("pfc", c.preferFirstComment)
        put("stg", c.strictTextGapDays)
        put("mt", c.maxTextLen)
        put("mcl", c.maxCommentLen)
        put("mrd", c.maxRunDurationMs)
    }.toString()

    /** 解析配置。版本不符或结构损坏返回 null，由调用方回落到默认配置。 */
    fun configFromJson(raw: String?): EngageConfig? {
        if (raw.isNullOrBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (o.optInt("v", 0) != CONFIG_VERSION) return null
        val d = EngageConfig()

        // 间隔：0.13.0 之前这五个区间**没有任何 UI 入口**，落盘的值只可能来自当时的
        // 默认值（GENTLE）。所以「恰好等于旧默认」可以安全地一次性迁移到新默认档
        // （BALANCED），否则升级后用户抱怨的「间隔太长」根本不会改变。
        //
        // 只在配置**没有 gv 标记**时迁移：有了标记说明它由新版写出，值就是用户的选择
        // （包括主动选「稳健」得到的、与旧默认完全相同的那组数值）。
        val migrateGaps = !o.has("gv") || o.optInt("gv", GAP_VERSION) < GAP_VERSION
        val postGap = migrateGap(migrateGaps, o, "pg1", "pg2", LEGACY_GAPS.post, d.gapRangeOf(EngageAction.POST))
        val repostGap = migrateGap(migrateGaps, o, "rg1", "rg2", LEGACY_GAPS.repost, d.gapRangeOf(EngageAction.REPOST))
        val commentGap = migrateGap(migrateGaps, o, "cg1", "cg2", LEGACY_GAPS.comment, d.gapRangeOf(EngageAction.COMMENT))
        val likeGap = migrateGap(migrateGaps, o, "lg1", "lg2", LEGACY_GAPS.like, d.gapRangeOf(EngageAction.LIKE))
        val actionGap = migrateGap(migrateGaps, o, "ag1", "ag2", LEGACY_GAPS.actionGap, d.actionGapRange())

        return EngageConfig(
            enabled = o.optBoolean("e", d.enabled),
            consentAt = o.optLong("ca", d.consentAt),
            consentVersion = o.optInt("cv", d.consentVersion),
            accountUid = o.optString("uid", d.accountUid),
            // 老数据可能只存了 hash（没有 100808 前缀），读的时候直接归一，
            // 避免升级后配置判非法、用户还得重新选一次超话。
            topicId = o.optString("tid", d.topicId)
                .let { if (it.isEmpty()) it else EngageSchedule.topicContainerId(it) },
            topicName = o.optString("tn", d.topicName),
            postEnabled = o.optBoolean("p", d.postEnabled),
            repostEnabled = o.optBoolean("r", d.repostEnabled),
            commentEnabled = o.optBoolean("c", d.commentEnabled),
            likeEnabled = o.optBoolean("l", d.likeEnabled),
            // 缺键时按「自动」读：老版本没有这个维度，行为必须保持不变
            postAuto = o.optBoolean("pa", d.postAuto),
            repostAuto = o.optBoolean("ra", d.repostAuto),
            commentAuto = o.optBoolean("ca2", d.commentAuto),
            likeAuto = o.optBoolean("la", d.likeAuto),
            postCount = o.optInt("pc", d.postCount),
            repostCount = o.optInt("rc", d.repostCount),
            commentCount = o.optInt("cc", d.commentCount),
            likeCount = o.optInt("lc", d.likeCount),
            quietStartHour = o.optInt("qs", d.quietStartHour),
            quietEndHour = o.optInt("qe", d.quietEndHour),
            autoRunOnOpen = o.optBoolean("aro", d.autoRunOnOpen),
            postGapMinMs = postGap.first,
            postGapMaxMs = postGap.last,
            repostGapMinMs = repostGap.first,
            repostGapMaxMs = repostGap.last,
            commentGapMinMs = commentGap.first,
            commentGapMaxMs = commentGap.last,
            likeGapMinMs = likeGap.first,
            likeGapMaxMs = likeGap.last,
            actionGapMinMs = actionGap.first,
            actionGapMaxMs = actionGap.last,
            failStreakAbort = o.optInt("fs", d.failStreakAbort),
            maxActionsPerRun = o.optInt("ma", d.maxActionsPerRun),
            requireConfirmBeforePost = o.optBoolean("rcp", d.requireConfirmBeforePost),
            postInBackground = o.optBoolean("pib", d.postInBackground),
            syncToTimeline = o.optBoolean("stl", d.syncToTimeline),
            videoUrl = o.optString("vu", d.videoUrl),
            preferFirstComment = o.optBoolean("pfc", d.preferFirstComment),
            strictTextGapDays = o.optInt("stg", d.strictTextGapDays),
            maxTextLen = o.optInt("mt", d.maxTextLen),
            maxCommentLen = o.optInt("mcl", d.maxCommentLen),
            // 0.12.6 把默认预算从 8 分钟放宽到 12 分钟。老配置里存的正是旧默认值
            // 480000，不做迁移的话升级后用户拿到的还是 8 分钟（这个值当时没有 UI 可改，
            // 所以 480000 只可能来自旧默认值，迁移是安全的）。
            maxRunDurationMs = o.optLong("mrd", d.maxRunDurationMs)
                .takeIf { it != LEGACY_RUN_BUDGET_MS } ?: d.maxRunDurationMs,
        )
    }

    /**
     * 读一组间隔并做一次性迁移。
     *
     * 只有在**上下限同时等于旧默认值**时才替换：单边相等更像是用户手动调过一半，
     * 那种情况必须原样保留。[shouldMigrate] 为 false（配置已带 gv 标记）时一律原样读。
     */
    private fun migrateGap(
        shouldMigrate: Boolean,
        o: JSONObject,
        keyMin: String,
        keyMax: String,
        legacy: LongRange,
        def: LongRange,
    ): LongRange {
        if (!o.has(keyMin) && !o.has(keyMax)) return def
        val min = o.optLong(keyMin, def.first)
        val max = o.optLong(keyMax, def.last)
        if (!shouldMigrate) return min..max
        return if (min == legacy.first && max == legacy.last) def else min..max
    }

    /** 节奏代际：0.13.0 引入。老配置（无 `gv`）读时迁移一次，之后值不再被动。 */
    private const val GAP_VERSION = 1

    /** 0.12.x 的默认间隔（现为 `EngagePace.GENTLE`），仅用于老配置迁移。 */
    private val LEGACY_GAPS = EngagePacing.gapsOf(EngagePace.GENTLE)!!

    /** 0.12.6 之前的默认单轮预算（8 分钟）；仅用于老配置迁移。 */
    private const val LEGACY_RUN_BUDGET_MS = 8 * 60_000L

    // ---------- 文案池 ----------

    fun textsToJson(list: List<EngageText>): String {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject().apply {
                    put("i", t.id)
                    put("b", t.body)
                    put("ca", t.createdAt)
                    put("n", t.usedCount)
                    put("lu", t.lastUsedAt)
                    put("ld", t.lastUsedDay)
                    put("d", t.deleted)
                    put("s", t.source.name)
                    put("dg", t.digest)
                }
            )
        }
        return arr.toString()
    }

    fun textsFromJson(raw: String?): List<EngageText> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<EngageText>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("i", "")
            val body = o.optString("b", "")
            if (id.isEmpty() || body.isEmpty()) continue
            out.add(
                EngageText(
                    id = id,
                    body = body,
                    createdAt = o.optLong("ca", 0L),
                    usedCount = o.optInt("n", 0),
                    lastUsedAt = o.optLong("lu", 0L),
                    lastUsedDay = o.optString("ld", ""),
                    deleted = o.optBoolean("d", false),
                    source = runCatching { TextSource.valueOf(o.optString("s", "MANUAL")) }
                        .getOrDefault(TextSource.MANUAL),
                    digest = o.optString("dg", "").ifEmpty { EngageTextPool.digest(body) },
                )
            )
        }
        return out
    }

    // ---------- 当日计数 ----------

    fun counterToJson(c: EngageDayCounter): String = JSONObject().apply {
        put("uid", c.accountUid)
        put("day", c.day)
        put("done", JSONObject().apply {
            c.done.forEach { (k, v) -> put(k.name, v) }
        })
        put("lpa", c.lastPostAt)
        put("pending", JSONArray().apply {
            c.pending.forEach { s ->
                put(
                    JSONObject().apply {
                        put("k", s.key)
                        put("a", s.action.name)
                        put("at", s.at)
                        put("t", s.textId)
                    }
                )
            }
        })
    }.toString()

    fun counterFromJson(raw: String?): EngageDayCounter? {
        if (raw.isNullOrBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val done = HashMap<EngageAction, Int>()
        o.optJSONObject("done")?.let { d ->
            for (a in EngageAction.entries) {
                if (d.has(a.name)) done[a] = d.optInt(a.name, 0)
            }
        }
        val pending = ArrayList<PendingSlot>()
        o.optJSONArray("pending")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val action = runCatching { EngageAction.valueOf(s.optString("a")) }.getOrNull() ?: continue
                val key = s.optString("k", "")
                if (key.isEmpty()) continue
                pending.add(PendingSlot(key, action, s.optLong("at", 0L), s.optString("t", "")))
            }
        }
        return EngageDayCounter(
            accountUid = o.optString("uid", ""),
            day = o.optString("day", ""),
            done = done,
            lastPostAt = o.optLong("lpa", 0L),
            pending = pending,
        )
    }

    // ---------- 运行记录 ----------

    fun runToJson(r: EngageRun): String = JSONObject().apply {
        put("id", r.runId)
        put("uid", r.accountUid)
        put("tid", r.topicId)
        put("tn", r.topicName)
        put("st", r.startedAt)
        put("ft", r.finishedAt)
        put("sr", r.stopReason)
        put("tr", r.trigger.name)
        put("ol", JSONArray().apply { r.only.forEach { put(it.name) } })
        put("sp", JSONArray().apply {
            r.steps.forEach { s ->
                put(
                    JSONObject().apply {
                        put("i", s.id)
                        put("t", s.title)
                        put("s", s.state.name)
                        put("b", s.startedAt)
                        put("e", s.endedAt)
                        put("d", s.detail)
                    }
                )
            }
        })
        put("lg", JSONArray().apply {
            // 落盘再截一次：内存里最多 140 条，但一次运行可能反复刷新草稿，
            // 每条都写全量会让 prefs 写入量随运行时长线性增长。
            r.logs.takeLast(MAX_LOGS_PERSISTED).forEach { e ->
                put(
                    JSONObject().apply {
                        put("at", e.at)
                        put("l", e.level.name)
                        put("a", e.action?.name ?: "")
                        put("q", e.seq)
                        put("x", e.text)
                    }
                )
            }
        })
        put("rec", JSONArray().apply {
            r.records.forEach { a ->
                put(
                    JSONObject().apply {
                        put("a", a.action.name)
                        put("q", a.seq)
                        put("s", a.state.name)
                        put("k", a.errorKind.name)
                        put("mid", a.targetMid)
                        put("rid", a.remoteId)
                        put("t", a.textId)
                        put("m", a.message)
                        put("at", a.at)
                    }
                )
            }
        })
    }.toString()

    fun runFromJson(raw: String?): EngageRun? {
        if (raw.isNullOrBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val runId = o.optString("id", "")
        if (runId.isEmpty()) return null
        val records = ArrayList<EngageActionRecord>()
        o.optJSONArray("rec")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val action = runCatching { EngageAction.valueOf(a.optString("a")) }.getOrNull() ?: continue
                records.add(
                    EngageActionRecord(
                        action = action,
                        seq = a.optInt("q", 0),
                        state = runCatching { ActionState.valueOf(a.optString("s")) }
                            .getOrDefault(ActionState.FAILED),
                        errorKind = runCatching { EngageErrorKind.valueOf(a.optString("k")) }
                            .getOrDefault(EngageErrorKind.NONE),
                        targetMid = a.optString("mid", ""),
                        remoteId = a.optString("rid", ""),
                        textId = a.optString("t", ""),
                        message = a.optString("m", ""),
                        at = a.optLong("at", 0L),
                    )
                )
            }
        }
        return EngageRun(
            runId = runId,
            accountUid = o.optString("uid", ""),
            topicId = o.optString("tid", ""),
            topicName = o.optString("tn", ""),
            startedAt = o.optLong("st", 0L),
            finishedAt = o.optLong("ft", 0L),
            stopReason = o.optString("sr", ""),
            records = records,
            trigger = runCatching { EngageTrigger.valueOf(o.optString("tr")) }
                .getOrDefault(EngageTrigger.MANUAL_ALL),
            only = o.optJSONArray("ol")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    runCatching { EngageAction.valueOf(arr.optString(i)) }.getOrNull()
                }
            }.orEmpty(),
            steps = o.optJSONArray("sp")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = s.optString("i", "")
                    if (id.isEmpty()) return@mapNotNull null
                    EngageStep(
                        id = id,
                        title = s.optString("t", id),
                        state = runCatching { EngageStepState.valueOf(s.optString("s")) }
                            .getOrDefault(EngageStepState.PENDING),
                        startedAt = s.optLong("b", 0L),
                        endedAt = s.optLong("e", 0L),
                        detail = s.optString("d", ""),
                    )
                }
            }.orEmpty(),
            logs = o.optJSONArray("lg")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    EngageLogEntry(
                        at = e.optLong("at", 0L),
                        level = runCatching { EngageLogLevel.valueOf(e.optString("l")) }
                            .getOrDefault(EngageLogLevel.INFO),
                        action = e.optString("a", "").takeIf { it.isNotEmpty() }?.let {
                            runCatching { EngageAction.valueOf(it) }.getOrNull()
                        },
                        seq = e.optInt("q", 0),
                        text = e.optString("x", ""),
                    )
                }
            }.orEmpty(),
        )
    }

    /** 单条运行记录落盘的流水条数上限（内存里是 [EngageProgress.MAX_LOGS]）。 */
    private const val MAX_LOGS_PERSISTED = 120

    // ---------- 发帖台账 ----------

    fun postsToJson(list: List<EngagePostRecord>): String {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject().apply {
                    put("i", p.id)
                    put("r", p.runId)
                    put("q", p.seq)
                    put("at", p.at)
                    put("uid", p.accountUid)
                    put("un", p.accountName)
                    put("tid", p.topicId)
                    put("tn", p.topicName)
                    put("b", p.body)
                    put("t", p.textId)
                    put("dg", p.textDigest)
                    put("s", p.state.name)
                    put("rid", p.remoteId)
                    put("k", p.errorKind.name)
                    put("m", p.message)
                    put("mc", p.manualConfirmed)
                }
            )
        }
        return arr.toString()
    }

    fun postsFromJson(raw: String?): List<EngagePostRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<EngagePostRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("i", "")
            val body = o.optString("b", "")
            if (id.isEmpty()) continue
            out.add(
                EngagePostRecord(
                    id = id,
                    runId = o.optString("r", ""),
                    seq = o.optInt("q", 0),
                    at = o.optLong("at", 0L),
                    accountUid = o.optString("uid", ""),
                    accountName = o.optString("un", ""),
                    topicId = o.optString("tid", ""),
                    topicName = o.optString("tn", ""),
                    body = body,
                    textId = o.optString("t", ""),
                    textDigest = o.optString("dg", ""),
                    state = runCatching { ActionState.valueOf(o.optString("s")) }
                        .getOrDefault(ActionState.FAILED),
                    remoteId = o.optString("rid", ""),
                    errorKind = runCatching { EngageErrorKind.valueOf(o.optString("k")) }
                        .getOrDefault(EngageErrorKind.NONE),
                    message = o.optString("m", ""),
                    manualConfirmed = o.optBoolean("mc", false),
                )
            )
        }
        return out
    }
}
