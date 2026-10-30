package com.wcm.checkin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相册模式的两处纯解析：
 * - `assetsFromWebStatus`：Web 端扁平 status → 图清单（18 图必须一张不少）；
 * - `cardPicNum`：时间线卡片截断时的粗筛张数（0/18 与 9/18 两种形态都不能漏）。
 */
class AlbumAssetsTest {

    private fun webStatus(
        picIds: List<String>,
        declared: Int = picIds.size,
        livePids: Set<String> = emptySet(),
    ): JSONObject {
        val root = JSONObject()
        root.put("pic_num", declared)
        root.put("pic_ids", JSONArray(picIds))
        val infos = JSONObject()
        picIds.forEach { pid ->
            val info = JSONObject()
            if (pid in livePids) {
                info.put("type", "livephoto")
                info.put("video", "https://f.video.weibocdn.com/$pid.mp4")
            } else {
                info.put("type", "pic")
                info.put("largest", JSONObject().put("url", "https://wx1.sinaimg.cn/large/$pid.jpg"))
            }
            infos.put(pid, info)
        }
        root.put("pic_infos", infos)
        return root
    }

    @Test
    fun `18 图 web 详情一张不少`() {
        val ids = (1..18).map { "001iT40ely1pic$it" }
        val assets = Crawler.assetsFromWebStatus(webStatus(ids))
        assertEquals(18, assets.picIds.size)
        assertEquals(ids, assets.picIds)
        assertEquals(18, assets.expectedFiles)
        assertTrue(assets.liveByIndex.isEmpty())
        // Web 版没有帖级 live_photo，不应该凭空造出额外 Live
        assertTrue(assets.liveExtras.isEmpty())
    }

    @Test
    fun `livephoto 按 pic_ids 下标配对`() {
        val ids = (1..3).map { "pid$it" }
        val assets = Crawler.assetsFromWebStatus(webStatus(ids, livePids = setOf("pid2")))
        assertEquals(3, assets.picIds.size)
        assertEquals(setOf(1), assets.liveByIndex.keys)
        assertEquals("https://f.video.weibocdn.com/pid2.mp4", assets.liveByIndex[1])
    }

    @Test
    fun `pic_ids 里的空串被丢掉`() {
        val assets = Crawler.assetsFromWebStatus(webStatus(listOf("a", "", "b")))
        assertEquals(listOf("a", "b"), assets.picIds)
    }

    @Test
    fun `没有 pic_ids 时不出图`() {
        val root = JSONObject().put("id", "123")
        assertTrue(Crawler.assetsFromWebStatus(root).picIds.isEmpty())
    }

    @Test
    fun `卡片 pic_num 为 0 但 pics 有图时仍算有图`() {
        val mb = JSONObject()
            .put("pic_num", 0)
            .put("pics", JSONArray().put(JSONObject().put("pid", "a")))
        assertEquals(1, WeiboApi.cardPicNum(mb))
    }

    @Test
    fun `卡片 pic_num 为 0 但 pic_ids 有 18 个时取 18`() {
        val ids = JSONArray()
        (1..18).forEach { ids.put("pid$it") }
        val mb = JSONObject().put("pic_num", 0).put("pic_ids", ids)
        assertEquals(18, WeiboApi.cardPicNum(mb))
    }

    @Test
    fun `卡片三处口径不一致时取最大值`() {
        val mb = JSONObject()
            .put("pic_num", 18)
            .put("pics", JSONArray().put(JSONObject()).put(JSONObject()))
        assertEquals(18, WeiboApi.cardPicNum(mb))
    }

    @Test
    fun `纯文字帖仍然是 0`() {
        val mb = JSONObject().put("pic_num", 0)
        assertEquals(0, WeiboApi.cardPicNum(mb))
        assertFalse(mb.has("pics"))
    }
}
