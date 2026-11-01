package com.wcm.checkin

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用级协程作用域：签到 / 抓取这类「不能半途而废」的长任务挂在这里。
 *
 * 为什么不挂 `viewLifecycleOwner.lifecycleScope`：旋转屏幕、切换深浅色、进出页面都会
 * 销毁 View，协程随之取消 —— 一轮十几分钟的签到被砍在半路，已经按下的签到不会回滚，
 * 用户还看不到任何提示（旧实现的 `autoHandled` 闸门甚至会让这一轮永远不会再跑）。
 * 长任务的生命周期应该跟随进程，而不是某一个 View。
 *
 * 用 `SupervisorJob`：一个账号抛异常不该连带取消其它账号。
 * 所有下游都用 `Dispatchers.Main` 回主线程更新 UI，调用点自带 `isAdded` 判断。
 */
object AppScope {

    /**
     * 兜底：SupervisorJob 只保证兄弟协程不被连带取消，**不负责捕获异常** ——
     * 没有 handler 时，未捕获异常会走默认处理器直接崩进程。长任务里任何一处
     * 意外（写盘 IO、日志追加、UI 回调）都不该把整个 App 带走。
     */
    private val handler = CoroutineExceptionHandler { _, e ->
        Log.e("AppScope", "未捕获的协程异常", e)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)
}
