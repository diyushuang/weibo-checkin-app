package com.wcm.checkin

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器冒烟：启动、四 Tab、Compose 设置页、设置开关持久化。
 *
 * 依赖 UiAutomator 而不是仅 Espresso：Compose 文本不是 View，Espresso 的
 * onView(withText) 看不到；UiAutomator 同时覆盖 View 与 Compose 语义树。
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun tabsAndComposeSettingsRender() {
        device.wait(Until.hasObject(By.text("首页")), 15_000)
        activityRule.scenario.onActivity { it.selectSettingsTab() }
        device.wait(Until.hasObject(By.text("面板同步")), 10_000)
        assertNotNull(device.findObject(By.text("面板同步")))
        assertNotNull(device.findObject(By.text("行为")))
        // 「关于」在首屏下方，滚动后再断言，同时验证页面可滚动
        device.swipe(540, 1800, 540, 800, 20)
        device.wait(Until.hasObject(By.text("关于")), 5_000)
        assertNotNull(device.findObject(By.text("关于")))
    }

    @Test
    fun settingsTogglePersistsToPrefs() {
        device.wait(Until.hasObject(By.text("首页")), 15_000)
        activityRule.scenario.onActivity { it.selectSettingsTab() }
        device.wait(Until.hasObject(By.text("打开 App 即自动签到")), 10_000)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val before = PanelSettings.autoOnOpen(ctx)
        val row = device.findObject(By.text("打开 App 即自动签到"))
        assertNotNull(row)
        row.click()
        device.waitForIdle()
        assertEquals(!before, PanelSettings.autoOnOpen(ctx))
        row.click()
        device.waitForIdle()
        assertEquals(before, PanelSettings.autoOnOpen(ctx))
    }
}