package com.wcm.checkin

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 签到编排（[CheckinService]）的单测。
 *
 * 这个类此前是**零测试**：它依赖 final 的 `AccountMaintenanceService`，JVM 里既造不出来
 * 也替换不掉。0.15.0 抽出 `:core` 的 [ProbePort] 之后才可测——这里的用例同时也是
 * 那个端口化改动的存在理由。
 *
 * 覆盖面（都不到真实网络）：
 * - 已判死的账号不发请求、直接返回；
 * - 探活 `login=false` 走「判死 + 写小结」，不碰超话列表；
 * - 正常 2/2 签到写「签到 2/2」并把 done 回写快照；
 * - 单条签到失败只记失败、不影响其余；
 * - **取消必须向上抛**（A1 的回归用例：吞掉会让账号被打上「签到 0/N」的假小结）。
 */
class CheckinServiceTest {

    private val ctx = mockk<Context>(relaxed = true)
    private val loginOk = WeiboAuthApi.Verify(login = true, st = "st_token", cookie = "SUB=new")

    private fun account(uid: String = "1234567890", dead: Boolean = false) = Account(
        uid = uid,
        name = "测试账号",
        cookie = "SUB=test_sub; SUBP=test_subp",
        cookieDead = dead,
    )

    private fun topic(name: String, id: String, done: Boolean = false) = WeiboTopicApi.Topic(
        name = name,
        scheme = "sinaweibo://pageinfo?containerid=100808$id",
        done = done,
        topicId = id,
    )

    @Before
    fun setUp() {
        // 超话列表与签到都是真实 HTTP；单测里整体替换成假响应。
        mockkObject(WeiboTopicApi)
    }

    @After
    fun tearDown() {
        unmockkObject(WeiboTopicApi)
    }

    @Test
    fun cookieDeadAccount_isSkippedWithoutAnyRequest() = runBlocking {
        val probe = FakeProbePort(loginOk)
        val port = FakeAccountPort()
        val svc = CheckinService(port, probe, FakeTopicCache())

        val out = svc.runAccount(ctx, account(dead = true), log = {})

        assertEquals("已判死的账号不该发探活请求", 0, probe.calls)
        assertTrue(out.error.contains("失效"))
        assertTrue(port.deadUids.isEmpty())
        assertTrue("不该写小结", port.lastResults.isEmpty())
    }

    @Test
    fun probeLoginFalse_marksDeadAndWritesSummary() = runBlocking {
        val probe = FakeProbePort(WeiboAuthApi.Verify(login = false, st = null, cookie = "SUB=old"))
        val port = FakeAccountPort()
        val svc = CheckinService(port, probe, FakeTopicCache())
        val acc = account()

        val out = svc.runAccount(ctx, acc, log = {})

        assertEquals(listOf(acc.uid), port.deadUids)
        assertEquals(1, port.lastResults.size)
        assertTrue(out.error.isNotEmpty())
    }

    @Test
    fun allTopicsSigned_writesSummaryAndMarksSnapshotDone() = runBlocking {
        val topics = listOf(topic("超话A", "aaaabbbb"), topic("超话B", "ccccdddd"))
        coEvery { WeiboTopicApi.listTopics(any<WeiboHttp.WeiboSession>(), any(), any()) } returns topics
        every { WeiboTopicApi.sign(any<WeiboHttp.WeiboSession>(), any(), any()) } returns true

        val port = FakeAccountPort()
        val cache = FakeTopicCache()
        val svc = CheckinService(port, FakeProbePort(loginOk), cache)

        val out = svc.runAccount(ctx, account(), log = {}, pause = {})

        assertEquals(2, out.ok)
        assertEquals(0, out.failed)
        assertEquals(2, out.pending)
        // 小结末尾是「 · HH:mm:ss」，这里只断言稳定的前半段
        assertEquals(1, port.lastResults.size)
        assertTrue(port.lastResults.single().startsWith("签到 2/2 · "))
        // 签到成功后要把 done 标记回写，否则首页仍显示「今日已签 0/2」
        assertEquals("快照应被更新两次（初次 + 标记完成）", 2, cache.saved.size)
        assertTrue(cache.saved.last().all { it.done })
    }

    @Test
    fun signFailure_countsAsFailedButKeepsGoing() = runBlocking {
        val topics = listOf(topic("超话A", "aaaabbbb"), topic("超话B", "ccccdddd"))
        coEvery { WeiboTopicApi.listTopics(any<WeiboHttp.WeiboSession>(), any(), any()) } returns topics
        every { WeiboTopicApi.sign(any<WeiboHttp.WeiboSession>(), any(), any()) } throws RuntimeException("网络抖动")

        val port = FakeAccountPort()
        val svc = CheckinService(port, FakeProbePort(loginOk), FakeTopicCache())

        val out = svc.runAccount(ctx, account(), log = {}, pause = {})

        assertEquals(0, out.ok)
        assertEquals(2, out.failed)
        assertTrue(port.lastResults.single().startsWith("签到 0/2（失败 2）"))
    }

    /**
     * A1 回归：签到途中被取消，必须把 `CancellationException` 抛出去。
     *
     * 修复前这里会把取消记成「✗ 超话A：…」并落库一条「签到 0/2（失败 2）」——用户点了
     * 停止，账号却被写进一条失败小结。修复后：异常向上抛，一条小结都不写。
     */
    @Test
    fun cancellationDuringPause_propagatesAndWritesNoSummary() = runBlocking {
        coEvery { WeiboTopicApi.listTopics(any<WeiboHttp.WeiboSession>(), any(), any()) } returns
            listOf(topic("超话A", "aaaabbbb"), topic("超话B", "ccccdddd"))
        every { WeiboTopicApi.sign(any<WeiboHttp.WeiboSession>(), any(), any()) } returns true

        val port = FakeAccountPort()
        val svc = CheckinService(port, FakeProbePort(loginOk), FakeTopicCache())

        val thrown = runCatching {
            svc.runAccount(ctx, account(), log = {}, pause = { throw CancellationException("用户点了停止") })
        }.exceptionOrNull()

        assertTrue("取消必须向上传播，而不是被吞成一条失败小结", thrown is CancellationException)
        assertTrue("取消时不该落任何小结", port.lastResults.isEmpty())
    }

    /** A1 回归（探活分支）：探活途中被取消，不能被记成「探活失败」。 */
    @Test
    fun cancellationDuringProbe_isNotRecordedAsProbeFailure() = runBlocking {
        val port = FakeAccountPort()
        val svc = CheckinService(
            port,
            FakeProbePort(loginOk, error = CancellationException("取消")),
            FakeTopicCache(),
        )

        val thrown = runCatching { svc.runAccount(ctx, account(), log = {}) }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue(port.lastResults.isEmpty())
        assertTrue(port.deadUids.isEmpty())
    }

    /** 多账号之间的间隔也要能被取消打断（[CheckinService.runAll] 的第一跳）。 */
    @Test
    fun runAll_pauseBetweenAccounts_isInterruptible() = runBlocking {
        val port = FakeAccountPort()
        val svc = CheckinService(port, FakeProbePort(loginOk), FakeTopicCache())

        val thrown = runCatching {
            svc.runAll(
                ctx,
                listOf(account(dead = true), account(uid = "2222")),
                log = {},
                pause = { throw CancellationException("批量取消") },
            )
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }
}
