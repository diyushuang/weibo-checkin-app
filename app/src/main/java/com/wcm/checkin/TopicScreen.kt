package com.wcm.checkin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 超话页渲染：账号切换、三列概览、等级徽章、筛选与排序、逐条展开详情、行菜单、字段说明。
 *
 * 详情是懒加载的（点开才拉），行菜单可单独签到 / 复制签到链接 / 查看原始 JSON。
 */
@Composable
fun TopicScreen(
    state: TopicUiState,
    onRefresh: () -> Unit,
    onOverflowField: () -> Unit,
    onOverflowRefresh: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onFilter: (TopicFilter) -> Unit,
    onSort: (TopicSort) -> Unit,
    onRowClick: (TopicRowUi) -> Unit,
    onRowCheckin: (TopicRowUi) -> Unit,
    onRowCopy: (TopicRowUi) -> Unit,
    onRowOpen: (TopicRowUi) -> Unit,
    onRowJson: (TopicRowUi) -> Unit,
    onEmptyAction: () -> Unit,
    onRetry: () -> Unit,
) {
    val pagePad = dimensionResource(R.dimen.page_padding_horizontal)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg)),
        contentPadding = PaddingValues(horizontal = pagePad, vertical = pagePad),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") { Header(state, onRefresh, onOverflowField, onOverflowRefresh) }
        item(key = "metrics") { MetricsCard(state) }
        if (state.pills.isNotEmpty()) {
            item(key = "pills") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    state.pills.forEach { pill ->
                        Chip(pill.label, pill.selected) { onSelectAccount(pill.uid) }
                    }
                }
            }
        }
        item(key = "filters") { FilterRow(state, onFilter, onSort) }
        if (state.showError) {
            item(key = "error") {
                MessageCard(state.errorText, action = stringResource(R.string.topic_retry), onAction = onRetry)
            }
        }
        if (state.showEmpty) {
            item(key = "empty") {
                MessageCard(
                    state.emptyText,
                    action = if (state.emptyActionVisible) stringResource(R.string.topic_go_home) else null,
                    onAction = onEmptyAction,
                )
            }
        }
        items(state.rows, key = { it.uid + "\u0000" + it.key }) { row ->
            TopicRowCard(row, onRowClick, onRowCheckin, onRowCopy, onRowOpen, onRowJson)
        }
    }
}

@Composable
private fun Header(
    state: TopicUiState,
    onRefresh: () -> Unit,
    onField: () -> Unit,
    onRefreshAll: () -> Unit,
) {
    var overflow by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.topic_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorResource(R.color.ink),
            )
            Text(
                text = state.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.ink_3),
            )
        }
        val refreshDesc = stringResource(R.string.btn_refresh_topics)
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.semantics { contentDescription = refreshDesc },
        ) {
            Text("⟳", style = MaterialTheme.typography.headlineSmall, color = colorResource(R.color.ink_2))
        }
        Box {
            IconButton(onClick = { overflow = true }) {
                Text("⋮", style = MaterialTheme.typography.headlineSmall, color = colorResource(R.color.ink_2))
            }
            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.topic_field_entry)) },
                    onClick = {
                        overflow = false
                        onField()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.btn_refresh_topics)) },
                    onClick = {
                        overflow = false
                        onRefreshAll()
                    },
                )
            }
        }
    }
}

@Composable
private fun MetricsCard(state: TopicUiState) {
    WcmTopicCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Metric(stringResource(R.string.topic_metric_total), state.metricTotal, Modifier.weight(1f))
            Metric(stringResource(R.string.topic_metric_done), state.metricDone, Modifier.weight(1f))
            Metric(stringResource(R.string.topic_metric_pending), state.metricPending, Modifier.weight(1f))
        }
        if (state.loading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = colorResource(R.color.brand),
                trackColor = colorResource(R.color.surface_3),
            )
        }
        if (state.status.isNotEmpty()) {
            Text(
                text = state.status,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(R.color.ink_3),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = colorResource(R.color.ink),
        )
    }
}

@Composable
private fun FilterRow(
    state: TopicUiState,
    onFilter: (TopicFilter) -> Unit,
    onSort: (TopicSort) -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            state.filters.forEach { chip ->
                Chip(chip.label, chip.selected) { onFilter(chip.filter) }
            }
        }
        Box {
            TextButton(onClick = { sortOpen = true }) {
                Text(stringResource(R.string.topic_sort_label), color = colorResource(R.color.ink_2))
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                TopicSort.values().forEach { value ->
                    DropdownMenuItem(
                        text = { Text(stringResource(sortLabel(value))) },
                        onClick = {
                            sortOpen = false
                            onSort(value)
                        },
                    )
                }
            }
        }
    }
}

