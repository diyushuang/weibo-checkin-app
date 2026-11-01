package com.wcm.checkin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 勾选抓取（v0.9.11）的纯函数口径：
 *
 * 1. [Crawler.slotsForPics]：picId → 槽位映射。槽位编号是「勾选抓取」与增量抓取
 *    在同一文件名上相遇的前提，编错号 = 同一张图落两份文件。
 * 2. [WeiboApi.sanitizeThumbUrl] / [WeiboApi.parseThumbUrls]：浏览页缩略图的
 *    安全口径（https 强制 + 域名白名单）。
 */
class PickCrawlTest {

    // ---------- slotsForPics ----------

    private fun assets(
        picIds: List<String>,
        liveIdx: Set<Int> = emptySet(),
    ): Crawler.PostAssets {
        val live = liveIdx.associateWith { "https://video.weibo.com/media/play?livephoto=x" }
        return Crawler.PostAssets(picIds, live, emptyList())
    }

    @Test
    fun `picked still images map to even slots`() {
        val a = assets(listOf("p1", "p2", "p3"))
        assertEquals(setOf(0, 4), Crawler.slotsForPics(a, setOf("p1", "p3")))
    }

    @Test
    fun `picked live image maps to odd paired slot`() {
        val a = assets(listOf("p1", "p2", "p3"), liveIdx = setOf(1))
        // p2 是 Live：选中它只落 pairedLiveSlot(1)=3，不产生 stillSlot(1)=2
        assertEquals(setOf(3), Crawler.slotsForPics(a, setOf("p2")))
    }

    @Test
    fun `mixed pick keeps both still and live slots`() {
        val a = assets(listOf("p1", "p2", "p3"), liveIdx = setOf(1))
        assertEquals(setOf(0, 3, 4), Crawler.slotsForPics(a, setOf("p1", "p2", "p3")))
    }

    @Test
    fun `unknown picIds produce no slots`() {
        val a = assets(listOf("p1", "p2"))
        assertTrue(Crawler.slotsForPics(a, setOf("gone", "pX"))!!.isEmpty())
    }

    @Test
    fun `empty pick is empty set not null`() {
        val a = assets(listOf("p1"))
        assertTrue(Crawler.slotsForPics(a, emptySet())!!.isEmpty())
    }

    @Test
    fun `null pick means whole post`() {
        val a = assets(listOf("p1", "p2"), liveIdx = setOf(0))
        assertNull(Crawler.slotsForPics(a, null))
    }

    @Test
    fun `slot numbering matches incremental crawl`() {
        // 槽位口径必须与增量抓取共用：still i*2、paired i*2+1（Crawler.kt 槽位编码注释）。
        val a = assets(listOf("a", "b", "c", "d"), liveIdx = setOf(2))
        assertEquals(Crawler.stillSlot(0), 0)
        assertEquals(Crawler.stillSlot(2), 4)
        assertEquals(Crawler.pairedLiveSlot(2), 5)
        assertEquals(setOf(5), Crawler.slotsForPics(a, setOf("c")))
    }

    // ---------- notPickedSlots ----------

    @Test
    fun `first picked crawl marks unpicked slots as pending`() {
        // 只勾了第 1 张（slot 0）：其余槽位必须是「待补」，否则这批帖被判 DONE，
        // 剩下两张以后再也不会被任何一轮抓取碰到。
        assertEquals(
            setOf(2, 4),
            Crawler.notPickedSlots(setOf(0, 2, 4), setOf(0), countNotPicked = true),
        )
    }

    @Test
    fun `retry never marks the complement as pending`() {
        // RETRY 传进来的 onlySlots 就是上一轮的 missing，它的补集是「已经抽到的槽位」。
        // 一旦并进 missing，集合就在 {已抽} / {未抽} 之间永久乒乓。
        assertEquals(
            emptySet<Int>(),
            Crawler.notPickedSlots(setOf(0, 2, 4), setOf(0), countNotPicked = false),
        )
    }

    @Test
    fun `whole post crawl has nothing pending`() {
        assertEquals(emptySet<Int>(), Crawler.notPickedSlots(setOf(0, 1), null, countNotPicked = true))
    }

