package com.wcm.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 单个动作（评论 / 转发 / 点赞 / 发帖）的子页面。
 *
 * 任务主页只留一行状态，细则全在这里：任务配置 → 运行状态 → 执行间隔 → 独立日志。
 * 四张页面共用这一份实现，差异只在 [ActionConfigCard] 里的「专属选项」一段。
 *
 * 为什么不做成四条独立页面：四个动作的差异只在几行专属配置，
 * 拆成四份会让「加一个动作」「改一处文案」都要改四个文件。
 */
@Composable
fun EngageTaskDetailScreen(
    viewModel: EngageViewModel,
    action: EngageAction,
    onBack: () -> Unit,
    onOpenTexts: (TextKind) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    // 同 EngageScreen：传 State 而不是 Long，运行中只重组读它的状态卡
    val nowMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running) {
        if (!state.running) return@LaunchedEffect
        while (true) {
            nowMs.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val summary = state.summaryOf(action)

    val pagePad = dimensionResource(R.dimen.page_padding_horizontal)
    // v3.0：二级页改用统一顶栏（返回箭头 + 22sp 标题 + 12sp 副标题），
    // 原来这里是「文字按钮『返回』+ titleLarge」，和首页顶栏不是一套。
    WcmPage(
        title = action.label,
        subtitle = summary.mode.label,
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
            // 详情页按「分组」读：组与组之间 20dp（卡内才是 12dp）
            verticalArrangement = Arrangement.spacedBy(WcmSpace.section),
        ) {
            Spacer(Modifier.height(WcmSpace.section))

            ActionConfigCard(
                state = state,
                action = action,
                onSetCount = { viewModel.setCount(action, it) },
                onSetMode = { viewModel.setMode(action, it) },
                onUpdate = { transform -> viewModel.update(transform) },
                onSetPickMode = { kind, mode -> viewModel.setPickMode(kind, mode) },
                onSetRepostTextEnabled = { viewModel.setRepostTextEnabled(it) },
                onOpenTexts = onOpenTexts,
            )

            RunStatusCard(
                state = state,
                action = action,
                nowMs = nowMs,
                onRunOnly = { viewModel.runOnly(action) },
            )

            ActionPaceCard(
                state = state,
                action = action,
                onApplyPace = { viewModel.applyPace(it) },
                onAdjustGap = { delta -> viewModel.adjustGap(action, delta) },
            )

            ActionLogCard(state, action)
        }
    }
}

