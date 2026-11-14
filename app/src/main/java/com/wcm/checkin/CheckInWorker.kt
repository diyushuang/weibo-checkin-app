package com.wcm.checkin

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 后台保活：每 6 小时对每个账号发一次探活请求（顺带收下微博换发的新 Cookie），
 * 并把最新 Cookie 推送到面板。WorkManager 自动跨重启持久化，无需任何额外权限。
 *
 * 注意：这里只做轻量保活，不做签到 —— 签到由用户打开 App 触发（前台、真机、
 * 看得见进度），避免后台签到被系统省电策略掐断造成半途状态。
 */
class CheckInWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val accounts = AccountStore.load(ctx)
        val panelUrl = AccountStore.panelUrl(ctx)
        val panelToken = AccountStore.panelToken(ctx)
        var alive = 0
        // 明确判未登录的账号数：全部失效说明再重试也没用，不该让 WorkManager 反复拉起
        var dead = 0

        // OkHttp 的 execute() 是阻塞调用：CoroutineWorker 默认跑在 Dispatchers.Default 上，
        // 直接在里面阻塞会占住计算线程池（账号多时更明显），显式切到 IO。
        withContext(Dispatchers.IO) {
            /**
             * 探活成功后的统一收尾：补昵称 → 记账（真实登录时间 / 服务端到期 / 换发锚点）
             * → 推面板。cookie 是这次探活拿到的最新一份（可能已经轮换）。
             */
            fun markAlive(
                account: AccountStore.Account,
                cookie: String,
                loginAt: Long,
                serverExpiresAt: Long,
            ) {
                val at = System.currentTimeMillis()
                // 同步更新内存快照，避免整轮结束的 mergeSave 用旧 cookie 覆盖新结果
                account.cookie = cookie
                account.cookieSeenAt = at
                account.cookieLastCheckAt = at
                account.cookieDead = false
                account.cookieLastError = ""
                if (loginAt > 0L) account.cookieLoginAt = loginAt
                if (serverExpiresAt > 0L) account.cookieServerExpiresAt = serverExpiresAt
                // 昵称兜底：登录时资料接口没回名字的账号，在这里顺手补上
                // （后台跑，用户下次打开 App 就不再看到「（未获取到昵称）」）
                val name = if (AccountStore.needsNameResolve(account)) {
                    try {
                        WeiboAuthApi.fetchProfile(cookie, account.uid).first
                    } catch (e: Exception) {
                        ""
                    }
                } else {
                    ""
                }
                // 探活成功立即落盘时间锚点 / 昵称：整轮跑完再落会有几分钟窗口，
                // 期间首页那张 Cookie 卡显示的仍是旧值
                AccountStore.markVerified(
                    ctx, account.uid, cookie,
                    name = name,
                    at = at,
                    serverExpiresAt = serverExpiresAt,
                    loginAt = loginAt,
                )
                alive++
                if (panelUrl.isNotEmpty() && panelToken.isNotEmpty()) {
                    try {
                        val r = PanelApi.sync(
                            panelUrl, panelToken, cookie, account.uid,
                            name.ifEmpty { account.name },
                        )
                        if (r.ok) {
                            // 同步只影响面板侧计时；本机寿命仍以真实登录/换发为准
                            AccountStore.markSynced(ctx, account.uid)
                            account.cookieSyncedAt = System.currentTimeMillis()
                        }
                    } catch (e: Exception) {
                        // 面板同步失败不影响本地保活
                    }
                }
            }

            for (acc in accounts) {
                try {
                    // 临期或已判失效：先尝试账密重登（可用时）；失败再退回普通探活
                    val life = CookieLife.describe(acc)
                    if (acc.cookieDead || acc.cookie.isEmpty() ||
                        life.state == CookieLife.State.EXPIRING
                    ) {
                        val outcome = AutoRelogin.tryAutoRelogin(ctx, acc, "keepalive_expiring")
                        if (outcome.ok) {
                            val fresh = AccountStore.load(ctx)
                                .firstOrNull { it.uid == acc.uid } ?: acc
                            markAlive(fresh, fresh.cookie, fresh.cookieLoginAt, fresh.cookieServerExpiresAt)
                            continue
                        }
                    }

                    val verify = WeiboAuthApi.verify(acc.cookie, force = true)
                    if (verify.login) {
                        acc.lastResult = acc.lastResult.ifEmpty { "后台保活 ✓" }
                        markAlive(acc, verify.cookie, verify.loginAt, verify.serverExpiresAt)
                    } else {
                        // 探活明确判未登录：先自动重登；不行再记失效，首页 Cookie 卡标红
                        val outcome = AutoRelogin.tryAutoRelogin(ctx, acc, "keepalive_invalid")
                        val fresh = if (outcome.ok) {
                            AccountStore.load(ctx).firstOrNull { it.uid == acc.uid } ?: acc
                        } else {
                            acc
                        }
                        val again = if (outcome.ok) {
                            try {
                                WeiboAuthApi.verify(fresh.cookie, force = true)
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                        if (again != null && again.login) {
                            markAlive(fresh, again.cookie, again.loginAt, again.serverExpiresAt)
                        } else {
                            val reason = outcome.message
                                .ifEmpty { "登录态已失效，请重新扫码登录" }
                            AccountStore.markDead(ctx, acc.uid, reason)
                            dead++
                        }
                    }
                } catch (e: CancellationException) {
                    // 取消必须原样抛出：吞掉会让 doWork 谎报成功，WorkManager 以为跑完了
                    throw e
                } catch (e: Exception) {
                    // 网络抖动/风控：记下原因供界面展示，但绝不判失效（断网不是掉登录）
                    val reason = when (e) {
                        is WeiboHttp.WeiboException ->
                            if (e.httpCode == 429 || e.httpCode == 432 || e.httpCode == 403) {
                                "被微博风控拦截（HTTP ${e.httpCode}）"
                            } else {
                                e.message ?: "探活失败"
                            }
                        else -> "网络异常：" + (e.message ?: e.javaClass.simpleName)
                    }
                    AccountStore.markCheckError(ctx, acc.uid, reason.take(80))
                }
            }
        }
        // 合并式写回：整轮跑下来要几分钟，期间用户新增/删除的账号不被旧快照覆盖
        AccountStore.mergeSave(ctx, accounts)
        // 一个都没保住 → 交给 WorkManager 退避重试，否则要等满 6 小时才轮到下一次。
        // 但如果「一个都没保住」的原因是全部账号明确失效（dead 全中），重试毫无意义：
        // 探活照样返回未登录，只会按指数退避反复拉起，白耗电还刷风控。这种直接成功退出。
        return if (accounts.isNotEmpty() && alive == 0 && dead < accounts.size) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "wcm-keepalive"

        fun schedule(ctx: Context) {
            // 没网/低电不空跑：省电省流量，条件满足后 WorkManager 自动补跑
            val request = PeriodicWorkRequestBuilder<CheckInWorker>(
                6, TimeUnit.HOURS, 1, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
