package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 序列化测试。
 *
 * 这类代码的失败模式很隐蔽：改字段名时不报错，用户升级后配置全变默认值。
 * 所以这里既测「往返一致」，也测「缺字段落到默认值」和「版本不符要返回 null」。
 */
class EngageCodecTest {

    private val tid = "1008088cf9880c8ec5f07f84c2f30e74d43574"

    private fun sampleConfig() = EngageConfig(
        enabled = true,
        consentAt = 1_700_000_000_000L,
        consentVersion = EngageConfig.CONSENT_VERSION,
        accountUid = "1000000001",
        topicId = tid,
        topicName = "棋手战鹰",
        postCount = 2,
        repostCount = 3,
        commentCount = 5,
        likeCount = 6,
        quietStartHour = 1,
        quietEndHour = 6,
        autoRunOnOpen = true,
        failStreakAbort = 4,
        maxActionsPerRun = 20,
        requireConfirmBeforePost = true,
        postInBackground = false,
        syncToTimeline = true,
        postTextMode = TextPickMode.RANDOM,
        commentTextMode = TextPickMode.SEQUENTIAL,
        repostTextMode = TextPickMode.RANDOM,
        repostTextEnabled = false,
        preferFirstComment = false,
        strictTextGapDays = 3,
        maxTextLen = 800,
        maxCommentLen = 120,
        maxRunDurationMs = 5 * 60_000L,
    )

    // ---------- 配置 ----------

    @Test
    fun `配置往返一致`() {
        val cfg = sampleConfig()
        val back = EngageCodec.configFromJson(EngageCodec.configToJson(cfg))
        assertEquals(cfg, back)
    }

    @Test
    fun `默认配置也能往返`() {
        val back = EngageCodec.configFromJson(EngageCodec.configToJson(EngageConfig()))
        assertEquals(EngageConfig(), back)
    }

    @Test
    fun `自动执行开关默认关闭`() {
        assertFalse(EngageConfig().autoRunOnOpen)
        // 老数据没有 aro 键时也必须落到「关」，绝不能因为缺字段就变成自动跑
        val back = EngageCodec.configFromJson("""{"v":1}""")
        assertNotNull(back)
        assertFalse(back!!.autoRunOnOpen)
    }

    @Test
    fun `超话 ID 读取时归一成完整 containerid`() {
        // 老版本存的是去掉 100808 的 hash，读回来必须补上前缀，
        // 否则写接口的 topic_id 与帖子流的 flowId 都会指向错误对象。
        val hashOnly = EngageCodec.configFromJson("""{"v":1,"tid":"8cf9880c8ec5f07f84c2f30e74d43574"}""")
        assertEquals("1008088cf9880c8ec5f07f84c2f30e74d43574", hashOnly!!.topicId)

        val already = EngageCodec.configFromJson("""{"v":1,"tid":"1008088cf9880c8ec5f07f84c2f30e74d43574"}""")
        assertEquals("1008088cf9880c8ec5f07f84c2f30e74d43574", already!!.topicId)
    }

    @Test
    fun `时间预算与评论上限缺字段时取默认`() {
        val back = EngageCodec.configFromJson("""{"v":1}""")!!
        assertEquals(EngageConfig().maxRunDurationMs, back.maxRunDurationMs)
        assertEquals(EngageConfig().maxCommentLen, back.maxCommentLen)
    }

    @Test
    fun `空超话 ID 不会被补成前缀`() {
        val back = EngageCodec.configFromJson("""{"v":1,"tid":""}""")!!
        assertEquals("", back.topicId)
    }

    @Test
    fun `缺字段落到默认值`() {
        // 只有版本号的最小 JSON：必须能读出来，且其余取默认
        val back = EngageCodec.configFromJson("""{"v":1,"uid":"u1"}""")
        assertNotNull(back)
        assertEquals("u1", back!!.accountUid)
        assertEquals(EngageConfig().commentCount, back.commentCount)
    }

    @Test
    fun `版本不符返回 null`() {
        assertNull(EngageCodec.configFromJson("""{"v":99,"uid":"u1"}"""))
    }

    @Test
    fun `坏 JSON 返回 null`() {
        assertNull(EngageCodec.configFromJson("{not json"))
        assertNull(EngageCodec.configFromJson(""))
        assertNull(EngageCodec.configFromJson(null))
    }

    // ---------- 动作三态与老数据迁移 ----------

