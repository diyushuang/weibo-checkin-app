package com.wcm.checkin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

/** 超话 Tab：只渲染 [TopicViewModel] 的状态，一次性事件在这里落到 Toast/弹窗/剪贴板。 */
@Composable
fun TopicTab(viewModel: TopicViewModel, onGoHome: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var textDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    OnResume { viewModel.onShown() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TopicEvent.Toast -> Toast.makeText(
                    context, event.text, Toast.LENGTH_SHORT,
                ).show()
                is TopicEvent.ShowText -> textDialog = event.title to event.body
                is TopicEvent.Copy -> {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("weibo", event.text))
                }
                is TopicEvent.OpenUrl -> {
                    val url = event.url
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    } catch (e: Exception) {
                        Toast.makeText(
                            context, context.getString(R.string.topic_open_fail), Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    TopicScreen(
        state = state,
        onRefresh = { viewModel.refresh(manual = true) },
        onOverflowField = { viewModel.showFieldHelp() },
        onOverflowRefresh = { viewModel.refresh(manual = true) },
        onSelectAccount = { viewModel.selectAccount(it) },
        onFilter = { viewModel.setFilter(it) },
        onSort = { viewModel.setSort(it) },
        onRowClick = { viewModel.toggleDetail(it) },
        onRowCheckin = { viewModel.checkin(it) },
        onRowCopy = { viewModel.copySignLink(it) },
        onRowOpen = { viewModel.openInWeibo(it) },
        onRowJson = { viewModel.showRawJson(it) },
        onEmptyAction = onGoHome,
        onRetry = { viewModel.refresh(manual = true) },
    )
    textDialog?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { textDialog = null },
            title = { Text(title) },
            text = {
                SelectionContainer {
                    Text(
                        text = body,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                WcmTextButton(onClick = { textDialog = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}