package com.wcm.checkin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超话字段解析单元测试（不触网）。
 *
 * 覆盖：等级解析与分档、scheme 提 ID、containerid 拼接、
 * 列表页 JSON 解析（含 card_group / 非超话卡 / 缺按钮 / 已签 / ok=-100）、
 * 详情页 JSON 解析（含 desc_more 两种形态 / 缺字段）。
 */
class TopicParseTest {

    // ---------- 等级 ----------

    @Test
    fun parseTopicLevel_variants() {
        assertEquals(7, WeiboApi.parseTopicLevel("Lv.7"))
        assertEquals(12, WeiboApi.parseTopicLevel("Lv 12"))
        assertEquals(12, WeiboApi.parseTopicLevel("LV.12"))
        assertEquals(7, WeiboApi.parseTopicLevel("等级7"))
        assertEquals(3, WeiboApi.parseTopicLevel("  Lv.3 · 今日已签"))
        assertEquals(0, WeiboApi.parseTopicLevel(""))
        assertEquals(0, WeiboApi.parseTopicLevel("Lv."))
        assertEquals(0, WeiboApi.parseTopicLevel("暂无"))
    }

    @Test
    fun parseTopicLevel_capsAbsurdValues() {
        assertEquals(999, WeiboApi.parseTopicLevel("Lv.100000"))
    }

    @Test
    fun topicLevelTier_boundaries() {
        assertEquals(0, WeiboApi.topicLevelTier(0))
        assertEquals(0, WeiboApi.topicLevelTier(-1))
        assertEquals(1, WeiboApi.topicLevelTier(1))
        assertEquals(1, WeiboApi.topicLevelTier(3))
        assertEquals(2, WeiboApi.topicLevelTier(4))
        assertEquals(2, WeiboApi.topicLevelTier(6))
        assertEquals(3, WeiboApi.topicLevelTier(7))
        assertEquals(3, WeiboApi.topicLevelTier(9))
        assertEquals(4, WeiboApi.topicLevelTier(10))
        assertEquals(4, WeiboApi.topicLevelTier(12))
    }

    // ---------- 超话 ID ----------

    @Test
    fun extractTopicId_fromScheme() {
        assertEquals(
            "aaa111bbb",
            WeiboApi.extractTopicId("sinaweibo://pageinfo?containerid=100808aaa111bbb&luicode=10000011"),
        )
        assertEquals(
            "deadbeef",
            WeiboApi.extractTopicId("https://m.weibo.cn/p/100808deadbeef/super_index"),
        )
    }

    @Test
    fun extractTopicId_decodesPercentEncoding() {
        assertEquals(
            "ff00aa",
            WeiboApi.extractTopicId("sinaweibo://pageinfo%3Fcontainerid%3D100808ff00aa%26x%3D1"),
        )
    }

    @Test
    fun extractTopicId_missingWhenAbsent() {
        assertEquals("", WeiboApi.extractTopicId(""))
        assertEquals("", WeiboApi.extractTopicId("follow_super_follow_1_0"))
        assertEquals("", WeiboApi.extractTopicId("sinaweibo://pageinfo?containerid=100803_-_followsuper"))
    }

    @Test
    fun topicDetailContainerId_prefixesAndValidates() {
        assertEquals("100808abc123", WeiboApi.topicDetailContainerId("abc123"))
        assertThrows(WeiboApi.WeiboException::class.java) {
            WeiboApi.topicDetailContainerId("abc&page=2")
        }
        assertThrows(WeiboApi.WeiboException::class.java) {
            WeiboApi.topicDetailContainerId("")
        }
    }

    // ---------- 列表页解析 ----------