    @Test
    fun `动作的自动开关往返一致`() {
        val cfg = sampleConfig().copy(
            postAuto = false,
            commentAuto = false,
        )
        val back = EngageCodec.configFromJson(EngageCodec.configToJson(cfg))!!
        assertEquals(EngageActionMode.MANUAL, back.modeOf(EngageAction.POST))
        assertEquals(EngageActionMode.MANUAL, back.modeOf(EngageAction.COMMENT))
        assertEquals(EngageActionMode.AUTO, back.modeOf(EngageAction.REPOST))
    }

    @Test
    fun `老配置缺自动开关时按自动读`() {
        // 0.13.0 之前没有这个维度：升级后行为必须保持不变，否则会少做动作
        val back = EngageCodec.configFromJson("""{"v":1,"p":true,"r":true,"c":true,"l":true}""")!!
        EngageAction.entries.forEach { assertEquals(it.label, EngageActionMode.AUTO, back.modeOf(it)) }
    }

    @Test
    fun `0_12 的老默认间隔升级后迁移到新默认档`() {
        // 0.12.x 的默认值（发帖 5–8 分钟）当时没有任何 UI 入口，存下来只可能来自默认。
        // 不迁移的话用户抱怨的「间隔太长」升级后一点没变。
        val legacy = """{"v":1,"pg1":300000,"pg2":480000,"rg1":60000,"rg2":180000,
            "cg1":25000,"cg2":60000,"lg1":15000,"lg2":40000,"ag1":5000,"ag2":20000}"""
        val back = EngageCodec.configFromJson(legacy)!!
        assertEquals(EngageConfig(), back)
        assertEquals(EngagePace.FAST, EngagePacing.presetOf(back))
    }

    @Test
    fun `0_13 的老默认间隔也迁移到新默认档`() {
        // 0.13.x 的默认值（发帖 1–2 分钟），gv=1 标记说明它由新版写出，但值是默认档
        val legacy = """{"v":1,"gv":1,"pg1":60000,"pg2":120000,"rg1":20000,"rg2":45000,
            "cg1":10000,"cg2":20000,"lg1":8000,"lg2":15000,"ag1":3000,"ag2":8000}"""
        val back = EngageCodec.configFromJson(legacy)!!
        assertEquals(EngageConfig(), back)
        assertEquals(EngagePace.FAST, EngagePacing.presetOf(back))
    }

    @Test
    fun `已写入 gv2 的配置不会被再次迁移`() {
        // 用户手动点回「稳健」后存下来的值，重启不能自己变回「快速」
        val gentle = EngagePacing.apply(EngageConfig(), EngagePace.GENTLE)
        val back = EngageCodec.configFromJson(EngageCodec.configToJson(gentle))!!
        assertEquals(gentle, back)
        assertEquals(EngagePace.GENTLE, EngagePacing.presetOf(back))
    }

    @Test
    fun `自定义间隔不会被迁移覆盖`() {
        // 上下限不同时等于旧默认，说明用户调过一半，必须原样保留
        val custom = """{"v":1,"cg1":25000,"cg2":90000}"""
        val back = EngageCodec.configFromJson(custom)!!
        assertEquals(25_000L, back.commentGapMinMs)
        assertEquals(90_000L, back.commentGapMaxMs)
        assertEquals(EngagePace.CUSTOM, EngagePacing.presetOf(back))
    }

    @Test
    fun `主动选稳健档不会被当成老配置迁移掉`() {
        // 稳健档的数值与旧默认完全相同，靠 gv 标记区分「用户选的」与「老版本默认的」。
        // 少了这个标记，用户选完稳健、重启 App 就自己变回均衡。
        val gentle = EngagePacing.apply(EngageConfig(), EngagePace.GENTLE)
        val back = EngageCodec.configFromJson(EngageCodec.configToJson(gentle))!!
        assertEquals(EngagePace.GENTLE, EngagePacing.presetOf(back))
        assertEquals(gentle, back)
    }

    // ---------- 文案池 ----------

    @Test
    fun `文案池往返一致`() {
        val list = listOf(
            EngageText(id = "1", body = "文案一", createdAt = 100, usedCount = 2, lastUsedAt = 200, lastUsedDay = "2026-11-15", source = TextSource.IMPORT),
            EngageText(id = "2", body = "文案二", deleted = true),
        )
        val back = EngageCodec.textsFromJson(EngageCodec.textsToJson(list))
        assertEquals(2, back.size)
        assertEquals("文案一", back[0].body)
        assertEquals(2, back[0].usedCount)
        assertEquals(TextSource.IMPORT, back[0].source)
        assertTrue(back[1].deleted)
    }

