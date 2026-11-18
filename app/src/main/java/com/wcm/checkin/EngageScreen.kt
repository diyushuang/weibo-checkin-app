package com.wcm.checkin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 任务页主界面。
 *
 * 分区顺序是刻意的：**状态 → 过程 → 预览 → 参数**。
 * - 状态与过程最靠前：一轮动辄几分钟，用户点完执行最想看的是「现在到哪一步了」；
 * - 预览排在参数前：默认不逐次确认，用户对「到底会发什么」的掌控只能靠预览；
 * - 参数按「动作 → 节奏 → 时间 → 发帖」排列，先回答「做什么」再回答「怎么排」。
 *
 * 过程区的展示方式参考 GitHub Actions 的 job 页：步骤清单（状态 + 每步耗时）
 * 加一条带时间戳的流水，运行中的步骤有明确标记，失败步骤用红色直接指出。
 */
@Composable
fun EngageScreen(
    state: EngageUiState,
    onToggleEnable: () -> Unit,
    onRunNow: () -> Unit,
    onRunOnly: (EngageAction) -> Unit,
    onSelectAccount: (String) -> Unit,
    onSelectTopic: (EngageTopicOption) -> Unit,
    onRefreshTopics: () -> Unit,
    onSetCount: (EngageAction, Int) -> Unit,
    onSetMode: (EngageAction, EngageActionMode) -> Unit,
    onApplyPace: (EngagePace) -> Unit,
    onAdjustGap: (EngageAction?, Long) -> Unit,
    onUpdate: ((EngageConfig) -> EngageConfig) -> Unit,
    onNextPreview: () -> Unit,
    onOpenTexts: () -> Unit,
    onOpenPosts: () -> Unit,
    onClearAll: () -> Unit,
) {
    // 运行中每秒刷新一次「已用时间 / 等待倒计时 / 步骤耗时」。
    // 放在页面层而不是每个卡片里：一次 tick 同时驱动状态卡与过程卡，避免两套计时器不同步。
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running) {
        if (!state.running) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    var openRunId by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state, nowMs, onToggleEnable, onRunNow)
        TraceCard(state, nowMs)
        RunsCard(state, openRunId) { id -> openRunId = if (openRunId == id) "" else id }

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
        ActionsCard(state, onSetCount, onSetMode, onRunOnly)
        PaceCard(state, onApplyPace, onAdjustGap, onUpdate)
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

// ============================ 状态 ============================

