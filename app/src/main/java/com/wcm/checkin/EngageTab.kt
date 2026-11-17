package com.wcm.checkin

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext

/**
 * 任务页的 route 入口：只做副作用（Toast / 授权弹窗 / 跳转），渲染交给 [EngageScreen]。
 */
@Composable
fun EngageTab(
    viewModel: EngageViewModel,
    onOpenTexts: () -> Unit,
    onOpenPosts: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        if (state.message.isNotEmpty()) {
            Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    // 超话列表进页面才拉（放在 ViewModel 初始化里会导致每次冷启动都白拉十几页）
    LaunchedEffect(Unit) { viewModel.onEnter() }

    if (state.showConsent) {
        EngageConsentDialog(
            onAccept = { viewModel.acceptConsent() },
            onDismiss = { viewModel.dismissConsent() },
        )
    }

    EngageScreen(
        state = state,
        onToggleEnable = { viewModel.requestEnable() },
        onRunNow = { viewModel.runNow() },
        onSelectAccount = { viewModel.selectAccount(it) },
        onSelectTopic = { viewModel.selectTopic(it) },
        onRefreshTopics = { viewModel.refreshTopics() },
        onSetCount = { action, n -> viewModel.setCount(action, n) },
        onUpdate = { transform -> viewModel.update(transform) },
        onNextPreview = { viewModel.nextPreview() },
        onOpenTexts = onOpenTexts,
        onOpenPosts = onOpenPosts,
        onClearAll = { viewModel.clearAll() },
    )
}
