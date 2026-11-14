package com.wcm.checkin

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 超话 Tab 的页面状态（筛选）在切 Tab 后仍保留。
 *
 * 用假账号 + 本地快照驱动，不依赖网络；结束后恢复账号表与快照。
 */
@RunWith(AndroidJUnit4::class)
class TopicStateSmokeTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun doneFilterSurvivesTabSwitch() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = "test-topic-uid"
        val oldAccounts = AccountStore.load(ctx)
        val oldSnap = TopicStore.snapshot(ctx, uid)
        val oldAuto = PanelSettings.autoOnOpen(ctx)
        val now = System.currentTimeMillis()
        try {
            PanelSettings.setAutoOnOpen(ctx, false)
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

            ActivityScenario.launch(MainActivity::class.java).use {
                device.wait(Until.hasObject(By.text("首页")), 15_000)
                device.findObject(By.text("超话")).click()
                device.wait(Until.hasObject(By.text("测试超话")), 10_000)
                assertNotNull(device.findObject(By.text("测试超话")))

                device.findObject(By.text("已签")).click()
                device.wait(Until.gone(By.text("第二个超话")), 5_000)

                device.findObject(By.text("首页")).click()
                device.wait(Until.hasObject(By.text("立即签到")), 10_000)
                device.findObject(By.text("超话")).click()
                device.wait(Until.hasObject(By.text("测试超话")), 10_000)
                assertNotNull(device.findObject(By.text("测试超话")))
                assertTrue(device.findObjects(By.text("第二个超话")).isEmpty())
            }
        } finally {
            PanelSettings.setAutoOnOpen(ctx, oldAuto)
            AccountStore.save(ctx, oldAccounts)
            if (oldSnap != null) {
                TopicStore.save(ctx, uid, oldSnap.topics, at = oldSnap.at)
            } else {
                TopicStore.clear(ctx, uid)
            }
        }
    }
}