@Composable
private fun StatusCard(state: EngageUiState, nowMs: Long, onToggle: () -> Unit, onRunNow: () -> Unit) {
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

            val progress = state.progress
            if (state.running && progress != null) {
                Spacer(Modifier.height(8.dp))
                // 总体进度：一眼看出「还剩多少」，比一行文字更直观
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${progress.done}/${progress.total} · ${progress.percent}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        buildString {
                            append("已用 ").append(EngagePacing.formatDuration(progress.elapsedMs))
                            val waitLeft = progress.waitUntilMs - nowMs
                            when {
                                waitLeft > 0 -> append(" · 等待 ").append((waitLeft + 999) / 1000).append(" 秒")
                                else -> progress.etaLabel(nowMs).takeIf { it.isNotEmpty() }?.let {
                                    append(" · ").append(it)
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRunNow,
                enabled = state.enabled && !state.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.engage_run_now))
            }
            // 配置不合法时把原因直接摊在按钮下面。
            // 否则用户点了「单独执行」只看到一句「未选择账号」，还得自己去翻是哪一项没填。
            val invalid = state.config.validate()
            if (state.enabled && invalid != null) {
                Text(
                    text = "当前配置还不能执行：$invalid",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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

// ============================ 执行过程 ============================

/**
 * 过程卡：步骤清单 + 实时流水。
 *
 * 只在「运行中」或「本次进程跑过一轮」时出现；没有过程数据时整卡隐藏，
 * 避免首页一进来就被一块空白占掉。
 */
@Composable
private fun TraceCard(state: EngageUiState, nowMs: Long) {
    val progress = state.progress ?: return
    if (progress.steps.isEmpty() && progress.logs.isEmpty() && !state.running) return

    SectionCard(stringResource(R.string.engage_trace_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = progress.stage.ifEmpty { stringResource(R.string.engage_trace_idle) },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (progress.isFinished) {
                    "用时 " + EngagePacing.formatDuration(progress.elapsedMs)
                } else {
                    (progress.waitUntilMs - nowMs).takeIf { it > 0 }
                        ?.let { "等待 ${(it + 999) / 1000} 秒" }
                        ?: "进行中 · " + EngagePacing.formatDuration(progress.elapsedMs)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))

        progress.steps.forEach { step ->
            StepRow(step, nowMs)
        }

        if (progress.logs.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LogStream(progress.logs, running = !progress.isFinished)
        }

        if (progress.message.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                progress.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepRow(step: EngageStep, nowMs: Long) {
    val color = when (step.state) {
        EngageStepState.OK -> MaterialTheme.colorScheme.primary
        EngageStepState.RUNNING -> MaterialTheme.colorScheme.primary
        EngageStepState.WARN -> MaterialTheme.colorScheme.error
        EngageStepState.FAILED -> MaterialTheme.colorScheme.error
        EngageStepState.SKIPPED, EngageStepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val symbol = when (step.state) {
        EngageStepState.PENDING -> "○"
        EngageStepState.RUNNING -> "◉"
        EngageStepState.OK -> "✓"
        EngageStepState.WARN -> "!"
        EngageStepState.FAILED -> "✕"
        EngageStepState.SKIPPED -> "–"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.width(16.dp),
        )
        Text(
            step.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (step.state == EngageStepState.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
            color = if (step.state == EngageStepState.PENDING) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val detail = step.detail.ifEmpty {
            if (step.startedAt > 0L) step.elapsedLabel(nowMs) else ""
        }
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(96.dp),
            )
        }
    }
}

/**
 * 流水区：固定高度 + 内部滚动 + 自动跟随最新一条。
 *
 * 用 LazyColumn 而不是整页铺开：一轮几十条流水会把页面撑到几屏，
 * 用户反而看不到下面的参数；固定高度里滚动才像「日志面板」。
 */
@Composable
private fun LogStream(logs: List<EngageLogEntry>, running: Boolean) {
    val listState = rememberLazyListState()
    // 用「最后一条的身份」而不是条数作为 key：条数到达上限（MAX_LOGS）后不再变化，
    // 拿 size 当 key 会导致后面的新日志不再自动滚到可见位置。
    val tailKey = logs.lastOrNull()?.let { "${it.at}|${it.level}|${it.text}" } ?: ""
    LaunchedEffect(tailKey, running) {
        // 跟随最新一条：不 auto-scroll 的话，用户看到的永远是开头几条，
        // 最新进展要靠手动滑，等于没实时。
        // runCatching：列表在同一帧里变短时索引可能越界，不能让展示层崩掉一轮执行。
        if (logs.isNotEmpty()) runCatching { listState.animateScrollToItem(logs.lastIndex) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp, max = 220.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 不用 key：同一毫秒可能写入两条一模一样的流水（例如同原因连续跳过），
        // LazyColumn 的 key 必须唯一，重复会直接抛 IllegalArgumentException。
        items(logs) { entry ->
            LogRow(entry)
        }
    }
}

@Composable
private fun LogRow(entry: EngageLogEntry) {
    val color = when (entry.level) {
        EngageLogLevel.ERROR -> MaterialTheme.colorScheme.error
        EngageLogLevel.WARN -> MaterialTheme.colorScheme.error
        EngageLogLevel.OK -> MaterialTheme.colorScheme.primary
        EngageLogLevel.WAIT -> MaterialTheme.colorScheme.primary
        EngageLogLevel.STEP -> MaterialTheme.colorScheme.onSurface
        EngageLogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(
            entry.clock(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            buildString {
                val target = entry.action?.let { a ->
                    if (entry.seq > 0) "${a.label}#${entry.seq} " else "${a.label} "
                }
                if (!target.isNullOrEmpty()) append(target)
                append(entry.text)
            },
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

// ============================ 运行记录 ============================

@Composable
private fun RunsCard(state: EngageUiState, openRunId: String, onToggle: (String) -> Unit) {
    SectionCard(stringResource(R.string.engage_runs_title)) {
        if (state.runs.isEmpty()) {
            Text(
                stringResource(R.string.engage_runs_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.runs.take(5).forEach { run ->
                val expanded = run.runId == openRunId
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                EngageSchedule.dateLabel(run.startedAt) + " " +
                                    EngageSchedule.clockLabel(run.startedAt) + " · " + run.summary(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                buildString {
                                    append(run.trigger.label)
                                    if (run.durationMs > 0L) {
                                        append(" · ").append(EngagePacing.formatDuration(run.durationMs))
                                    }
                                    if (run.logs.isNotEmpty()) append(" · ").append(run.logs.size).append(" 条日志")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onToggle(run.runId) }) {
                            Text(if (expanded) "收起" else "详情")
                        }
                    }
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
                    // 展开：先给步骤清单，再给最近若干条流水。
                    // 这两块此前完全没落盘，跑完只剩「评论 0/1 失败」，排查只能靠 logcat。
                    if (expanded) {
                        if (run.steps.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            run.steps.forEach { step ->
                                Text(
                                    "  ${stepSymbol(step)} ${step.title}" +
                                        if (step.detail.isNotEmpty()) "  ${step.detail}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (step.state == EngageStepState.FAILED ||
                                        step.state == EngageStepState.WARN
                                    ) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        if (run.logs.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "最近流水",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            run.logs.takeLast(40).forEach { entry ->
                                Text(
                                    "  " + entry.line(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (entry.level == EngageLogLevel.ERROR) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        run.records.forEach { r ->
                            val tail = if (r.message.isEmpty()) "" else "：${r.message}"
                            Text(
                                "  · ${r.action.label}#${r.seq} ${r.state.label}$tail",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun stepSymbol(step: EngageStep): String = when (step.state) {
    EngageStepState.PENDING -> "○"
    EngageStepState.RUNNING -> "◉"
    EngageStepState.OK -> "✓"
    EngageStepState.WARN -> "!"
    EngageStepState.FAILED -> "✕"
    EngageStepState.SKIPPED -> "–"
}

// ============================ 预览 ============================

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

// ============================ 账号与超话 ============================

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

// ============================ 动作与次数 ============================

/**
 * 单个动作的三态 + 次数 + 单独执行。
 *
 * 上一版只有一行次数增减，动作开不开是隐藏配置（`postEnabled` 等字段没有入口），
 * 用户既没法只跑点赞，也没法把发帖改成「只在我点的时候做」。
 *
 * 三态用 FlowRow 而不是 Row：三枚 FilterChip 加一个按钮在小屏（320dp）上
 * 刚好会挤出右边界，Row 不会换行、只会把按钮裁掉。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsCard(
    state: EngageUiState,
    onSetCount: (EngageAction, Int) -> Unit,
    onSetMode: (EngageAction, EngageActionMode) -> Unit,
    onRunOnly: (EngageAction) -> Unit,
) {
    SectionCard(stringResource(R.string.engage_actions_title)) {
        EngageAction.entries.forEachIndexed { index, action ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
            val mode = state.config.modeOf(action)
            val count = state.config.countOf(action)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(action.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (mode != EngageActionMode.OFF) {
                    TextButton(onClick = { onSetCount(action, count - 1) }, enabled = count > 0) { Text("−") }
                    Text("$count", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = { onSetCount(action, count + 1) },
                        enabled = count < 30,
                    ) { Text("+") }
                    Text(
                        stringResource(R.string.engage_count_unit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.engage_action_off_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EngageActionMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { onSetMode(action, m) },
                        label = { Text(m.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
                TextButton(
                    onClick = { onRunOnly(action) },
                    enabled = state.enabled && !state.running && mode != EngageActionMode.OFF,
                ) { Text(stringResource(R.string.engage_action_run_only)) }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            stringResource(R.string.engage_actions_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.hints.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
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

// ============================ 执行节奏 ============================

/**
 * 节奏设置：预设档位 + 逐项间隔。
 *
 * 「默认间隔太长」是这次改动的起点：上一版把间隔写死在代码里（发帖 5–8 分钟），
 * 一轮要跑十几分钟，用户既等不了也改不了。现在给三档预设，并把逐项微调收在
 * 折叠区里——多数人只需要选一档，少数人再往下调。
 */
@Composable
private fun PaceCard(
    state: EngageUiState,
    onApplyPace: (EngagePace) -> Unit,
    onAdjustGap: (EngageAction?, Long) -> Unit,
    onUpdate: ((EngageConfig) -> EngageConfig) -> Unit,
) {
    val config = state.config
    val pace = EngagePacing.presetOf(config)
    var expanded by remember { mutableStateOf(false) }
    val estimate = EngagePacing.estimateRunMs(
        config,
        EngageAction.entries
            .filter { config.modeOf(it) != EngageActionMode.OFF }
            .associateWith { config.countOf(it) },
    )
    // 间隔比预算还长时，那个动作永远轮不到 —— 用户会以为「设了却不执行」。
    val overBudget = EngageAction.entries.filter { action ->
        config.modeOf(action) != EngageActionMode.OFF &&
            config.gapRangeOf(action).first > config.maxRunDurationMs
    }

    SectionCard(stringResource(R.string.engage_pace_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(EngagePace.GENTLE, EngagePace.BALANCED, EngagePace.FAST).forEach { p ->
                FilterChip(
                    selected = pace == p,
                    onClick = { onApplyPace(p) },
                    label = { Text(p.label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (pace == EngagePace.CUSTOM) "自定义" else pace.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            (if (pace == EngagePace.CUSTOM) "已逐项调整间隔。" else pace.hint + "。") +
                "按当前次数估算一轮约 ${EngagePacing.formatDuration(estimate)}。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (overBudget.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = overBudget.joinToString("、") { it.label } +
                    " 的间隔已超过单轮时间预算（${EngagePacing.formatDuration(config.maxRunDurationMs)}），" +
                    "这一轮很可能等不到它。把预算调大，或把间隔调短。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起逐项间隔" else "逐项调整间隔")
        }

        if (expanded) {
            GapRow(stringResource(R.string.engage_pace_post), config.gapRangeOf(EngageAction.POST)) {
                onAdjustGap(EngageAction.POST, it)
            }
            GapRow(stringResource(R.string.engage_pace_comment), config.gapRangeOf(EngageAction.COMMENT)) {
                onAdjustGap(EngageAction.COMMENT, it)
            }
            GapRow(stringResource(R.string.engage_pace_repost), config.gapRangeOf(EngageAction.REPOST)) {
                onAdjustGap(EngageAction.REPOST, it)
            }
            GapRow(stringResource(R.string.engage_pace_like), config.gapRangeOf(EngageAction.LIKE)) {
                onAdjustGap(EngageAction.LIKE, it)
            }
            GapRow(stringResource(R.string.engage_pace_between), config.actionGapRange()) {
                onAdjustGap(null, it)
            }
            BudgetRow(config.maxRunDurationMs) { onUpdate { cfg -> cfg.copy(maxRunDurationMs = it) } }
            Text(
                stringResource(R.string.engage_pace_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 单轮时间预算。
 *
 * 原来这个值只有默认 12 分钟、没有入口；间隔一旦调到 20 分钟以上，那个动作就永远
 * 轮不到。间隔可调而预算不可调是不成立的组合，所以把它一起放出来。
 */
@Composable
private fun BudgetRow(budgetMs: Long, onSet: (Long) -> Unit) {
    val minutes = (budgetMs / 60_000L).toInt()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.engage_pace_budget),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$minutes 分钟",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        TextButton(
            onClick = { onSet((minutes - 1).coerceAtLeast(1) * 60_000L) },
            enabled = minutes > 1,
        ) { Text("−") }
        TextButton(
            onClick = { onSet((minutes + 1).coerceAtMost(20) * 60_000L) },
            enabled = minutes < 20,
        ) { Text("+") }
    }
}

@Composable
private fun GapRow(label: String, range: LongRange, onShift: (Long) -> Unit) {
    val step = EngagePacing.stepOf(range)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            EngagePacing.describe(range),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        TextButton(onClick = { onShift(-step) }, enabled = range.first > 0L) { Text("−") }
        TextButton(onClick = { onShift(step) }, enabled = range.last < EngagePacing.MAX_GAP_MS) { Text("+") }
    }
}

/** 给 Range 用的便捷读取，避免每处都写 `xxxMinMs..xxxMaxMs`。 */

// ============================ 时间与发帖 ============================

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
                "打开 App 时，若今天还没有一次成功的运行，会自动执行一轮；" +
                    "设为「仅手动」的动作不会被带上。",
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
