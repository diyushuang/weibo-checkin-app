package com.wcm.checkin

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 互动任务的执行入口（由「打开 App 时补跑」触发）。
 *
 * 只做一件事：读配置 → 跑一轮。所有业务逻辑都在 [EngageService] 里，
 * 这样「手动补跑」与「自动执行」走的是同一条代码路径，不会出现两套行为。
 *
 * 失败语义：这里**从不返回 retry()**。自动加重试会在短时间内连续重发，
 * 而本功能的失败大多是「被限流 / 需人工」，再试一次只会更糟；
 * 真正的重试交给用户手动补跑（下次打开 App 也会自然再试）。
 */
@HiltWorker
class EngageWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val service: EngageService,
    private val configPort: EngageConfigPort,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val config = configPort.loadConfig()
        if (!config.enabled || config.validate() != null) {
            Log.i(TAG, "跳过：未开启或配置不完整（${config.validate() ?: "未开启"}）")
            return Result.success()
        }

        Log.i(TAG, "后台补跑开始 uid=${config.accountUid} topic=${config.topicId}")
        // 任何异常都不该让应用崩溃，也不能让排期状态卡住；但不能静默——
        // 原来这里只有 runCatching 没有日志，出问题时界面上什么都看不到。
        //
        // 预算压到 9 分钟：配置里允许开到 20 分钟（手动执行用），但 CoroutineWorker
        // 超过 10 分钟会被系统直接停掉，那种停法不写运行记录、不回写换发 cookie。
        runCatching { service.run(background = true, budgetCapMs = WORKER_BUDGET_MS) }
            .onSuccess { Log.i(TAG, "后台补跑结束 blocked=${it.blockedReason} records=${it.run?.records?.size}") }
            .onFailure { Log.w(TAG, "后台补跑异常", it) }
        return Result.success()
    }

    private companion object {
        const val TAG = "WcmEngage"

        /** 后台单轮预算上限：系统对 `CoroutineWorker` 的 10 分钟硬限制，留 1 分钟收尾余量。 */
        const val WORKER_BUDGET_MS = 9 * 60_000L
    }
}
