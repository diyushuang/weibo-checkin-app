package com.wcm.checkin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `runInternal` 主循环的特征测试（characterization tests）。
 *
 * 存在的理由：runInternal 是 600 行、30+ 个可变局部状态的编排函数（「连续三个
 * 版本都在这里修 bug」的那一个），后续要拆分它。拆分前必须先把**现有行为**锁住
 * ——这些用例不是在描述「应该怎样」，而是在记录「现在就是这样」。拆分过程中
 * 它们是行为不变的护栏：任何一条红了，都说明拆分改了语义，而不是测试过时。
 *
 * 网络出口全部经 `EngageGateway`（构造参数换成 [FakeGateway]），不碰真实 HTTP；
 * 节流靠 gap 全 0 旁路（`WeiboThrottle` 对 gap ≤ 0 直接放行）；免打扰时段设成
 * `[0,0)` 空区间（`isQuietHour` 对 start==end 恒 false），与测试机的时区无关。
 */
class EngageServiceRunTest {

    /** 只实现被用到的那几条；带调用记录供断言。 */
    private class FakeConfigPort(var cfg: EngageConfig) : EngageConfigPort {
        private val pools: MutableMap<TextKind, MutableList<EngageText>> =
            TextKind.entries.associateWith { mutableListOf<EngageText>() }.toMutableMap()
        val mutatedKinds = LinkedHashSet<TextKind>()

        override fun loadConfig(): EngageConfig = cfg
        override fun saveConfig(config: EngageConfig) { cfg = config }
        override fun loadTexts(kind: TextKind): List<EngageText> = pools.getValue(kind).toList()
        override fun saveTexts(kind: TextKind, texts: List<EngageText>) { pools[kind] = texts.toMutableList() }
        override fun appendTexts(kind: TextKind, newTexts: List<EngageText>): List<EngageText> {
            pools.getValue(kind).addAll(newTexts)
            return loadTexts(kind)
        }
        override fun mutateTexts(
            kind: TextKind,
            transform: (List<EngageText>) -> List<EngageText>,
        ): List<EngageText> {
            mutatedKinds.add(kind)
            val next = transform(loadTexts(kind))
            saveTexts(kind, next)
            return next
        }
        override fun clearAll() {
            cfg = EngageConfig()
            TextKind.entries.forEach { pools[it] = mutableListOf() }
        }
    }

    private class FakeRunPort : EngageRunPort {
        var counter = EngageDayCounter()
        val posts = ArrayList<EngagePostRecord>()
        val runs = ArrayList<EngageRun>()
        val reserved = ArrayList<EngageAction>()
        val committed = ArrayList<EngageAction>()
        val released = ArrayList<EngageAction>()
        val uncertain = ArrayList<EngageAction>()
        var sinkWrites = 0

        override fun loadCounter(uid: String, day: String): EngageDayCounter = counter
        override fun reserveSlot(uid: String, day: String, action: EngageAction, seq: Int, textId: String, now: Long): Boolean {
            reserved.add(action)
            return true
        }
        override fun commitSlot(uid: String, day: String, action: EngageAction, seq: Int, now: Long): EngageDayCounter {
            committed.add(action)
            return counter
        }
        override fun releaseSlot(uid: String, day: String, action: EngageAction, seq: Int): EngageDayCounter {
            released.add(action)
            return counter
        }
        override fun markUncertain(uid: String, day: String, action: EngageAction, seq: Int, now: Long): EngageDayCounter {
            uncertain.add(action)
            return counter
        }
        override fun saveRun(run: EngageRun) { runs.add(run) }
        override fun loadRuns(uid: String, limit: Int): List<EngageRun> = runs
        override fun loadPosts(uid: String, day: String): List<EngagePostRecord> = posts
        override fun savePost(record: EngagePostRecord) { posts.add(record) }
        override fun settlePost(uid: String, day: String, postId: String, state: ActionState, manualConfirmed: Boolean): EngagePostRecord? = null
        override fun prune(uid: String, today: String) = Unit
        override fun clearAll(uid: String) = Unit
    }

