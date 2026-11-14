package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTest {

    private fun topic(name: String, done: Boolean) = WeiboTopicApi.Topic(
        name = name,
        scheme = "https://m.weibo.cn/p/100808$name",
        done = done,
    )

    @Test
    fun setTopicStateReplacesSnapshotInsteadOfMutatingOldOne() {
        val vm = HomeTopicStates()
        assertTrue(vm.states.value.isEmpty())

        vm.set(
            "u1",
            TopicState(
                topics = listOf(topic("a", true), topic("b", false)),
                atMs = 1L,
            ),
        )
        val first = vm.states.value
        assertEquals(1, first.size)
        assertEquals(2, first.getValue("u1").total)
        assertEquals(1, first.getValue("u1").done)

        vm.set("u2", TopicState())
        val second = vm.states.value

        // 旧快照必须保持不变：UI 线程可能正在遍历它
        assertNotSame(first, second)
        assertFalse(first.containsKey("u2"))
        assertTrue(second.containsKey("u1"))
        assertTrue(second.containsKey("u2"))
    }

    @Test
    fun removeTopicStateOnlyRemovesTargetUid() {
        val vm = HomeTopicStates()
        vm.set("u1", TopicState(topics = listOf(topic("a", false))))
        vm.set("u2", TopicState(topics = listOf(topic("b", true))))

        vm.remove("u1")

        val state = vm.states.value
        assertFalse(state.containsKey("u1"))
        assertTrue(state.containsKey("u2"))
        assertEquals(1, state.getValue("u2").total)
    }

    @Test
    fun missingUidReadsAsNull() {
        val vm = HomeTopicStates()
        assertNull(vm.states.value["nope"])
    }
}
