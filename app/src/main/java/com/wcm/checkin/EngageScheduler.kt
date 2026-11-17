package com.wcm.checkin

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * 互动任务的触发方式：**打开 App 时若今天还没跑过就顺带跑一轮**。
 *
 * 为什么不用「每日固定时刻」：
 * - 国产 ROM 的省电策略经常直接掐掉 WorkManager 的定时唤醒，且不报错，
 *   表现是"设了但就是不跑"，用户完全无从判断；
 * - 到点集中发帖本身就比"随手用手机时发"更像自动化行为。
 *
 * 仍然用 WorkManager 而不是在 ViewModel 里直接跑：一轮动作含发帖间隔可能十几分钟，
 * 挂 `viewModelScope` 会随 Activity 销毁被取消，跑一半停在那里，反而留下
 * "不知道发没发"的烂摊子。
 *
 * 工作名固定，但用 **KEEP 而不是 REPLACE**：REPLACE 会取消正在跑的那一轮——
 * 用户反复开关 App 就能把已经跑了一半的任务反复打断，每次都留下预占未收敛的残局。
 */
object EngageScheduler {

    const val WORK_NAME = "wcm-engage-now"

    /**
     * 立即排一次执行。
     *
     * [EngageService] 内部有实例级 Mutex，重复排同一份工作会被 KEEP 吞掉，
     * 真正并发时第二次调用直接返回"已有任务在运行"，不会叠加。
     */
    fun runOnce(context: Context, config: EngageConfig) {
        if (!config.enabled || config.validate() != null) {
            Log.i(TAG, "不排期：${config.validate() ?: "任务未开启"}")
            return
        }
        val request = OneTimeWorkRequestBuilder<EngageWorker>()
            .addTag(WORK_NAME)
            // **刻意不加 NetworkType.CONNECTED**。
            //
            // 真机实测（Android 16 + 国内网络）：WorkManager 判约束时看的是
            // `NetworkState.isValidated`，而国内网络连不上 Google 的 generate_204
            // 验证服务器，`isValidated` 恒为 false → 每次都是
            // `Constraints not met: Cancelling work`，表现是「排期成功但永远不跑」，
            // 而且不报错、界面上也看不出来。
            //
            // 网络确实不通时，`EngageService` 自己的失败分类（NETWORK）+ 熔断会兜住，
            // 只会烧掉一轮的时间预算，比「永远不执行」好得多。
            .build()
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }.onSuccess {
            Log.i(TAG, "已排入后台执行 id=${request.id}")
        }.onFailure {
            Log.w(TAG, "排期失败", it)
        }
    }

    /** 关闭任务时清掉可能还排着的执行，避免关完又跑一轮。 */
    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
            .onSuccess { Log.i(TAG, "已取消排期") }
    }

    private const val TAG = "WcmEngage"
}
