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
class CheckinService(
    /** 账号表：签到小结与失效标记的唯一写入点。 */
    private val account: AccountPort,
    /** 探活 / 补昵称 / 面板同步。 */
    private val maintenance: AccountMaintenanceService,
    /** 超话快照缓存。 */
    private val topicCache: TopicCachePort,
) {

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
    fun noteResult(ctx: Context, acc: Account, text: String) {
        val at = System.currentTimeMillis()
        acc.lastResult = text
        acc.lastResultAt = at
        account.setLastResult(ctx, acc.uid, text, at)
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
        accounts: List<Account>,
        log: (String) -> Unit,
        onTopics: (uid: String, topics: List<WeiboTopicApi.Topic>) -> Unit = { _, _ -> },
        onAccountDone: () -> Unit = {},
        cookieDeadText: String = "Cookie 已失效，请删除后重新添加账号",
        pause: suspend (Long) -> Unit = { delay(it) },
    ): List<AccountOutcome> {
        val outcomes = ArrayList<AccountOutcome>(accounts.size)
        accounts.forEachIndexed { index, acc ->
            if (index > 0) pause(Random.nextLong(2000, 5000))
            outcomes.add(runAccount(ctx, acc, log, onTopics, cookieDeadText, pause))
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
        acc: Account,
        log: (String) -> Unit,
        onTopics: (uid: String, topics: List<WeiboTopicApi.Topic>) -> Unit = { _, _ -> },
        cookieDeadText: String = "Cookie 已失效，请删除后重新添加账号",
        pause: suspend (Long) -> Unit = { delay(it) },
    ): AccountOutcome {
        val name = acc.name.ifEmpty { acc.uid }
        log("── $name ──")
        // 已判失效的账号不再发业务请求：探活必然失败、签到必然 0/N，徒增一次
        // 必失败的往返并把 IP 往风控上推。恢复由两条路负责——后台保活的自动重登
        // （账密 / ALC），或用户在首页点「重登」。
        if (acc.cookieDead) {
            log("  ✗ $cookieDeadText")
            return AccountOutcome(acc.uid, error = cookieDeadText)
        }
        val verify = try {
            maintenance.probeAndPersist(ctx, acc)
        } catch (e: Exception) {
            // 登录墙（302 到登录页 / 访客页 HTML）是"服务端明确要你重新登录"，
            // 属于允许判死的强判据；其余异常只记原因，绝不判死。
            if (e is WeiboHttp.WeiboException && e.loginWall) {
                noteResult(ctx, acc, cookieDeadText)
                account.markDead(ctx, acc.uid, e.message.orEmpty(), System.currentTimeMillis())
                log("  ✗ $cookieDeadText")
                return AccountOutcome(acc.uid, error = cookieDeadText)
            }
            noteResult(ctx, acc, "探活失败：${e.message}")
            log("  ✗ 探活失败：${e.message}")
            return AccountOutcome(acc.uid, error = "探活失败：${e.message}")
        }
        if (!verify.login) {
            val text = cookieDeadText
            noteResult(ctx, acc, text)
            account.markDead(ctx, acc.uid, "", System.currentTimeMillis())
            log("  ✗ $text")
            return AccountOutcome(acc.uid, error = text)
        }
        try {
            // 一个账号一次会话：后续每个请求都用它，响应里的 Set-Cookie 会被逐条收下。
            // 整批结束（无论成败）把 session.cookie 写回账号，完成"换发回写"。
            val session = WeiboHttp.WeiboSession.of(acc.cookie, acc.cookieDomains)
            try {
                val topics = WeiboTopicApi.listTopics(session)
                val pending = topics.filter { !it.done }
                log("  ${topics.size} 个超话，${topics.size - pending.size} 已签，待签 ${pending.size}")
                onTopics(acc.uid, topics)
                topicCache.save(ctx, acc.uid, topics, System.currentTimeMillis())
                var ok = 0
                var fail = 0
                // 签到成功的超话 key：循环结束后要把它们标记成已签再写回快照，
                // 否则首页仍显示「今日已签 0/4」，和日志里的「签到 4/4」自相矛盾
                // （真机实测踩到：日志 4/4，首页 0/4）。
                val signed = LinkedHashSet<String>()
                for (topic in pending) {
                    pause(Random.nextLong(3000, 8000))
                    try {
                        if (WeiboTopicApi.sign(session, topic.scheme ?: "", verify.st)) {
                            ok++
                            signed.add(topic.key)
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
                if (signed.isNotEmpty()) {
                    val updated = topics.map { if (it.key in signed) it.copy(done = true) else it }
                    onTopics(acc.uid, updated)
                    topicCache.save(ctx, acc.uid, updated, System.currentTimeMillis())
                }
                noteResult(
                    ctx, acc,
                    "签到 $ok/${pending.size}" +
                        (if (fail > 0) "（失败 $fail）" else "") + " · ${now()}",
                )
                log("  小结：${acc.lastResult}")
                return AccountOutcome(acc.uid, ok = ok, failed = fail, pending = pending.size)
            } finally {
                // 即使中途抛异常也要写回：签到途中拿到的新 cookie 同样是有效的换发。
                // 同时把内存快照的 cookie 对齐，避免调用方随后的 mergeSave 把旧串写回去。
                if (session.cookie != acc.cookie) {
                    acc.cookie = session.cookie
                    account.updateCookie(ctx, acc.uid, session.cookie, session.domainHints)
                }
            }
        } catch (e: Exception) {
            noteResult(ctx, acc, "超话列表失败：${e.message}")
            log("  ✗ ${e.message}")
            return AccountOutcome(acc.uid, error = "超话列表失败：${e.message}")
        }
    }
}