    @Test
    fun `缺失 kind 的老文案按发帖库读`() {
        val back = EngageCodec.textsFromJson("""[{"i":"1","b":"老文案"}]""")
        assertEquals(TextKind.POST, back[0].kind)
    }

    @Test
    fun `文案种类可以往返`() {
        val list = listOf(
            EngageText(id = "c", body = "评论", kind = TextKind.COMMENT),
            EngageText(id = "r", body = "转发", kind = TextKind.REPOST),
        )
        val back = EngageCodec.textsFromJson(EngageCodec.textsToJson(list))
        assertEquals(listOf(TextKind.COMMENT, TextKind.REPOST), back.map { it.kind })
    }

    @Test
    fun `取用方式与转发文案开关可以往返`() {
        val cfg = EngageConfig().copy(postTextMode = TextPickMode.RANDOM, repostTextEnabled = false)
        val back = EngageCodec.configFromJson(EngageCodec.configToJson(cfg))!!
        assertEquals(TextPickMode.RANDOM, back.postTextMode)
        assertEquals(TextPickMode.SEQUENTIAL, back.commentTextMode)
        assertFalse(back.repostTextEnabled)
    }

    @Test
    fun `单条坏数据被跳过而不是整池丢弃`() {
        val raw = """[{"i":"1","b":"好文案"},{"i":"","b":"缺 id"},{"b":"缺 id 也缺键"},{"i":"2","b":"另一条"}]"""
        val back = EngageCodec.textsFromJson(raw)
        assertEquals(2, back.size)
        assertEquals(listOf("1", "2"), back.map { it.id })
    }

    @Test
    fun `缺失 digest 会按正文重算`() {
        val raw = """[{"i":"1","b":"补算指纹"}]"""
        val back = EngageCodec.textsFromJson(raw)
        assertEquals(EngageTextPool.digest("补算指纹"), back[0].digest)
    }

    @Test
    fun `空文案池返回空列表`() {
        assertTrue(EngageCodec.textsFromJson("").isEmpty())
        assertTrue(EngageCodec.textsFromJson(null).isEmpty())
        assertTrue(EngageCodec.textsFromJson("[]").isEmpty())
    }

    // ---------- 当日计数 ----------

    @Test
    fun `计数往返一致且保留预占`() {
        val c = EngageDayCounter(
            accountUid = "1000000001",
            day = "2026-11-16",
            done = mapOf(EngageAction.COMMENT to 3, EngageAction.LIKE to 1),
            lastPostAt = 123456L,
            pending = listOf(PendingSlot("POST#1", EngageAction.POST, 999L, "t1")),
        )
        val back = EngageCodec.counterFromJson(EngageCodec.counterToJson(c))
        assertNotNull(back)
        assertEquals(3, back!!.doneOf(EngageAction.COMMENT))
        assertEquals(1, back.doneOf(EngageAction.LIKE))
        assertEquals(0, back.doneOf(EngageAction.POST))
        assertEquals(123456L, back.lastPostAt)
        assertTrue(back.hasPendingPost())
    }

    @Test
    fun `坏计数返回 null`() {
        assertNull(EngageCodec.counterFromJson("oops"))
        assertNull(EngageCodec.counterFromJson(null))
    }

    // ---------- 运行记录 ----------

    @Test
    fun `运行记录往返一致`() {
        val run = EngageRun(
            runId = "r1",
            accountUid = "1000000001",
            topicId = tid,
            topicName = "棋手战鹰",
            startedAt = 1000L,
            finishedAt = 2000L,
            stopReason = "操作频繁",
            records = listOf(
                EngageActionRecord(EngageAction.COMMENT, 1, ActionState.OK, targetMid = "123", textId = "t1"),
                EngageActionRecord(EngageAction.POST, 1, ActionState.UNCERTAIN, EngageErrorKind.NETWORK, message = "超时"),
            ),
        )
        val back = EngageCodec.runFromJson(EngageCodec.runToJson(run))
        assertNotNull(back)
        assertEquals("r1", back!!.runId)
        assertEquals(2, back.records.size)
        assertEquals(ActionState.UNCERTAIN, back.records[1].state)
        assertEquals(EngageErrorKind.NETWORK, back.records[1].errorKind)
        assertEquals(1, back.uncertainCount)
        assertEquals(1, back.okCount)
    }

