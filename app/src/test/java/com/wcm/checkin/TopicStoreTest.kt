package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TopicStore 纯函数测试（不依赖 Android）：快照/详情序列化往返、坏数据降级、新鲜期、孤儿键。 */
class TopicStoreTest {

    private val topics = listOf(
        WeiboApi.Topic(
            name = "王一博",
            scheme = null,
            done = true,
            topicId = "aaa111",
            level = 7,
            desc2 = "本周热度上升",
        ),
        WeiboApi.Topic(name = "无 ID 超话", scheme = null, done = false, topicId = "", level = 0),
    )

    @Test
    fun snapshot_roundtrip() {
        val snap = TopicStore.Snapshot(uid = "1234567", at = 1_700_000_000_000L, topics = topics)
        val restored = TopicStore.deserializeSnapshot("1234567", TopicStore.serializeSnapshot(snap))
        assertEquals(snap, restored)
    }

    @Test
    fun snapshot_dropsSchemeButKeepsRest() {
        // 即使调用方传了 scheme，落盘再读回也必须是 null（签到链接会过期）
        val withScheme = listOf(
            WeiboApi.Topic(name = "A", scheme = "/sign?a=1", done = false, topicId = "x", level = 3)
        )
        val raw = TopicStore.serializeSnapshot(TopicStore.Snapshot("u", 1L, withScheme))
        val restored = TopicStore.deserializeSnapshot("u", raw)!!
        assertNull(restored.topics[0].scheme)
        assertEquals("A", restored.topics[0].name)
        assertEquals(3, restored.topics[0].level)
        assertFalse(restored.topics[0].done)
    }

    @Test
    fun snapshot_countsAndMaxLevel() {
        val snap = TopicStore.Snapshot(uid = "u", at = 1L, topics = topics)
        assertEquals(2, snap.total)
        assertEquals(1, snap.done)
        assertEquals(1, snap.pending)
        assertEquals(7, snap.maxLevel)
        assertEquals(0, TopicStore.Snapshot(uid = "u").maxLevel)
    }

    @Test
    fun snapshot_badInputReturnsNull() {
        assertNull(TopicStore.deserializeSnapshot("u", ""))
        assertNull(TopicStore.deserializeSnapshot("u", "not json"))
        assertNull(TopicStore.deserializeSnapshot("u", """{"v":99,"topics":[]}"""))
    }

    @Test
    fun snapshot_skipsNamelessTopics() {
        val raw = """{"v":1,"at":5,"error":"","topics":[{"id":"a","name":"","lv":1},{"id":"b","name":"B"}]}"""
        val snap = TopicStore.deserializeSnapshot("u", raw)!!
        assertEquals(1, snap.topics.size)
        assertEquals("B", snap.topics[0].name)
        assertEquals(5L, snap.at)
    }

    @Test
    fun detail_roundtrip() {
        val d = WeiboApi.TopicDetail(
            topicId = "aaa111",
            title = "王一博",
            intro = "这里是简介",
            stats = listOf("关注 12.3万", "帖子 4.5万"),
            portraitText = "超话",
            oid = "100808:aaa111",
            fetchedAt = 1234L,
        )
        assertEquals(d, TopicStore.deserializeDetail("aaa111", TopicStore.serializeDetail(d)))
    }

    @Test
    fun detail_badInputReturnsNull() {
        assertNull(TopicStore.deserializeDetail("x", ""))
        assertNull(TopicStore.deserializeDetail("x", "{oops"))
        assertNull(TopicStore.deserializeDetail("x", """{"v":0}"""))
    }

    @Test
    fun isFresh_respectsTtl() {
        assertTrue(TopicStore.isFresh(1_000L, now = 2_000L, ttlMs = 5_000L))
        assertFalse(TopicStore.isFresh(1_000L, now = 9_000L, ttlMs = 5_000L))
        assertFalse(TopicStore.isFresh(0L, now = 2_000L, ttlMs = 5_000L))
        assertFalse(TopicStore.isFresh(5_000L, now = 1_000L, ttlMs = 5_000L))   // 时钟回拨
    }

    @Test
    fun orphanKeys_returnsRemovedAccounts() {
        assertEquals(
            setOf("b"),
            TopicStore.orphanKeys(setOf("a", "b"), setOf("a", "c")),
        )
        assertTrue(TopicStore.orphanKeys(emptySet(), setOf("a")).isEmpty())
    }
}
