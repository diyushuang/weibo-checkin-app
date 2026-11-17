package com.wcm.checkin

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/**
 * 发帖台账页。
 *
 * 这里是「我到底发了什么」的唯一可信来源：每条都存了**最终提交的完整正文快照**。
 * [ActionState.UNCERTAIN] 的记录额外给两个人工按钮——客户端无法自证发没发出去，
 * 只能由用户到微博看一眼再收敛；不收敛就会一直锁着当日发帖。
 */
@Composable
fun EngagePostLogScreen(viewModel: EngageViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        viewModel.loadPostHistory()
    }
    // 这一页有自己的操作按钮（确认发送/收敛），提示也要在这一页显示
    LaunchedEffect(state.message) {
        if (state.message.isNotEmpty()) {
            Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(stringResource(R.string.engage_posts_title), style = MaterialTheme.typography.titleLarge)
        }

        if (state.posts.isEmpty()) {
            Text(
                stringResource(R.string.engage_posts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.posts.forEach { post ->
            val uncertain = post.state == ActionState.UNCERTAIN
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = if (uncertain) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                } else {
                    CardDefaults.cardColors()
                },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            EngageSchedule.dateLabel(post.at) + " " + EngageSchedule.clockLabel(post.at),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            postStateLabel(post.state),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uncertain) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    if (post.topicName.isNotEmpty()) {
                        Text(
                            "#" + post.topicName + "#",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(post.body, style = MaterialTheme.typography.bodyMedium)
                    if (post.message.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            post.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (uncertain) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.engage_posts_uncertain_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.settlePost(post, sent = true) }) {
                                Text(stringResource(R.string.engage_posts_confirm_sent))
                            }
                            OutlinedButton(onClick = { viewModel.settlePost(post, sent = false) }) {
                                Text(stringResource(R.string.engage_posts_confirm_not_sent))
                            }
                        }
                    }

                    if (post.state == ActionState.AWAITING_CONFIRM) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.engage_posts_awaiting_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { viewModel.confirmPost(post) },
                            enabled = !state.running,
                        ) { Text(stringResource(R.string.engage_posts_confirm_send)) }
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(post.body))
                        }) { Text(stringResource(R.string.engage_posts_copy)) }

                        if (post.remoteId.isNotEmpty()) {
                            TextButton(onClick = {
                                // 详情页用 m 站，域名仍在微博白名单内
                                val url = "https://m.weibo.cn/detail/${post.remoteId}"
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                }.onFailure {
                                    // 设备没有浏览器/微博时 startActivity 会抛 ActivityNotFoundException，
                                    // 原来只是静默无反应，用户以为按钮坏了
                                    Toast.makeText(
                                        context,
                                        R.string.engage_posts_no_browser,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }) { Text(stringResource(R.string.engage_posts_view_on_weibo)) }
                        }
                    }
                }
            }
        }
    }
}
