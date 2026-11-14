package com.wcm.checkin

import okhttp3.Cookie
import okhttp3.Request
import org.json.JSONObject

/**
 * 微博身份接口：探活（/api/config）、昵称/UID/头像解析、UID 解析。
 *
 * 从原 `WeiboApi` 拆出。所有出站请求走 [WeiboHttp]，纯解析函数保持无 Android 依赖，
 * 可继续用 JVM 单测覆盖。
 */
object WeiboAuthApi {

    data class Verify(
        val login: Boolean,
        val st: String?,
        val cookie: String,
        /** 配置接口顺带回的 UID（部分响应缺这个字段，由调用方兜底补拉）。 */
        val uid: String = "",
        /** 服务端声明/名义到期（Set-Cookie 的 Expires/Max-Age 或 ALF；0 = 未知）。 */
        val serverExpiresAt: Long = 0L,
        /** 真实登录时刻（cookie 的 SSOLoginState；0 = 未知）。 */
        val loginAt: Long = 0L,
    )

    /** 账号资料：昵称 / UID / 头像（任一字段都可能为空，调用方按需兜底）。 */
    data class Profile(val name: String = "", val uid: String = "", val avatarUrl: String = "")

    /**
     * 探活结果的缓存时长。
     *
     * 只有 `login=true` 的结果才会被缓存：登录页轮询依赖「未登录」的实时反馈，
     * 一旦把 `false` 缓存 90 秒，用户登录成功后还会卡在「等待微博确认」。
     * 而登录态的重复探活（刷新/签到/详情前各一次）正是请求量翻倍的主因，缓存它能直接减半。
     */
    private const val VERIFY_TTL_MS = 90_000L

    @Volatile private var verifyCache: CachedVerify? = null

    private data class CachedVerify(val cookie: String, val at: Long, val value: Verify)

