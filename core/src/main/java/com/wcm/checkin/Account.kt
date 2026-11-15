package com.wcm.checkin

/**
 * 一个微博账号的完整本地状态。
 *
 * **放在 `:core` 而不是 `:data`**：它是 `AccountPort` 的返回类型，而端口在 `:core`，
 * 若模型留在 `:data`，端口签名就会把 `:domain` 重新拖回对 `:data` 的依赖——
 * 依赖倒置的第一步必须是「模型下沉到共享内核」。
 *
 * 字段与 JSON 键一一对应（`AccountCodec` 负责序列化），**新增字段只能加默认值**，
 * 否则老用户升级后整张账号表会解析失败。字段名就是 prefs 里的 JSON 键名，
 * 不允许为了「好看」改名字——那是破坏性迁移。
 */
data class Account(
    val uid: String,
    val name: String,
    var cookie: String,
    var lastResult: String = "",
    /**
     * [lastResult] 的写入时刻（epoch ms；0 = 旧版本写的 / 时间未知）。
     *
     * 签到状态是**按天**的：只有知道小结是哪天写的，才能判断它能不能当「今天」的
     * 结论。没有这个时间戳时，昨天 22:00 的「签到 7/7 · 22:00」会被当成今天已签。
     */
    var lastResultAt: Long = 0L,
    /** 本机首次存下这份 cookie 的时刻（epoch ms；0 = 没有记录）。 */
    var cookieIssuedAt: Long = 0L,
    /** 最近一次观察到微博**真的换发**登录关键字段（SUB/SUBP/SCF/SSOLoginState/ALF）的时刻。 */
    var cookieRotatedAt: Long = 0L,
    /** 最近一次探活确认「登录有效」的时刻。只证明还活着，**不作为寿命基准**。 */
    var cookieSeenAt: Long = 0L,
    /** 最近一次成功推送到面板的时刻。同步只影响面板，**不作为本机寿命基准**。 */
    var cookieSyncedAt: Long = 0L,
    /**
     * 探活已明确判定登录态失效（`login=false`）。
     *
     * 有效期是**估算**：一个已经掉登录的账号照样能算出「还剩 5 天」，
     * 这比不给结论更糟。所以探活失败要单独记一笔，界面上直接显示「已失效」，
     * 让用户去重新登录，而不是等那个算出来的倒计时走完。
     * 任何一次探活成功（`AccountStore.markVerified`）都会清掉它。
     */
    var cookieDead: Boolean = false,
    /**
     * 真实登录时刻（来自 cookie 的 `SSOLoginState`，缺失时用本次账密登录时刻）。
     *
     * 与 [cookieSyncedAt] 严格分开：同步不是登录，不能把同步时刻当寿命起点。
     */
    var cookieLoginAt: Long = 0L,
    /** 服务端**声明/名义**到期时间（Set-Cookie 的 Expires/Max-Age 或 ALF；0 = 未知）。 */
    var cookieServerExpiresAt: Long = 0L,
    /** 最近一次让寿命起算的关键字段列表（逗号分隔）；仅用于界面说明「为什么续期」。 */
    var cookieRotateKeys: String = "",
    /** 最近一次探测失败原因（网络/风控/失效），给用户看，成功时清空。 */
    var cookieLastError: String = "",
    /** 最近一次探测（无论成败）的时刻。 */
    var cookieLastCheckAt: Long = 0L,
    // ---- 账号密码自动重登（凭证本身在 CredentialVault 加密保存，不落在这里）----
    var autoReloginEnabled: Boolean = true,
    /** 最近一次保存/更新凭证的时刻。 */
    var credentialAt: Long = 0L,
    var reloginLastAttemptAt: Long = 0L,
    var reloginNextAt: Long = 0L,
    var reloginFailStreak: Int = 0,
    /** 24 小时窗口内的尝试时刻（epoch ms）。 */
    var reloginAttempts: List<Long> = emptyList(),
    var reloginBlocked: Boolean = false,
    var reloginLastReason: String = "",
    var reloginLastCode: String = "",
    /**
     * 从响应 `Set-Cookie` 里学到的 (name → domain) 映射，形如 `SUB=weibo.cn;SUBP=weibo.cn`。
     *
     * **只作诊断与后续域路由的原料，当前不参与过滤**（见 `WeiboHttp.WeiboSession` 类注释）。
     * 默认空串：老数据没有它，等价于"域未知"，行为与改造前完全一致。
     */
    var cookieDomains: String = "",
    /**
     * 最近一次保存 `ALC`（SSO 长效凭证）的时刻；0 = 没存过。
     *
     * 只存时刻不存值：`ALC` 本身在 `AlcVault` 里加密保存，与密码同一个 Keystore。
     */
    var alcAt: Long = 0L,
)
