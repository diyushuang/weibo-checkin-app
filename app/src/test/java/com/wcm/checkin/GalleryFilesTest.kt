package com.wcm.checkin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图库纯函数单元测试（不触存储、不碰 android.net.Uri）：过滤口径 / 排序 / 分组 / 搜索。 */
class GalleryFilesTest {

    // ---------- isImage 过滤 ----------

    @Test
    fun isImage_byMime() {
        assertTrue(GalleryFiles.isImage("随便", "image/jpeg"))
        assertTrue(GalleryFiles.isImage("随便", "IMAGE/GIF"))
        assertFalse(GalleryFiles.isImage("x.mov", "video/quicktime"))
        assertFalse(GalleryFiles.isImage("doc", "application/pdf"))
    }

    @Test
    fun isImage_byExtensionWhenMimeMissing() {
        assertTrue(GalleryFiles.isImage("20260101_120000_作者_01.jpg", null))
        assertTrue(GalleryFiles.isImage("a.JPEG", ""))
        assertTrue(GalleryFiles.isImage("a.GIF", "application/octet-stream"))
        assertFalse(GalleryFiles.isImage("a.mov", null))
        assertFalse(GalleryFiles.isImage("noext", "application/octet-stream"))
    }

    @Test
    fun isImage_clearNonImageMimeWinsOverExtension() {
        // 有明确非图片 mime 时不看扩展名（x.jpg 伪装的文本不算图）
        assertFalse(GalleryFiles.isImage("evil.jpg", "text/plain"))
    }

    // ---------- 排序 ----------

    @Test
    fun sortNames_newestFirst() {
        val sorted = GalleryFiles.sortNames(
            listOf(
                "20260101_090000_甲_01.jpg",
                "20261025_120000_乙_01.jpg",
                "20260303_080000_丙_01.jpg",
            )
        )
        assertEquals(
            listOf(
                "20261025_120000_乙_01.jpg",
                "20260303_080000_丙_01.jpg",
                "20260101_090000_甲_01.jpg",
            ),
            sorted,
        )
    }

    @Test
    fun sortNames_empty() {
        assertEquals(emptyList<String>(), GalleryFiles.sortNames(emptyList()))
    }

    // ==================== v0.9.0 浏览侧筛选 / 排序 / 分组 ====================

    /** 按与 index() 相同的口径建一行（不构造 Uri，JVM 单测可直接跑）。 */
    private fun row(name: String, size: Long = 100L): GalleryFiles.Row {
        val parsed = AssetName.parse(name)
        return GalleryFiles.Row(
            uri = "content://test/$name",
            name = name,
            size = size,
            date = parsed?.date?.takeIf { it != "unknown" } ?: "",
            label = parsed?.label ?: "",
            ext = name.substringAfterLast('.', "").lowercase(),
        )
    }

    private val rows = listOf(
        row("2026-10-25_120000_乙_01.jpg", 500),
        row("2026-10-25_090000_乙_01_live.gif", 100),
        row("2026-10-24_200000_甲_01.jpg", 300),
        row("IMG_0001.jpg", 200),
        row("voice.mov", 900),
    )

    @Test
    fun applyFilter_byKind() {
        fun names(kind: GalleryFiles.KindFilter) =
            GalleryFiles.applyFilter(rows, GalleryFiles.Filter(kind = kind)).map { it.name }

        assertEquals(1, names(GalleryFiles.KindFilter.GIF).size)
        assertEquals(listOf("voice.mov"), names(GalleryFiles.KindFilter.MOV))
        // 静图 = 除 gif / mov 之外的一切（含外部图片）
        assertEquals(
            listOf("2026-10-25_120000_乙_01.jpg", "2026-10-24_200000_甲_01.jpg", "IMG_0001.jpg"),
            names(GalleryFiles.KindFilter.STILL),
        )
        assertEquals(5, names(GalleryFiles.KindFilter.ALL).size)
    }

    @Test
    fun applyFilter_byQueryMatchesNameLabelAndDate() {
        fun search(q: String) =
            GalleryFiles.applyFilter(rows, GalleryFiles.Filter(query = q)).map { it.label }

        assertEquals(listOf("甲"), search("甲"))
        assertEquals(2, search("2026-10-25").size)
        assertEquals(1, search("IMG_00").size)      // 外部文件名也能搜到
        assertEquals(1, search("img_00").size)      // 忽略大小写
        assertEquals(5, search("  ").size)          // 纯空白 = 不过滤
    }

    @Test
    fun applyFilter_byLabel() {
        val hit = GalleryFiles.applyFilter(rows, GalleryFiles.Filter(label = "乙"))
        assertEquals(2, hit.size)
        assertTrue(hit.all { it.label == "乙" })
    }

    @Test
    fun sortRows_orders() {
        assertEquals("voice.mov", GalleryFiles.sortRows(rows, GalleryFiles.SortOrder.SIZE_DESC)[0].name)
        assertEquals("2026-10-25_090000_乙_01_live.gif",
            GalleryFiles.sortRows(rows, GalleryFiles.SortOrder.SIZE_ASC)[0].name)
        // 时间排序：最新在前；无日期的外部文件既不冒充最新，也不冒充最旧，统一垫底
        assertEquals("2026-10-25_120000_乙_01.jpg",
            GalleryFiles.sortRows(rows, GalleryFiles.SortOrder.NEWEST)[0].name)
        assertEquals("2026-10-24_200000_甲_01.jpg",
            GalleryFiles.sortRows(rows, GalleryFiles.SortOrder.OLDEST)[0].name)
        assertEquals(
            listOf("IMG_0001.jpg", "voice.mov"),
            GalleryFiles.sortRows(rows, GalleryFiles.SortOrder.NEWEST).takeLast(2).map { it.name }.sorted(),
        )
    }

    @Test
    fun groupByDate_keepsOrderAndPutsUnknownLast() {
        val sorted = GalleryFiles.sortRows(rows, GalleryFiles.SortOrder.NEWEST)
        val groups = GalleryFiles.groupByDate(sorted)
        assertEquals(3, groups.size)
        assertEquals("2026-10-25", groups[0].title)
        assertEquals(2, groups[0].count)
        assertEquals("2026-10-24", groups[1].title)
        // 无日期的外部文件（IMG_0001.jpg / voice.mov）归到同一个「未知」桶，且排最后
        assertEquals(GalleryFiles.UNKNOWN_KEY, groups[2].key)
        assertEquals("", groups[2].title)
        assertEquals(2, groups[2].count)
    }

    @Test
    fun groupByLabel_sortsByCountDescAndUnknownLast() {
        val groups = GalleryFiles.groupByLabel(rows)
        assertEquals("乙", groups[0].title)
        assertEquals(2, groups[0].count)
        assertEquals(GalleryFiles.UNKNOWN_KEY, groups.last().key)
    }

    @Test
    fun labelTabs_skipsUnknownAndSortsByCount() {
        val tabs = GalleryFiles.labelTabs(rows)
        assertEquals(listOf("乙" to 2, "甲" to 1), tabs)
    }

    @Test
    fun summarize_counts() {
        val s = GalleryFiles.summarize(328, 96, 12)
        assertEquals(328, s.total)
        assertEquals(96, s.shown)
        assertEquals(12, s.groups)
    }
}
