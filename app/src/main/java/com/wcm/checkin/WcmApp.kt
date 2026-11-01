package com.wcm.checkin

import android.app.Application
import com.google.android.material.color.DynamicColors

/** Android 12+ 自动跟随系统壁纸取色（Material You），低版本回退品牌色。 */
class WcmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // 游客身份要把 cookie 缓存到磁盘，必须先拿到 applicationContext
        GuestSession.init(this)
        // 启动即把超话快照读进内存：ensureLoaded 会遍历 wcm_topics 的 prefs.all，
        // 留在首页 onViewCreated（主线程）里做就是一次同步磁盘 IO，冷启动会掉帧。
        AppScope.launch { TopicStore.warm(this@WcmApp) }
    }
}