// ============================ 任务配置 ============================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionConfigCard(
    state: EngageUiState,
    action: EngageAction,
    onSetCount: (Int) -> Unit,
    onSetMode: (EngageActionMode) -> Unit,
    onUpdate: ((EngageConfig) -> EngageConfig) -> Unit,
    onSetPickMode: (TextKind, TextPickMode) -> Unit,
    onSetRepostTextEnabled: (Boolean) -> Unit,
    onOpenTexts: (TextKind) -> Unit,
) {
    val config = state.config
    val mode = config.modeOf(action)
    val count = config.countOf(action)

    SectionBlock(stringResource(R.string.engage_task_config)) {
        // 次数
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.engage_task_count),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            WcmTextButton(onClick = { onSetCount(count - 1) }, enabled = count > 0) { Text("−") }
            Text("$count", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(4.dp))
            WcmTextButton(onClick = { onSetCount(count + 1) }, enabled = count < config.maxCountOf(action)) { Text("+") }
            Text(
                stringResource(R.string.engage_count_unit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 三态
        Spacer(Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EngageActionMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { onSetMode(m) },
                    label = { Text(m.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Text(
            mode.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // 专属选项
        when (action) {
            EngageAction.POST -> PostActionConfig(state, onUpdate, onSetPickMode, onOpenTexts)
            EngageAction.COMMENT -> ShortTextConfig(
                kind = TextKind.COMMENT,
                state = state,
                onSetPickMode = onSetPickMode,
                onOpenTexts = onOpenTexts,
            )
            EngageAction.REPOST -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.engage_repost_text_enabled),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.engage_repost_text_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = config.repostTextEnabled,
                        onCheckedChange = { onSetRepostTextEnabled(it) },
                    )
                }
                if (config.repostTextEnabled) {
                    Spacer(Modifier.height(6.dp))
                    ShortTextConfig(
                        kind = TextKind.REPOST,
                        state = state,
                        onSetPickMode = onSetPickMode,
                        onOpenTexts = onOpenTexts,
                    )
                } else {
                    Text(
                        stringResource(R.string.engage_repost_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            EngageAction.LIKE -> Text(
                stringResource(R.string.engage_like_no_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 发帖的专属配置。 */
@Composable
private fun PostActionConfig(
    state: EngageUiState,
    onUpdate: ((EngageConfig) -> EngageConfig) -> Unit,
    onSetPickMode: (TextKind, TextPickMode) -> Unit,
    onOpenTexts: (TextKind) -> Unit,
) {
    val config = state.config
    SwitchRow2(stringResource(R.string.engage_post_sync), config.syncToTimeline) { v ->
        onUpdate { it.copy(syncToTimeline = v) }
    }
    SwitchRow2(stringResource(R.string.engage_post_confirm), config.requireConfirmBeforePost) { v ->
        onUpdate { it.copy(requireConfirmBeforePost = v) }
    }
    SwitchRow2(stringResource(R.string.engage_post_background), config.postInBackground) { v ->
        onUpdate { it.copy(postInBackground = v) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.engage_post_maxlen),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        WcmTextButton(onClick = {
            onUpdate { it.copy(maxTextLen = (it.maxTextLen - 100).coerceAtLeast(100)) }
        }) { Text("−") }
        Text("${config.maxTextLen}", style = MaterialTheme.typography.bodyMedium)
        WcmTextButton(onClick = {
            onUpdate { it.copy(maxTextLen = (it.maxTextLen + 100).coerceAtMost(2000)) }
        }) { Text("+") }
    }
    Spacer(Modifier.height(6.dp))
    TextLibraryConfig(TextKind.POST, state, onSetPickMode, onOpenTexts)
}

/** 评论 / 转发共用的「短文案库」配置：库状态 + 取用方式 + 140 字上限说明。 */
@Composable
private fun ShortTextConfig(
    kind: TextKind,
    state: EngageUiState,
    onSetPickMode: (TextKind, TextPickMode) -> Unit,
    onOpenTexts: (TextKind) -> Unit,
) {
    TextLibraryConfig(kind, state, onSetPickMode, onOpenTexts)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextLibraryConfig(
    kind: TextKind,
    state: EngageUiState,
    onSetPickMode: (TextKind, TextPickMode) -> Unit,
    onOpenTexts: (TextKind) -> Unit,
) {
    val available = state.availableTexts(kind)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.engage_texts_of_kind, kind.label, available),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (kind == TextKind.POST) {
                    stringResource(R.string.engage_task_limit_post)
                } else {
                    stringResource(R.string.engage_task_limit_hard_fmt, EngageTextPool.COMMENT_LIMIT)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WcmTextButton(onClick = { onOpenTexts(kind) }) {
            Text(stringResource(R.string.engage_texts_manage))
        }
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.engage_texts_mode_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextPickMode.entries.forEach { m ->
            FilterChip(
                selected = state.config.textModeOf(kind) == m,
                onClick = { onSetPickMode(kind, m) },
                label = { Text(m.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
    Text(
        state.config.textModeOf(kind).hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ============================ 运行状态 ============================

@Composable
private fun RunStatusCard(
    state: EngageUiState,
    action: EngageAction,
    nowMs: State<Long>,
    onRunOnly: () -> Unit,
) {
    val summary = state.summaryOf(action)
    val progress = state.progress
    val runningThis = state.running && progress?.action == action

    SectionBlock(stringResource(R.string.engage_task_status)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.engage_task_today, summary.done, summary.count),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (summary.pendingPost) {
                    stringResource(R.string.engage_task_pending_post)
                } else if (summary.isOff) {
                    stringResource(R.string.engage_action_off_hint)
                } else {
                    stringResource(R.string.engage_task_left, summary.remaining)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (summary.pendingPost) {
                    colorResource(R.color.danger_text)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        val last = summary.lastLabel(state.today)
        if (last.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.engage_task_last, last) +
                    if (summary.lastMessage.isEmpty()) "" else " · ${summary.lastMessage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.engage_task_no_record),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (runningThis && progress != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(progress.done).append("/").append(progress.total)
                    val waitLeft = progress.waitUntilMs - nowMs.value
                    if (waitLeft > 0) {
                        append(stringResource(R.string.engage_wait_fmt, (waitLeft + 999) / 1000))
                    } else if (progress.stage.isNotEmpty()) {
                        append(stringResource(R.string.engage_eta_fmt, progress.stage))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))
        WcmOutlinedButton(
            onClick = onRunOnly,
            enabled = state.enabled && !state.running && !summary.isOff,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.engage_action_run_only))
        }
        if (!state.enabled) {
            Text(
                stringResource(R.string.engage_run_need_enable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================ 执行间隔 ============================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionPaceCard(
    state: EngageUiState,
    action: EngageAction,
    onApplyPace: (EngagePace) -> Unit,
    onAdjustGap: (Long) -> Unit,
) {
    val config = state.config
    val range = config.gapRangeOf(action)
    val pace = EngagePacing.presetOf(config)

    SectionBlock(stringResource(R.string.engage_task_pace)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EngagePace.entries.filter { it != EngagePace.CUSTOM }.forEach { p ->
                FilterChip(
                    selected = pace == p,
                    onClick = { onApplyPace(p) },
                    label = { Text(p.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.engage_task_pace_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        GapRow("${action.label}${stringResource(R.string.engage_task_gap_suffix)}", range, onAdjustGap)

        if (range.first > config.maxRunDurationMs) {
            Text(
                stringResource(R.string.engage_task_gap_over_budget),
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.danger_text),
            )
        }
        EngagePacing.riskHint(config)?.let { hint ->
            Spacer(Modifier.height(6.dp))
            // tertiary 在 WcmTheme 里没有映射，原来拿到的是 M3 默认粉紫；
            // 这段文案讲的是「间隔超出预算」，按 warn 语义着色才和正文说得一致。
            Card(
                colors = CardDefaults.cardColors(containerColor = colorResource(R.color.warn_soft)),
                shape = WcmShape.inner,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

// ============================ 独立日志 ============================

/**
 * 该动作的独立日志。
 *
 * 分两组：本动作的流水，以及 `action == null` 的全局流水。后者不能省——
 * 「登录态已失效」「未关注超话」「当前处于免打扰时段」都打在没有 action 的流水上，
 * 只显示本动作的话，用户点进来会看到一片空白而不知道发生了什么。
 */
@Composable
private fun ActionLogCard(state: EngageUiState, action: EngageAction) {
    var showGlobal by remember { mutableStateOf(true) }
    val own = state.logsOf(action, includeGlobal = false)
    val global = state.logsOf(action, includeGlobal = true).filter { it.action == null }

    SectionBlock(stringResource(R.string.engage_task_logs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.engage_task_logs_own, own.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            WcmTextButton(onClick = { showGlobal = !showGlobal }) {
                Text(
                    if (showGlobal) {
                        stringResource(R.string.engage_task_logs_hide_global)
                    } else {
                        stringResource(R.string.engage_task_logs_show_global)
                    },
                )
            }
        }

        if (own.isEmpty()) {
            Text(
                stringResource(R.string.engage_task_logs_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        own.takeLast(80).forEach { entry -> EngageLogRow(entry, showAction = false) }

        if (showGlobal && global.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.engage_task_logs_global, global.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            global.takeLast(40).forEach { entry ->
                Text(
                    entry.line(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow2(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
