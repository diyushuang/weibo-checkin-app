package com.wcm.checkin

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 微博登录页：账密 / 验证码全部在微博官方页面完成，本应用只读取登录后的
 * Cookie（CookieManager 持久化，关闭 App 不丢）。
 *
 * 判定登录成功的标准与服务端一致：SUB + (SCF|SSOLoginState|ALF|MLOGIN) 之一，
 * 且 m.weibo.cn/api/config 明确返回 login=true（裸 SUB 只是访客假登录）。
 *
 * v0.9.2 修的两件事（症状：一直停在「已检测到 Cookie，等待微博确认登录态」）：
 * 1. 导航白名单补上新浪 SSO 域 —— 少了 `login.sina.com.cn` 这一跳，登录态只会
 *    落到 `.weibo.com`，`m.weibo.cn` 拿不到会话，于是本地看着像登录成功、服务端
 *    一律判未登录；
 * 2. 轮询不再无声死循环：两种 cookie 合并口径都试、失败两次后重新走一次 SSO
 *    把 `.weibo.cn` 的会话补齐，并且把失败原因写进状态行、支持点按立即重试。
 *
 * v0.9.3 换掉了登录入口（症状：登录成功却跳到游客浏览的微博首页、判定未登录）：
 * 原来手拼的是主站通道 `sso/signin?entry=wapsso&source=wapsso`，登录态只写进
 * `.weibo.com`；回到 `m.weibo.cn` 时 m 站没有会话，被 visitor 系统发了一个访客 SUB。
 * 现改用 m 站官方入口 `passport.weibo.cn/signin/login?r=…`（服务端会重定向成
 * `source=wapssowb`），登录态直接落在 `.weibo.cn`。详见 [startLoginFlow]。
 */
class LoginWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())

    /** 已成功入库：此后不再轮询。 */
    private var finished = false

    /** 正在验证 / 保存：重入保护，避免轮询叠加出并发请求。 */
    private var busy = false

    /** 连续「拿到 Cookie 但服务端判未登录」的次数（网络类失败不计）。 */
    private var failedChecks = 0

    /** 是否已经主动打开过一次 m.weibo.cn 补会话（只做一次）。 */
    private var nudged = false

    /** 已验证通过但没取到 uid 时的重试次数。 */
    private var uidRetries = 0

    /**
     * 最近一次被导航白名单拦下的目标。
     *
     * 必须单独留一份：状态行每轮轮询都会被重写，原来那条「已拦截跳转到…」的提示
     * 会在 1.5 秒内被「等待微博确认登录态」覆盖 —— 而拦截恰恰是登录卡住的最常见
     * 原因，提示被吃掉等于把现场抹掉。
     */
    private var blockedHost: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // 登录页同样走边到边：微博页面自己会处理安全区，但顶部那两行提示文字
        // 需要让出状态栏，否则文字会压到时间/电量上。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        webView = findViewById(R.id.webView)
        statusView = findViewById(R.id.loginStatus)

        findViewById<View>(R.id.loginRoot)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.updatePadding(
                    top = bars.top,
                    bottom = bars.bottom,
                    left = bars.left,
                    right = bars.right,
                )
                WindowInsetsCompat.CONSUMED
            }
        }

        // 卡住时不用退出重进：点一下状态行立刻重新检测（会把重试计数清零）
        statusView.setOnClickListener { retryNow() }

        webView.settings.apply {
            javaScriptEnabled = true          // 登录页必需
            domStorageEnabled = true
            // 登录只加载微博自家的 https 页面，下面这些能力一个都不需要：
            // 关掉它们可以避免 file:// / content:// 被页面脚本读取（minSdk 23 时
            // 系统默认是允许的，必须显式关闭）。
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }
        webView.webViewClient = object : android.webkit.WebViewClient() {
            // 页面真的开始加载了 → 之前那条拦截提示已经过期，清掉，别在状态行里
            // 一直挂着误导人（拦截发生时不会有新的页面开始加载，所以清得干净）。
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                blockedHost = null
            }

            // API 24+
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean = blockNavigation(view, request.url)

            // API 21–23 仍会走这个重载
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                blockNavigation(view, url.toUri())
        }

        val cm = CookieManager.getInstance()
        // 每次进入都是干净会话：添加第二个账号时不继承第一个的登录。
        // 清库是异步的，等回调确认清完再加载页面，避免轮询读到残留 cookie 误判已登录。
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        cm.removeAllCookies {
            runOnUiThread { startLoginFlow() }
        }
    }

    /**
     * 只允许「https + 微博域 + 新浪护照/SSO 域」在 WebView 内导航。
     *
     * 登录页会多次 302 跳转，默认 WebViewClient 对目标不做任何限制：一旦被
     * 开放重定向带到第三方站点（或 `intent://` 之类的外链），WebView 会照单全收。
     * 这里直接拦下，只把提示写回状态栏。
     *
     * 注意：`passport.sina.cn` / `login.sina.com.cn` 这类新浪护照域是登录链路的
     * 必经一跳（跨域分发会话），拦掉它就等于拦掉登录态写回 `.weibo.cn` 的最后一步
     * （完整白名单口径见 [WeiboApi.isLoginHost]）。
     * 顺带把少量仍指向 http 的 302 升级到 https，而不是直接判死。
     */
    private fun blockNavigation(view: WebView, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (host.isNotEmpty() && WeiboApi.isLoginHost(host)) {
            if (scheme == "https") return false
            if (scheme == "http") {
                view.loadUrl(uri.buildUpon().scheme("https").build().toString())
                return true
            }
        }
        blockedHost = blockedLabel(uri)
        android.util.Log.w("WcmLogin", "blocked navigation: $uri")
        setStatus(getString(R.string.login_waiting))
        return true
    }

    /** 状态行上的拦截标识：host + path，便于一眼看出是哪一跳被拦（截断防刷屏）。 */
    private fun blockedLabel(uri: Uri): String {
        val host = uri.host?.lowercase().orEmpty()
        if (host.isEmpty()) return uri.toString().take(70)
        return (host + uri.path.orEmpty()).take(70)
    }

    /** 写状态行；有拦截记录时把它挂在第二行，别被轮询重写吃掉。 */
    private fun setStatus(base: String) {
        val blocked = blockedHost
        statusView.text = if (blocked == null) {
            base
        } else {
            base + "\n" + getString(R.string.login_blocked_nav, blocked)
        }
    }

    private fun startLoginFlow() {
        if (isFinishing || isDestroyed) return
        // 必须走 m 站自己的登录入口，不能用主站通道手拼 sso/signin：
        //
        //   https://passport.weibo.cn/signin/login?r=https://m.weibo.cn/
        //      → 302 https://passport.weibo.com/sso/signin?entry=wapsso&source=wapssowb&url=https://m.weibo.cn/
        //
        // 差别在 source：wapsso 是主站通道（/sso/v2/web/config 回 first_show=show_sms、
        // reg_url=weibo.com/signup），登录态只写进 `.weibo.com`；wapssowb 才是 m 站通道
        // （reg_url=m.weibo.cn/reg/index），登录态写进 `.weibo.cn`。
        // 拿主站通道登完再回 m.weibo.cn，m 站没有会话 → 302 到
        // visitor.passport.weibo.cn 发一个**访客 SUB** → 就是「登录成功却跳到游客首页」。
        webView.loadUrl(
            "https://passport.weibo.cn/signin/login?r=" +
                android.net.Uri.encode("https://m.weibo.cn/")
        )
        handler.postDelayed(::pollLogin, POLL_MS)
    }

    /**
     * 让 SSO 把已有登录态再分发一次。
     *
     * 已经登录过（`.weibo.com` 上有 SUB/ALF/SSOLoginState）时再访问一次登录入口，
     * SSO 不会重新要账号密码，而是直接凭 SSO 会话跳回 `r=` 指定的 m 站，顺带把
     * `.weibo.cn` 的会话补上 —— 这是「主站已登录、m 站还是访客」时唯一干净的修法。
     * 直接打开 m.weibo.cn 反而会被 visitor 系统塞一个访客 SUB，越弄越糟。
     */
    private fun resyncSso() {
        if (isFinishing || isDestroyed) return
        webView.loadUrl(
            "https://passport.weibo.cn/signin/login?r=" +
                android.net.Uri.encode("https://m.weibo.cn/")
        )
    }

    /**
     * 读出两套 cookie 合并口径。
     *
     * 微博 SSO 会在 `.weibo.com` 与 `.weibo.cn` 各下一份 SUB，主站与 m 站谁先建好
     * 会话并不固定；原来固定「m 域覆盖 passport/weibo.com」的合并顺序，一旦 m 域
     * 只剩访客 SUB，就会把主站那份真正有效的 SUB 覆盖掉，探活永远返回未登录。
     * 这里两套顺序都返回，先验先命中的那套。
     */
    private fun cookieVariants(): List<String> {
        val cm = CookieManager.getInstance()
        // 下标固定：0=passport 1=weibo.com 2=m.weibo.cn，后面的顺序只是为了显式表达优先级
        val byDomain = listOf(
            "https://passport.weibo.com", "https://weibo.com", "https://m.weibo.cn"
        ).map { url -> WeiboApi.parseCookieString(cm.getCookie(url).orEmpty()) }

        fun merge(order: List<Int>): String {
            val merged = LinkedHashMap<String, String>()
            order.forEach { idx -> byDomain[idx].forEach { (k, v) -> merged[k] = v } }
            return WeiboApi.cookieToString(merged)
        }

        return listOf(
            merge(listOf(0, 1, 2)),   // m 域优先（原口径）
            merge(listOf(0, 2, 1)),   // 主站优先：m 域只剩访客 SUB 时的兜底
        ).distinct()
    }

    private fun looksLoggedIn(jar: Map<String, String>): Boolean {
        if (!jar.containsKey("SUB")) return false
        val realKeys = listOf("SCF", "SSOLoginState", "ALF", "MLOGIN")
        return realKeys.any { jar.containsKey(it) }
    }

    private fun retryNow() {
        // 正在验证时不叠加：那一轮的结束回调一定会再排下一次轮询
        if (finished || busy) return
        handler.removeCallbacksAndMessages(null)
        failedChecks = 0
        nudged = false
        uidRetries = 0
        setStatus(getString(R.string.login_waiting))
        pollLogin()
    }

    private fun pollLogin() {
        if (finished || busy || isFinishing || isDestroyed) return

        val candidates = cookieVariants().filter { looksLoggedIn(WeiboApi.parseCookieString(it)) }
        if (candidates.isEmpty()) {
            // 还没登录（或只是访客态）：保持静默轮询
            handler.postDelayed(::pollLogin, POLL_MS)
            return
        }
        verifyAsync(candidates)
    }

    /**
     * 逐个变体验证，取第一个「服务端明确认登录」的结果。
     *
     * 两套口径都试是有意的：微博在 `.weibo.com` / `.weibo.cn` 上各有一份 SUB，
     * 合成一个 Cookie 头时同名后者覆盖前者，而真正被 m 站认的可能恰恰是被覆盖的那份。
     */
    private fun verifyAsync(candidates: List<String>) {
        busy = true
        setStatus(getString(R.string.login_found_verifying))
        val notLoggedIn = getString(R.string.login_verify_login_false)

        // 用 lifecycleScope：页面关掉就自动取消，不再有「Activity 已销毁、后台线程还在跑
        // 25 秒」的裸 Thread；回调也不再依赖 runOnUiThread。
        lifecycleScope.launch {
            val (verified, reason) = withContext(Dispatchers.IO) {
                var ok: WeiboApi.Verify? = null
                var why: String? = null
                for (cookie in candidates) {
                    try {
                        val v = WeiboApi.verify(cookie)
                        if (v.login) {
                            ok = v
                            break
                        }
                        why = notLoggedIn
                    } catch (e: Exception) {
                        // 网络 / 风控 / 非 JSON：如实记录，别再吞成「等待中」
                        why = e.message ?: e.javaClass.simpleName
                    }
                }
                ok to why
            }
            busy = false
            if (isFinishing || isDestroyed) return@launch
            if (verified != null) onVerified(verified) else onVerifyFailed(reason)
        }
    }

    private fun onVerified(verify: WeiboApi.Verify) {
        if (finished) return
        busy = true
        setStatus(getString(R.string.login_found_verifying))
        // CookieManager 落盘是异步的：拿到有效登录态就立刻 flush，
        // 免得紧接着被系统回收、下次启动又是未登录
        CookieManager.getInstance().flush()

        lifecycleScope.launch {
            // 把探活已经拿到的 uid 传进去：即使 /api/config 这次没回 uid，也能靠它去
            // Web 端把昵称取回来（旧实现只读 data.screen_name，常年是空串）
            val profile = withContext(Dispatchers.IO) {
                WeiboApi.fetchProfile(verify.cookie, verify.uid)
            }
            val uid = profile.second.ifEmpty { verify.uid }
            busy = false
            if (isFinishing || isDestroyed) return@launch
            when {
                uid.isNotEmpty() -> {
                    finished = true
                    val name = profile.first.ifEmpty { AccountStore.placeholderName(uid) }
                    val now = System.currentTimeMillis()
                    AccountStore.upsert(
                        this@LoginWebViewActivity,
                        AccountStore.Account(
                            uid = uid,
                            name = name,
                            cookie = verify.cookie,
                            // 新录入的登录态：有效期从这里起算（7 天后到期，见 CookieLife）
                            cookieIssuedAt = now,
                            cookieSeenAt = now,
                        )
                    )
                    Toast.makeText(this@LoginWebViewActivity, getString(R.string.login_added_fmt, name), Toast.LENGTH_SHORT).show()
                    finish()
                }
                // 登录态成立但配置接口没给 uid：再轮几次（多为刚跳转完、字段还没回填）
                uidRetries < UID_RETRIES -> {
                    uidRetries++
                    setStatus(getString(R.string.login_uid_pending))
                    handler.postDelayed(::pollLogin, POLL_MS)
                }
                else -> {
                    finished = true
                    Toast.makeText(this@LoginWebViewActivity, R.string.login_uid_missing, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun onVerifyFailed(reason: String?) {
        if (finished) return
        failedChecks++

        // 只拿到 Cookie、m 站却不认：多半是登录态只落在 `.weibo.com`。
        // 再走一次 SSO（不会重新要账号密码），让它把 `.weibo.cn` 的会话补上再验。
        if (!nudged && failedChecks >= NUDGE_AFTER) {
            nudged = true
            setStatus(getString(R.string.login_sync_sso))
            resyncSso()
            handler.postDelayed(::pollLogin, NUDGE_POLL_MS)
            return
        }

        val detail = reason?.trim()?.take(MAX_REASON_CHARS).orEmpty()
        setStatus(
            if (detail.isEmpty()) {
                getString(R.string.login_found_waiting)
            } else {
                getString(R.string.login_verify_failed_fmt, detail)
            }
        )
        handler.postDelayed(::pollLogin, if (failedChecks >= SLOW_AFTER) SLOW_POLL_MS else POLL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        /** 常规轮询间隔。 */
        const val POLL_MS = 1_500L

        /** 连续失败后的退避间隔（别把探活打成请求风暴）。 */
        const val SLOW_POLL_MS = 4_000L

        /** 第几次失败后主动打开一次 m 站补会话。 */
        const val NUDGE_AFTER = 2
        const val NUDGE_POLL_MS = 2_500L

        /** 连续失败到第几次开始退避。 */
        const val SLOW_AFTER = 4

        /** uid 缺失时的重试次数。 */
        const val UID_RETRIES = 3

        /** 状态行里显示的错误原因长度上限。 */
        const val MAX_REASON_CHARS = 60
    }
}
