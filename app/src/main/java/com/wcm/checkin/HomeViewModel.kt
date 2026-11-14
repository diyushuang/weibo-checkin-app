package com.wcm.checkin

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** 各账号最近一次超话状态（key = uid）。 */
data class TopicState(
    val topics: List<WeiboTopicApi.Topic> = emptyList(),
    /**
     * 这份状态的取得时刻（epoch ms；0 = 没有时间戳）。
     *
     * 存时间戳而不是格式化好的 "HH:mm"：签到状态是**按天**的，界面上要能判断
     * 「这是今天的结论，还是昨天的」。只留 HH:mm 就永远分不出跨天。
     */
    val atMs: Long = 0L,
    val error: String = "",
    val loading: Boolean = false,
) {
    val total: Int get() = topics.size
    val done: Int get() = topics.count { it.done }

    /** 是否是「今天」取得的状态（false 时不能拿来当今日已签/待签的结论）。 */
    val freshToday: Boolean get() = TopicStore.isToday(atMs)
}

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

    /**
     * 各账号最近一次超话状态。
     *
     * 用 StateFlow + 不可变 Map 整体替换，而不是 HashMap 原地改：写入方是 IO 线程
     * （刷新 / 签到），读取方是主线程（render、进度环、账号行、Hero 卡遍历）。
     * 读到的永远是某一份完整快照，不会在遍历时被并发扩容打断。
     */
    private val _topicStates = MutableStateFlow<Map<String, TopicState>>(emptyMap())
    val topicStates: StateFlow<Map<String, TopicState>> = _topicStates.asStateFlow()

    fun setTopicState(uid: String, state: TopicState) {
        _topicStates.update { it + (uid to state) }
    }

    fun removeTopicState(uid: String) {
        _topicStates.update { it - uid }
    }
}