    private val listPayload = """
        {"ok":1,"data":{"cardlistInfo":{"since_id":"P2"},"cards":[
          {"card_group":[
            {"card_type":"8","title_sub":"王一博","desc1":"Lv.7","desc2":"本周热度上升",
             "scheme":"sinaweibo://pageinfo?containerid=100808aaa111&x=1",
             "buttons":[{"name":"签到","scheme":"/api/container/checkin?a=1"}]},
            {"card_type":"11","title_sub":"不是超话"},
            {"card_type":"8","title_sub":"张三","desc1":"Lv.12",
             "scheme":"https://m.weibo.cn/p/100808bbb222/super_index",
             "buttons":[{"name":"已签","scheme":"/x"}]},
            {"card_type":"8","title_sub":"缺按钮","desc1":"Lv.1","scheme":"/p/100808ccc/d"}
          ]},
          {"card_type":"8","title_sub":"独立卡","desc1":"Lv.3",
           "scheme":"sinaweibo://pageinfo?containerid=100808ccc333",
           "buttons":[{"name":"签到","scheme":"/y"}]}
        ]}}
    """.trimIndent()

    @Test
    fun parseTopicPage_readsAllFields() {
        val page = WeiboApi.parseTopicPage(JSONObject(listPayload))
        assertEquals("1", page.ok)
        assertEquals("P2", page.sinceId)
        // 非超话卡与缺按钮卡被跳过：card_group 里 3 条有效 + 1 条独立卡 = 3 条
        assertEquals(3, page.topics.size)

        val first = page.topics[0]
        assertEquals("王一博", first.name)
        assertEquals("aaa111", first.topicId)
        assertEquals(7, first.level)
        assertEquals("Lv.7", first.levelLabel)
        assertEquals("本周热度上升", first.desc2)
        assertFalse(first.done)
        assertEquals("/api/container/checkin?a=1", first.scheme)
        assertTrue(first.raw.contains("王一博"))

        val second = page.topics[1]
        assertEquals("张三", second.name)
        assertEquals(12, second.level)
        assertTrue(second.done)
        assertNull(second.scheme)   // 已签不再保留签到链接

        val third = page.topics[2]
        assertEquals("独立卡", third.name)
        assertEquals("ccc333", third.topicId)
        assertEquals(3, third.level)
    }

    @Test
    fun parseTopicPage_cookieInvalid() {
        val page = WeiboApi.parseTopicPage(JSONObject("""{"ok":"-100","msg":"请先登录"}"""))
        assertEquals("-100", page.ok)
        assertEquals("请先登录", page.msg)
        assertTrue(page.topics.isEmpty())
    }

    @Test
    fun parseTopicPage_toleratesMissingCards() {
        val page = WeiboApi.parseTopicPage(JSONObject("""{"ok":1,"data":{}}"""))
        assertEquals("1", page.ok)
        assertTrue(page.topics.isEmpty())
        assertEquals("", page.sinceId)
    }

    // ---------- 详情页解析 ----------

    @Test
    fun parseTopicDetail_readsPageInfo() {
        val payload = JSONObject(
            """
            {"ok":1,"data":{"pageInfo":{
              "oid":"100808:aaa111","title_top":"王一博",
              "desc":"这里是简介","desc_more":["关注 12.3万","帖子 4.5万"],
              "portrait_sub_text":"超话"
            }}}
            """.trimIndent()
        )
        val detail = WeiboApi.parseTopicDetail("aaa111", payload, now = 123L)
        assertEquals("aaa111", detail.topicId)
        assertEquals("王一博", detail.title)
        assertEquals("这里是简介", detail.intro)
        assertEquals(listOf("关注 12.3万", "帖子 4.5万"), detail.stats)
        assertEquals("超话", detail.portraitText)
        assertEquals("100808:aaa111", detail.oid)
        assertEquals(123L, detail.fetchedAt)
    }

    @Test
    fun parseTopicDetail_handlesObjectDescMore() {
        val payload = JSONObject(
            """{"ok":1,"data":{"pageInfo":{"desc_more":[{"name":"关注","value":"1万"}]}}}"""
        )
        val detail = WeiboApi.parseTopicDetail("x", payload)
        assertEquals(listOf("关注 1万"), detail.stats)
    }

    @Test
    fun parseTopicDetail_toleratesMissingPageInfo() {
        val detail = WeiboApi.parseTopicDetail("x", JSONObject("""{"ok":1,"data":{}}"""))
        assertEquals("", detail.title)
        assertEquals("", detail.intro)
        assertTrue(detail.stats.isEmpty())
    }
}
