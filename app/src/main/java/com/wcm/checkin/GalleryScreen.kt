package com.wcm.checkin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 图库抓取页：目录选择、抓取目标列表、运行卡（阶段/进度/停止/重试）、模式开关。
 *
 * 本页只渲染 `GalleryUiState` 并把用户意图转成事件；真正跑抓取的是 `CrawlService`
 * （单闸门），页面不持有任务。
 */
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    onAddTarget: () -> Unit,
    onPickDir: () -> Unit,
    onOpenGallery: () -> Unit,
    onModeToggle: (Boolean) -> Unit,
    onAutoToggle: (Boolean) -> Unit,
    onStart: () -> Unit,
    onFull: () -> Unit,
    onStop: () -> Unit,
    onRetryFailed: () -> Unit,
    onBrowse: () -> Unit,
    onSinglePost: () -> Unit,
    onLogToggle: () -> Unit,
    onTargetIncremental: (String) -> Unit,
    onTargetFull: (String) -> Unit,
    onTargetSince: (String) -> Unit,
    onTargetDelete: (String) -> Unit,
) {
    val pagePad = dimensionResource(R.dimen.page_padding_horizontal)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg)),
        contentPadding = PaddingValues(horizontal = pagePad, vertical = pagePad),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") { Header(onAddTarget) }
        item(key = "gallery") { GalleryEntry(state.galleryCount, onOpenGallery) }
        item(key = "dir") { DirCard(state, onPickDir) }
        item(key = "targets") {
            TargetsCard(state, onTargetIncremental, onTargetFull, onTargetSince, onTargetDelete)
        }
        item(key = "run") {
            RunCard(state, onModeToggle, onAutoToggle, onStart, onFull, onStop, onRetryFailed, onBrowse, onSinglePost)
        }
        item(key = "log") {
            LogCard(
                state = state.log,
                onToggle = onLogToggle,
                labelRes = R.string.crawl_log_title,
                cdExpandedRes = R.string.cd_crawl_log_expanded,
                cdCollapsedRes = R.string.cd_crawl_log_collapsed,
            )
        }
    }
}

@Composable
private fun Header(onAddTarget: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.page_gallery_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorResource(R.color.ink),
            )
            Text(
                text = stringResource(R.string.crawl_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.ink_3),
            )
        }
        Surface(shape = RoundedCornerShape(12.dp), color = colorResource(R.color.brand)) {
            IconButton(onClick = onAddTarget) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.cd_add_target),
                    tint = colorResource(R.color.brand_on),
                )
            }
        }
    }
}

@Composable
private fun GalleryEntry(count: String, onOpen: () -> Unit) {
    WcmCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(10.dp), color = colorResource(R.color.brand_soft)) {
                Icon(
                    painter = painterResource(R.drawable.ic_image),
                    contentDescription = stringResource(R.string.gallery_open),
                    tint = colorResource(R.color.brand_strong),
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.gallery_card_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colorResource(R.color.ink),
                )
                Text(
                    text = count,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = colorResource(R.color.ink_3),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun DirCard(state: GalleryUiState, onPickDir: () -> Unit) {
    WcmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(10.dp), color = colorResource(R.color.surface_2)) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = colorResource(R.color.ink_2),
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.crawl_dir_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colorResource(R.color.ink),
                )
                Text(
                    text = state.dirPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.dirStatus.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = colorResource(R.color.warn_soft),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { onPickDir() },
                    ) {
                        Text(
                            text = state.dirStatus,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(R.color.warn_text),
                        )
                    }
                }
            }
            OutlinedButton(onClick = onPickDir) {
                Text(stringResource(R.string.crawl_pick_dir))
            }
        }
    }
}

@Composable
private fun TargetsCard(
    state: GalleryUiState,
    onIncremental: (String) -> Unit,
    onFull: (String) -> Unit,
    onSince: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    WcmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.targetsTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorResource(R.color.ink),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.crawl_target_hint_short),
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (state.targets.isEmpty()) {
            Text(
                text = state.targetsEmptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            state.targets.forEachIndexed { index, target ->
                if (index > 0) HorizontalDivider(color = colorResource(R.color.line))
                TargetRow(target, onIncremental, onFull, onSince, onDelete)
            }
        }
    }
}

@Composable
private fun TargetRow(
    target: GalleryTargetUi,
    onIncremental: (String) -> Unit,
    onFull: (String) -> Unit,
    onSince: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { menu = true }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = target.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colorResource(R.color.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = target.uid,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
            )
            Text(
                text = target.progress,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Text(
                    text = "⋮",
                    style = MaterialTheme.typography.titleLarge,
                    color = colorResource(R.color.ink_3),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.crawl_target_incremental)) },
                    onClick = {
                        menu = false
                        onIncremental(target.uid)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.crawl_target_full)) },
                    onClick = {
                        menu = false
                        onFull(target.uid)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.crawl_since_btn)) },
                    onClick = {
                        menu = false
                        onSince(target.uid)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.crawl_remove_short)) },
                    onClick = {
                        menu = false
                        onDelete(target.uid)
                    },
                )
            }
        }
    }
}

@Composable
private fun RunCard(
    state: GalleryUiState,
    onModeToggle: (Boolean) -> Unit,
    onAutoToggle: (Boolean) -> Unit,
    onStart: () -> Unit,
    onFull: () -> Unit,
    onStop: () -> Unit,
    onRetryFailed: () -> Unit,
    onBrowse: () -> Unit,
    onSinglePost: () -> Unit,
) {
    WcmCard {
        Text(
            text = stringResource(R.string.crawl_run_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colorResource(R.color.ink),
        )
        Spacer(Modifier.height(6.dp))
        SwitchRow(
            label = state.modeLabel,
            checked = state.modeAlbum,
            enabled = true,
            onToggle = onModeToggle,
        )
        HorizontalDivider(color = colorResource(R.color.line_2))
        SwitchRow(
            label = stringResource(R.string.crawl_auto_after_checkin),
            checked = state.autoAfterCheckin,
            enabled = true,
            onToggle = onAutoToggle,
        )
        val progress = state.progress
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress.ratio },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = colorResource(R.color.brand),
                trackColor = colorResource(R.color.surface_3),
            )
            Text(
                text = progress.step,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = progress.detail,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (state.retryCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.retryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetryFailed) {
                    Text(stringResource(R.string.crawl_retry_action))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (state.running) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(R.color.danger),
                    contentColor = colorResource(R.color.brand_on),
                ),
            ) {
                Text(stringResource(R.string.crawl_stop))
            }
        } else {
            Button(
                onClick = onStart,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.crawl_start))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onFull,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.crawl_full_all))
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.browse_entry))
        }
        TextButton(
            onClick = onSinglePost,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.single_title))
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colorResource(R.color.ink),
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = enabled,
        )
    }
}

@Composable
private fun WcmCard(
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = colorResource(R.color.surface))
    val border = BorderStroke(1.dp, colorResource(R.color.line))
    val shape = RoundedCornerShape(14.dp)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            colors = colors,
            border = border,
            elevation = elevation,
        ) {
            Column(Modifier.padding(14.dp), content = content)
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            colors = colors,
            border = border,
            elevation = elevation,
        ) {
            Column(Modifier.padding(14.dp), content = content)
        }
    }
}