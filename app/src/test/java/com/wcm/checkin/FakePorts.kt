package com.wcm.checkin

import android.content.Context

/**
 * JVM 单测用的端口假实现。
 *
 * 为什么集中在一个文件：`:core` 的端口一旦被两个以上服务用到（`AccountPort` 同时被
 * 签到、重登、探活使用），每个测试类各写一份假实现就会开始分叉——某天端口加了方法，
 * 只改了一份，另一份静默失效。这里统一维护，端口变更时只有一处需要跟着改。
 *
 * 约定：**只实现被断言用到的行为**，其余方法返回「什么都没发生」的安全默认值，
 * 并在注释里标明它属于「测试不该走到这里」的分支。
 */
class FakeAccountPort : AccountPort {

    /** `load()` 返回的账号表；重登用例靠它模拟「调用方手里的对象已过期」。 */
    var accounts: MutableList<Account> = ArrayList()

    var reloginEnabled = false
    var maxPerDay = 3
    var backoffHours = 6.0

    /** 当前持久化的重登状态（每个 uid 一份）。 */
    val states = HashMap<String, ReloginPolicy.State>()

    // ---- 断言用记录 ----
    val lastResults = ArrayList<String>()
    val deadUids = ArrayList<String>()
    val cookies = ArrayList<String>()
    val successUids = ArrayList<String>()
    val failures = ArrayList<Triple<String, ReloginPolicy.State, String>>()

    override fun load(ctx: Context): MutableList<Account> = accounts

    override fun markVerified(
        ctx: Context,
        uid: String,
        cookie: String,
        name: String,
        at: Long,
        serverExpiresAt: Long,
        loginAt: Long,
    ): Boolean = true

    override fun markDead(ctx: Context, uid: String, reason: String, at: Long): Boolean {
        deadUids.add(uid)
        return true
    }

    override fun markSynced(ctx: Context, uid: String, at: Long): Boolean = true

    override fun updateCookie(ctx: Context, uid: String, cookie: String, domainHints: String): Boolean {
        cookies.add(cookie)
        return true
    }

    override fun setLastResult(ctx: Context, uid: String, result: String, at: Long) {
        lastResults.add(result)
    }

    override fun needsNameResolve(acc: Account): Boolean = false

    override fun reloginState(acc: Account): ReloginPolicy.State = states[acc.uid] ?: ReloginPolicy.State()

    override fun saveReloginState(ctx: Context, uid: String, state: ReloginPolicy.State): Boolean {
        states[uid] = state
        return true
    }

    override fun markReloginSuccess(
        ctx: Context,
        uid: String,
        cookie: String,
        loginAt: Long,
        serverExpiresAt: Long,
        at: Long,
    ): Boolean {
        successUids.add(uid)
        return true
    }

    override fun markReloginFailure(
        ctx: Context,
        uid: String,
        state: ReloginPolicy.State,
        message: String,
        at: Long,
        dead: Boolean,
    ): Boolean {
        failures.add(Triple(uid, state, message))
        return true
    }

    override fun autoReloginEnabled(ctx: Context): Boolean = reloginEnabled
    override fun autoReloginMaxPerDay(ctx: Context): Int = maxPerDay
    override fun autoReloginBackoffHours(ctx: Context): Double = backoffHours
}

/** 凭证端口：只决定「有没有密码 / 有没有 ALC」，不涉及 Keystore。 */
class FakeCredentialPort(
    var password: String? = null,
    var alc: String? = null,
) : CredentialPort {

    override fun has(ctx: Context, uid: String): Boolean = password != null
    override fun load(ctx: Context, uid: String): Pair<String, String>? =
        password?.let { "user" to it }

    override fun hasAlc(ctx: Context, uid: String): Boolean = alc != null
    override fun loadAlc(ctx: Context, uid: String): String? = alc
    override fun saveAlc(ctx: Context, uid: String, alc: String) { this.alc = alc }
}

/** 探活端口：返回预置结果，或抛预置异常（用于登录墙 / 取消语义的用例）。 */
class FakeProbePort(
    private val verify: WeiboAuthApi.Verify,
    private val error: Exception? = null,
) : ProbePort {

    var calls = 0
        private set

    override fun probe(ctx: Context, acc: Account): WeiboAuthApi.Verify {
        calls++
        error?.let { throw it }
        return verify
    }
}

/** 超话快照缓存：记录每次写入的列表，用来断言「签到成功后是否回写了 done」。 */
class FakeTopicCache : TopicCachePort {

    val saved = ArrayList<List<WeiboTopicApi.Topic>>()

    override fun save(ctx: Context, uid: String, topics: List<WeiboTopicApi.Topic>, at: Long) {
        saved.add(topics)
    }
}
