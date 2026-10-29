package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 游标与日期工具单测：+0800/UTC 边界、覆盖区间单调性、起始日期语义。 */
class CrawlCursorTest {

    // ---------- 日期工具 ----------

    @Test
    fun epochOfMidnightEqualsDayStart() {
        val d = "2026-10-22"
        assertEquals(CrawlDate.dayStartSec(d), CrawlDate.epochSecOf(d, "00:00:00"))
    }

    @Test
    fun dateOf_roundTripsInBeijingZone() {
        val d = "2024-03-01"
        assertEquals(d, CrawlDate.dateOf(CrawlDate.dayStartSec(d)!!))
    }

    @Test
    fun dayStart_usesPlusEight() {
        // 该日 +0800 零点 = UTC 同日零点往前 8 小时
        val d = "2024-01-01"
        assertEquals(
            CrawlDate.toPickerMillis(d)!! / 1000 - 8 * 3600L,
            CrawlDate.dayStartSec(d)!!,
        )
    }

    @Test
    fun pickerMillis_roundTrips() {
        val d = "2024-01-01"
        val ms = CrawlDate.toPickerMillis(d)
        assertNotNull(ms)
        assertEquals(d, CrawlDate.fromPickerMillis(ms!!))
    }

    @Test
    fun invalidDatesAreRejectedWithoutThrowing() {
        assertNull(CrawlDate.dayStartSec(""))
        assertNull(CrawlDate.dayStartSec("2026-13-40"))
        assertTrue(CrawlDate.isValid(""))
        assertTrue(CrawlDate.isValid("2026-10-22"))
        assertFalse(CrawlDate.isValid("20261022"))
    }

    // ---------- 序列化 ----------

    @Test
    fun json_roundTrip() {
        val c = CrawlCursor(
            uid = "u1", newestAt = 3000, oldestCoveredAt = 1000,
            anchorPostId = "p9", anchorAt = 1000, anchorPageHint = 7,
            reachedBottom = true, historyParked = true,
        )
        val back = CrawlCursor.fromJson(c.toJson())
        assertEquals(c, back)
    }

    @Test
    fun json_missingFieldsFallBackToDefaults() {
        val back = CrawlCursor.fromJson(org.json.JSONObject())
        assertEquals(CrawlCursor(), back)
    }

    // ---------- 起始日期 ----------

    @Test
    fun sinceDate_pickingNewerDateParksHistory() {
        val c = CrawlCursor(oldestCoveredAt = CrawlDate.dayStartSec("2025-01-01")!!, newestAt = 9_999_999_999)
        val out = CursorLogic.onSinceDateChanged(c, "2026-01-01")
        assertTrue(out.historyParked)
        assertEquals(c.oldestCoveredAt, out.oldestCoveredAt)   // 物理事实不变
        assertEquals(c.newestAt, out.newestAt)
    }

    @Test
    fun sinceDate_pickingOlderDateUnparksHistory() {
        val c = CrawlCursor(
            oldestCoveredAt = CrawlDate.dayStartSec("2025-01-01")!!,
            newestAt = 9_999_999_999,
            historyParked = true,
        )
        val out = CursorLogic.onSinceDateChanged(c, "2024-01-01")
        assertFalse(out.historyParked)
    }

    @Test
    fun sinceDate_reachedBottomStaysParked() {
        val c = CrawlCursor(oldestCoveredAt = 1000, newestAt = 2000, reachedBottom = true)
        assertTrue(CursorLogic.onSinceDateChanged(c, "2025-01-01").historyParked)
    }

    // ---------- 覆盖区间单调性 ----------

    @Test
    fun afterNewPhase_setsBothBoundsOnFirstRun() {
        val out = CursorLogic.afterNewPhase(CrawlCursor(uid = "u"), coverageHigh = 2000, coverageLow = 1000)
        assertEquals(2000L, out.newestAt)
        assertEquals(1000L, out.oldestCoveredAt)
    }

    @Test
    fun afterNewPhase_upperBoundOnlyGrowsLowerBoundOnlyShrinks() {
        val base = CrawlCursor(newestAt = 2000, oldestCoveredAt = 1000)
        val out = CursorLogic.afterNewPhase(base, coverageHigh = 3000, coverageLow = 1500)
        assertEquals(3000L, out.newestAt)          // 上端抬升
        assertEquals(1000L, out.oldestCoveredAt)   // 下端不能因为 Phase A 反而变浅
    }

    @Test
    fun afterNewPhase_ignoresEmptyCoverage() {
        val base = CrawlCursor(newestAt = 2000, oldestCoveredAt = 1000)
        assertEquals(base, CursorLogic.afterNewPhase(base, coverageHigh = 0, coverageLow = 0))
    }

    @Test
    fun afterHistoryPhase_advancesAnchorAndLowerBound() {
        val base = CrawlCursor(newestAt = 5000, oldestCoveredAt = 5000, anchorAt = 5000, anchorPostId = "a")
        val out = CursorLogic.afterHistoryPhase(
            base, coverageLow = 3000, anchorPostId = "z", anchorAt = 3000, hintPage = 12,
        )
        assertEquals(3000L, out.oldestCoveredAt)
        assertEquals("z", out.anchorPostId)
        assertEquals(3000L, out.anchorAt)
        assertEquals(12, out.anchorPageHint)
    }

    @Test
    fun afterHistoryPhase_keepsPreviousAnchorWhenNothingNew() {
        val base = CrawlCursor(newestAt = 5000, oldestCoveredAt = 5000, anchorAt = 4000, anchorPostId = "a")
        val out = CursorLogic.afterHistoryPhase(
            base, coverageLow = 0, anchorPostId = "", anchorAt = 0, hintPage = 1, reachedBottom = true,
        )
        assertEquals("a", out.anchorPostId)
        assertEquals(4000L, out.anchorAt)
        assertTrue(out.reachedBottom)
    }

    @Test
    fun coverage_overlapCheck() {
        val c = Coverage(oldestAt = 1000, newestAt = 2000)
        assertTrue(c.overlaps(1500))
        assertTrue(c.overlaps(1000))
        assertTrue(c.overlaps(2000))
        assertFalse(c.overlaps(999))
        assertTrue(Coverage(0, 0).isEmpty)
    }

    @Test
    fun hasCoverage_flag() {
        assertFalse(CrawlCursor().hasCoverage)
        assertTrue(CrawlCursor(newestAt = 1).hasCoverage)
        assertTrue(CrawlCursor(oldestCoveredAt = 1).hasCoverage)
    }
}
