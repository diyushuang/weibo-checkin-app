package com.wcm.checkin

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 接口节流闸门：同一个 key 的两次请求之间至少间隔 [gapMs]。
 *
 * 为什么要有它：此前节流散落在 9 处（`Thread.sleep(500)` 翻页、1200ms 详情闸、
 * 800ms 多账号间隔、3000–8000ms 签到随机间隔…），既改不动也测不了，而且
 * `Thread.sleep` 跑在 `Dispatchers.IO` 上时**协程取消打断不了** —— 用户切走页面，
 * 线程还挂在那儿睡。
 *
 * 这里只做「请求间隔」这一件事，用 `delay` 实现，随协程一起取消。
 *
 * 刻意**不**接管签到的那段 3–8 秒随机间隔：那是为了打散指纹的抖动，不是单纯限速，
 * 固定在 View 层反而更可控。抓取翻页/详情则另有预算约束（`CrawlCursor.Budget`）。
 */
object WeiboThrottle {

    /** 翻页类请求（超话列表逐页）。 */
    const val KEY_TOPIC_PAGE = "topic-page"

    /** 超话详情（用户点开才触发，防连点打卡）。 */
    const val KEY_TOPIC_DETAIL = "topic-detail"

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastAt = ConcurrentHashMap<String, Long>()

    /**
     * 等到允许发请求为止。[gapMs] ≤ 0 时直接放行。
     *
     * 用 Mutex 而不是 `synchronized`：等待期间必须让出线程，否则和 `Thread.sleep`
     * 是同一个毛病。
     */
    suspend fun await(key: String, gapMs: Long) {
        if (gapMs <= 0L) return
        val mutex = locks.getOrPut(key) { Mutex() }
        mutex.withLock {
            val last = lastAt[key] ?: 0L
            val wait = gapMs - (System.currentTimeMillis() - last)
            if (wait > 0) delay(wait)
            lastAt[key] = System.currentTimeMillis()
        }
    }
}
