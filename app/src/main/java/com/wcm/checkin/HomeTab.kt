package com.wcm.checkin

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource

/**
 * 首页 Tab：只渲染 [HomeViewModel] 的状态；登录/设置/超话跳转由壳注入。
 *
 * ## 间距节奏（v3.0）
 *
 * 这一页刻意不用 `verticalArrangement = spacedBy(…)`，而让每个 item 自己带
 * `padding(top = …)`：
 *
 * ```
 * 顶栏
 *   20  Hero（结论）
 *   12  主行动（与 Hero 同组：看完结论就能点）
 *   20  登录状态
 *   12  账号
 *   20  运行日志
 * ```
 *
 * 两级间距是这一页「有主次」的主要来源 —— 间距一律 10dp 时，标题、Hero、
 * 卡片、日志读起来是一串等价的块。用 `spacedBy` 的话，组内 12 与组间 20
 * 会叠加成 32，反而不准，所以必须逐个 item 给。
 */
@Composable
fun HomeTab(
    viewModel: HomeViewModel,
    onOpenLogin: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTopics: (String?) -> Unit,
    onSetBadge: (Int?) -> Unit,
) {
    val pagePad = dimensionResource(R.dimen.page_padding_horizontal)
    val hero by viewModel.hero.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val cookie by viewModel.cookie.collectAsState()
    val log by viewModel.log.collectAsState()
    val header by viewModel.header.collectAsState()
    val run by viewModel.run.collectAsState()
    val topicsDialog by viewModel.topicsDialog.collectAsState()
    val reloginDialog by viewModel.reloginDialog.collectAsState()
    val badge by viewModel.badge.collectAsState()

    val context = LocalContext.current
    var messageDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    OnResume { viewModel.onShown() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.Toast -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                HomeEvent.OpenLogin -> onOpenLogin()
                HomeEvent.OpenSettings -> onOpenSettings()
                is HomeEvent.OpenTopicsTab -> onOpenTopics(event.uid)
                is HomeEvent.ShowMessage -> messageDialog = event.title to event.message
            }
        }
    }
    LaunchedEffect(badge) { onSetBadge(badge) }

    val refreshDesc = stringResource(R.string.btn_refresh_topics)
    val addDesc = stringResource(R.string.cd_add_account)

    WcmPage(
        title = header.greeting,
        subtitle = header.today,
        actions = {
            WcmTopBarAction(
                icon = R.drawable.ic_refresh,
                description = refreshDesc,
                onClick = { viewModel.refreshTopics(manual = true) },
            )
            WcmTopBarAccentAction(
                icon = R.drawable.ic_add,
                description = addDesc,
                onClick = { viewModel.openLogin() },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = pagePad,
                end = pagePad,
                bottom = WcmSpace.pageBottom,
            ),
        ) {
            // 结论 + 行动：同一组（组内 12dp）
            item(key = "hero") {
                Column(Modifier.padding(top = WcmSpace.section)) {
                    HomeHeroCard(state = hero, onClick = { viewModel.onHeroClick() })
                    Row(
                        modifier = Modifier.padding(top = WcmSpace.cardGap),
                        horizontalArrangement = Arrangement.spacedBy(WcmSpace.cardGap),
                    ) {
                        WcmButton(
                            onClick = { viewModel.runAll(auto = false) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(
                                    if (run.busy && !run.syncing) R.string.btn_checkin_running
                                    else R.string.btn_checkin,
                                ),
                            )
                        }
                        WcmOutlinedButton(
                            onClick = { viewModel.syncOnly() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(
                                    if (run.syncing) R.string.btn_sync_running else R.string.btn_sync,
                                ),
                            )
                        }
                    }
                }
            }
            // 换组：登录状态
            item(key = "cookie") {
                Box(Modifier.padding(top = WcmSpace.section)) {
                    CookieCard(state = cookie, onConfig = { viewModel.openSettings() })
                }
            }
            // 同组：账号明细
            item(key = "accounts") {
                Box(Modifier.padding(top = WcmSpace.cardGap)) {
                    AccountsCard(
                        title = header.accountsTitle,
                        rows = accounts,
                        onRowClick = viewModel::showTopics,
                        onRelogin = viewModel::showRelogin,
                        onDelete = viewModel::deleteAccount,
                        onAdd = { viewModel.openLogin() },
                    )
                }
            }
            // 换组：追溯
            item(key = "log") {
                Box(Modifier.padding(top = WcmSpace.section)) {
                    LogCard(state = log, onToggle = { viewModel.setLogExpanded(!log.expanded) })
                }
            }
        }
    }

    topicsDialog?.let { dlg ->
        TopicsDialog(
            state = dlg,
            onDismiss = { viewModel.dismissTopicsDialog() },
            onResign = { pickedKeys ->
                viewModel.dismissTopicsDialog()
                viewModel.resignTopics(dlg, pickedKeys)
            },
            onViewAll = {
                viewModel.dismissTopicsDialog()
                onOpenTopics(dlg.uid)
            },
        )
    }

    reloginDialog?.let { dlg ->
        ReloginDialog(
            state = dlg,
            onDismiss = { viewModel.dismissRelogin() },
            onSave = { user, pass, alc -> viewModel.saveReloginCredentials(dlg.uid, user, pass, alc) },
            onDelete = { viewModel.deleteReloginCredentials(dlg.uid) },
        )
    }

    messageDialog?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { messageDialog = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { messageDialog = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

/** 账号列表卡：卡头 + 行列表，材质与内边距走 [WcmCard]。 */
@Composable
private fun AccountsCard(
    title: String,
    rows: List<AccountRowUi>,
    onRowClick: (String) -> Unit,
    onRelogin: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    WcmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colorResource(R.color.ink),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.home_row_hint_short),
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
            )
        }
        AccountList(
            rows = rows,
            onRowClick = onRowClick,
            onViewTopics = onRowClick,
            onRelogin = onRelogin,
            onDelete = onDelete,
            onAdd = onAdd,
        )
    }
}
