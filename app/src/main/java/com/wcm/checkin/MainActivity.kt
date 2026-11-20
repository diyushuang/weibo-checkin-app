package com.wcm.checkin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

private const val ROUTE_HOME = "home"
private const val ROUTE_TOPICS = "topics"
private const val ROUTE_GALLERY = "gallery"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_BROWSING = "browsing"
private const val ROUTE_POST_PICK = "post_pick"
private const val ROUTE_REMOTE_PREVIEW = "remote_preview"
private const val ROUTE_GALLERY_BROWSE = "gallery_browse"
private const val ROUTE_IMAGE_PREVIEW = "image_preview"
private const val ROUTE_ENGAGE = "engage"

/** 三套文案库共用一条 route，`{kind}` 取 [TextKind.key]（post / comment / repost）。 */
private const val ROUTE_ENGAGE_TEXTS = "engage_texts"

/** 四个小任务共用一条 route，`{action}` 取 [EngageAction.key]。 */
private const val ROUTE_ENGAGE_TASK = "engage_task"
private const val ROUTE_ENGAGE_POSTS = "engage_posts"

/**
 * 单 Activity + Navigation Compose 壳：四个 Tab 都是纯 Compose 目的地。
 *
 * ViewModel 挂在 Activity 上（而不是每个目的地），切 Tab 不丢筛选/展开/日志/
 * 正在跑的签到状态；登录 WebView 仍是独立 Activity。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val topicViewModel: TopicViewModel by viewModels()
    private val galleryViewModel: GalleryViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val engageViewModel: EngageViewModel by viewModels()

    private val badgeState = MutableStateFlow<Int?>(null)
    private var navController: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 后台保活是增值能力：WorkManager 初始化会打开本地数据库，万一失败
        // （数据库损坏 / 系统限制），不该连带首页一起起不来。
        runCatching { CheckInWorker.schedule(this) }

        setContent {
            val nav = rememberNavController()
            navController = nav
            WcmTheme { AppShell(nav, homeViewModel, topicViewModel, galleryViewModel, settingsViewModel, engageViewModel, badgeState) }
        }
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
    engageViewModel: EngageViewModel,
    badgeState: MutableStateFlow<Int?>,
) {
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: ROUTE_HOME
    val badge by badgeState.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val showBottomBar = route == ROUTE_HOME || route == ROUTE_TOPICS ||
        route == ROUTE_GALLERY || route == ROUTE_SETTINGS || route == ROUTE_ENGAGE

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
                            galleryViewModel.onDirPicked(uri.toString())
                        }
                    }
                    GalleryTab(
                        viewModel = galleryViewModel,
                        onPickDir = { dirPicker.launch(null) },
                        onBrowse = { uid ->
                            nav.navigate("$ROUTE_BROWSING?uid=${Uri.encode(uid)}")
                        },
                        onOpenGallery = { tree ->
                            nav.navigate("$ROUTE_GALLERY_BROWSE?tree=${Uri.encode(tree)}")
                        },
                    )
                }
                composable(
                    route = "$ROUTE_BROWSING?uid={uid}",
                    arguments = listOf(
                        navArgument("uid") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { entry ->
                    val vm: BrowsingViewModel = hiltViewModel()
                    val state by vm.state.collectAsState()
                    val initialUid = entry.arguments?.getString("uid").orEmpty()
                    LaunchedEffect(initialUid) { vm.start(initialUid) }
                    OnResume { vm.onResume() }
                    LaunchedEffect(vm) {
                        vm.events.collect { event ->
                            when (event) {
                                is BrowsingEvent.Toast ->
                                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                                is BrowsingEvent.OpenPostPick ->
                                    nav.navigate("$ROUTE_POST_PICK?bid=${Uri.encode(event.bid)}")
                                is BrowsingEvent.OpenRemotePreview ->
                                    nav.navigate(
                                        "$ROUTE_REMOTE_PREVIEW?urls=" +
                                            "${Uri.encode(event.urls.joinToString("\n"))}" +
                                            "&index=${event.index}",
                                    )
                            }
                        }
                    }
                    BackHandler(enabled = state.selectMode) { vm.exitSelectMode() }
                    BrowsingScreen(
                        state = state,
                        onBack = { nav.popBackStack() },
                        onInputChange = vm::updateInput,
                        onLoad = vm::reload,
                        onTargetClick = vm::selectTarget,
                        onToggleOriginal = vm::toggleOriginal,
                        onTogglePic = vm::togglePic,
                        onPostClick = vm::onPostClick,
                        onPostLongClick = vm::onPostLongClick,
                        onThumbClick = vm::onThumbClick,
                        onMorePics = vm::openPostPick,
                        onExitSelect = vm::exitSelectMode,
                        onDownload = vm::submitSelected,
                        onLoadMore = vm::loadMore,
                        onVisibleIndex = vm::onVisibleIndex,
                    )
                }
                composable(
                    route = "$ROUTE_POST_PICK?bid={bid}",
                    arguments = listOf(
                        navArgument("bid") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { entry ->
                    val vm: PostPickViewModel = hiltViewModel()
                    val state by vm.state.collectAsState()
                    val bid = entry.arguments?.getString("bid").orEmpty()
                    var fullPreview by remember { mutableStateOf<Pair<String, String>?>(null) }
                    LaunchedEffect(bid) { vm.start(bid) }
                    LaunchedEffect(vm) {
                        vm.events.collect { event ->
                            when (event) {
                                is PostPickEvent.Toast ->
                                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                                is PostPickEvent.ShowFullPreview ->
                                    fullPreview = event.fullUrl to event.picId
                            }
                        }
                    }
                    PostPickScreen(
                        state = state,
                        onBack = { nav.popBackStack() },
                        onToggleAll = vm::toggleAll,
                        onItemClick = vm::toggle,
                        onItemLongClick = vm::requestFullPreview,
                        onDownload = vm::submitSelected,
                        onVisibleIndex = vm::onVisibleIndex,
                    )
                    fullPreview?.let { (url, picId) ->
                        FullPickPreviewDialog(
                            fullUrl = url,
                            onOnlyThis = {
                                fullPreview = null
                                vm.submit(setOf(picId))
                            },
                            onDismiss = { fullPreview = null },
                        )
                    }
                }
                composable(
                    route = "$ROUTE_REMOTE_PREVIEW?urls={urls}&index={index}",
                    arguments = listOf(
                        navArgument("urls") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("index") {
                            type = NavType.StringType
                            defaultValue = "0"
                        },
                    ),
                ) { entry ->
                    val vm: RemotePreviewViewModel = hiltViewModel()
                    val state by vm.state.collectAsState()
                    val urls = entry.arguments?.getString("urls").orEmpty()
                        .split("\n")
                        .filter { it.isNotEmpty() }
                    val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
                    LaunchedEffect(Unit) { vm.start(urls, index) }
                    LaunchedEffect(vm) {
                        vm.events.collect { event ->
                            when (event) {
                                is RemotePreviewEvent.Toast ->
                                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    RemotePreviewScreen(
                        state = state,
                        isDoneAt = vm::isDone,
                        loadPage = vm::loadPage,
                        onPageChanged = vm::onPageChanged,
                    )
                }
                composable(
                    route = "$ROUTE_GALLERY_BROWSE?tree={tree}",
                    arguments = listOf(
                        navArgument("tree") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { entry ->
                    val vm: GalleryBrowseViewModel = hiltViewModel()
                    val state by vm.state.collectAsState()
                    val tree = entry.arguments?.getString("tree").orEmpty()
                    var confirmDelete by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { vm.start(tree) }
                    OnResume { vm.onResume() }
                    LaunchedEffect(vm) {
                        vm.events.collect { event ->
                            when (event) {
                                is GalleryBrowseEvent.Toast ->
                                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                                is GalleryBrowseEvent.Share -> {
                                    val uris = event.uris.map { it.toUri() }
                                    if (uris.isEmpty()) return@collect
                                    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                        type = "image/*"
                                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        clipData = android.content.ClipData.newUri(
                                            context.contentResolver, "images", uris.first(),
                                        ).apply {
                                            uris.drop(1).forEach {
                                                addItem(android.content.ClipData.Item(it))
                                            }
                                        }
                                    }
                                    context.startActivity(
                                        Intent.createChooser(
                                            send,
                                            context.getString(R.string.gallery_batch_share),
                                        ),
                                    )
                                }
                                is GalleryBrowseEvent.OpenPreview ->
                                    nav.navigate(
                                        "$ROUTE_IMAGE_PREVIEW?tree=${Uri.encode(tree)}" +
                                            "&index=${event.index}&name=${Uri.encode(event.name)}",
                                    )
                            }
                        }
                    }
                    BackHandler(enabled = state.selectMode) { vm.exitSelectMode() }
                    GalleryBrowseScreen(
                        state = state,
                        onBack = { nav.popBackStack() },
                        onRefresh = vm::reload,
                        onToggleSearch = vm::setSearchOpen,
                        onQueryChange = vm::onQueryChanged,
                        onSelectModeToggle = vm::toggleSelectMode,
                        onSort = vm::setSort,
                        onGroup = vm::setGroup,
                        onKindFilter = vm::setKind,
                        onAuthorFilter = vm::setAuthor,
                        onCellClick = vm::onCellClick,
                        onCellLongClick = vm::onCellLongClick,
                        onExitSelect = vm::exitSelectMode,
                        onShare = vm::shareSelected,
                        onDelete = { confirmDelete = true },
                        onEmptyAction = { nav.popBackStack() },
                        onVisibleIndex = vm::onVisibleIndex,
                    )
                    if (confirmDelete) {
                        AlertDialog(
                            onDismissRequest = { confirmDelete = false },
                            title = {
                                Text(
                                    stringResource(
                                        R.string.gallery_batch_delete_title,
                                        state.selectedCount,
                                    ),
                                )
                            },
                            text = { Text(stringResource(R.string.gallery_batch_delete_msg)) },
                            confirmButton = {
                                WcmTextButton(
                                    onClick = {
                                        confirmDelete = false
                                        vm.batchDelete()
                                    },
                                ) { Text(stringResource(R.string.gallery_batch_delete)) }
                            },
                            dismissButton = {
                                WcmTextButton(onClick = { confirmDelete = false }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            },
                        )
                    }
                }
                composable(
                    route = "$ROUTE_IMAGE_PREVIEW?tree={tree}&index={index}&name={name}",
                    arguments = listOf(
                        navArgument("tree") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("index") {
                            type = NavType.StringType
                            defaultValue = "0"
                        },
                        navArgument("name") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { entry ->
                    val vm: ImagePreviewViewModel = hiltViewModel()
                    val state by vm.state.collectAsState()
                    val tree = entry.arguments?.getString("tree").orEmpty()
                    val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
                    val name = entry.arguments?.getString("name").orEmpty()
                    var deleteIndex by remember { mutableStateOf<Int?>(null) }
                    LaunchedEffect(Unit) { vm.start(tree, index, name.ifEmpty { null }) }
                    LaunchedEffect(vm) {
                        vm.events.collect { event ->
                            when (event) {
                                is ImagePreviewEvent.Toast ->
                                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                                ImagePreviewEvent.Finish -> nav.popBackStack()
                            }
                        }
                    }
                    ImagePreviewScreen(
                        state = state,
                        nameAt = vm::nameAt,
                        loadPage = vm::loadPage,
                        onShare = vm::share,
                        onOpen = vm::open,
                        onDelete = { deleteIndex = it },
                        onPageChanged = vm::onPageChanged,
                    )
                    deleteIndex?.let { at ->
                        AlertDialog(
                            onDismissRequest = { deleteIndex = null },
                            title = { Text(stringResource(R.string.preview_delete_title)) },
                            text = {
                                Text(
                                    vm.nameAt(at) + "\n" +
                                        stringResource(R.string.preview_delete_msg),
                                )
                            },
                            confirmButton = {
                                WcmTextButton(
                                    onClick = {
                                        deleteIndex = null
                                        vm.delete(at)
                                    },
                                ) { Text(stringResource(R.string.preview_delete)) }
                            },
                            dismissButton = {
                                WcmTextButton(onClick = { deleteIndex = null }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            },
                        )
                    }
                }
                composable(ROUTE_SETTINGS) {
                    SettingsTab(viewModel = settingsViewModel)
                }
                composable(ROUTE_ENGAGE) {
                    EngageTab(
                        viewModel = engageViewModel,
                        onOpenTexts = { kind -> nav.navigate("$ROUTE_ENGAGE_TEXTS/${kind.key}") },
                        onOpenTask = { action -> nav.navigate("$ROUTE_ENGAGE_TASK/${action.key}") },
                        onOpenPosts = { nav.navigate(ROUTE_ENGAGE_POSTS) },
                    )
                }
                composable(
                    "$ROUTE_ENGAGE_TEXTS/{kind}",
                    arguments = listOf(navArgument("kind") { type = NavType.StringType }),
                ) { entry ->
                    // 非法参数（深链/手改）回落发帖库，不让导航崩掉
                    val kind = TextKind.ofKey(entry.arguments?.getString("kind")) ?: TextKind.POST
                    EngageTextScreen(
                        viewModel = engageViewModel,
                        kind = kind,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    "$ROUTE_ENGAGE_TASK/{action}",
                    arguments = listOf(navArgument("action") { type = NavType.StringType }),
                ) { entry ->
                    // 这里必须复用 Activity 作用域的同一个 ViewModel：
                    // EngageService.onProgress 是单值回调，第二个实例会把它抢走，
                    // 表现就是「主页进度条不动、子页面在动」。
                    val action = entry.arguments?.getString("action")
                        ?.let { key -> EngageAction.entries.firstOrNull { it.key == key } }
                        ?: EngageAction.POST
                    EngageTaskDetailScreen(
                        viewModel = engageViewModel,
                        action = action,
                        onBack = { nav.popBackStack() },
                        onOpenTexts = { kind -> nav.navigate("$ROUTE_ENGAGE_TEXTS/${kind.key}") },
                    )
                }
                composable(ROUTE_ENGAGE_POSTS) {
                    EngagePostLogScreen(
                        viewModel = engageViewModel,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
        if (showBottomBar && !imeVisible) {
            HorizontalDivider(thickness = 1.dp, color = colorResource(R.color.line))
            // 5 个 Tab 收成一个 Tab 停靠点：否则从内容区穿到底栏要按 5 次，
            // 方向键在组内走即可（这是桌面模式下底栏的常规行为）
            NavigationBar(
                containerColor = colorResource(R.color.surface),
                tonalElevation = 0.dp,
                modifier = Modifier.wcmFocusGroup(),
            ) {
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
                NavItem(
                    selected = route == ROUTE_ENGAGE,
                    icon = R.drawable.ic_nav_tasks,
                    label = R.string.nav_tasks,
                    badge = null,
                    onClick = {
                        nav.navigate(ROUTE_ENGAGE) {
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
        modifier = Modifier.wcmFocusRing(WcmShape.button),
        // 显式指定选中态：不依赖 M3 默认（默认 indicator 取 secondaryContainer，
        // 主题把 secondaryContainer 映射成了 surface_2，深色下几乎看不出选中）
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = colorResource(R.color.brand),
            selectedTextColor = colorResource(R.color.brand),
            indicatorColor = colorResource(R.color.brand_soft),
            unselectedIconColor = colorResource(R.color.ink_3),
            unselectedTextColor = colorResource(R.color.ink_3),
        ),
        icon = {
            if (badge != null) {
                BadgedBox(
                    badge = {
                        Badge(
                            containerColor = colorResource(R.color.brand),
                            contentColor = colorResource(R.color.brand_on),
                        ) { Text(if (badge > 99) "99+" else badge.toString()) }
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