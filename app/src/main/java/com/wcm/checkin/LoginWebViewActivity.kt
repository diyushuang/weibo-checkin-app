package com.wcm.checkin

import android.annotation.SuppressLint
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

/**
 * 微博登录页：账密 / 验证码全部在微博官方页面完成，本应用只读取登录后的
 * Cookie（CookieManager 持久化，关闭 App 不丢）。
 *
 * 判定登录成功的标准与服务端一致：SUB + (SCF|SSOLoginState|ALF|MLOGIN) 之一，
 * 且 m.weibo.cn/api/config 明确返回 login=true（裸 SUB 只是访客假登录）。
 */
class LoginWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

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
            // API 24+
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean = blockNavigation(request.url)

            // API 21–23 仍会走这个重载
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                blockNavigation(url.toUri())
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
     * 只允许「https + 微博域名」在 WebView 内导航。
     *
     * 登录页会多次 302 跳转，默认 WebViewClient 对目标不做任何限制：一旦被
     * 开放重定向带到第三方站点（或 `intent://` 之类的外链），WebView 会照单全收。
     * 这里直接拦下，只把提示写回状态栏。
     */
    private fun blockNavigation(uri: android.net.Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val allowed = scheme == "https" && WeiboApi.isWeiboHost(host)
        if (!allowed) {
            statusView.text = getString(R.string.login_blocked_nav, if (host.isEmpty()) uri.toString() else host)
        }
        return !allowed
    }

    private fun startLoginFlow() {
        if (isFinishing || isDestroyed) return
        // 与面板扫码登录同源的 wapsso 入口，成功后 302 回 m.weibo.cn
        webView.loadUrl(
            "https://passport.weibo.com/sso/signin?entry=wapsso&source=wapsso" +
                "&url=https%3A%2F%2Fm.weibo.cn%2F"
        )
        handler.postDelayed(::pollLogin, 1500)
    }

    private fun cookieString(): String {
        val cm = CookieManager.getInstance()
        val merged = LinkedHashMap<String, String>()
        // weibo.cn 域最后合并（同名 key 以 m 域为准，对齐服务端聚合语义）
        for (url in listOf(
            "https://passport.weibo.com", "https://weibo.com", "https://m.weibo.cn"
        )) {
            val raw = cm.getCookie(url) ?: continue
            WeiboApi.parseCookieString(raw).forEach { (k, v) -> merged[k] = v }
        }
        return WeiboApi.cookieToString(merged)
    }

    private fun looksLoggedIn(jar: Map<String, String>): Boolean {
        if (!jar.containsKey("SUB")) return false
        val realKeys = listOf("SCF", "SSOLoginState", "ALF", "MLOGIN")
        return realKeys.any { jar.containsKey(it) }
    }

    private fun pollLogin() {
        if (finished || isFinishing) return
        val cookie = cookieString()
        val jar = WeiboApi.parseCookieString(cookie)
        if (looksLoggedIn(jar)) {
            statusView.text = getString(R.string.login_found_verifying)
            Thread {
                val verify = try {
                    WeiboApi.verify(cookie)
                } catch (e: Exception) {
                    null
                }
                if (verify != null && verify.login) {
                    val (name, uid) = WeiboApi.fetchProfile(verify.cookie)
                    runOnUiThread {
                        finished = true
                        if (uid.isNotEmpty()) {
                            AccountStore.upsert(
                                this,
                                AccountStore.Account(
                                    uid = uid,
                                    name = name.ifEmpty { AccountStore.placeholderName(uid) },
                                    cookie = verify.cookie,
                                )
                            )
                            Toast.makeText(this, getString(R.string.login_added_fmt, name), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, R.string.login_uid_missing, Toast.LENGTH_SHORT).show()
                        }
                        finish()
                    }
                } else {
                    runOnUiThread {
                        // 服务端不认：多半还在跳转中，继续轮询
                        statusView.text = getString(R.string.login_found_waiting)
                        handler.postDelayed(::pollLogin, 1500)
                    }
                }
            }.start()
        } else {
            handler.postDelayed(::pollLogin, 1500)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