    /** 8 个出站口全换成内存假响应；测试按需改写对应字段。 */
    private open class FakeGateway : EngageGateway() {
        var verifyResult = WeiboAuthApi.Verify(login = true, st = null, cookie = "SUB=rotated")
        var verifyError: Exception? = null
        var topics: List<WeiboTopicApi.Topic> = listOf()
        var feed: List<WeiboFeedApi.FeedPost> = listOf()
        var feedError: Exception? = null
        /** 每次写请求记一条 (动作, 目标或空, 正文)；出参按 [results] 查。 */
        val written = ArrayList<Triple<EngageAction, String, String>>()
        var results: Map<EngageAction, WeiboWriteApi.WriteResult> = emptyMap()

        private fun out(action: EngageAction, target: String, body: String): WeiboWriteApi.WriteResult {
            written.add(Triple(action, target, body))
            return results[action] ?: WeiboWriteApi.WriteResult()
        }

        override fun verify(cookie: String, force: Boolean): WeiboAuthApi.Verify {
            verifyError?.let { throw it }
            return verifyResult
        }
        override suspend fun listTopics(session: WeiboHttp.WeiboSession, maxPages: Int) = topics
        override suspend fun listTopicFeed(session: WeiboHttp.WeiboSession, topicId: String): List<WeiboFeedApi.FeedPost> {
            feedError?.let { throw it }
            return feed
        }
        override fun publishTopicPost(session: WeiboHttp.WeiboSession, topicId: String, content: String, syncToTimeline: Boolean) =
            out(EngageAction.POST, "", content)
        override fun comment(session: WeiboHttp.WeiboSession, mid: String, text: String) =
            out(EngageAction.COMMENT, mid, text)
        override fun repost(session: WeiboHttp.WeiboSession, mid: String, text: String) =
            out(EngageAction.REPOST, mid, text)
        override fun like(session: WeiboHttp.WeiboSession, mid: String) =
            out(EngageAction.LIKE, mid, "")
    }

    private val tid = "1008088cf9880c8ec5f07f84c2f30e74d43574"
    private val uid = "1000000001"
    private val account = EngageAccount(uid = uid, name = "测试号", cookie = "SUB=abc")

    private fun runConfig(post: Int, comment: Int, like: Int) = EngageConfig(
        enabled = true,
        consentAt = 1L,
        consentVersion = EngageConfig.CONSENT_VERSION,
        accountUid = uid,
        topicId = tid,
        topicName = "棋手战鹰",
        postCount = post,
        repostCount = 0,
        commentCount = comment,
        likeCount = like,
        // gap 全 0：WeiboThrottle 对 gap ≤ 0 直接放行，测试不真等
        postGapMinMs = 0, postGapMaxMs = 0,
        repostGapMinMs = 0, repostGapMaxMs = 0,
        commentGapMinMs = 0, commentGapMaxMs = 0,
        likeGapMinMs = 0, likeGapMaxMs = 0,
        actionGapMinMs = 0, actionGapMaxMs = 0,
        // [0,0) 是空区间：任何时刻都不免打扰（isQuietHour 对 start==end 恒 false）
        quietStartHour = 0, quietEndHour = 0,
    )

    private class Env(
        val svc: EngageService,
        val configPort: FakeConfigPort,
        val runPort: FakeRunPort,
        val gateway: FakeGateway,
    )

