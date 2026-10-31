package com.wcm.checkin

import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * 单 Activity 壳：底部导航（首页 / 图片 / 设置），Material 3。
 *
 * 三个页面用 add + show/hide 常驻（不 replace）：切换 Tab 不会销毁视图与
 * 进行中的签到/抓取协程，滚动位置与日志也得以保留。
 *
 * v0.7 起这里同时是**系统栏适配的唯一入口**：
 * - `enableEdgeToEdge()` 统一处理状态栏/导航栏透明与图标明暗（按系统深浅模式），
 *   并在三键导航栏上自动加对比度衬底，不再靠主题里写死 statusBarColor；
 * - 状态栏 + 挖孔（含横屏侧挖孔）高度变成 root 的上/左右内边距，
 *   导航栏高度的 inset 变成 bottomBar 的下内边距 —— 手势条、三键导航、
 *   横屏挖孔三种情况都不会压住内容；
 * - 键盘（IME）弹出时：底栏整块淡出，内容区底部垫到键盘顶，输入框不会被挡住。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var home: HomeFragment
    private lateinit var topics: TopicFragment
    private lateinit var gallery: GalleryFragment
    private lateinit var settings: SettingsFragment

    /** 键盘是否正把底栏顶掉（避免每一帧 insets 回调都重放动画）。 */
    private var bottomBarHidden = false

    /** M3 标准缓动 cubic-bezier(0.2, 0, 0, 1)：起步快、收尾稳，切页不"甩"。 */
    private val pageInterpolator by lazy { PathInterpolator(0.2f, 0f, 0f, 1f) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 setContentView 之前：让内容延伸到系统栏下方，
        // 之后所有让位都由下面的 insets 监听精确控制。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 后台保活是增值能力：WorkManager 初始化会打开本地数据库，万一失败
        // （数据库损坏 / 系统限制），不该连带首页一起起不来。
        runCatching { CheckInWorker.schedule(this) }

        restoreFragments()

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_topics -> topics
                R.id.nav_gallery -> gallery
                R.id.nav_settings -> settings
                else -> home
            }
            show(target)
            true
        }
        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_home
        }

        applySystemBarInsets()
    }

    /**
     * 建好 / 找回四个常驻页面。
     *
     * **不要**把「恢复」写成 `findFragmentByTag(...) as HomeFragment`：进程被系统回收后
     * 重建、或首次 `commit()` 还没执行完就发生配置变更（旋转/深色模式切换）时，
     * FragmentManager 里可能一个 tag 都没有 —— 对 null 做强转会直接抛
     * TypeCastException，表现为「一打开就闪退」。这里的口径是：
     * 找到就复用（保留其隐藏态与视图状态），没找到就补建并重新 add，
     * 补建的页面先隐藏（首屏固定是首页），等用户切 Tab 再显示。
     */
    private fun restoreFragments() {
        val fm = supportFragmentManager
        home = fm.findFragmentByTag(TAG_HOME) as? HomeFragment ?: HomeFragment()
        topics = fm.findFragmentByTag(TAG_TOPICS) as? TopicFragment ?: TopicFragment()
        gallery = fm.findFragmentByTag(TAG_GALLERY) as? GalleryFragment ?: GalleryFragment()
        settings = fm.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment ?: SettingsFragment()

        val tx = fm.beginTransaction()
        var pending = false
        for ((fragment, tag) in listOf(
            home to TAG_HOME,
            topics to TAG_TOPICS,
            gallery to TAG_GALLERY,
            settings to TAG_SETTINGS,
        )) {
            if (fragment.isAdded) continue
            tx.add(R.id.fragmentContainer, fragment, tag)
            if (fragment !== home) tx.hide(fragment)
            pending = true
        }
        if (pending) tx.commit()
    }

    private fun show(fragment: Fragment) {
        // 已经在前台的页面：只保留"点了就响应"的事实，不重放动画，也不白跑一次事务。
        // 用 isHidden 判断而不是记一个 tag 字段 —— 进程重建后 FragmentManager 会恢复
        // 各页面的隐藏状态，记字段反而会记错。
        if (!fragment.isHidden) return
        supportFragmentManager.beginTransaction()
            .hide(home).hide(topics).hide(gallery).hide(settings)
            .show(fragment)
            .commit()
        playEnter(fragment.view)
    }

    /**
     * 跳到「超话」Tab 并选中指定账号（首页点账号行 / Hero 卡时调用）。
     *
     * 先选账号再切 Tab：超话页可能还没建视图，`selectAccount` 会把 uid 暂存下来，
     * 在 onViewCreated 里消费，用户看到的直接就是那个账号的列表。
     */
    fun selectTopicTab(uid: String) {
        topics.selectAccount(uid)
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (nav.selectedItemId == R.id.nav_topics) show(topics) else nav.selectedItemId = R.id.nav_topics
    }

    fun selectHomeTab() {
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_home
    }

    /**
     * 跳到「设置」Tab（首页 Cookie 卡上的「去设置配置面板」用）。
     *
     * 与 [selectTopicTab] 同一套写法：先看底栏当前选中的是不是它，是就直接 show ——
     * 直接改 `selectedItemId` 时，若已经在设置页则不会触发 listener，页面看起来没反应。
     */
    fun selectSettingsTab() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (nav.selectedItemId == R.id.nav_settings) show(settings) else nav.selectedItemId = R.id.nav_settings
    }

    /** 底栏「超话」上的待签角标；[count] 为 null 或 ≤0 时移除。 */
    fun setTopicBadge(count: Int?) {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (count == null || count <= 0) {
            nav.removeBadge(R.id.nav_topics)
            return
        }
        val badge = nav.getOrCreateBadge(R.id.nav_topics)
        badge.isVisible = true
        badge.maxCharacterCount = 3
        badge.number = count
        badge.backgroundColor = ContextCompat.getColor(this, R.color.brand)
    }

    /**
     * 切页的入场动效：淡入 + 上移 8dp / 180ms。
     *
     * 只做"入场"不做"出场"——三个页面是常驻隐藏的，出场动画会让上一步的内容
     * 在隐藏前多停留一帧，反而显得迟疑。单一方向也让操作可预期。
     * 系统「移除动画」（开发者选项/无障碍）打开时直接跳过，尊重用户的动效偏好。
     */
    private fun playEnter(view: View?) {
        if (view == null) return
        if (!animationsEnabled()) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = 8 * resources.displayMetrics.density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(pageInterpolator)
            .start()
    }

    /** 用户把系统动画时长设为 0（或无障碍「移除动画」）时返回 false。 */
    private fun animationsEnabled(): Boolean = try {
        Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    } catch (e: Exception) {
        true
    }

    /**
     * 系统栏 / 挖孔 / 键盘的让位规则（targetSdk 35 起系统强制边到边）：
     *
     * - root 上内边距 = 状态栏 + 挖孔：内容不会钻进状态栏与刘海/挖孔；
     * - root 左右内边距 = 横屏时的侧挖孔或手势区，竖屏通常为 0；
     * - bottomBar 下内边距 = 导航栏高度：手势条（约 24dp）与三键导航（约 48dp）
     *   自动各就各位，不用自己判断导航模式；
     * - 键盘弹出（ime inset > 0）：底栏淡出、内容区底部垫到键盘顶。
     */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.rootLayout)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val content = findViewById<View>(R.id.fragmentContainer)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val keyboardShown = imeBottom > 0

            root.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            bottomBar.updatePadding(bottom = bars.bottom)
            // 键盘弹出时内容区底部垫到键盘顶；否则不留额外内边距（底栏自己占位）
            content.updatePadding(bottom = if (keyboardShown) imeBottom else 0)
            setBottomBarHidden(keyboardShown)

            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setBottomBarHidden(hidden: Boolean) {
        if (bottomBarHidden == hidden) return
        bottomBarHidden = hidden
        val bar = findViewById<View>(R.id.bottomBar)
        bar.animate().cancel()
        if (!animationsEnabled()) {
            bar.visibility = if (hidden) View.GONE else View.VISIBLE
            bar.alpha = 1f
            return
        }
        if (hidden) {
            bar.animate().alpha(0f).setDuration(120L).withEndAction {
                // 动画期间可能已被重新唤出，落地前再确认一次状态
                if (bottomBarHidden) bar.visibility = View.GONE
            }.start()
        } else {
            bar.visibility = View.VISIBLE
            bar.alpha = 0f
            bar.animate().alpha(1f).setDuration(150L).start()
        }
    }

    private companion object {
        const val TAG_HOME = "home"
        const val TAG_TOPICS = "topics"
        const val TAG_GALLERY = "gallery"
        const val TAG_SETTINGS = "settings"
    }
}
