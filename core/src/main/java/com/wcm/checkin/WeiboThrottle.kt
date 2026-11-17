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
    /**
     * 这个 key 现在真要等多久。**只查询，不占位、不修改状态。**
     *
     * 存在的理由是预算判断：此前调用方拿「配置的间隔区间随机值」直接和 deadline 比，
     * 但同一个 key 的**首次**调用其实一秒都不用等（`lastAt` 为空）。默认配置下
     * 发帖间隔是 5–8 分钟，于是「今天第一条帖子」被误判成来不及而直接跳过 ——
     * 表现就是评论转发点赞都跑了、发帖一条没有。
     */
    fun remaining(key: String, gapMs: Long): Long {
        if (gapMs <= 0L) return 0L
        val last = lastAt[key] ?: return 0L
        val wait = gapMs - (System.currentTimeMillis() - last)
        return if (wait > 0L) wait else 0L
    }

    suspend fun await(key: String, gapMs: Long) {
        if (gapMs <= 0L) return
        // 必须用 computeIfAbsent：ConcurrentHashMap 的 getOrPut 是「先查再放」两步，
        // 并发首次调用会各自 new 一个 Mutex —— 两个调用者各持一把锁，节流直接被击穿
        // （请求量翻倍，最容易吃 429）。
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        mutex.withLock {
            val last = lastAt[key] ?: 0L
            val wait = gapMs - (System.currentTimeMillis() - last)
            if (wait > 0) delay(wait)
            lastAt[key] = System.currentTimeMillis()
        }
    }
}
