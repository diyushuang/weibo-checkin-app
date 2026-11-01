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
            for (acc in accounts) {
                try {
                    val verify = WeiboApi.verify(acc.cookie)
                    if (verify.login) {
                        val at = System.currentTimeMillis()
                        acc.cookie = verify.cookie
                        acc.cookieSeenAt = at
                        acc.lastResult = acc.lastResult.ifEmpty { "后台保活 ✓" }
                        // 昵称兜底：登录时资料接口没回名字的账号，在这里顺手补上
                        // （后台跑，用户下次打开 App 就不再看到「（未获取到昵称）」）
                        val name = if (AccountStore.needsNameResolve(acc)) {
                            WeiboApi.fetchProfile(verify.cookie, acc.uid).first
                        } else {
                            ""
                        }
                        // 探活成功立即落盘时间锚点 / 昵称：整轮跑完再落会有几分钟窗口，
                        // 期间首页那张 Cookie 卡显示的仍是旧值
                        AccountStore.markVerified(ctx, acc.uid, verify.cookie, name = name, at = at)
                        alive++
                        if (panelUrl.isNotEmpty() && panelToken.isNotEmpty()) {
                            try {
                                val r = PanelApi.sync(
                                    panelUrl, panelToken, acc.cookie, acc.uid,
                                    name.ifEmpty { acc.name },
                                )
                                if (r.ok) {
                                    // 面板侧生命周期以这次同步为基准重新计时（约 7 天）
                                    AccountStore.markSynced(ctx, acc.uid)
                                    acc.cookieSyncedAt = System.currentTimeMillis()
                                }
                            } catch (e: Exception) {
                                // 面板同步失败不影响本地保活
                            }
                        }
                    } else {
                        // 探活明确判未登录：记一笔失效，首页 Cookie 卡会直接标红提示重新登录
                        AccountStore.markDead(ctx, acc.uid)
                        dead++
                    }
                } catch (e: CancellationException) {
                    // 取消必须原样抛出：吞掉会让 doWork 谎报成功，WorkManager 以为跑完了
                    throw e
                } catch (e: Exception) {
                    // 网络抖动/风控：下一轮再说
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
