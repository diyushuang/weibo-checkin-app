package com.wcm.checkin

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import javax.inject.Inject

/**
 * 首页的进程级 UI 状态：忙碌闸门、自动签到闩、日志缓冲、昵称补拉标记。
 *
 * 以前这些字段放在 HomeFragment 的 companion object 里，目的是「旋转/换实例后
 * 不丢正在跑的签到」。改由 ViewModel 持有：
 * - 配置变更（旋转、深浅色）后仍是同一个实例；
 * - 进程被杀后自然重置（与 companion 的进程级语义一致）；
 * - 长任务本身仍挂在 [AppScope] 上，不随 ViewModel 销毁而取消。
 */
@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {

    /** 本次进程只自动签到一次（切 Tab 不重复触发）。 */
    @Volatile var autoHandled = false

    /** 本次进程只自动刷新一次超话状态（打开 App 时那一次）。 */
    @Volatile var topicsAutoHandled = false

    @Volatile var busy = false
    @Volatile var topicBusy = false

    /** 当前正在跑的签到 / 同步协程（挂在 AppScope 上，不随视图销毁而取消）。 */
    @Volatile var taskJob: Job? = null

    /** 正在跑的「刷新超话状态」协程。 */
    @Volatile var topicJob: Job? = null

    /** 日志行缓冲：放 ViewModel，旋转后日志不丢。 */
    val logLines = ArrayDeque<String>()

    /** 已经尝试过补拉昵称的账号集合（`uid` 升序拼接）。 */
    @Volatile var nameBackfillFor = ""

    /** 当前这轮 busy 是否是「Cookie 同步」。 */
    @Volatile var busySyncing = false
}
