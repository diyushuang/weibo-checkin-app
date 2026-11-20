package com.wcm.checkin

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 发帖台账页。
 *
 * 这里是「我到底发了什么」的唯一可信来源：每条都存了**最终提交的完整正文快照**。
 * [ActionState.UNCERTAIN] 的记录额外给两个人工按钮——客户端无法自证发没发出去，
 * 只能由用户到微博看一眼再收敛；不收敛就会一直锁着当日发帖。
 */
@Composable
fun EngagePostLogScreen(viewModel: EngageViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    val pagePad = dimensionResource(R.dimen.page_padding_horizontal)
    WcmPage(
        title = stringResource(R.string.engage_posts_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = pagePad,
                    end = pagePad,
                    bottom = WcmSpace.pageBottom,
                ),
            verticalArrangement = Arrangement.spacedBy(WcmSpace.cardGap),
        ) {
            Spacer(Modifier.height(WcmSpace.section))
            if (state.posts.isEmpty()) {
                WcmEmptyState(
                    icon = R.drawable.ic_image,
                    title = stringResource(R.string.engage_posts_empty),
                )
            }

            state.posts.forEach { post ->
                val uncertain = post.state == ActionState.UNCERTAIN
                // 不确定态整卡用 danger_soft 底（原来取 M3 默认 errorContainer，
                // 与首页 pill 的品牌红不是一个红）
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = WcmShape.card,
                    colors = CardDefaults.cardColors(
                        containerColor = if (uncertain) colorResource(R.color.danger_soft)
                        else colorResource(R.color.surface),
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (uncertain) colorResource(R.color.danger_text).copy(alpha = 0.2f)
                        else colorResource(R.color.line),
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(WcmSpace.cardPad)) {
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
                                    colorResource(R.color.danger_text)
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
                                color = colorResource(R.color.danger_text),
                            )
                        }

                        if (uncertain) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.engage_posts_uncertain_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WcmOutlinedButton(onClick = { viewModel.settlePost(post, sent = true) }) {
                                    Text(stringResource(R.string.engage_posts_confirm_sent))
                                }
                                WcmOutlinedButton(onClick = { viewModel.settlePost(post, sent = false) }) {
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
                            WcmOutlinedButton(
                                onClick = { viewModel.confirmPost(post) },
                                enabled = !state.running,
                            ) { Text(stringResource(R.string.engage_posts_confirm_send)) }
                        }

                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WcmTextButton(onClick = {
                                clipboard.setText(AnnotatedString(post.body))
                            }) { Text(stringResource(R.string.engage_posts_copy)) }

                            if (post.remoteId.isNotEmpty()) {
                                WcmTextButton(onClick = {
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
}
