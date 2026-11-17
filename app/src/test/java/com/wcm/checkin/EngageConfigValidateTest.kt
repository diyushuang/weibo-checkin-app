package com.wcm.checkin

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置校验测试。
 *
 * 这些边界以前只在界面上被 {@code coerce} 兜住，落到 JSON（手改、旧版本、导入导出）
 * 就没有任何防线：例如 `min > max` 的间隔区间会让 `randomIn` 直接返回 0，
 * 变成「无间隔连发」。校验收在这一层，所有执行入口都会经过。
 */
class EngageConfigValidateTest {

    private val tid = "1008088cf9880c8ec5f07f84c2f30e74d43574"

    private fun ok() = EngageConfig(
        enabled = true,
        consentAt = 1L,
        consentVersion = EngageConfig.CONSENT_VERSION,
        accountUid = "1000000001",
        topicId = tid,
    )

    @Test
    fun `合法配置通过`() {
        assertNull(ok().validate())
    }

    @Test
    fun `间隔区间上限小于下限被拒`() {
        assertNotNull(ok().copy(commentGapMinMs = 60_000, commentGapMaxMs = 1_000).validate())
    }

    @Test
    fun `负间隔被拒`() {
        assertNotNull(ok().copy(likeGapMinMs = -1).validate())
    }

    @Test
    fun `免打扰小时越界被拒`() {
        assertNotNull(ok().copy(quietStartHour = 24).validate())
        assertNotNull(ok().copy(quietEndHour = 25).validate())
    }

    @Test
    fun `时间预算上限放宽到 20 分钟`() {
        // 0.12.6 起手动执行允许到 20 分钟（后台由 Worker 另外压到 9 分钟），
        // 原来卡在 8 分钟是为了迁就 CoroutineWorker 的 10 分钟系统上限。
        assertNull(ok().copy(maxRunDurationMs = 12 * 60_000L).validate())
        assertNull(ok().copy(maxRunDurationMs = 20 * 60_000L).validate())
        assertNotNull(ok().copy(maxRunDurationMs = 21 * 60_000L).validate())
        assertNotNull(ok().copy(maxRunDurationMs = 30_000L).validate())
    }

    @Test
    fun `评论长度上限越界被拒`() {
        assertNotNull(ok().copy(maxCommentLen = 10).validate())
    }

    @Test
    fun `只有 hash 的超话 ID 被拒`() {
        val reason = ok().copy(topicId = "8cf9880c8ec5f07f84c2f30e74d43574").validate()
        assertNotNull(reason)
        assertTrue(reason!!.contains("containerid"))
    }
}
