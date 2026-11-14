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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class LogCardUi(
    val expanded: Boolean = false,
    val lines: List<String> = emptyList(),
    val summary: String = "",
)

/** 首页运行日志卡：收起时一行摘要，展开是固定高度终端窗口。 */
@Composable
fun LogCard(state: LogCardUi, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(R.color.surface)),
        border = androidx.compose.foundation.BorderStroke(1.dp, colorResource(R.color.line)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val cd = stringResource(
                if (state.expanded) R.string.cd_log_expanded else R.string.cd_log_collapsed,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .semantics { contentDescription = cd },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.log_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorResource(R.color.ink),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (state.expanded) "⌃" else "⌄",
                    style = MaterialTheme.typography.titleLarge,
                    color = colorResource(R.color.ink_3),
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
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 18.7.sp,
        color = colorResource(R.color.console_ink),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(200.dp)
            .clip(RoundedCornerShape(10.dp))
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