package com.wcm.checkin

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun shellNavigatesTabsAndBrowsingRoute() {
        device.wait(Until.hasObject(By.text("首页")), 15_000)
        device.findObject(By.text("超话")).click()
        device.wait(Until.hasObject(By.text("全部")), 10_000)
        assertNotNull(device.findObject(By.text("全部")))

        device.findObject(By.text("图片")).click()
        device.wait(Until.hasObject(By.text("图库预览")), 10_000)
        assertNotNull(device.findObject(By.text("图库预览")))

        device.swipe(540, 1800, 540, 900, 20)
        device.wait(Until.hasObject(By.text("浏览博主抓图（预览 · 选图 · 批量）")), 5_000)
        device.findObject(By.text("浏览博主抓图（预览 · 选图 · 批量）")).click()
        device.wait(Until.hasObject(By.text("浏览博主")), 10_000)
        assertNotNull(device.findObject(By.text("浏览博主")))
        // 叶子路由隐藏底栏：这里不应再出现「设置」Tab
        assertTrue(device.findObjects(By.text("设置")).isEmpty())

        device.pressBack()
        device.wait(Until.hasObject(By.text("浏览博主抓图（预览 · 选图 · 批量）")), 10_000)
        assertNotNull(device.findObject(By.text("浏览博主抓图（预览 · 选图 · 批量）")))
        assertNotNull(device.findObject(By.text("设置")))
    }


    @Test
    fun settingsRejectsPlainHttpPanel() {
        device.wait(Until.hasObject(By.text("首页")), 15_000)
        activityRule.scenario.onActivity { it.selectSettingsTab() }
        device.wait(Until.hasObject(By.text("保存")), 10_000)
        val field = device.findObject(By.clazz("android.widget.EditText"))
        assertNotNull(field)
        field.text = "http://example.com"
        device.waitForIdle()
        device.wait(Until.hasObject(By.text("保存")), 5_000)
        val save = device.findObject(By.text("保存"))
        assertNotNull(save)
        save.click()
        device.wait(Until.hasObject(By.textContains("公网面板必须用 https")), 8_000)
        assertNotNull(device.findObject(By.textContains("公网面板必须用 https")))
    }


    @Test
    fun homeShortcutsNavigate() {
        device.wait(Until.hasObject(By.text("首页")), 15_000)
        device.findObject(By.text("去设置配置面板 ›")).click()
        device.wait(Until.hasObject(By.text("面板同步")), 10_000)
        assertNotNull(device.findObject(By.text("面板同步")))

        device.pressBack()
        device.wait(Until.hasObject(By.text("今日已签")), 10_000)
        device.findObject(By.text("今日已签")).click()
        device.wait(Until.hasObject(By.text("关注超话的等级、签到状态与详情")), 10_000)
        assertNotNull(device.findObject(By.text("全部")))
        device.pressBack()
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