private fun sortLabel(sort: TopicSort): Int = when (sort) {
    TopicSort.PENDING_FIRST -> R.string.topic_sort_default
    TopicSort.LEVEL_DESC -> R.string.topic_sort_level
    TopicSort.NAME -> R.string.topic_sort_name
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) colorResource(R.color.brand_soft) else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, colorResource(R.color.line_2)),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colorResource(R.color.brand_strong)
            else colorResource(R.color.ink_2),
        )
    }
}

@Composable
private fun TopicRowCard(
    row: TopicRowUi,
    onClick: (TopicRowUi) -> Unit,
    onCheckin: (TopicRowUi) -> Unit,
    onCopy: (TopicRowUi) -> Unit,
    onOpen: (TopicRowUi) -> Unit,
    onJson: (TopicRowUi) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // 整张卡是可点的（点一下展开/收起详情），展开态必须能被 TalkBack 播报出来，
    // 否则读屏用户点完之后听不到任何变化。
    val expandState = stringResource(
        if (row.expanded) R.string.cd_topic_expanded else R.string.cd_topic_collapsed,
    )
    WcmTopicCard(
        onClick = { onClick(row) },
        modifier = Modifier.semantics { stateDescription = expandState },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.levelTier > 0) {
                LevelBadge(row.levelLabel, row.levelTier)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colorResource(R.color.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            StatePill(row.done)
            Box {
                val moreDesc = stringResource(R.string.cd_topic_more)
                IconButton(onClick = { menu = true }) {
                    Text(
                        text = "⋮",
                        style = MaterialTheme.typography.titleLarge,
                        color = colorResource(R.color.ink_3),
                        modifier = Modifier.semantics { contentDescription = moreDesc },
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.topic_action_checkin)) },
                        enabled = !row.done,
                        onClick = {
                            menu = false
                            onCheckin(row)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.topic_action_copy)) },
                        onClick = {
                            menu = false
                            onCopy(row)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.topic_action_open)) },
                        onClick = {
                            menu = false
                            onOpen(row)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.topic_action_json)) },
                        onClick = {
                            menu = false
                            onJson(row)
                        },
                    )
                }
            }
        }
        Text(
            text = row.meta,
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(R.color.ink_3),
            modifier = Modifier.padding(top = 2.dp),
        )
        if (row.expanded) {
            Spacer(Modifier.height(8.dp))
            if (row.detailLoading) {
                Text(
                    text = stringResource(R.string.topic_detail_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                )
            } else {
                if (row.intro.isNotEmpty()) {
                    Text(
                        text = row.intro,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(R.color.ink_2),
                    )
                }
                if (row.stats.isNotEmpty()) {
                    Text(
                        text = row.stats,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(R.color.ink_2),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (row.detailHint.isNotEmpty()) {
                    Text(
                        text = row.detailHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(R.color.ink_3),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelBadge(label: String, tier: Int) {
    val (bg, fg) = when (tier) {
        1 -> colorResource(R.color.level_neutral_bg) to colorResource(R.color.level_neutral_fg)
        2 -> colorResource(R.color.level_blue_bg) to colorResource(R.color.level_blue_fg)
        3 -> colorResource(R.color.level_purple_bg) to colorResource(R.color.level_purple_fg)
        else -> colorResource(R.color.level_gold_bg) to colorResource(R.color.level_gold_fg)
    }
    val desc = stringResource(R.string.cd_level_badge, label)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        modifier = Modifier.semantics { contentDescription = desc },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = fg,
        )
    }
}

@Composable
private fun StatePill(done: Boolean) {
    val bg = if (done) colorResource(R.color.ok_soft) else colorResource(R.color.warn_soft)
    val fg = if (done) colorResource(R.color.ok_text) else colorResource(R.color.warn_text)
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Text(
            text = stringResource(if (done) R.string.topic_state_done else R.string.topic_state_pending),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
private fun MessageCard(text: String, action: String?, onAction: () -> Unit) {
    WcmTopicCard {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colorResource(R.color.ink_3),
        )
        if (action != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) {
                Text(action)
            }
        }
    }
}

@Composable
private fun WcmTopicCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val baseModifier = modifier.fillMaxWidth()
    val colors = CardDefaults.cardColors(containerColor = colorResource(R.color.surface))
    val border = BorderStroke(1.dp, colorResource(R.color.line))
    val shape = RoundedCornerShape(14.dp)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = baseModifier,
            shape = shape,
            colors = colors,
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(14.dp), content = content)
        }
    } else {
        Card(
            modifier = baseModifier,
            shape = shape,
            colors = colors,
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(14.dp), content = content)
        }
    }
}