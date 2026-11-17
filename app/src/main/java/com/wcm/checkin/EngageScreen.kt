package com.wcm.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 任务页主界面。
 *
 * 分区顺序是刻意的：**状态 → 预览 → 参数**。
 * 因为默认不逐次确认，用户对「到底会发什么」的掌控只能靠预览，
 * 所以它必须排在参数前面，而不是塞在某个二级页里。
 */
@Composable
fun EngageScreen(
    state: EngageUiState,
    onToggleEnable: () -> Unit,
    onRunNow: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onSelectTopic: (EngageTopicOption) -> Unit,
    onRefreshTopics: () -> Unit,
    onSetCount: (EngageAction, Int) -> Unit,
    onUpdate: ((EngageConfig) -> EngageConfig) -> Unit,
    onNextPreview: () -> Unit,
    onOpenTexts: () -> Unit,
    onOpenPosts: () -> Unit,
    onClearAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state, onToggleEnable, onRunNow)
        // 运行记录紧跟状态卡：原来排在第 8 个卡片、要滑 6 屏才看得到，
        // 用户根本不知道执行结果去哪看。
        RunsCard(state)

        if (state.hasUncertain) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.engage_uncertain_warn),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (state.hasAwaiting) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.engage_awaiting_warn, state.awaitingPosts.size),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        PreviewCard(state, onNextPreview)
        TargetCard(state, onSelectAccount, onSelectTopic, onRefreshTopics)
        CountsCard(state, onSetCount)
        ScheduleCard(state, onUpdate)
        PostSettingsCard(state, onUpdate)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.engage_texts_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                R.string.engage_texts_count,
                                state.availableTexts,
                                state.texts.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = onOpenTexts) {
                        Text(stringResource(R.string.engage_texts_open))
                    }
                }
            }
        }

        PostsCard(state, onOpenPosts)
        DangerCard(
            enabled = state.enabled,
            onDisable = { if (state.enabled) onToggleEnable() },
            onClearAll = onClearAll,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatusCard(state: EngageUiState, onToggle: () -> Unit, onRunNow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.engage_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.engage_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = { onToggle() })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    state.running -> stringResource(R.string.engage_status_running)
                    state.enabled -> stringResource(R.string.engage_status_on)
                    else -> stringResource(R.string.engage_status_off)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            // 一轮最长 8 分钟，光一行「正在执行…」看不出是在等间隔还是已经卡住。
            // 把阶段 / 当前动作 / 已完成条数 / 最近一次结果直接摊在这里。
            val progress = state.progress
            if (state.running && progress != null) {
                Text(
                    text = progress.line(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRunNow,
                enabled = state.enabled && !state.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.engage_run_now))
            }
            Text(
                // 未开启时按钮是灰的，只说「未开启」用户不知道去哪开 —— 直接给下一步动作
                text = if (!state.enabled && !state.running) {
                    stringResource(R.string.engage_run_need_enable)
                } else {
                    stringResource(R.string.engage_run_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewCard(state: EngageUiState, onNext: () -> Unit) {
    SectionCard(stringResource(R.string.engage_preview_title)) {
        val body = state.preview
        if (body.isEmpty()) {
            Text(
                // 空正文有两种原因：真的没文案，或本轮根本不会发帖（额度用完/在免打扰）。
                // 一律显示「文案库为空」会误导用户去加文案。
                text = state.previewError.ifEmpty { stringResource(R.string.engage_preview_empty) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.previewError.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        } else {
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.engage_preview_length, state.previewLength, state.config.maxTextLen),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.previewError.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onNext) { Text(stringResource(R.string.engage_preview_next)) }
            }
            if (state.previewError.isNotEmpty()) {
                Text(
                    state.previewError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            stringResource(R.string.engage_preview_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TargetCard(
    state: EngageUiState,
    onSelectAccount: (String) -> Unit,
    onSelectTopic: (EngageTopicOption) -> Unit,
    onRefreshTopics: () -> Unit,
) {
    SectionCard(stringResource(R.string.engage_account_label)) {
        if (state.accounts.isEmpty()) {
            Text(
                text = "还没有已登录的账号",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            state.accounts.forEach { acc ->
                val selected = acc.uid == state.config.accountUid
                TextButton(
                    onClick = { onSelectAccount(acc.uid) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = (if (selected) "● " else "○ ") + acc.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.engage_topic_label), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefreshTopics) { Text(stringResource(R.string.engage_refresh_topics)) }
        }
        if (state.topicsLoading) {
            Text("加载中…", style = MaterialTheme.typography.bodySmall)
        }
        if (state.topics.isEmpty() && !state.topicsLoading) {
            Text(
                stringResource(R.string.engage_topic_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.topics.forEach { topic ->
            val selected = topic.id == state.config.topicId
            TextButton(
                onClick = { onSelectTopic(topic) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = (if (selected) "● " else "○ ") + topic.name +
                        if (topic.level > 0) "（Lv.${topic.level}）" else "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CountsCard(state: EngageUiState, onSetCount: (EngageAction, Int) -> Unit) {
    SectionCard(stringResource(R.string.engage_counts_title)) {
        CountRow(stringResource(R.string.engage_count_comment), state.config.commentCount) {
            onSetCount(EngageAction.COMMENT, it)
        }
        CountRow(stringResource(R.string.engage_count_repost), state.config.repostCount) {
            onSetCount(EngageAction.REPOST, it)
        }
        CountRow(stringResource(R.string.engage_count_like), state.config.likeCount) {
            onSetCount(EngageAction.LIKE, it)
        }
        CountRow(stringResource(R.string.engage_count_post), state.config.postCount) {
            onSetCount(EngageAction.POST, it)
        }
        if (state.hints.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            state.hints.forEach { hint ->
                Text(
                    "· $hint",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CountRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onChange(value - 1) }, enabled = value > 0) { Text("−") }
        Text("$value", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = { onChange(value + 1) }, enabled = value < 30) { Text("+") }
        Text(
            stringResource(R.string.engage_count_unit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScheduleCard(state: EngageUiState, onUpdate: ((EngageConfig) -> EngageConfig) -> Unit) {
    SectionCard(stringResource(R.string.engage_time_title)) {
        // 默认关闭：开启任务 ≠ 允许它自己动
        SwitchRow(stringResource(R.string.engage_auto_run), state.config.autoRunOnOpen) { v ->
            onUpdate { it.copy(autoRunOnOpen = v) }
        }
        if (!state.config.autoRunOnOpen) {
            Text(
                stringResource(R.string.engage_auto_run_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "打开 App 时，若今天还没有一次成功的运行，会自动执行一轮。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.engage_time_quiet), modifier = Modifier.weight(1f))
            TextButton(onClick = {
                onUpdate { it.copy(quietEndHour = (it.quietEndHour - 1).coerceAtLeast(it.quietStartHour + 1)) }
            }) { Text("−") }
            Text(
                stringResource(
                    R.string.engage_time_quiet_value,
                    state.config.quietStartHour,
                    state.config.quietEndHour,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                onUpdate { it.copy(quietEndHour = (it.quietEndHour + 1).coerceAtMost(12)) }
            }) { Text("+") }
        }
    }
}

@Composable
private fun PostSettingsCard(state: EngageUiState, onUpdate: ((EngageConfig) -> EngageConfig) -> Unit) {
    SectionCard(stringResource(R.string.engage_post_title)) {
        OutlinedTextField(
            value = state.config.videoUrl,
            onValueChange = { v -> onUpdate { it.copy(videoUrl = v.take(200)) } },
            label = { Text(stringResource(R.string.engage_post_video)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(stringResource(R.string.engage_post_sync), state.config.syncToTimeline) { v ->
            onUpdate { it.copy(syncToTimeline = v) }
        }
        SwitchRow(stringResource(R.string.engage_post_confirm), state.config.requireConfirmBeforePost) { v ->
            onUpdate { it.copy(requireConfirmBeforePost = v) }
        }
        SwitchRow(stringResource(R.string.engage_post_background), state.config.postInBackground) { v ->
            onUpdate { it.copy(postInBackground = v) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.engage_post_maxlen),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                onUpdate { it.copy(maxTextLen = (it.maxTextLen - 100).coerceAtLeast(100)) }
            }) { Text("−") }
            Text("${state.config.maxTextLen}", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                onUpdate { it.copy(maxTextLen = (it.maxTextLen + 100).coerceAtMost(2000)) }
            }) { Text("+") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PostsCard(state: EngageUiState, onOpenPosts: () -> Unit) {
    SectionCard(stringResource(R.string.engage_posts_title)) {
        if (state.posts.isEmpty()) {
            Text(
                stringResource(R.string.engage_posts_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.posts.take(3).forEach { post ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = EngageSchedule.clockLabel(post.at) + "  " + postStateLabel(post.state),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (post.state == ActionState.UNCERTAIN) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        post.body.replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        TextButton(onClick = onOpenPosts) { Text(stringResource(R.string.engage_posts_open)) }
    }
}

@Composable
private fun RunsCard(state: EngageUiState) {
    SectionCard(stringResource(R.string.engage_runs_title)) {
        if (state.runs.isEmpty()) {
            Text(
                stringResource(R.string.engage_runs_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.runs.take(5).forEachIndexed { index, run ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        EngageSchedule.dateLabel(run.startedAt) + " " +
                            EngageSchedule.clockLabel(run.startedAt) + " · " + run.summary(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (run.finishedAt == 0L) {
                        Text(
                            stringResource(R.string.engage_run_in_progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (run.stopReason.isNotEmpty()) {
                        Text(
                            run.stopReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // 最近一轮展开每条动作：失败原因本来就存在 records 里，
                    // 之前只渲染摘要，等于把唯一的排查线索藏起来了。
                    if (index == 0) {
                        run.records.forEach { r ->
                            val tail = if (r.message.isEmpty()) "" else "：${r.message}"
                            Text(
                                "· ${r.action.label}#${r.seq} ${r.state.label}$tail",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DangerCard(enabled: Boolean, onDisable: () -> Unit, onClearAll: () -> Unit) {
    // 清除是不可恢复的（配置 + 文案库 + 运行记录 + 台账全删），必须二次确认
    var confirming by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.engage_danger_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (enabled) {
                OutlinedButton(onClick = onDisable) { Text(stringResource(R.string.engage_disable)) }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = { confirming = true }) { Text(stringResource(R.string.engage_clear_data)) }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.engage_clear_data)) },
            text = { Text(stringResource(R.string.engage_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onClearAll()
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun postStateLabel(state: ActionState): String = when (state) {
    ActionState.OK -> stringResource(R.string.engage_posts_state_ok)
    ActionState.UNCERTAIN -> stringResource(R.string.engage_posts_state_uncertain)
    ActionState.FAILED -> stringResource(R.string.engage_posts_state_failed)
    ActionState.SKIPPED -> stringResource(R.string.engage_posts_state_skipped)
    ActionState.AWAITING_CONFIRM -> stringResource(R.string.engage_posts_state_awaiting)
}

/** 风险声明弹窗：去掉逐次确认后，这是唯一一次「事前知情」。 */
@Composable
fun EngageConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.engage_consent_title)) },
        text = { Text(stringResource(R.string.engage_consent_body)) },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.engage_consent_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.engage_consent_decline)) }
        },
    )
}
