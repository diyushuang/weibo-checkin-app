package com.wcm.checkin

import android.content.Context
import androidx.core.content.edit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 游客身份（免登录会话）。
 *
 * 微博的 `weibo.com/ajax/…` 读取类接口不要求账号，只要求一份「访客 cookie」。
 * 访客 cookie 的签发入口在护照端，**必须走两步握手**：
 *
 * ```text
 * 1) POST https://passport.weibo.com/visitor/genvisitor2
 *      body: cb=visitor_gray_callback&ver=20250916&tid=&from=weibo&webdriver=false&return_url=https://weibo.com/
 *      响应是 JSONP：visitor_gray_callback({...});  取 data.tid / data.sub / data.subp
 * 2) GET  https://passport.weibo.com/visitor/visitor
 *      ?a=crossdomain&t=<tid>&s=<sub>&sp=<subp>&from=weibo&_rand=<随机数>&url=https://weibo.com/
 *      响应 Set-Cookie 里是 SUB / SUBP（domain=.weibo.com，约一年有效）
 * ```
 *
 * **不要**再用 H5 端的单跳入口（`visitor.passport.weibo.cn/visitor/visitor?entry=sinawap`）：
 * 实测它返回的是 JS 驱动的 `Sina Visitor System` 页面，Set-Cookie 里只有 `WEIBOCN_FROM`，
 * 拿不到 `_T_WM`（见 `docs/research/weibo-guest-endpoint-audit.md` §2.3）。
 *
 * 三个刻意的设计：
 * 1. **不跟随重定向**：cookie 就在 302/200 的 Set-Cookie 里，跟随后反而拿不全；
 * 2. **拿到就探活**：只有 weibo.com 侧真能返回 JSON 的才落盘，避免把风控页的假
 *    cookie 缓存一年。探活接口本身可能随版本变化，因此「接口不存在 / 5xx」判为
 *    **无法判定**并仍然采信（拿到 SUB/SUBP 已是很强的信号），只有明确的 401/403/432
 *    才算被拒 —— 否则一次接口改版就会把可用身份判死；
 * 3. **365 天缓存**：与社区实现（gallery-dl）同口径；失效时由 [refresh] 重取。
 *
 * 全程不抛异常：拿不到就返回 null，让调用方回落到账号身份。抓图可用性不允许被它拖垮。
 */
@Suppress("StaticFieldLeak")   // appCtx 存的是 applicationContext（进程级），见字段处注释
object GuestSession {

    private const val PREFS = "wcm_guest"
    private const val KEY_COOKIE = "cookie_v1"
    private const val KEY_AT = "at_v1"

    /** 游客 cookie 的缓存有效期。社区实测与 gallery-dl 同口径：一年。 */
    private const val TTL_MS = 365L * 24 * 3600 * 1000

    /** 单次获取最多握手几轮（第一轮失败时立刻重试一次，避开偶发风控抖动）。 */
    private const val HANDSHAKE_ATTEMPTS = 2

    /** [refresh] 的合并窗口：这段时间内刚握手成功过就不再重复握手。 */
    private const val REFRESH_REUSE_MS = 30_000L

    private const val GEN_VISITOR = "https://passport.weibo.com/visitor/genvisitor2"
    private const val VISITOR = "https://passport.weibo.com/visitor/visitor"
    private const val WEB_HOME = "https://weibo.com/"

    /** 探活用的 Web 端配置接口（L1，只需游客 cookie）。 */
    private const val PROBE_URL = "https://weibo.com/ajax/statuses/config"

    /** 握手用的表单版本号：与 2026-10 实测页面保持一致。 */
    private const val GEN_VISITOR_VER = "20250916"

    // lint: StaticFieldLeak —— 存的是 applicationContext，生命周期与进程一致，
    // 不存在 Activity/Fragment 泄漏；单例必须持一份 ctx 才能读写 SharedPreferences。
    @Suppress("StaticFieldLeak")
    @Volatile private var appCtx: Context? = null
    @Volatile private var cached: String? = null
    @Volatile private var cachedAt: Long = 0L

    /** 最近一次获取失败的原因（给抓取日志用，不参与逻辑判断）。 */
    @Volatile var lastError: String? = null
        private set

    private val gate = Any()

