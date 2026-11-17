package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 规划器测试：把「用户设的次数」翻译成「这一轮做哪些动作」的边界穷举。
 */
class EngagePlannerTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val tid = "1008088cf9880c8ec5f07f84c2f30e74d43574"

    private fun at(hour: Int, minute: Int = 0, day: Int = 16): Long =
        LocalDate.of(2026, 11, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun config(
        post: Int = 1,
        repost: Int = 2,
        comment: Int = 4,
        like: Int = 3,
        maxActions: Int = 15,
    ) = EngageConfig(
        enabled = true,
        consentAt = 1L,
        consentVersion = EngageConfig.CONSENT_VERSION,
        accountUid = "1000000001",
        topicId = tid,
        topicName = "棋手战鹰",
        postCount = post,
        repostCount = repost,
        commentCount = comment,
        likeCount = like,
        maxActionsPerRun = maxActions,
    )

    private fun counter(done: Map<EngageAction, Int> = emptyMap(), pending: List<PendingSlot> = emptyList()) =
        EngageDayCounter(
            accountUid = "1000000001",
            day = "2026-11-16",
            done = done,
            pending = pending,
        )

    // ---------- 默认计划 ----------

    @Test
    fun `默认计划为 1 发帖 4 评论 2 转发 3 点赞且发帖排最前`() {
        // 0.12.6 起执行顺序改为「发帖 → 评论 → 转发 → 点赞」：
        // 发帖是用户唯一要求必须成功的动作，排最后会被前面的间隔吃光时间预算。
        val plan = EngagePlanner.plan(config(), counter(), at(10))
        assertEquals(
            listOf(
                EngageAction.POST,
                EngageAction.COMMENT, EngageAction.COMMENT, EngageAction.COMMENT, EngageAction.COMMENT,
                EngageAction.REPOST, EngageAction.REPOST,
                EngageAction.LIKE, EngageAction.LIKE, EngageAction.LIKE,
            ),
            plan.actions.map { it.action },
        )
        assertEquals("", plan.blocked)
    }

    @Test
    fun `序号按动作各自从 1 开始`() {
        val plan = EngagePlanner.plan(config(), counter(), at(10))
        assertEquals(listOf(1, 2, 3, 4), plan.actions.filter { it.action == EngageAction.COMMENT }.map { it.seq })
        assertEquals(listOf(1, 2), plan.actions.filter { it.action == EngageAction.REPOST }.map { it.seq })
    }

    // ---------- 已做次数抵扣 ----------

    @Test
    fun `已完成次数会被抵扣`() {
        val plan = EngagePlanner.plan(
            config(),
            counter(done = mapOf(EngageAction.COMMENT to 3, EngageAction.LIKE to 3)),
            at(10),
        )
        assertEquals(1, plan.countOf(EngageAction.COMMENT))
        assertEquals(0, plan.countOf(EngageAction.LIKE))
        assertEquals(2, plan.countOf(EngageAction.REPOST))
    }

    @Test
    fun `全部做完时返回空计划与提示`() {
        val plan = EngagePlanner.plan(
            config(),
            counter(done = EngageAction.entries.associateWith { 99 }),
            at(10),
        )
        assertTrue(plan.actions.isEmpty())
        assertEquals("今日已无待执行动作", plan.blocked)
    }

    // ---------- 开关 ----------

    @Test
    fun `关闭的动作不会进计划`() {
        val cfg = config().copy(likeEnabled = false, postEnabled = false)
        val plan = EngagePlanner.plan(cfg, counter(), at(10))
        assertEquals(0, plan.countOf(EngageAction.LIKE))
        assertEquals(0, plan.countOf(EngageAction.POST))
        assertEquals(4, plan.countOf(EngageAction.COMMENT))
    }

    // ---------- 上限截断 ----------

    @Test
    fun `单轮上限会截断并给提示`() {
        val plan = EngagePlanner.plan(config(maxActions = 5), counter(), at(10))
        assertEquals(5, plan.actions.size)
        assertTrue(plan.notes.any { it.contains("单轮上限") })
    }

    // ---------- 未收敛的发帖预占 ----------

    @Test
    fun `存在未收敛发帖预占时今日不再发帖`() {
        val plan = EngagePlanner.plan(
            config(post = 3),
            counter(pending = listOf(PendingSlot("POST#1", EngageAction.POST, at(9)))),
            at(10),
        )
        assertEquals(0, plan.countOf(EngageAction.POST))
        assertTrue(plan.notes.any { it.contains("结果未知") })
    }

    // ---------- 免打扰时段 ----------

    @Test
    fun `免打扰时段直接阻止执行`() {
        val plan = EngagePlanner.plan(config(), counter(), at(3))
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.blocked.contains("免打扰"))
    }

    @Test
    fun `免打扰结束后可以执行`() {
        val plan = EngagePlanner.plan(config(), counter(), at(8))
        assertTrue(plan.actions.isNotEmpty())
    }

    // ---------- 后台发帖开关 ----------

    @Test
    fun `后台任务禁止发帖时只跳过发帖`() {
        val cfg = config().copy(postInBackground = false)
        val plan = EngagePlanner.plan(cfg, counter(), at(10), background = true)
        assertEquals(0, plan.countOf(EngageAction.POST))
        assertEquals(4, plan.countOf(EngageAction.COMMENT))
        assertTrue(plan.notes.any { it.contains("后台") })
    }

    @Test
    fun `手动补跑不受后台发帖开关影响`() {
        val cfg = config().copy(postInBackground = false)
        val plan = EngagePlanner.plan(cfg, counter(), at(10), background = false)
        assertEquals(1, plan.countOf(EngageAction.POST))
    }

    // ---------- 配置非法 ----------

    @Test
    fun `未授权时拒绝规划`() {
        val plan = EngagePlanner.plan(config().copy(consentAt = 0), counter(), at(10))
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.blocked.contains("风险声明"))
    }

    @Test
    fun `超话 ID 非法时拒绝规划`() {
        val plan = EngagePlanner.plan(config().copy(topicId = "../etc"), counter(), at(10))
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.blocked.contains("超话 ID"))
    }

    // ---------- 软约束提示 ----------

    @Test
    fun `评论转发超上限时给出计分提示`() {
        val hints = EngagePlanner.quotaHints(config(post = 1, repost = 5, comment = 9, like = 3))
        assertTrue(hints.any { it.contains("评论每日仅前 4 条") })
        assertTrue(hints.any { it.contains("转发每日仅前 2 条") })
        assertTrue(hints.any { it.contains("点赞不产生超话经验") })
        assertTrue(hints.any { it.contains("发帖本身不加经验") })
    }

    @Test
    fun `次数在上限内时不出现白跑提示`() {
        val hints = EngagePlanner.quotaHints(config(post = 1, repost = 2, comment = 4, like = 0))
        assertTrue(hints.none { it.contains("仅前") })
    }

    @Test
    fun `超过 3 帖时给出风控提示`() {
        val hints = EngagePlanner.quotaHints(config(post = 5))
        assertTrue(hints.any { it.contains("3 帖") })
    }

    @Test
    fun `全部动作关闭时提示为空`() {
        val cfg = config().copy(
            postEnabled = false, repostEnabled = false,
            commentEnabled = false, likeEnabled = false,
        )
        assertTrue(EngagePlanner.quotaHints(cfg).isEmpty())
    }

    // ---------- 同日续跑的序号 ----------

    @Test
    fun `同日第二轮序号接续已完成数而不是从 1 重排`() {
        // 已发过 2 帖、配额 3 帖：本轮只剩 1 次，序号必须是 3
        val plan = EngagePlanner.plan(
            config(post = 3),
            counter(done = mapOf(EngageAction.POST to 2)),
            at(10),
        )
        assertEquals(listOf(3), plan.actions.filter { it.action == EngageAction.POST }.map { it.seq })
    }

    @Test
    fun `同日续跑的台账 id 与上一轮不重复`() {
        val first = EngagePlanner.plan(config(post = 2), counter(done = mapOf(EngageAction.POST to 1)), at(10))
        val second = EngagePlanner.plan(config(post = 2), counter(done = mapOf(EngageAction.POST to 2)), at(11))
        val ids = (first.actions + second.actions)
            .filter { it.action == EngageAction.POST }
            .map { EngageService.postIdOf("u1", "2026-11-16", it.seq) }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ---------- 互动目标需求 ----------

    @Test
    fun `互动目标需求是三类次数之和而不是最大值`() {
        val plan = EngagePlanner.plan(config(comment = 4, repost = 2, like = 3), counter(), at(10))
        // 4 + 2 + 3 = 9 个目标；用 max 会只算出 4
        assertEquals(9, EngagePlanner.targetDemand(plan))
    }

    @Test
    fun `只发帖时不需要互动目标`() {
        val plan = EngagePlanner.plan(
            config().copy(repostEnabled = false, commentEnabled = false, likeEnabled = false),
            counter(),
            at(10),
        )
        assertEquals(0, EngagePlanner.targetDemand(plan))
    }

    @Test
    fun `刚好用满上限时不提示截断`() {
        val plan = EngagePlanner.plan(config(maxActions = 10), counter(), at(10))
        assertEquals(10, plan.actions.size)
        assertTrue(plan.notes.none { it.contains("单轮上限") })
    }
}
