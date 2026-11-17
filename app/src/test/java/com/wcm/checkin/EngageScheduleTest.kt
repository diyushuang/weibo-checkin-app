package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 日期与排期口径测试：所有「今天」「下一次」「哪些天不能用」的判定都从这里出。 */
class EngageScheduleTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDate.of(2026, 11, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun config(quietStart: Int = 0, quietEnd: Int = 7) = EngageConfig(
        quietStartHour = quietStart,
        quietEndHour = quietEnd,
    )

    // ---------- 超话 ID ----------

    @Test
    fun `真实超话 ID 合法`() {
        assertTrue(EngageSchedule.isValidTopicId("1008088cf9880c8ec5f07f84c2f30e74d43574"))
    }

    @Test
    fun `空串与过短的 ID 非法`() {
        assertFalse(EngageSchedule.isValidTopicId(""))
        assertFalse(EngageSchedule.isValidTopicId("abc"))
    }

    @Test
    fun `带特殊字符的 ID 非法`() {
        assertFalse(EngageSchedule.isValidTopicId("100808abc&topic_id=1"))
        assertFalse(EngageSchedule.isValidTopicId("../etc/passwd"))
    }

    // ---------- 日期标签 ----------

    @Test
    fun `dayKey 使用短横线零填充格式`() {
        assertEquals("2026-11-16", EngageSchedule.dayKey(at(16, 10), zone))
    }

    @Test
    fun `同日不同时刻的 dayKey 相同`() {
        assertEquals(EngageSchedule.dayKey(at(16, 0, 1), zone), EngageSchedule.dayKey(at(16, 23, 59), zone))
    }

    @Test
    fun `时间标签为两位时分`() {
        assertEquals("09:05", EngageSchedule.clockLabel(at(16, 9, 5), zone))
    }

    // ---------- 文案禁用日期 ----------

    @Test
    fun `不设间隔时只禁用今天`() {
        assertEquals(setOf("2026-11-16"), EngageSchedule.forbiddenTextDays("2026-11-16", 0))
    }

    @Test
    fun `设 3 天间隔时禁用今天与前两天`() {
        assertEquals(
            setOf("2026-11-16", "2026-11-15", "2026-11-14"),
            EngageSchedule.forbiddenTextDays("2026-11-16", 3),
        )
    }

    @Test
    fun `跨月回退正确`() {
        assertEquals(
            setOf("2026-12-01", "2026-11-30"),
            EngageSchedule.forbiddenTextDays("2026-12-01", 2),
        )
    }

    // ---------- 免打扰 ----------

    @Test
    fun `默认零点到七点为免打扰`() {
        val cfg = config()
        assertTrue(EngageSchedule.isQuietHour(0, cfg))
        assertTrue(EngageSchedule.isQuietHour(3, cfg))
        assertFalse(EngageSchedule.isQuietHour(7, cfg))
        assertFalse(EngageSchedule.isQuietHour(12, cfg))
    }

    @Test
    fun `跨夜时段也生效`() {
        val cfg = config(quietStart = 23, quietEnd = 7)
        assertTrue(EngageSchedule.isQuietHour(23, cfg))
        assertTrue(EngageSchedule.isQuietHour(2, cfg))
        assertFalse(EngageSchedule.isQuietHour(12, cfg))
    }

    @Test
    fun `起止相同表示不免打扰`() {
        assertFalse(EngageSchedule.isQuietHour(3, config(quietStart = 5, quietEnd = 5)))
    }

    // ---------- 今日是否已跑 ----------

    @Test
    fun `今天跑过能被识别`() {
        assertTrue(EngageSchedule.ranToday(at(16, 9), at(16, 20), zone))
    }

    @Test
    fun `昨天跑过不算今天`() {
        assertFalse(EngageSchedule.ranToday(at(15, 9), at(16, 20), zone))
    }

    @Test
    fun `从未跑过`() {
        assertFalse(EngageSchedule.ranToday(0L, at(16, 20), zone))
    }

    // ---------- 超话 ID 口径 ----------

    @Test
    fun `hash 会补成完整 containerid`() {
        assertEquals(
            "1008088cf9880c8ec5f07f84c2f30e74d43574",
            EngageSchedule.topicContainerId("8cf9880c8ec5f07f84c2f30e74d43574"),
        )
    }

    @Test
    fun `已经是 containerid 时不会重复加前缀`() {
        val full = "1008088cf9880c8ec5f07f84c2f30e74d43574"
        assertEquals(full, EngageSchedule.topicContainerId(full))
        assertTrue(EngageSchedule.isContainerTopicId(full))
    }

    @Test
    fun `只有 hash 不算 containerid`() {
        assertFalse(EngageSchedule.isContainerTopicId("8cf9880c8ec5f07f84c2f30e74d43574"))
        assertFalse(EngageSchedule.isContainerTopicId(""))
    }
}