    @Test
    fun `缺 runId 的运行记录判为无效`() {
        assertNull(EngageCodec.runFromJson("""{"uid":"x"}"""))
    }

    @Test
    fun `运行记录的过程快照可以回放`() {
        // 过程日志与步骤是这次新增的排查依据，必须能落盘再读回来，
        // 否则「跑完只剩一句失败」的问题等于没解。
        val run = EngageRun(
            runId = "r2",
            accountUid = "u1",
            topicId = tid,
            topicName = "棋手战鹰",
            startedAt = 1000L,
            finishedAt = 9000L,
            trigger = EngageTrigger.MANUAL_ONLY,
            only = listOf(EngageAction.LIKE),
            steps = listOf(
                EngageStep("auth", "校验登录态", EngageStepState.OK, 1000L, 1500L, "有效"),
                EngageStep("run", "执行动作", EngageStepState.WARN, 1500L, 9000L, "2/3 成功"),
            ),
            logs = listOf(
                EngageLogEntry(1200L, EngageLogLevel.INFO, EngageAction.LIKE, 1, "提交中：点赞帖子 500"),
                EngageLogEntry(1300L, EngageLogLevel.ERROR, EngageAction.LIKE, 1, "失败：操作频繁被限流"),
            ),
        )
        val back = EngageCodec.runFromJson(EngageCodec.runToJson(run))!!
        assertEquals(EngageTrigger.MANUAL_ONLY, back.trigger)
        assertEquals(listOf(EngageAction.LIKE), back.only)
        assertEquals(2, back.steps.size)
        assertEquals(EngageStepState.WARN, back.steps[1].state)
        assertEquals("2/3 成功", back.steps[1].detail)
        assertEquals(2, back.logs.size)
        assertEquals(EngageLogLevel.ERROR, back.logs[1].level)
        assertEquals(EngageAction.LIKE, back.logs[1].action)
        assertEquals("失败：操作频繁被限流", back.logs[1].text)
        assertEquals(8_000L, back.durationMs)
    }

    @Test
    fun `老运行记录缺过程字段时读成空列表`() {
        val back = EngageCodec.runFromJson("""{"id":"r9","uid":"u1","st":1}""")!!
        assertTrue(back.steps.isEmpty())
        assertTrue(back.logs.isEmpty())
        assertEquals(EngageTrigger.MANUAL_ALL, back.trigger)
        assertEquals(0L, back.durationMs)
    }

    @Test
    fun `运行摘要按动作聚合`() {
        val run = EngageRun(
            runId = "r", accountUid = "u", topicId = tid, topicName = "t", startedAt = 0,
            records = listOf(
                EngageActionRecord(EngageAction.COMMENT, 1, ActionState.OK),
                EngageActionRecord(EngageAction.COMMENT, 2, ActionState.FAILED),
            ),
        )
        val summary = run.summary()
        assertTrue(summary.contains("评论 1/2"))
    }

    // ---------- 发帖台账 ----------

    @Test
    fun `台账往返一致且保留正文快照`() {
        val list = listOf(
            EngagePostRecord(
                id = "p1", runId = "r1", seq = 1, at = 1000L,
                accountUid = "1000000001", accountName = "测试账号",
                topicId = tid, topicName = "棋手战鹰",
                body = "正文\n\n视频：https://v.example/1\n\n#棋手战鹰#",
                textId = "t1", textDigest = "abc",
                state = ActionState.OK, remoteId = "5000000000000001",
            ),
            EngagePostRecord(
                id = "p2", runId = "r1", seq = 2, at = 2000L,
                accountUid = "1000000001", topicId = tid, body = "另一条",
                state = ActionState.UNCERTAIN, errorKind = EngageErrorKind.NETWORK,
                message = "超时",
            ),
        )
        val back = EngageCodec.postsFromJson(EngageCodec.postsToJson(list))
        assertEquals(2, back.size)
        assertEquals("正文\n\n视频：https://v.example/1\n\n#棋手战鹰#", back[0].body)
        assertEquals("5000000000000001", back[0].remoteId)
        assertEquals(ActionState.UNCERTAIN, back[1].state)
        assertFalse(back[1].manualConfirmed)
    }

    @Test
    fun `台账坏条目被跳过`() {
        val raw = """[{"i":"p1","b":"好的"},{"b":"缺 id"}]"""
        assertEquals(1, EngageCodec.postsFromJson(raw).size)
    }
}
