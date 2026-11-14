package com.wcm.checkin

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** 首页在无账号时点「立即签到」：展开日志并提示，不发起网络。 */
@RunWith(AndroidJUnit4::class)
class HomeNoAccountSmokeTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun checkinWithoutAccountsShowsPrompt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val oldAccounts = AccountStore.load(ctx)
        val oldAuto = PanelSettings.autoOnOpen(ctx)
        try {
            PanelSettings.setAutoOnOpen(ctx, false)
            AccountStore.save(ctx, emptyList())

            ActivityScenario.launch(MainActivity::class.java).use {
                device.wait(Until.hasObject(By.text("首页")), 15_000)
                device.findObject(By.text("立即签到")).click()
                repeat(3) { device.swipe(540, 1800, 540, 600, 20) }
                device.wait(Until.hasObject(By.text("没有账号，请先「＋ 添加账号」")), 8_000)
                assertNotNull(device.findObject(By.text("没有账号，请先「＋ 添加账号」")))
                assertNotNull(device.findObject(By.text("运行日志")))
            }
        } finally {
            PanelSettings.setAutoOnOpen(ctx, oldAuto)
            AccountStore.save(ctx, oldAccounts)
        }
    }
}