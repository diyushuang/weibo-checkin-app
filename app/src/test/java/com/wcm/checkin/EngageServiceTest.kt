package com.wcm.checkin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编排服务的「前置拒绝」分支测试。
 *
 * 这里**刻意只测到达网络之前的路径**：配置非法、账号缺失、账号判死、并发互斥。
 * 再往后的登录校验与发请求都是真实 HTTP，单测里塞不了假——硬测等于把它们
 * 变成集成测试，反而不可靠。真正的端到端验证放在真机清单里（V9–V16）。
 */
class EngageServiceTest {

    private class FakeConfigPort(var cfg: EngageConfig) : EngageConfigPort {
        var texts: List<EngageText> = emptyList()
        override fun loadConfig(): EngageConfig = cfg
        override fun saveConfig(config: EngageConfig) { cfg = config }
        override fun loadTexts(): List<EngageText> = texts
        override fun saveTexts(texts: List<EngageText>) { this.texts = texts }
        override fun appendTexts(newTexts: List<EngageText>): List<EngageText> {
            texts = texts + newTexts
            return texts
        }
        override fun clearAll() { cfg = EngageConfig(); texts = emptyList() }
    }

    /** 只实现被断言用到的那几条，其余保持默认行为。 */
    private class FakeRunPort : EngageRunPort {
        var counter = EngageDayCounter()
        val posts = ArrayList<EngagePostRecord>()
        var released = 0
        override fun loadCounter(uid: String, day: String): EngageDayCounter = counter
        override fun reserveSlot(uid: String, day: String, action: EngageAction, seq: Int, textId: String, now: Long) = true
        override fun commitSlot(uid: String, day: String, action: EngageAction, seq: Int, now: Long): EngageDayCounter = counter
        override fun releaseSlot(uid: String, day: String, action: EngageAction, seq: Int): EngageDayCounter {
            released++
            return counter
        }
        override fun markUncertain(uid: String, day: String, action: EngageAction, seq: Int, now: Long): EngageDayCounter = counter
        override fun saveRun(run: EngageRun) = Unit
        override fun loadRuns(uid: String, limit: Int): List<EngageRun> = emptyList()
        override fun loadPosts(uid: String, day: String): List<EngagePostRecord> = posts
        override fun savePost(record: EngagePostRecord) { posts.add(record) }
        override fun settlePost(uid: String, day: String, postId: String, state: ActionState, manualConfirmed: Boolean): EngagePostRecord? = null
        override fun prune(uid: String, today: String) = Unit
        override fun clearAll(uid: String) = Unit
    }

    private val tid = "1008088cf9880c8ec5f07f84c2f30e74d43574"
    private val day = "2026-11-16"

    private fun enabledConfig() = EngageConfig(
        enabled = true,
        consentAt = 1L,
        consentVersion = EngageConfig.CONSENT_VERSION,
        accountUid = "1000000001",
        topicId = tid,
        topicName = "棋手战鹰",
    )

    private fun service(
        cfg: EngageConfig,
        account: EngageAccount?,
    ): Triple<EngageService, FakeConfigPort, FakeRunPort> {
        val configPort = FakeConfigPort(cfg)
        val runPort = FakeRunPort()
        val svc = EngageService(
            configPort = configPort,
            runPort = runPort,
            accountSource = { uid -> account?.takeIf { it.uid == uid } },
            accountSink = { _, _, _ -> },
        )
        return Triple(svc, configPort, runPort)
    }

    @Test
    fun `未开启任务直接拒绝且不发请求`() = runBlocking {
        val (svc, _, _) = service(EngageConfig(), null)
        val out = svc.run()
        assertNull(out.run)
        assertTrue(out.blockedReason.contains("未开启"))
    }

    @Test
    fun `未确认风险声明直接拒绝`() = runBlocking {
        val (svc, _, _) = service(enabledConfig().copy(consentAt = 0L), null)
        val out = svc.run()
        assertTrue(out.blockedReason.contains("风险声明"))
    }

    @Test
    fun `超话 ID 非法直接拒绝`() = runBlocking {
        val (svc, _, _) = service(enabledConfig().copy(topicId = "../etc"), null)
        assertTrue(svc.run().blockedReason.contains("超话 ID"))
    }

    @Test
    fun `账号不存在直接拒绝`() = runBlocking {
        val (svc, _, _) = service(enabledConfig(), null)
        assertTrue(svc.run().blockedReason.contains("账号不存在"))
    }

    @Test
    fun `账号判死直接拒绝`() = runBlocking {
        val acc = EngageAccount(uid = "1000000001", name = "测试", cookie = "SUB=x", dead = true)
        val (svc, _, _) = service(enabledConfig(), acc)
        assertTrue(svc.run().blockedReason.contains("登录态"))
    }

    @Test
    fun `缺少 cookie 直接拒绝`() = runBlocking {
        val acc = EngageAccount(uid = "1000000001", name = "测试", cookie = "")
        val (svc, _, _) = service(enabledConfig(), acc)
        assertTrue(svc.run().blockedReason.contains("Cookie"))
    }

    @Test
    fun `运行结束后释放运行标志`() = runBlocking {
        val (svc, _, _) = service(EngageConfig(), null)
        svc.run()
        assertFalse(svc.isRunning)
    }

    @Test
    fun `预占槽位接口可被调用`() {
        val port = FakeRunPort()
        assertTrue(port.reserveSlot("u", "2026-11-16", EngageAction.POST, 1, "t1", 0L))
        assertEquals(0, port.released)
    }

    // ---------- 确认后发送 ----------

    @Test
    fun `非待确认记录不能确认发送`() = runBlocking {
        val (svc, _, runPort) = service(enabledConfig(), null)
        runPort.posts.add(post(id = "p1", state = ActionState.OK))
        val out = svc.publishConfirmed("1000000001", day, "p1")
        assertNull(out.record)
        assertTrue(out.blockedReason.contains("等待确认"))
    }

    @Test
    fun `台账里没有的记录不能确认发送`() = runBlocking {
        val (svc, _, _) = service(enabledConfig(), null)
        val out = svc.publishConfirmed("1000000001", day, "missing")
        assertNull(out.record)
        assertTrue(out.blockedReason.contains("找不到"))
    }

    @Test
    fun `待确认记录在账号判死时被拦下且不改台账`() = runBlocking {
        val dead = EngageAccount(uid = "1000000001", name = "测试", cookie = "SUB=x", dead = true)
        val (svc, _, runPort) = service(enabledConfig(), dead)
        runPort.posts.add(post(id = "p2", state = ActionState.AWAITING_CONFIRM))
        val out = svc.publishConfirmed("1000000001", day, "p2")
        assertNull(out.record)
        assertTrue(out.blockedReason.contains("登录态"))
        assertEquals(ActionState.AWAITING_CONFIRM, runPort.posts.first().state)
    }

    private fun post(id: String, state: ActionState) = EngagePostRecord(
        id = id,
        runId = "r1",
        seq = 1,
        at = 0L,
        accountUid = "1000000001",
        topicId = tid,
        topicName = "棋手战鹰",
        body = "正文",
        state = state,
    )
}
