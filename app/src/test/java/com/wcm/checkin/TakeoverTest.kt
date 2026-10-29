package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 接管去重单测：按大小预筛，只在撞车时算哈希。 */
class TakeoverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun index(): CrawlIndex = CrawlIndex.open(tmp.root, "tag").also { it.load() }

    private val p = "2026-10-22_152712_某某"

    @Test
    fun claimableNameWithoutRecord_isClaimedWithoutHashing() {
        val idx = index()
        var hashCalls = 0
        val report = Takeover.claimAll(
            listOf(Takeover.Candidate("${p}_01.jpg", 1000)),
            idx,
        ) { hashCalls++; "h" }
        assertEquals(1, report.claimed)
        assertEquals(0, report.hashed)
        assertEquals(0, hashCalls)                      // 关键：一次字节都没读
        assertNotNull(idx.byName("${p}_01.jpg"))
    }

    @Test
    fun sameSize_isClaimedWithoutHashing() {
        val idx = index()
        idx.adopt("${p}_01.jpg", 1000)
        var hashCalls = 0
        val report = Takeover.claimAll(listOf(Takeover.Candidate("${p}_01.jpg", 1000)), idx) {
            hashCalls++; "h"
        }
        assertEquals(1, report.claimed)
        assertEquals(0, hashCalls)
    }

    @Test
    fun sizeMismatchButSameHash_isAcceptedAndSizeUpdated() {
        val idx = index()
        idx.adopt("${p}_01.jpg", 1000, "abc")
        var hashCalls = 0
        val report = Takeover.claimAll(listOf(Takeover.Candidate("${p}_01.jpg", 2000)), idx) {
            hashCalls++; "abc"
        }
        assertEquals(1, report.claimed)
        assertEquals(1, hashCalls)
        assertEquals(2000L, idx.byName("${p}_01.jpg")!!.size)
    }

    @Test
    fun sizeMismatchAndHashDiffers_isConflict() {
        val idx = index()
        idx.adopt("${p}_01.jpg", 1000, "abc")
        val report = Takeover.claimAll(listOf(Takeover.Candidate("${p}_01.jpg", 2000)), idx) { "zzz" }
        assertEquals(0, report.claimed)
        assertEquals(1, report.conflict)
        assertEquals(1000L, idx.byName("${p}_01.jpg")!!.size)   // 不覆盖用户文件
    }

    @Test
    fun duplicateSuffixVariant_claimedAsSameLogicalFile() {
        val idx = index()
        idx.adopt("${p}_01.jpg", 1000)
        val report = Takeover.claimAll(listOf(Takeover.Candidate("${p}_01 (1).jpg", 1000)), idx)
        assertEquals(1, report.claimed)
        assertEquals(0, report.hashed)
    }

    @Test
    fun foreignFilesAreIgnored() {
        val idx = index()
        val report = Takeover.claimAll(
            listOf(
                Takeover.Candidate("IMG_0001.jpg", 1000),
                Takeover.Candidate("20260101_120000_作者_01.jpg", 1000),   // 旧错格式
                Takeover.Candidate("", 0),
            ),
            idx,
        )
        assertEquals(3, report.ignored)
        assertEquals(0, report.claimed)
    }

    @Test
    fun movFilesAreClaimable() {
        val idx = index()
        val report = Takeover.claimAll(listOf(Takeover.Candidate("${p}_01_live.mov", 5000)), idx)
        assertEquals(1, report.claimed)
        assertEquals(0, report.ignored)
    }

    @Test
    fun emptyInputIsNoOp() {
        val idx = index()
        val report = Takeover.claimAll(emptyList(), idx)
        assertEquals(0, report.scanned)
        assertEquals(0, report.claimed)
        assertNull(idx.byName("${p}_01.jpg"))
    }

    /** 性能守卫：正常目录里哈希率应远低于 5%。 */
    @Test
    fun hashRateStaysLowOnCleanDirectory() {
        val idx = index()
        val files = (1..1000).map { i ->
            // 同名文件大小必须一致，否则会被判成「size 撞车」而触发哈希
            Takeover.Candidate(String.format("%s_%02d.jpg", p, i % 90 + 1), 1000L + (i % 90))
        }
        var hashCalls = 0
        val report = Takeover.claimAll(files, idx) { hashCalls++; "h" }
        assertEquals("首次接管应零哈希", 0, hashCalls)
        assertEquals(files.size, report.claimed)
    }

    @Test
    fun reportSummaryMentionsCounts() {
        val report = Takeover.Report(scanned = 3, claimed = 2, hashed = 1, conflict = 0, ignored = 1)
        assertTrue(report.summary.contains("2"))
        assertTrue(report.summary.contains("1"))
    }
}
