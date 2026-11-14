package com.wcm.checkin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 超话 ViewModel 的筛选逻辑（不启动 Activity，避免后台签到污染账号表）。
 *
 * 用假账号 + 本地快照驱动，结束后恢复账号表与快照。
 */
@RunWith(AndroidJUnit4::class)
class TopicStateSmokeTest {

    companion object {
        private const val TEST_UID = "test-topic-uid"

        /**
         * 兜底清理：若同一进程里其它测试启动的页面在恢复账号表后又把它写回，
         * 这里最后再移除一次测试账号，避免污染后续测试/真机。
         */
        @AfterClass
        @JvmStatic
        fun cleanTestAccount() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            Thread.sleep(1_500)
            val accounts = AccountStore.load(ctx)
            if (accounts.any { it.uid == TEST_UID }) {
                AccountStore.save(ctx, accounts.filterNot { it.uid == TEST_UID })
            }
            TopicStore.clear(ctx, TEST_UID)
        }
    }

    @Test
    fun doneFilterOnlyShowsDoneTopics() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = "test-topic-uid"
        val oldAccounts = AccountStore.load(ctx)
        val oldSnap = TopicStore.snapshot(ctx, uid)
        val now = System.currentTimeMillis()
        try {
            AccountStore.save(
                ctx,
                listOf(
                    AccountStore.Account(
                        uid = uid,
                        name = "测试账号",
                        cookie = "SUB=test",
                    ),
                ),
            )
            TopicStore.save(
                ctx,
                uid,
                listOf(
                    WeiboTopicApi.Topic(name = "测试超话", scheme = null, done = true, level = 7),
                    WeiboTopicApi.Topic(name = "第二个超话", scheme = null, done = false, level = 3),
                ),
                at = now,
            )

            val vm = TopicViewModel(ctx)
            vm.selectAccount(uid)
            vm.setFilter(TopicFilter.DONE)
            val done = vm.state.value
            assertTrue(done.rows.isNotEmpty())
            assertTrue(done.rows.all { it.done })

            vm.setFilter(TopicFilter.ALL)
            assertEquals(2, vm.state.value.rows.size)
        } finally {
            AccountStore.save(ctx, oldAccounts)
            if (oldSnap != null) {
                TopicStore.save(ctx, uid, oldSnap.topics, at = oldSnap.at)
            } else {
                TopicStore.clear(ctx, uid)
            }
        }
    }
}