    /** 握手专用 client：**必须关掉自动重定向**，否则 Set-Cookie 会被跟丢。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /** 只读缓存，不触发任何网络请求（用于判断某个 cookie 是不是游客身份）。 */
    fun peek(): String? = cached?.takeIf {
        cachedAt > 0 && System.currentTimeMillis() - cachedAt < TTL_MS
    }

    /**
     * 取一份可用的游客 cookie：内存 → 磁盘 → 现场握手。
     * 失败返回 null（原因见 [lastError]），调用方应回落到账号身份。
     */
    fun cookie(): String? {
        synchronized(gate) {
            // 双检：并发进来的线程只握手一次
            peek()?.let { return it }
            // 磁盘读与缓存写都必须在锁内：这两个字段是 [handshake] / [clear]
            // 共用的状态。放在锁外会与「刚判失效的 clear()」竞争 ——
            // 旧 cookie 被写回内存、cachedAt 刷成当前时间，又能再活 365 天。
            val disk = loadDisk()
            if (disk != null && System.currentTimeMillis() - disk.second < TTL_MS) {
                cached = disk.first
                cachedAt = disk.second
                return disk.first
            }
            return handshake()
        }
    }

    /**
     * 强制丢弃缓存并重新握手（游客 cookie 被判失效时调用）。
     *
     * 调用方：`Crawler` 在相册墙 / 单帖详情返回「身份可疑」错误时，先刷新一次再重试
     * —— 少了这一步，一次风控就会让整轮相册抓取退化到时间线模式。
     */
    fun refresh(): String? {
        synchronized(gate) {
            // 并发失效（多线程同时撞「身份可疑」）会排队各握手一次，每次两个请求。
            // 刚刷新成功的话直接复用：REFRESH_REUSE_MS 内的多次 refresh 合并成一次。
            val age = System.currentTimeMillis() - cachedAt
            peek()?.let { if (cachedAt > 0 && age in 0 until REFRESH_REUSE_MS) return it }
            clear()
            return handshake()
        }
    }

