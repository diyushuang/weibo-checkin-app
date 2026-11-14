package com.wcm.checkin

import android.content.Context
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 签到编排：探活 → 列超话 → 逐个签到 → 记小结。
 *
 * 从 HomeFragment 抽出的原因：这段逻辑以前只有 UI 能跑，后台想加定时签到就得复制一份；
 * 抽成服务后，前台/后台/未来的定时任务共用同一套节奏、记账与失败语义。
 *
 * 日志通过 [log] 回调输出；UI 状态通过 [onTopics] / [onAccountDone] 通知，
 * 服务本身不碰 View。
 */
object CheckinService {

    data class AccountOutcome(
        val uid: String,
        val ok: Int = 0,
        val failed: Int = 0,
        val pending: Int = 0,
        val error: String = "",
    )

    private val timeFmt = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    }

    internal fun now(): String {
        val fmt = timeFmt.get() ?: SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        return fmt.format(Date())
    }

    /** 只写一行「上次结果」，不整表覆盖（整表写会把并发轮换的 cookie 顶掉）。 */
    internal fun noteResult(ctx: Context, acc: AccountStore.Account, text: String) {
        val at = System.currentTimeMillis()
        acc.lastResult = text
        acc.lastResultAt = at
        AccountStore.setLastResult(ctx, acc.uid, text, at)
    }

    /**
     * 跑完一整批账号。
     *
     * @param accounts 调用方从 [AccountStore.load] 得到的快照；服务会就地更新
     *   每个账号的 cookie / lastResult，调用方结束时的 mergeSave 负责落盘。
     * @param onTopics 每拉到一份超话列表就回调一次（UI 更新内存快照并重绘）。
     * @param pause 账号之间的随机间隔；测试可注入 no-op。
     */
    suspend fun runAll(
        ctx: Context,
        accounts: List<AccountStore.Account>,
        log: (String) -> Unit,
        onTopics: (uid: String, topics: List<WeiboTopicApi.Topic>) -> Unit = { _, _ -> },
        onAccountDone: () -> Unit = {},
        pause: suspend (Long) -> Unit = { delay(it) },
    ): List<AccountOutcome> {
        val outcomes = ArrayList<AccountOutcome>(accounts.size)
        accounts.forEachIndexed { index, acc ->
            if (index > 0) pause(Random.nextLong(2000, 5000))
            outcomes.add(runAccount(ctx, acc, log, onTopics, pause))
            onAccountDone()
        }
        return outcomes
    }

    /**
     * 跑一个账号的完整签到。
     *
     * [ctx] 由调用方在启动协程前取好（`requireContext()`）：一轮签到要几分钟，
     * 期间用户完全可能退出页面，届时 `requireContext()` 会抛
     * IllegalStateException，把整轮签到（乃至进程）带崩。
     */
    suspend fun runAccount(
        ctx: Context,
        acc: AccountStore.Account,
        log: (String) -> Unit,
        onTopics: (uid: String, topics: List<WeiboTopicApi.Topic>) -> Unit = { _, _ -> },
        pause: suspend (Long) -> Unit = { delay(it) },
    ): AccountOutcome {
        val name = acc.name.ifEmpty { acc.uid }
        log("── $name ──")
        val verify = try {
            AccountMaintenanceService.probeAndPersist(ctx, acc)
        } catch (e: Exception) {
            noteResult(ctx, acc, "探活失败：${e.message}")
            log("  ✗ 探活失败：${e.message}")
            return AccountOutcome(acc.uid, error = "探活失败：${e.message}")
        }
        if (!verify.login) {
            val text = ctx.getString(R.string.home_cookie_dead)
            noteResult(ctx, acc, text)
            AccountStore.markDead(ctx, acc.uid)
            log("  ✗ $text")
            return AccountOutcome(acc.uid, error = text)
        }
        try {
            val topics = WeiboTopicApi.listTopics(acc.cookie)
            val pending = topics.filter { !it.done }
            log("  ${topics.size} 个超话，${topics.size - pending.size} 已签，待签 ${pending.size}")
            onTopics(acc.uid, topics)
            TopicStore.save(ctx, acc.uid, topics)
            var ok = 0
            var fail = 0
            for (topic in pending) {
                pause(Random.nextLong(3000, 8000))
                try {
                    if (WeiboTopicApi.sign(acc.cookie, topic.scheme ?: "", verify.st)) {
                        ok++
                        log("  ✓ ${topic.name}")
                    } else {
                        fail++
                        log("  ✗ ${topic.name}")
                    }
                } catch (e: Exception) {
                    fail++
                    log("  ✗ ${topic.name}：${e.message}")
                }
            }
            noteResult(
                ctx, acc,
                "签到 $ok/${pending.size}" +
                    (if (fail > 0) "（失败 $fail）" else "") + " · ${now()}",
            )
            log("  小结：${acc.lastResult}")
            return AccountOutcome(acc.uid, ok = ok, failed = fail, pending = pending.size)
        } catch (e: Exception) {
            noteResult(ctx, acc, "超话列表失败：${e.message}")
            log("  ✗ ${e.message}")
            return AccountOutcome(acc.uid, error = "超话列表失败：${e.message}")
        }
    }
}
