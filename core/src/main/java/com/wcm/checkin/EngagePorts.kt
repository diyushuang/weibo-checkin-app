package com.wcm.checkin

/**
 * 互动任务的端口（依赖倒置：接口在 `:core`，实现在 `:data`，由 `:app` 装配）。
 *
 * 单独一个文件而不是塞进 [Ports] 那种大而全的集合：本功能的读写语义与前五个端口
 * 差别很大（有原子预占、有三态收敛），混在一起会让那个文件继续膨胀。
 *
 * 端口**不声明默认实现**——默认值留在实现类，避免接口层偷偷替调用方做决定。
 */
interface EngageConfigPort {

    /** 读配置。没有存过或数据损坏时返回默认配置（不抛异常）。 */
    fun loadConfig(): EngageConfig

    fun saveConfig(config: EngageConfig)

    /** 整池读取。[:data] 实现应当按单键存取，避免碎键扫描。 */
    fun loadTexts(): List<EngageText>

    fun saveTexts(texts: List<EngageText>)

    /** 追加若干条并返回落盘后的整池（便于界面立刻刷新统计）。 */
    fun appendTexts(newTexts: List<EngageText>): List<EngageText>

    /** 清空配置与文案（危险操作，界面必须二次确认）。 */
    fun clearAll()
}

/**
 * 执行态的读写：当日计数、运行记录、发帖台账。
 *
 * 预占/提交/释放必须由实现保证**原子性**（读改写同锁），
 * 否则并发两次运行会把同一个槽扣两次，直接导致重复发帖。
 */
interface EngageRunPort {

    fun loadCounter(uid: String, day: String): EngageDayCounter

    /**
     * 写前预占一个槽位。返回 false 表示该槽已被占用或今日额度已用尽。
     *
     * 调用方拿到 true 才允许发请求；请求结束后必须 [commitSlot] 或 [releaseSlot]。
     */
    fun reserveSlot(uid: String, day: String, action: EngageAction, seq: Int, textId: String, now: Long): Boolean

    /** 预占转成功：清掉 pending，`done[action] += 1`。发帖还要更新 `lastPostAt`。 */
    fun commitSlot(uid: String, day: String, action: EngageAction, seq: Int, now: Long): EngageDayCounter

    /** 预占撤销（确定没发出去）：只清 pending，不计数。 */
    fun releaseSlot(uid: String, day: String, action: EngageAction, seq: Int): EngageDayCounter

    /** 保留预占（结果未知）：pending 留着，用于锁死当日发帖。 */
    fun markUncertain(uid: String, day: String, action: EngageAction, seq: Int, now: Long): EngageDayCounter

    fun saveRun(run: EngageRun)

    /** 最近的运行记录，按开始时间倒序。 */
    fun loadRuns(uid: String, limit: Int = 20): List<EngageRun>

    fun loadPosts(uid: String, day: String): List<EngagePostRecord>

    fun savePost(record: EngagePostRecord)

    /** 手工收敛：把某条台账改成指定状态（用户在微博里确认后调用）。 */
    fun settlePost(uid: String, day: String, postId: String, state: ActionState, manualConfirmed: Boolean): EngagePostRecord?

    /** 清理过期数据：计数与台账保留 30 天，台账 90 天，运行记录每账号保留 20 条。 */
    fun prune(uid: String, today: String)

    /** 清空该账号的全部执行数据（计数 / 运行记录 / 发帖台账）。 */
    fun clearAll(uid: String)
}