    private fun make(
        cfg: EngageConfig,
        gateway: FakeGateway = FakeGateway().apply {
            // 默认给一个已关注超话 + 3 条别人的帖子（评论/点赞有目标可弹）
            topics = listOf(WeiboTopicApi.Topic(name = "棋手战鹰", scheme = null, done = false, topicId = tid))
            feed = listOf(
                WeiboFeedApi.FeedPost(mid = "m1", uid = "999", text = "第一帖"),
                WeiboFeedApi.FeedPost(mid = "m2", uid = "998", text = "第二帖"),
                WeiboFeedApi.FeedPost(mid = "m3", uid = "997", text = "第三帖"),
            )
        },
        commentTextCount: Int = 3,
    ): Env {
        val configPort = FakeConfigPort(cfg)
        // 文案库：发帖 1 条、评论 N 条（评论连续失败用例需要 3 条评论都有文案，
        // 否则第 2 条起走 NO_TEXT 分支——那个分支不进熔断计数，测不到动作级放弃）
        configPort.appendTexts(TextKind.POST, listOf(EngageText(id = "p1", body = "今天的棋局", kind = TextKind.POST)))
        configPort.appendTexts(
            TextKind.COMMENT,
            (1..commentTextCount).map { EngageText(id = "c$it", body = "评论文案 $it", kind = TextKind.COMMENT) },
        )
        val runPort = FakeRunPort()
        val svc = EngageService(
            configPort = configPort,
            runPort = runPort,
            accountSource = { account },
            accountSink = { _, _, _ -> runPort.sinkWrites++ },
            gateway = gateway,
        )
        return Env(svc, configPort, runPort, gateway)
    }

    // ------------------------------------------------------------------
    // 全链路：一次完整签名轮
    // ------------------------------------------------------------------

    @Test
    fun `全签名轮三个动作全部成功并回写换发cookie`() = runBlocking {
        val env = make(runConfig(post = 1, comment = 1, like = 1))
        val out = env.svc.run()

        assertTrue(out.blockedReason, out.blockedReason.isEmpty())
        val run = out.run!!
        // 执行顺序固定：发帖 → 评论 → 点赞（EXECUTION_ORDER）
        assertEquals(listOf(EngageAction.POST, EngageAction.COMMENT, EngageAction.LIKE), run.records.map { it.action })
        run.records.forEach { assertEquals(ActionState.OK, it.state) }
        // 每个动作：预占 → 提交；换发 cookie 恰好回写一次
        assertEquals(listOf(EngageAction.POST, EngageAction.COMMENT, EngageAction.LIKE), env.runPort.reserved)
        assertEquals(listOf(EngageAction.POST, EngageAction.COMMENT, EngageAction.LIKE), env.runPort.committed)
        assertEquals(1, env.runPort.sinkWrites)
        assertTrue(run.finishedAt > 0)
        // 三次真实出站：发帖 + 评论 + 点赞；发帖正文带超话标签
        assertEquals(3, env.gateway.written.size)
        val postBody = env.gateway.written.first { it.first == EngageAction.POST }.third
        assertTrue("发帖正文应带超话标签", postBody.contains("#棋手战鹰#") && postBody.contains("今天的棋局"))
        // 发帖台账：请求前 UNCERTAIN 占位 + 最终 OK 收敛，共两笔
        assertEquals(2, env.runPort.posts.size)
        assertEquals(ActionState.UNCERTAIN, env.runPort.posts.first().state)
        assertEquals(ActionState.OK, env.runPort.posts.last().state)
        // 用过的文案各库记账一次
        assertTrue(env.configPort.mutatedKinds.contains(TextKind.POST))
        assertTrue(env.configPort.mutatedKinds.contains(TextKind.COMMENT))
    }

    // ------------------------------------------------------------------
    // 动作级熔断：连续失败只放弃该动作，其它动作照跑
    // ------------------------------------------------------------------

    @Test
    fun `评论连续失败达到阈值放弃剩余评论但不影响点赞`() = runBlocking {
        val cfg = runConfig(post = 1, comment = 3, like = 1).copy(failStreakAbort = 2)
        val env = make(cfg)
        env.gateway.results = mapOf(
            EngageAction.COMMENT to WeiboWriteApi.WriteResult(EngageErrorKind.NETWORK, message = "timeout"),
        )
        val out = env.svc.run()

        val recs = out.run!!.records
        // POST OK → 评论×2 连续失败（streak=2 触发动作级放弃）→ 第 3 条评论 SKIPPED → LIKE OK
        assertEquals(
            "实际轨迹: ${recs.joinToString(" | ") { "${it.action}#${it.seq}:${it.state}:${it.errorKind}" }}",
            listOf(
                ActionState.OK, ActionState.FAILED, ActionState.FAILED, ActionState.SKIPPED, ActionState.OK,
            ),
            recs.map { it.state },
        )
        // 动作级放弃不设整轮停止原因
        assertEquals("", out.run!!.stopReason)
        // 两条真发出去的评论 + 第 3 条没发（预占撤销）
        assertEquals(2, env.gateway.written.count { it.first == EngageAction.COMMENT })
        assertTrue(env.runPort.released.contains(EngageAction.COMMENT))
    }

