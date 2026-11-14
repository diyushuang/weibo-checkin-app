package com.wcm.checkin

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 首页 Tab：只渲染 [HomeViewModel] 的状态；登录/设置/超话跳转由壳注入。 */
@Composable
fun HomeTab(
    viewModel: HomeViewModel,
    onOpenLogin: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTopics: (String?) -> Unit,
    onSetBadge: (Int?) -> Unit,
) {
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

    WcmTheme {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(R.color.bg)),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Header(
                    greeting = header.greeting,
                    today = header.today,
                    onRefresh = { viewModel.refreshTopics(manual = true) },
                    onAdd = { viewModel.openLogin() },
                )
            }
            item(key = "hero") {
                HomeHeroCard(state = hero, onClick = { viewModel.onHeroClick() })
            }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
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
                    OutlinedButton(
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
            item(key = "cookie") {
                CookieCard(state = cookie, onConfig = { viewModel.openSettings() })
            }
            item(key = "accounts") {
                AccountsCard(
                    title = header.accountsTitle,
                    rows = accounts,
                    onRowClick = viewModel::showTopics,
                    onRelogin = viewModel::showRelogin,
                    onDelete = viewModel::deleteAccount,
                )
            }
            item(key = "log") {
                LogCard(state = log, onToggle = { viewModel.setLogExpanded(!log.expanded) })
            }
        }
    }

    topicsDialog?.let { dlg ->
        TopicsDialog(
            state = dlg,
            onDismiss = { viewModel.dismissTopicsDialog() },
            onResign = { picked ->
                viewModel.dismissTopicsDialog()
                viewModel.resignTopics(dlg, picked)
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
            onSave = { user, pass -> viewModel.saveReloginCredentials(dlg.uid, user, pass) },
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

@Composable
private fun Header(
    greeting: String,
    today: String,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
) {
    val refreshDesc = stringResource(R.string.btn_refresh_topics)
    val addDesc = stringResource(R.string.cd_add_account)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorResource(R.color.ink),
            )
            Text(
                text = today,
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.ink_3),
            )
        }
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.semantics {
                contentDescription = refreshDesc
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = null,
                tint = colorResource(R.color.ink_2),
            )
        }
        Surface(shape = RoundedCornerShape(12.dp), color = colorResource(R.color.brand)) {
            IconButton(onClick = onAdd) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = addDesc,
                    tint = colorResource(R.color.brand_on),
                )
            }
        }
    }
}

@Composable
private fun AccountsCard(
    title: String,
    rows: List<AccountRowUi>,
    onRowClick: (String) -> Unit,
    onRelogin: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(R.color.surface)),
        border = BorderStroke(1.dp, colorResource(R.color.line)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
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
            )
        }
    }
}