    private fun clear() {
        cached = null
        cachedAt = 0L
        val ctx = appCtx ?: return
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                remove(KEY_COOKIE)
                remove(KEY_AT)
            }
        } catch (e: Exception) {
            // 磁盘清不掉不影响正确性：内存已清，下次会重新握手
        }
    }

    private fun loadDisk(): Pair<String, Long>? {
        val ctx = appCtx ?: return null
        return try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cookie = SafePrefs.string(p, KEY_COOKIE)
            val at = SafePrefs.long(p, KEY_AT, 0L)
            if (cookie.isEmpty() || at <= 0L) null else cookie to at
        } catch (e: Exception) {
            null
        }
    }

    private fun saveDisk(cookie: String, at: Long) {
        val ctx = appCtx ?: return
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString(KEY_COOKIE, cookie)
                putLong(KEY_AT, at)
            }
        } catch (e: Exception) {
            // 落盘失败只是下次启动要重新握手，不影响本次使用
        }
    }

    /** 握手 → 探活 → 落盘。任何一步失败都返回 null。 */
    private fun handshake(): String? {
        lastError = null
        repeat(HANDSHAKE_ATTEMPTS) { attempt ->
            val cookie = try {
                requestVisitorCookie()
            } catch (e: Exception) {
                lastError = "访客系统请求失败：${e.message}"
                null
            }
            if (cookie.isNullOrEmpty()) {
                if (lastError == null) lastError = "访客系统未下发 cookie"
                return@repeat
            }
            when (probe(cookie)) {
                ProbeResult.OK -> {
                    cached = cookie
                    cachedAt = System.currentTimeMillis()
                    lastError = null
                    saveDisk(cookie, cachedAt)
                    return cookie
                }
                // 接口改版 / 5xx：判不了，但已经拿到 SUB 说明握手是成功的，采信
                ProbeResult.UNKNOWN -> {
                    cached = cookie
                    cachedAt = System.currentTimeMillis()
                    lastError = "游客 cookie 已获取，但探活接口无法访问（已按可用处理）"
                    saveDisk(cookie, cachedAt)
                    return cookie
                }
                ProbeResult.REJECTED -> {
                    lastError = "游客 cookie 探活被拒（第 ${attempt + 1} 次）"
                }
            }
        }
        return null
    }

    /** 两步握手：第一步拿 tid/sub/subp，第二步换 SUB/SUBP。 */
    private fun requestVisitorCookie(): String? {
        val tip = genVisitor() ?: return null
        return crossDomain(tip)
    }

    /** 第一步：genvisitor2 → JSONP 里的 `data.tid / sub / subp`。 */
    private fun genVisitor(): Tip? {
        val body = FormBody.Builder()
            .add("cb", "visitor_gray_callback")
            .add("ver", GEN_VISITOR_VER)
            .add("tid", "")
            .add("from", "weibo")
            .add("webdriver", "false")
            .add("return_url", WEB_HOME)
            .build()
        val req = Request.Builder().url(GEN_VISITOR)
            .post(body)
            .header("User-Agent", WeiboApi.WEB_UA)
            .header("Referer", WEB_HOME)
            .header("Origin", "https://weibo.com")
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                lastError = "genvisitor2 返回 HTTP ${resp.code}"
                return null
            }
            val text = resp.body?.string().orEmpty()
            val data = extractJsonpData(text) ?: run {
                lastError = "genvisitor2 响应无法解析（前 60 字：${text.trim().take(60)}）"
                return null
            }
            val tid = data.optString("tid", "").trim()
            if (tid.isEmpty()) {
                lastError = "genvisitor2 未返回 tid"
                return null
            }
            return Tip(
                tid = tid,
                sub = data.optString("sub", "").trim(),
                subp = data.optString("subp", "").trim(),
            )
        }
    }

    /** 第二步：crossdomain → Set-Cookie 里的 SUB/SUBP。 */
    private fun crossDomain(tip: Tip): String? {
        val url = "$VISITOR?a=crossdomain" +
            "&t=${enc(tip.tid)}" +
            (if (tip.sub.isEmpty()) "" else "&s=${enc(tip.sub)}") +
            (if (tip.subp.isEmpty()) "" else "&sp=${enc(tip.subp)}") +
            "&from=weibo&_rand=${Random.nextDouble()}&url=${enc(WEB_HOME)}"
        val req = Request.Builder().url(url)
            .header("User-Agent", WeiboApi.WEB_UA)
            .header("Referer", WEB_HOME)
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            val jar = LinkedHashMap<String, String>()
            for (header in resp.headers("set-cookie")) {
                val kv = header.substringBefore(';')
                val key = kv.substringBefore('=').trim()
                val value = kv.substringAfter('=', "").trim()
                if (key.isNotEmpty() && value.isNotEmpty()) jar[key] = value
            }
            if (jar.isEmpty()) {
                lastError = "crossdomain 未下发 cookie（HTTP ${resp.code}）"
                return null
            }
            return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    /**
     * 从 JSONP 里剥出 `data` 对象。
     *
     * 响应形如 `visitor_gray_callback({"retcode":20000000,"data":{...}});`，
     * 少数版本直接返回裸 JSON —— 两种都认。
     */
    private fun extractJsonpData(raw: String): JSONObject? {
        val text = raw.trim()
        val start = text.indexOf('(')
        val end = text.lastIndexOf(')')
        val payload = if (start >= 0 && end > start) text.substring(start + 1, end) else text
        return try {
            JSONObject(payload).optJSONObject("data")
        } catch (e: Exception) {
            null
        }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private enum class ProbeResult { OK, REJECTED, UNKNOWN }

    /**
     * 探活：`weibo.com/ajax/statuses/config` 返回 JSON 即认为游客身份可用。
     *
     * **不能用 m 站的 `/api/config`**：游客 cookie 的域是 `.weibo.com`，它在 m 站本来
     * 就不被接受（实测 `ok:-100`），拿它判活只会把可用身份判死。
     */
    private fun probe(cookie: String): ProbeResult {
        val req = Request.Builder().url(PROBE_URL)
            .header("User-Agent", WeiboApi.WEB_UA)
            .header("Referer", WEB_HOME)
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Cookie", cookie)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 || resp.code == 432 -> ProbeResult.REJECTED
                    !resp.isSuccessful -> ProbeResult.UNKNOWN
                    else -> {
                        val text = resp.body?.string().orEmpty()
                        if (text.trimStart().startsWith("{")) ProbeResult.OK else ProbeResult.REJECTED
                    }
                }
            }
        } catch (e: Exception) {
            lastError = "探活请求异常：${e.message}"
            ProbeResult.UNKNOWN
        }
    }

    private class Tip(val tid: String, val sub: String, val subp: String)
}