    // ------------------------------------------------------------------
    // 整轮熔断：需要人工的失败立刻停，剩余动作补 SKIPPED
    // ------------------------------------------------------------------

    @Test
    fun `登录墙整轮停止剩余动作补记SKIPPED`() = runBlocking {
        val env = make(runConfig(post = 1, comment = 1, like = 1))
        env.gateway.results = mapOf(
            EngageAction.COMMENT to WeiboWriteApi.WriteResult(EngageErrorKind.LOGIN_WALL),
        )
        val out = env.svc.run()

        val recs = out.run!!.records
        assertEquals(
            listOf(ActionState.OK, ActionState.FAILED, ActionState.SKIPPED),
            recs.map { it.state },
        )
        assertTrue("停止原因应可读", out.run!!.stopReason.contains("重新登录"))
        // 点赞没发请求
        assertTrue(env.gateway.written.none { it.first == EngageAction.LIKE })
    }

    // ------------------------------------------------------------------
    // 发帖结果未知：UNCERTAIN 落台账并锁死当日发帖计数
    // ------------------------------------------------------------------

    @Test
    fun `发帖网络失败结果未知进UNCERTAIN且预占保留`() = runBlocking {
        val env = make(runConfig(post = 1, comment = 0, like = 0))
        env.gateway.results = mapOf(
            EngageAction.POST to WeiboWriteApi.WriteResult(EngageErrorKind.NETWORK, message = "timeout"),
        )
        val out = env.svc.run()

        val rec = out.run!!.records.single()
        assertEquals(ActionState.UNCERTAIN, rec.state)
        assertEquals(listOf(EngageAction.POST), env.runPort.uncertain)
        // 台账两笔都是 UNCERTAIN：占位 + 最终收敛
        assertEquals(2, env.runPort.posts.size)
        assertTrue(env.runPort.posts.all { it.state == ActionState.UNCERTAIN })
    }

    // ------------------------------------------------------------------
    // 人工确认模式：发帖只组稿落台账，不发请求
    // ------------------------------------------------------------------

    @Test
    fun `人工确认模式发帖落台账等待确认不发请求`() = runBlocking {
        val cfg = runConfig(post = 1, comment = 1, like = 0).copy(requireConfirmBeforePost = true)
        val env = make(cfg)
        val out = env.svc.run()

        val recs = out.run!!.records
        assertEquals(
            listOf(ActionState.SKIPPED, ActionState.OK),
            recs.map { it.state },
        )
        assertEquals(EngageAction.POST, recs[0].action)
        assertTrue(recs[0].message.contains("确认"))
        // 台账里是「待确认」，从未预占、从未出站
        assertEquals(1, env.runPort.posts.size)
        assertEquals(ActionState.AWAITING_CONFIRM, env.runPort.posts.single().state)
        assertTrue("台账里是「待确认」", env.runPort.posts.single().state == ActionState.AWAITING_CONFIRM)
        // 发帖从未预占、从未出站（评论会预占，不算）
        assertTrue(env.runPort.reserved.none { it == EngageAction.POST })
        // 评论照常执行
        assertEquals(1, env.gateway.written.count { it.first == EngageAction.COMMENT })
    }

    // ------------------------------------------------------------------
    // 时间预算耗尽：整轮立即收尾，剩余动作补 SKIPPED，零出站
    // ------------------------------------------------------------------

