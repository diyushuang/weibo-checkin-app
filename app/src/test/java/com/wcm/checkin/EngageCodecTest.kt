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
        videoUrl = "https://v.example/1",
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
