package com.wcm.checkin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 首页运行日志卡：收起时是一行摘要，展开是深色等宽终端，自动贴底跟随。
 *
 * 贴底只在用户本来就停在底部时生效；用户手动往上翻看历史时不要抢回底部。
 */
data class LogCardUi(
    val expanded: Boolean = false,
    val lines: List<String> = emptyList(),
    val summary: String = "",
)

/**
 * 首页运行日志卡：收起时一行摘要，展开是固定高度终端窗口。
 *
 * v3.0：折叠箭头从文字字符「⌃ / ⌄」换成矢量图 —— 字符的基线不居中，
 * 收起态和展开态会看到箭头上下跳 1–2dp。
 */
@Composable
fun LogCard(
    state: LogCardUi,
    onToggle: () -> Unit,
    labelRes: Int = R.string.log_label,
    cdExpandedRes: Int = R.string.cd_log_expanded,
    cdCollapsedRes: Int = R.string.cd_log_collapsed,
) {
    WcmCard {
        val cd = stringResource(if (state.expanded) cdExpandedRes else cdCollapsedRes)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .semantics { contentDescription = cd },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.titleMedium,
                color = colorResource(R.color.ink),
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(
                    if (state.expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                ),
                contentDescription = null,
                tint = colorResource(R.color.ink_3),
                modifier = Modifier.size(20.dp),
            )
        }
        if (!state.expanded) {
            Text(
                text = state.summary.ifEmpty { stringResource(R.string.log_empty) },
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Console(state.lines)
        }
    }
}

@Composable
private fun Console(lines: List<String>) {
    val listState = rememberLazyListState()
    // 展开时滚到最新；新日志到达时只有原本就在底部才跟随，避免打断向上翻旧日志
    LaunchedEffect(lines.isNotEmpty()) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && !listState.canScrollForward) {
            listState.scrollToItem(lines.lastIndex)
        }
    }
    val mono = WcmType.mono.copy(color = colorResource(R.color.console_ink))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .height(200.dp)
            .clip(WcmShape.inner)
            .background(colorResource(R.color.console_bg)),
    ) {
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.log_empty),
                style = mono,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                itemsIndexed(lines) { _, line ->
                    Text(text = line, style = mono)
                }
            }
        }
    }
}
