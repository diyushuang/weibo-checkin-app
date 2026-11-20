package com.wcm.checkin

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 超话页渲染：账号切换、三列概览、等级徽章、筛选与排序、逐条展开详情、行菜单、字段说明。
 *
 * 详情是懒加载的（点开才拉），行菜单可单独签到 / 复制签到链接 / 查看原始 JSON。
 *
 * ## 列表页的签名（v3.0）
 *
 * 这一页与首页刻意不同：
 *
 * - 顶部概览条**没有卡片标题**（[WcmMetricStrip]），三列数字 + 1dp 竖线，一眼给结论；
 * - 行卡之间 **8dp**（首页卡间 12dp），同高度能多读一行；
 * - 行卡内边距 14dp，密度高于首页的 16dp。
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

    WcmPage(
        title = stringResource(R.string.topic_title),
        subtitle = state.subtitle,
        actions = {
            WcmTopBarAction(
                icon = R.drawable.ic_refresh,
                description = stringResource(R.string.btn_refresh_topics),
                onClick = onRefresh,
            )
            OverflowMenu(onField = onOverflowField, onRefreshAll = onOverflowRefresh)
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = pagePad,
                end = pagePad,
                top = WcmSpace.pageTop,
                bottom = WcmSpace.pageBottom,
            ),
        ) {
            item(key = "metrics") {
                Box(Modifier.padding(top = WcmSpace.section)) { MetricsStrip(state) }
            }
            if (state.pills.isNotEmpty()) {
                item(key = "pills") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = WcmSpace.cardGap)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.pills.forEach { pill ->
                            Chip(pill.label, pill.selected) { onSelectAccount(pill.uid) }
                        }
                    }
                }
            }
            item(key = "filters") {
                FilterRow(
                    state = state,
                    onFilter = onFilter,
                    onSort = onSort,
                    modifier = Modifier.padding(top = WcmSpace.cardGap),
                )
            }
            if (state.showError) {
                item(key = "error") {
                    Box(Modifier.padding(top = WcmSpace.section)) {
                        MessageCard(
                            text = state.errorText,
                            action = stringResource(R.string.topic_retry),
                            onAction = onRetry,
                        )
                    }
                }
            }
            if (state.showEmpty) {
                item(key = "empty") {
                    Box(Modifier.padding(top = WcmSpace.section)) {
                        WcmEmptyState(
                            icon = R.drawable.ic_nav_topics,
                            title = state.emptyText,
                            actionText = if (state.emptyActionVisible) {
                                stringResource(R.string.topic_go_home)
                            } else {
                                null
                            },
                            onAction = onEmptyAction,
                        )
                    }
                }
            }
            items(state.rows, key = { it.uid + "\u0000" + it.key }) { row ->
                // 列表页行距 8dp：比首页的卡片间距更紧，一屏能多读一行
                Box(Modifier.padding(top = 8.dp)) {
                    TopicRowCard(row, onRowClick, onRowCheckin, onRowCopy, onRowOpen, onRowJson)
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(onField: () -> Unit, onRefreshAll: () -> Unit) {
    var overflow by remember { mutableStateOf(false) }
    Box {
        WcmTopBarAction(
            icon = R.drawable.ic_more_vert,
            description = stringResource(R.string.cd_more),
            onClick = { overflow = true },
        )
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

@Composable
private fun MetricsStrip(state: TopicUiState) {
    Column {
        WcmMetricStrip(
            metrics = listOf(
                WcmMetric(stringResource(R.string.topic_metric_total), state.metricTotal),
                WcmMetric(stringResource(R.string.topic_metric_done), state.metricDone),
                WcmMetric(
                    label = stringResource(R.string.topic_metric_pending),
                    value = state.metricPending,
                    highlight = true,
                ),
            ),
        )
        if (state.loading) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(3.dp),
                color = colorResource(R.color.brand),
                trackColor = colorResource(R.color.surface_3),
            )
        }
        if (state.status.isNotEmpty()) {
            Text(
                text = state.status,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
    }
}

@Composable
private fun FilterRow(
    state: TopicUiState,
    onFilter: (TopicFilter) -> Unit,
    onSort: (TopicSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortOpen by remember { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                TopicSort.entries.forEach { value ->
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
        shape = WcmShape.button,
        color = if (selected) colorResource(R.color.brand_soft) else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, colorResource(R.color.line_2)),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
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
    WcmCard(
        onClick = { onClick(row) },
        padding = 14.dp,
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
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = moreDesc,
                        tint = colorResource(R.color.ink_3),
                        modifier = Modifier.size(18.dp),
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
        shape = WcmShape.chip,
        color = bg,
        modifier = Modifier.semantics { contentDescription = desc },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = fg,
        )
    }
}

@Composable
private fun StatePill(done: Boolean) {
    WcmStatusPill(
        text = stringResource(if (done) R.string.topic_state_done else R.string.topic_state_pending),
        tone = if (done) PillTone.OK else PillTone.WARN,
    )
}

@Composable
private fun MessageCard(text: String, action: String?, onAction: () -> Unit) {
    WcmCard {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colorResource(R.color.danger_text),
        )
        if (action != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 10.dp)) {
                Text(action)
            }
        }
    }
}