    @Test
    fun `预算耗尽全部动作补记SKIPPED且零出站`() = runBlocking {
        val env = make(runConfig(post = 1, comment = 1, like = 1))
        // 预算上限走调用方参数：config.maxRunDurationMs 有 1–20 分钟的校验下限，
        // 直接 copy(1) 会被 validate 判非法，整轮根本跑不起来
        val out = env.svc.run(budgetCapMs = 1L)

        val recs = out.run!!.records
        assertEquals(3, recs.size)
        recs.forEach { assertEquals(ActionState.SKIPPED, it.state) }
        assertTrue("停止原因应说明预算", out.run!!.stopReason.contains("预算"))
        assertEquals(0, env.gateway.written.size)
    }

    // ------------------------------------------------------------------
    // 目标流不可用：互动动作逐个失败并放弃，错误写进 notes
    // ------------------------------------------------------------------

    @Test
    fun `目标流为空互动动作逐个失败且错误进notes`() = runBlocking {
        val env = make(runConfig(post = 1, comment = 1, like = 1))
        env.gateway.feed = emptyList()
        val out = env.svc.run()

        val recs = out.run!!.records
        assertEquals(EngageAction.POST, recs[0].action)
        assertEquals(ActionState.OK, recs[0].state)
        // 评论/点赞各失败一次（TARGET_GONE），且没有真实出站
        assertEquals(ActionState.FAILED, recs[1].state)
        assertEquals(ActionState.FAILED, recs[2].state)
        assertEquals(EngageErrorKind.TARGET_GONE, recs[1].errorKind)
        assertEquals(EngageErrorKind.TARGET_GONE, recs[2].errorKind)
        assertTrue(env.gateway.written.none { it.first != EngageAction.POST })
        // feedError 必须能被用户看到
        assertTrue(out.notes.any { it.contains("目标") || it.contains("风控") })
    }

    // ------------------------------------------------------------------
    // 文案耗尽：逐条 NO_TEXT 失败，但不进熔断计数（没发请求就谈不上「连续失败」）
    // ------------------------------------------------------------------

    @Test
    fun `文案耗尽逐条NO_TEXT失败且不触发熔断`() = runBlocking {
        val env = make(runConfig(post = 1, comment = 3, like = 0), commentTextCount = 1)
        val out = env.svc.run()

        val recs = out.run!!.records
        assertEquals(
            "实际轨迹: ${recs.joinToString(" | ") { "${it.action}#${it.seq}:${it.state}:${it.errorKind}" }}",
            listOf(ActionState.OK, ActionState.OK, ActionState.FAILED, ActionState.FAILED),
            recs.map { it.state },
        )
        assertEquals(EngageErrorKind.NO_TEXT, recs[2].errorKind)
        assertEquals(EngageErrorKind.NO_TEXT, recs[3].errorKind)
        // 熔断器从未被触发（NO_TEXT 不计 streak）
        assertEquals("", out.run!!.stopReason)
        // 只有带文案的第 1 条真正出站
        assertEquals(1, env.gateway.written.count { it.first == EngageAction.COMMENT })
    }

    // ------------------------------------------------------------------
    // 登录态校验直接失败：不出站、不落任何记录
    // ------------------------------------------------------------------

    @Test
    fun `登录态校验失败整轮中止零出站`() = runBlocking {
        val env = make(runConfig(post = 1, comment = 1, like = 1))
        env.gateway.verifyError = RuntimeException("boom")
        val out = env.svc.run()

        assertNull(out.run)
        assertTrue(out.blockedReason.contains("登录态校验失败"))
        assertEquals(0, env.gateway.written.size)
        assertEquals(0, env.runPort.runs.size)
    }

    // ------------------------------------------------------------------
    // 换发 cookie 只在整轮结束时回写一次（中途失败也不能丢）
    // ------------------------------------------------------------------

    @Test
    fun `中途失败换发cookie仍然回写`() = runBlocking {
        val env = make(runConfig(post = 1, comment = 1, like = 1))
        env.gateway.results = mapOf(
            EngageAction.COMMENT to WeiboWriteApi.WriteResult(EngageErrorKind.LOGIN_WALL),
        )
        env.svc.run()
        assertEquals("收尾在 finally 里，熔断也要回写换发", 1, env.runPort.sinkWrites)
    }
}
