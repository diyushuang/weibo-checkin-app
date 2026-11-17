package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 超话帖子流解析与目标筛选测试。 */
class EngageFeedTest {

    private val sample = """
    {"items":[
      {"category":"cell","type":"span"},
      {"category":"feed","data":{"mid":"1111111111111111","user":{"id":"7303565520"},
        "text":"#棋手战鹰[超话]# 妈妈可爱捏","comments_count":0,"reposts_count":0,"attitudes_count":0}},
      {"category":"feed","data":{"mid":"2222222222222222","user":{"id":"1000000001"},
        "text":"我自己的帖子","comments_count":2,"reposts_count":0,"attitudes_count":1}},
      {"category":"feed","data":{"mid":"3333333333333333","user":{"id":"7710879694"},
        "text":"<a href='x'>带标签</a>正文","comments_count":5,"reposts_count":1,"attitudes_count":6,
        "retweeted_status":{"mid":"999"}}}
    ]}
    """.trimIndent()

    // ---------- 解析 ----------

    @Test
    fun `只解析 category 为 feed 的条目`() {
        val posts = WeiboFeedApi.parseFeed(sample)
        assertEquals(3, posts.size)
    }

    @Test
    fun `中间字段与 HTML 标签被正确清理`() {
        val posts = WeiboFeedApi.parseFeed(sample)
        assertEquals("1111111111111111", posts[0].mid)
        assertEquals("7303565520", posts[0].uid)
        assertEquals(6, posts[2].attitudes)
        assertEquals(1, posts[2].reposts)
        assertEquals("带标签正文", posts[2].text)
    }

    @Test
    fun `无互动帖被识别为冷帖`() {
        val posts = WeiboFeedApi.parseFeed(sample)
        assertTrue(posts[0].isCold)
        assertFalse(posts[2].isCold)
    }

    @Test
    fun `转发帖被标记`() {
        val posts = WeiboFeedApi.parseFeed(sample)
        assertFalse(posts[0].isRetweet)
        assertTrue(posts[2].isRetweet)
    }

    @Test
    fun `非 JSON 返回空列表而不抛异常`() {
        assertTrue(WeiboFeedApi.parseFeed("<html>login</html>").isEmpty())
    }

    @Test
    fun `缺 mid 的条目被丢弃`() {
        val body = """{"items":[{"category":"feed","data":{"text":"没有mid"}}]}"""
        assertTrue(WeiboFeedApi.parseFeed(body).isEmpty())
    }

    // ---------- 目标筛选 ----------

    @Test
    fun `排除自己的帖子与纯转发帖`() {
        val posts = WeiboFeedApi.parseFeed(sample)
        val picked = WeiboFeedApi.pickTargets(posts, selfUid = "1000000001")
        assertEquals(listOf("1111111111111111"), picked.map { it.mid })
    }

    @Test
    fun `已用过的 mid 不再参与`() {
        val posts = WeiboFeedApi.parseFeed(sample)
        val picked = WeiboFeedApi.pickTargets(
            posts, selfUid = "1000000001", usedMids = setOf("1111111111111111"),
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `优先挑无互动帖`() {
        val posts = listOf(
            WeiboFeedApi.FeedPost(mid = "1001", comments = 9, reposts = 3, attitudes = 20),
            WeiboFeedApi.FeedPost(mid = "1002", comments = 0, reposts = 0, attitudes = 0),
        )
        val picked = WeiboFeedApi.pickTargets(posts, selfUid = "", preferFirstComment = true)
        assertEquals(listOf("1002", "1001"), picked.map { it.mid })
    }

    @Test
    fun `关闭首评优先后保持原顺序`() {
        val posts = listOf(
            WeiboFeedApi.FeedPost(mid = "1001", comments = 9),
            WeiboFeedApi.FeedPost(mid = "1002", comments = 0),
        )
        val picked = WeiboFeedApi.pickTargets(posts, selfUid = "", preferFirstComment = false)
        assertEquals(listOf("1001", "1002"), picked.map { it.mid })
    }

    @Test
    fun `limit 生效`() {
        val posts = (1..5).map { WeiboFeedApi.FeedPost(mid = "100$it") }
        assertEquals(2, WeiboFeedApi.pickTargets(posts, selfUid = "", limit = 2).size)
        assertTrue(WeiboFeedApi.pickTargets(posts, selfUid = "", limit = 0).isEmpty())
    }

    @Test
    fun `selfUid 为空时不过滤作者`() {
        val posts = listOf(WeiboFeedApi.FeedPost(mid = "1001", uid = "1000000001"))
        assertEquals(1, WeiboFeedApi.pickTargets(posts, selfUid = "").size)
    }
}
