package com.wcm.checkin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

private const val ROUTE_HOME = "home"
private const val ROUTE_TOPICS = "topics"
private const val ROUTE_GALLERY = "gallery"
private const val ROUTE_SETTINGS = "settings"

/**
 * 单 Activity + Navigation Compose 壳：四个 Tab 都是纯 Compose 目的地。
 *
 * ViewModel 挂在 Activity 上（而不是每个目的地），切 Tab 不丢筛选/展开/日志/
 * 正在跑的签到状态；登录 WebView 仍是独立 Activity。
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val topicViewModel: TopicViewModel by viewModels()
    private val galleryViewModel: GalleryViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private val badgeState = MutableStateFlow<Int?>(null)
    private var navController: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 后台保活是增值能力：WorkManager 初始化会打开本地数据库，万一失败
        // （数据库损坏 / 系统限制），不该连带首页一起起不来。
        runCatching { CheckInWorker.schedule(this) }

        val view = androidx.compose.ui.platform.ComposeView(this)
        view.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        view.setContent {
            val nav = rememberNavController()
            navController = nav
            WcmTheme { AppShell(nav, homeViewModel, topicViewModel, galleryViewModel, settingsViewModel, badgeState) }
        }
        setContentView(view)
    }

    /** 跳到「超话」Tab 并选中指定账号（首页点账号行 / Hero 卡时调用）。 */
    fun selectTopicTab(uid: String) {
        topicViewModel.selectAccount(uid)
        navigateTab("topics")
    }

    fun selectHomeTab() = navigateTab("home")

    fun selectSettingsTab() = navigateTab("settings")

    /** 底栏「超话」上的待签角标；[count] 为 null 或 ≤0 时移除。 */
    fun setTopicBadge(count: Int?) {
        badgeState.value = if (count == null || count <= 0) null else count
    }

    private fun navigateTab(route: String) {
        val nav = navController ?: return
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

}

@Composable
private fun AppShell(
    nav: NavHostController,
    homeViewModel: HomeViewModel,
    topicViewModel: TopicViewModel,
    galleryViewModel: GalleryViewModel,
    settingsViewModel: SettingsViewModel,
    badgeState: MutableStateFlow<Int?>,
) {
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: ROUTE_HOME
    val badge by badgeState.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg))
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            ),
    ) {
        Box(modifier = Modifier.weight(1f).imePadding()) {
            NavHost(navController = nav, startDestination = ROUTE_HOME) {
                composable(ROUTE_HOME) {
                    HomeTab(
                        viewModel = homeViewModel,
                        onOpenLogin = {
                            context.startActivity(
                                Intent(context, LoginWebViewActivity::class.java),
                            )
                        },
                        onOpenSettings = {
                            nav.navigate(ROUTE_SETTINGS) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenTopics = { uid ->
                            topicViewModel.selectAccount(uid ?: "")
                            nav.navigate(ROUTE_TOPICS) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onSetBadge = { badgeState.value = it },
                    )
                }
                composable(ROUTE_TOPICS) {
                    TopicTab(
                        viewModel = topicViewModel,
                        onGoHome = { nav.navigate(ROUTE_HOME) },
                    )
                }
                composable(ROUTE_GALLERY) {
                    val dirPicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree(),
                    ) { uri: Uri? ->
                        if (uri != null) {
                            runCatching {
                                context.contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                )
                            }.onFailure {
                                runCatching {
                                    context.contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                }
                            }
                            CrawlSettings.setCrawlTreeUri(context, uri.toString())
                            galleryViewModel.onDirPicked()
                        }
                    }
                    GalleryTab(viewModel = galleryViewModel, onPickDir = { dirPicker.launch(null) })
                }
                composable(ROUTE_SETTINGS) {
                    SettingsTab(viewModel = settingsViewModel)
                }
            }
        }
        if (!imeVisible) {
            HorizontalDivider(thickness = 1.dp, color = colorResource(R.color.line))
            NavigationBar(containerColor = colorResource(R.color.surface), tonalElevation = 0.dp) {
                NavItem(
                    selected = route == ROUTE_HOME,
                    icon = R.drawable.ic_nav_home,
                    label = R.string.nav_home,
                    badge = null,
                    onClick = {
                        nav.navigate(ROUTE_HOME) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
                NavItem(
                    selected = route == ROUTE_TOPICS,
                    icon = R.drawable.ic_nav_topics,
                    label = R.string.nav_topics,
                    badge = badge,
                    onClick = {
                        nav.navigate(ROUTE_TOPICS) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
                NavItem(
                    selected = route == ROUTE_GALLERY,
                    icon = R.drawable.ic_nav_gallery,
                    label = R.string.nav_gallery,
                    badge = null,
                    onClick = {
                        nav.navigate(ROUTE_GALLERY) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
                NavItem(
                    selected = route == ROUTE_SETTINGS,
                    icon = R.drawable.ic_nav_settings,
                    label = R.string.nav_settings,
                    badge = null,
                    onClick = {
                        nav.navigate(ROUTE_SETTINGS) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(
    selected: Boolean,
    icon: Int,
    label: Int,
    badge: Int?,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            if (badge != null) {
                BadgedBox(
                    badge = {
                        Badge(
                            containerColor = colorResource(R.color.brand),
                            contentColor = colorResource(R.color.brand_on),
                        ) { Text(badge.toString()) }
                    },
                ) {
                    Icon(painterResource(icon), contentDescription = null)
                }
            } else {
                Icon(painterResource(icon), contentDescription = null)
            }
        },
        label = { Text(stringResource(label)) },
    )
}