    @Test
    fun `every slot picked leaves nothing pending`() {
        assertEquals(
            emptySet<Int>(),
            Crawler.notPickedSlots(setOf(0, 2), setOf(0, 2), countNotPicked = true),
        )
    }

    // ---------- retainExistingSlots ----------

    @Test
    fun `stale failed slots are dropped`() {
        // 重试会把上一轮的失败槽位整体灌进 failed；若其中某个槽位在这帖当前详情里
        // 已经不存在（图被删 / 详情截断），它永远不会被尝试 —— 留着就让该帖永远
        // 停在 PARTIAL，每轮 RETRY 白拉一次详情。
        val failed = linkedSetOf(0, 5, 1000)
        Crawler.retainExistingSlots(failed, setOf(0, 2, 4))
        assertEquals(setOf(0), failed)
    }

    @Test
    fun `retain keeps every slot that still exists`() {
        val failed = linkedSetOf(0, 2)
        Crawler.retainExistingSlots(failed, setOf(0, 2, 4))
        assertEquals(setOf(0, 2), failed)
    }

    // ---------- sanitizeThumbUrl ----------

    @Test
    fun `http thumb url is upgraded to https`() {
        assertEquals(
            "https://wx1.sinaimg.cn/orj360/abc.jpg",
            WeiboApi.sanitizeThumbUrl("http://wx1.sinaimg.cn/orj360/abc.jpg"),
        )
    }

    @Test
    fun `https sinaimg url passes through`() {
        val u = "https://wx2.sinaimg.cn/orj360/abc.gif"
        assertEquals(u, WeiboApi.sanitizeThumbUrl(u))
    }

    @Test
    fun `foreign host is rejected`() {
        assertNull(WeiboApi.sanitizeThumbUrl("https://evil.example.com/orj360/abc.jpg"))
    }

    @Test
    fun `empty and garbage are rejected`() {
        assertNull(WeiboApi.sanitizeThumbUrl(""))
        assertNull(WeiboApi.sanitizeThumbUrl("   "))
        assertNull(WeiboApi.sanitizeThumbUrl("not a url"))
    }

    // ---------- parseThumbUrls ----------

    @Test
    fun `pics array with urls is parsed in order`() {
        val mb = JSONObject()
            .put(
                "pics",
                JSONArray()
                    .put(JSONObject().put("pid", "p1").put("url", "https://wx1.sinaimg.cn/orj360/p1.jpg"))
                    .put(JSONObject().put("pid", "p2").put("url", "http://wx1.sinaimg.cn/orj360/p2.jpg")),
            )
        assertEquals(
            listOf(
                "https://wx1.sinaimg.cn/orj360/p1.jpg",
                "https://wx1.sinaimg.cn/orj360/p2.jpg",
            ),
            WeiboApi.parseThumbUrls(mb),
        )
    }

    @Test
    fun `pic without url falls back to pid based thumbnail`() {
        val mb = JSONObject().put(
            "pics",
            JSONArray().put(JSONObject().put("pid", "abc")),
        )
        val thumbs = WeiboApi.parseThumbUrls(mb)
        assertEquals(1, thumbs.size)
        assertTrue(thumbs[0].endsWith("/orj360/abc.jpg"))
        assertTrue(thumbs[0].startsWith("https://wx"))
    }

    @Test
    fun `foreign pic url is dropped not propagated`() {
        val mb = JSONObject().put(
            "pics",
            JSONArray()
                .put(JSONObject().put("url", "https://evil.example.com/x.jpg"))
                .put(JSONObject().put("url", "https://wx1.sinaimg.cn/orj360/ok.jpg")),
        )
        assertEquals(listOf("https://wx1.sinaimg.cn/orj360/ok.jpg"), WeiboApi.parseThumbUrls(mb))
    }

    @Test
    fun `missing pics yields empty list`() {
        assertTrue(WeiboApi.parseThumbUrls(JSONObject()).isEmpty())
        assertFalse(WeiboApi.parseThumbUrls(JSONObject().put("pics", "not-an-array")).contains("x"))
    }
}
