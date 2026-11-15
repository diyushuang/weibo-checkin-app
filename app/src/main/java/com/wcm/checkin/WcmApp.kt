package com.wcm.checkin

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Android 12+ 自动跟随系统壁纸取色（Material You），低版本回退品牌色。 */
@HiltAndroidApp
class WcmApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // 游客身份要把 cookie 缓存到磁盘，必须先拿到 applicationContext
        GuestSession.init(this)
        // 升级后首次启动：把存量明文 cookie 一次性改写成 Keystore 密文。
        // AppScope 本身就是 Dispatchers.IO，不阻塞启动；迁移前读到明文也能正常工作
        // （SecretBox.open 兼容明文），迁移由 AccountStore 内部持锁完成，不会和首次读账号表打架。
        AppScope.launch { AccountStore.migrateToSealed(this@WcmApp) }
        // 启动即把超话快照读进内存：ensureLoaded 会遍历 wcm_topics 的 prefs.all，
        // 留在首页 onViewCreated（主线程）里做就是一次同步磁盘 IO，冷启动会掉帧。
        AppScope.launch { TopicStore.warm(this@WcmApp) }
    }
}