    /**
     * 探活 + 轮换：返回是否登录、st、uid 与合并轮换后的完整 cookie。
     *
     * 登录态结果缓存 [VERIFY_TTL_MS]：[force] 可跳过缓存。签到验签重试、登录轮询这类
     * 必须看到实时状态的场景要显式传 `force = true`。
     */
    fun verify(cookie: String, force: Boolean = false): Verify {
        if (!force) {
            val c = verifyCache
            if (c != null && c.cookie == cookie &&
                System.currentTimeMillis() - c.at < VERIFY_TTL_MS
            ) {
                return c.value
            }
        }
        val jar = WeiboHttp.parseCookieString(cookie)
        val result = WeiboHttp.client
            .newCall(WeiboHttp.request("${WeiboHttp.BASE}/api/config", cookie))
            .execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw WeiboHttp.httpFailure("探活", resp.code)
                }
                // 服务端声明的到期时间：优先取登录关键字段的 Set-Cookie 属性
                val expiryByName = LinkedHashMap<String, Long>()
                for (header in resp.headers("set-cookie")) {
                    val parsed = Cookie.parse(resp.request.url, header) ?: continue
                    if (parsed.expiresAt > 0L) expiryByName[parsed.name] = parsed.expiresAt
                }
                WeiboHttp.mergeSetCookies(jar, resp)
                val body = resp.body?.string().orEmpty()
                val payload = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    // 风控页 / HTML 不是「未登录」：抛出去让调用方显示原因，别静默当成没登录
                    throw WeiboHttp.WeiboException(
                        "探活响应不是 JSON（前 60 字：${body.trim().take(60)}）",
                        identitySuspect = true,
                    )
                }
                val data = payload.optJSONObject("data") ?: JSONObject()
                val stRaw = data.optString("st", "")
                val merged = WeiboHttp.cookieToString(jar)
                Verify(
                    login = parseLoginFlag(data.opt("login")),
                    st = stRaw.ifEmpty { null },
                    cookie = merged,
                    uid = (data.optString("uid", "").ifEmpty { data.optString("user_id", "") }).trim(),
                    serverExpiresAt = WeiboHttp.serverExpiryFromHeaders(expiryByName, merged),
                    loginAt = CookieLife.loginTimeOf(merged),
                )
            }
        // 只缓存「已登录」的结果：把 false 缓存住会让登录页卡在「等待微博确认」。
        if (result.login) {
            verifyCache = CachedVerify(cookie, System.currentTimeMillis(), result)
        }
        return result
    }

    /**
     * `data.login` 的宽松解析：布尔 / 数字 / 字符串都认。
     *
     * 微博把 `true` 写成 `1`（或被序列化成 `"true"`）时，`optBoolean` 只会返回
     * false —— 登录页就会永久停在「等待微博确认登录态」，所以这里显式放宽。
     */
    fun parseLoginFlag(raw: Any?): Boolean = when (raw) {
        null -> false
        is Boolean -> raw
        is Number -> raw.toInt() == 1
        is String -> raw.trim().let { it == "1" || it.equals("true", ignoreCase = true) }
        else -> false
    }

    /** 昵称所在的键。微博各端两种写法都出现过，统一大小写口径后逐个试。 */
    private val NAME_KEYS = listOf("screen_name", "screenName", "nickname")

    /** 泛用键：只允许从「看起来像用户」的对象里取，否则会把卡片的 `name:"签到"` 当昵称。 */
    private val GENERIC_NAME_KEYS = listOf("name")

    /** 判断一个 dict 像不像「用户对象」：要有 id 类字段，且带一处用户专有字段。 */
    private val USER_ID_KEYS = listOf("idstr", "id", "uid", "user_id")
    private val USER_MARK_KEYS = listOf(
        "profile_image_url", "avatar_hd", "avatar_large", "avatar_url",
        "followers_count", "followers_count_str", "verified", "description",
    )

    /**
     * 从任意微博响应里挖出昵称 / UID / 头像（对齐面板 `_extract_weibo_profile`）。
     *
     * 为什么不能直接读某个固定字段：昵称散落在 `data.screen_name`（旧版 m 站）、
     * `data.userInfo.screen_name`（m 站容器，键名大小写随版本变）、
     * `data.user.screen_name`（Web 端 `ajax/profile/info`）三处，写死任一个都会在
     * 另两个端点上拿到空串 —— App 的「（未获取到昵称）」就是这么来的。
     *
     * 但**不能无脑递归取第一个 name**：超话卡片里 `buttons[i].name` 就是「签到 / 已签」，
     * 无差别取 `name` 会把昵称显示成「签到」。所以只认两类对象：
     * 1. 带 `screen_name` / `screenName` / `nickname` 的对象（键名本身就是用户专有）；
     * 2. 带 id 类字段**且**带头像/粉丝数/认证等用户标记的对象，才允许取泛用的 `name`。
     */
    fun extractProfile(payload: JSONObject): Profile {
        var name = ""
        var uid = ""
        var avatar = ""

        fun harvest(any: Any?) {
            when (any) {
                is JSONObject -> {
                    val keys = any.keys().asSequence().toSet()
                    val userLike = USER_MARK_KEYS.any { it in keys }
                    if (name.isEmpty()) {
                        val strict = NAME_KEYS.firstNotNullOfOrNull { k ->
                            any.optString(k, "").trim().ifEmpty { null }
                        }
                        val loose = if (userLike) {
                            GENERIC_NAME_KEYS.firstNotNullOfOrNull { k ->
                                any.optString(k, "").trim().ifEmpty { null }
                            }
                        } else {
                            null
                        }
                        name = strict ?: loose ?: ""
                    }
                    if (uid.isEmpty() && (userLike || NAME_KEYS.any { it in keys })) {
                        uid = USER_ID_KEYS.firstNotNullOfOrNull { k ->
                            any.opt(k)?.toString()?.trim()?.ifEmpty { null }
                        } ?: ""
                    }
                    if (avatar.isEmpty()) {
                        avatar = listOf("avatar_hd", "avatar_large", "profile_image_url", "avatar_url")
                            .firstNotNullOfOrNull { k ->
                                any.optString(k, "").trim()
                                    .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                            } ?: ""
                    }
                    any.keys().forEach { k -> harvest(any.opt(k)) }
                }

                is org.json.JSONArray -> for (i in 0 until any.length()) harvest(any.opt(i))
            }
        }
        harvest(payload)
        return Profile(name = name, uid = uid, avatarUrl = avatar)
    }

    /**
     * 取当前账号 uid 与昵称（登录成功后、昵称补拉时调用）。
     *
     * 三条链路依次尝试，与面板 `_fetch_weibo_profile` 同口径但顺序按「实测可用性」重排：
     * 1. **Web 端 `ajax/profile/info`** —— 实测游客 cookie 就能返回
     *    `data.user.screen_name`（`m.weibo.cn/api/config` 根本不带昵称字段，
     *    见 2026-10 实测：data 里只有 login/st/loginUrl）；
     * 2. m 站 `getIndex?type=uid` —— 键名大小写随版本变，用 [extractProfile] 容错解析；
     * 3. 都失败就返回空昵称，交给调用方用占位名，不阻断登录。
     *
     * [knownUid]：登录/探活已经拿到的 uid（/api/config 的 `data.uid`）。传进来可以省掉
     * 一次配置请求，也能在配置接口没回 uid 时仍然完成昵称补拉。
     */
    fun fetchProfile(cookie: String, knownUid: String = ""): Pair<String, String> {
        var uid = knownUid.trim()
        var name = ""

        // 1) 配置接口：主要为了 uid（以及拿不到 knownUid 时的兜底）
        if (uid.isEmpty()) {
            try {
                WeiboHttp.client
                    .newCall(WeiboHttp.request("${WeiboHttp.BASE}/api/config", cookie))
                    .execute().use { resp ->
                        if (resp.isSuccessful) {
                            val payload = JSONObject(resp.body?.string() ?: "{}")
                            val data = payload.optJSONObject("data") ?: JSONObject()
                            uid = (data.optString("uid", "").ifEmpty { data.optString("user_id", "") }).trim()
                            val parsed = extractProfile(payload)
                            if (uid.isEmpty()) uid = parsed.uid
                            name = parsed.name
                        }
                    }
            } catch (e: Exception) {
                // 网络/风控：继续走下面两条链路，昵称拿不到不该让登录流程失败
            }
        }
        if (name.isNotEmpty()) return name to uid
        if (uid.isEmpty()) return "" to ""

        // 2) Web 端用户资料：游客身份可用，是当前唯一稳定的昵称来源
        webProfileName(cookie, uid)?.let { return it to uid }
        // 3) m 站用户主页兜底（风控严格，常为空体；能拿到就用）
        mStationName(cookie, uid)?.let { return it to uid }
        return "" to uid
    }

    /** Web 端 `ajax/profile/info`：先账号身份，再游客身份。拿不到返回 null。 */
    private fun webProfileName(identity: String, uid: String): String? {
        if (identity.isNotEmpty()) {
            webUserName(identity, uid)?.let { return it }
        }
        // 账号 cookie 的域是 `.weibo.cn`，Web 端不一定认；游客 cookie 实测可用。
        GuestSession.cookie()?.let { guest ->
            webUserName(guest, uid)?.let { return it }
        }
        return null
    }

    /** m 站用户主页：`getIndex?type=uid`，昵称键名不固定，交给 [extractProfile] 容错。 */
    private fun mStationName(cookie: String, uid: String): String? = try {
        val url = "${WeiboHttp.BASE}/api/container/getIndex?type=uid&value=${WeiboHttp.q(uid)}"
        WeiboHttp.client.newCall(WeiboHttp.request(url, cookie)).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            extractProfile(JSONObject(body)).name.ifEmpty { null }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 任意 uid 的昵称（抓图目标显示用）。
     *
     * 两级：
     * 1. **游客身份走 Web 端 `ajax/profile/info`** —— 实测免登录可用，不消耗账号频次；
     *    添加抓图目标时即使一个账号都没有，也能把博主名显示出来。
     * 2. 游客不可用时回落 m 站 `type=uid`（要账号）。
     *
     * 失败一律返回空串：调用方只用来做展示兜底，不该因为它中断抓取。
     */
    fun fetchUserName(cookie: String?, uid: String): String {
        // 顺序与 fetchProfile 一致：Web 端（游客即可）稳定，m 站只作兜底
        val acc = cookie?.trim().orEmpty()
        if (acc.isNotEmpty()) webUserName(acc, uid)?.let { return it }
        GuestSession.cookie()?.let { guest ->
            webUserName(guest, uid)?.let { return it }
        }
        if (acc.isEmpty()) return ""
        return mStationName(acc, uid) ?: ""
    }

    /** Web 端用户信息（游客可用）。返回 null 表示「这条路拿不到」，交给调用方回落。 */
    private fun webUserName(identity: String, uid: String): String? = try {
        val req = Request.Builder()
            .url("${WeiboHttp.WEB_BASE}/ajax/profile/info?uid=${WeiboHttp.q(uid)}")
            .header("User-Agent", WeiboHttp.WEB_UA)
            .header("Referer", "${WeiboHttp.WEB_BASE}/u/$uid")
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Cookie", identity)
            .build()
        WeiboHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val payload = WeiboHttp.parseJson(resp.body?.string() ?: "{}", "用户信息")
            if (payload.optInt("ok", 0) != 1) return null
            // 正常是 data.user.screen_name；个别版本会包成 data.userInfo，交给容错解析
            extractProfile(payload).name.ifEmpty { null }
        }
    } catch (e: Exception) {
        null
    }

    /** 从用户输入解析 UID：纯数字 / 含 /u/ 或 profile/ 的链接 / 个性域名（302 跳转）。 */
    fun resolveUid(input: String): String {
        val text = input.trim()
        if (text.matches(Regex("^\\d+$"))) return text
        Regex("/u/(\\d+)").find(text)?.let { return it.groupValues[1] }
        Regex("profile/(\\d+)").find(text)?.let { return it.groupValues[1] }
        Regex("containerid=107603(\\d+)").find(text)?.let { return it.groupValues[1] }
        // 个性域名：m.weibo.cn/n/<name> 会 302 到 /u/<uid>
        val domain = Regex("([A-Za-z][A-Za-z0-9_-]*)/?$").find(text.trimEnd('/'))
            ?.groupValues?.get(1) ?: return ""
        return try {
            WeiboHttp.client.newCall(
                Request.Builder().url("${WeiboHttp.BASE}/n/$domain")
                    .header("User-Agent", WeiboHttp.UA).build()
            ).execute().use { resp ->
                resp.request.url.toString().let { finalUrl ->
                    Regex("/u/(\\d+)").find(finalUrl)?.groupValues?.get(1) ?: ""
                }
            }
        } catch (e: Exception) {
            ""
        }
    }
}
