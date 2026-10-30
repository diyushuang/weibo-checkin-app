package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 索引层单测：往返、last-wins、损坏容错、compact、删图回退、重试退避、旧数据迁移。 */
class CrawlIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun open(tag: String = "deadbeef"): CrawlIndex {
        val idx = CrawlIndex.open(tmp.root, tag)
        idx.load()
        return idx
    }

    private fun fileOf(tag: String = "deadbeef"): File =
        File(File(tmp.root, "crawl/index"), "$tag.ndjson")

    // ---------- 往返 ----------

    @Test
    fun roundTrip_postsAndFiles() {
        val idx = open()
        idx.onFileSaved("p1", 0, CrawlIndex.Kind.STILL, "picA", "h1", 100L, "a_01.jpg")
        idx.onFileSaved("p1", 2, CrawlIndex.Kind.STILL, "picB", "h2", 200L, "a_02.jpg")
        idx.onPostSealed("p1", 1000L, 2, savedAny = true, failed = emptySet())
        idx.lockLabel("u1", "某某")
        idx.close()

        val re = open()
        assertEquals(2, re.fileCount())
        assertEquals(1, re.postCount())
        assertTrue(re.isTerminal("p1"))
        assertEquals(2, re.expectedSlots("p1"))
        assertEquals("某某", re.labelOf("u1"))
        assertNotNull(re.picOwner("picA"))
        assertEquals(100L, re.picOwner("picA")!!.size)
        assertEquals("a_01.jpg", re.byName("a_01.jpg")!!.name)
    }

    @Test
    fun lastWins_postStatusOverwritten() {
        val idx = open()
        idx.onPostSealed("p1", 10L, 3, savedAny = false, failed = setOf(0, 1, 2))
        assertFalse(idx.isTerminal("p1"))
        assertEquals(setOf(0, 1, 2), idx.failedSlots("p1"))

        idx.onPostSealed("p1", 10L, 3, savedAny = true, failed = emptySet())
        assertTrue(idx.isTerminal("p1"))
        assertTrue(idx.failedSlots("p1").isEmpty())

        idx.close()
        val re = open()
        assertTrue(re.isTerminal("p1"))
    }

    // ---------- 容错 ----------

    @Test
    fun corruptLines_areSkipped() {
        val idx = open()
        idx.onPostSealed("p1", 10L, 1, savedAny = true, failed = emptySet())
        idx.close()

        fileOf().appendText("{not json}\n{\"t\":\"zzz\"}\n")
        val re = open()
        assertEquals(1, re.postCount())
        assertTrue(re.isTerminal("p1"))
    }

    @Test
    fun truncatedLastLine_isDiscarded() {
        val idx = open()
        idx.onPostSealed("p1", 10L, 1, savedAny = true, failed = emptySet())
        idx.close()

        // 模拟断电：最后一行只写了一半
        fileOf().appendText("{\"t\":\"p\",\"i\":\"p2\",\"c\":2")
        val re = open()
        assertEquals(1, re.postCount())
        assertNull(re.post("p2"))
    }

    @Test
    fun staleCompactTempFile_isRemoved() {
        val idx = open()
        idx.onPostSealed("p1", 10L, 1, savedAny = true, failed = emptySet())
        idx.close()

        File(File(tmp.root, "crawl/index"), "deadbeef.ndjson.new").writeText("garbage")
        val re = open()
        assertFalse(File(File(tmp.root, "crawl/index"), "deadbeef.ndjson.new").exists())
        assertEquals(1, re.postCount())
    }

    // ---------- compact ----------

    @Test
    fun compact_keepsSemanticsAndShrinks() {
        val idx = open()
        repeat(50) { i ->
            idx.onPostSealed("p$i", (i + 1) * 1000L, 2, savedAny = true, failed = emptySet())
        }
        idx.onFileSaved("p0", 0, CrawlIndex.Kind.STILL, "pic0", "h0", 10L, "n0.jpg")
        idx.close()
        val before = fileOf().length()

        val re = open()
        assertTrue(re.compact())
        val after = fileOf().length()
        assertTrue("compact 后应变小：$before → $after", after <= before)

        val again = open()
        assertEquals(50, again.postCount())
        assertEquals(1, again.fileCount())
        assertTrue(again.isTerminal("p0"))
    }

    @Test
    fun compact_dropsPostsOlderThanSinceDate() {
        val idx = open()
        val old = CrawlDate.dayStartSec("2020-01-01")!!
        val fresh = CrawlDate.dayStartSec("2026-01-01")!!
        idx.onPostSealed("old", old, 1, savedAny = true, failed = emptySet())
        idx.onPostSealed("new", fresh, 1, savedAny = true, failed = emptySet())
        assertTrue(idx.compact(sinceDate = "2024-01-01"))
        idx.close()

        val re = open()
        assertEquals(1, re.postCount())
        assertNull(re.post("old"))
        assertNotNull(re.post("new"))
    }

    // ---------- 删图回退 ----------

    @Test
    fun onFileDeleted_rollsPostBackToPartial() {
        val idx = open()
        idx.onFileSaved("p1", 0, CrawlIndex.Kind.STILL, "picA", "h1", 10L, "a_01.jpg")
        idx.onFileSaved("p1", 2, CrawlIndex.Kind.STILL, "picB", "h2", 20L, "a_02.jpg")
        idx.onPostSealed("p1", 10L, 2, savedAny = true, failed = emptySet())
        assertTrue(idx.isTerminal("p1"))

        assertTrue(idx.onFileDeleted("a_01.jpg"))
        assertFalse(idx.isTerminal("p1"))
        assertEquals(setOf(0), idx.failedSlots("p1"))
        assertNull(idx.picOwner("picA"))
        assertNotNull(idx.picOwner("picB"))
    }

    @Test
    fun onFileDeleted_allFilesGoesOpen() {
        val idx = open()
        idx.onFileSaved("p1", 0, CrawlIndex.Kind.STILL, "picA", "h1", 10L, "a_01.jpg")
        idx.onPostSealed("p1", 10L, 1, savedAny = true, failed = emptySet())
        idx.onFileDeleted("a_01.jpg")
        val p = idx.post("p1")!!
        assertEquals(CrawlIndex.Status.OPEN, p.status)
    }

    @Test
    fun onFileDeleted_unknownNameOnlyWritesTombstone() {
        val idx = open()
        assertFalse(idx.onFileDeleted("IMG_0001.jpg"))
        assertFalse(idx.onFileDeleted(""))
        idx.close()
        val re = open()
        // 墓碑不制造假记录
        assertEquals(0, re.fileCount())
        assertEquals(0, re.postCount())
    }

    @Test
    fun onFileDeleted_matchesCanonicalName() {
        val idx = open()
        idx.onFileSaved("p1", 0, CrawlIndex.Kind.STILL, "picA", "h1", 10L, "a_01.jpg")
        idx.onPostSealed("p1", 10L, 1, savedAny = true, failed = emptySet())
        // 用户删的是 SAF 撞名副本 "a_01 (1).jpg"，也要正确回退
        assertTrue(idx.onFileDeleted("a_01 (1).jpg"))
        assertFalse(idx.isTerminal("p1"))
    }

    // ---------- 重试 ----------

    @Test
    fun pendingRetries_respectsBackoff() {
        val idx = open()
        idx.onPostSealed("p1", 10L, 3, savedAny = true, failed = setOf(1))
        // 刚失败：退避未到
        assertTrue(idx.pendingRetries(System.currentTimeMillis()).isEmpty())
        // 一天后：可重试
        val later = System.currentTimeMillis() + 25L * 3600_000L
        assertEquals(listOf("p1"), idx.pendingRetries(later).map { it.postId })
    }

    @Test
    fun pendingRetries_ignoresVoidAndDone() {
        val idx = open()
        idx.onVoid("v1", 5L)
        idx.onPostSealed("d1", 6L, 1, savedAny = true, failed = emptySet())
        assertTrue(idx.pendingRetries(System.currentTimeMillis() + 100_000_000L).isEmpty())
    }

    // ---------- 迁移 ----------

    @Test
    fun migrateLegacy_idempotent() {
        val legacy = mapOf("u1" to mapOf("p1" to 3, "p2" to 1))
        val idx = open()
        assertEquals(2, idx.migrateLegacy(legacy))
        assertTrue(idx.isTerminal("p1"))
        assertEquals(3, idx.expectedSlots("p1"))

        assertEquals(0, idx.migrateLegacy(legacy))
        idx.close()

        val re = open()
        assertEquals(2, re.postCount())
    }

    // ---------- 命名空间 ----------

    @Test
    fun treeTag_isStableEightHex() {
        val a = CrawlIndex.treeTagOf("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
        val b = CrawlIndex.treeTagOf("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
        assertEquals(a, b)
        assertEquals(8, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertFalse(a == CrawlIndex.treeTagOf("content://other"))
    }

    @Test
    fun differentTags_doNotShareState() {
        val a = open("tagA")
        a.onPostSealed("p1", 10L, 1, savedAny = true, failed = emptySet())
        a.close()
        val b = open("tagB")
        assertFalse(b.isTerminal("p1"))   // 换目录后不得继承进度
    }

    @Test
    fun prune_keepsOnlyGivenTags() {
        open("tagA").apply { onVoid("p", 1L); close() }
        open("tagB").apply { onVoid("p", 1L); close() }
        open("tagC").apply { onVoid("p", 1L); close() }
        CrawlIndex.prune(tmp.root, setOf("tagA", "tagC"))
        val dir = File(tmp.root, "crawl/index")
        // 一个 tag 不止一个文件（`.ndjson` 正文 + `.retry` 待重试提示，v0.9.9 起），
        // 所以只断言「剩下哪些 tag」，不绑定具体后缀，免得再加旁挂文件又炸一次。
        assertEquals(setOf("tagA", "tagC"), dir.list()!!.map { it.substringBefore('.') }.toSet())
    }
}
