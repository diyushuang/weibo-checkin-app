package com.wcm.checkin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 图库抓取页：目录选择、抓取目标列表、运行卡（阶段/进度/停止/重试）、模式开关。
 *
 * 本页只渲染 `GalleryUiState` 并把用户意图转成事件；真正跑抓取的是 `CrawlService`
 * （单闸门），页面不持有任务。
 *
 * v3.0：顶栏与卡片材质统一走 [WcmPage] / [WcmCard]；图标托圆角归到 `WcmShape.inner`，
 * 一屏只剩「开始抓取」一块品牌实底（顶栏「＋」已降为软底）。
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
    WcmPage(
        title = stringResource(R.string.page_gallery_title),
        subtitle = stringResource(R.string.crawl_intro),
        actions = {
            WcmTopBarAccentAction(
                icon = R.drawable.ic_add,
                description = stringResource(R.string.cd_add_target),
                onClick = onAddTarget,
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = pagePad,
                end = pagePad,
                bottom = WcmSpace.pageBottom,
            ),
        ) {
            item(key = "gallery") {
                Box(Modifier.padding(top = WcmSpace.section)) {
                    GalleryEntry(state.galleryCount, onOpenGallery)
                }
            }
            item(key = "dir") {
                Box(Modifier.padding(top = WcmSpace.cardGap)) {
                    DirCard(state, onPickDir)
                }
            }
            item(key = "targets") {
                Box(Modifier.padding(top = WcmSpace.section)) {
                    TargetsCard(
                        state = state,
                        onIncremental = onTargetIncremental,
                        onFull = onTargetFull,
                        onSince = onTargetSince,
                        onDelete = onTargetDelete,
                    )
                }
            }
            item(key = "run") {
                Box(Modifier.padding(top = WcmSpace.section)) {
                    RunCard(
                        state = state,
                        onModeToggle = onModeToggle,
                        onAutoToggle = onAutoToggle,
                        onStart = onStart,
                        onFull = onFull,
                        onStop = onStop,
                        onRetryFailed = onRetryFailed,
                        onBrowse = onBrowse,
                        onSinglePost = onSinglePost,
                    )
                }
            }
            item(key = "log") {
                Box(Modifier.padding(top = WcmSpace.section)) {
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
    }
}

@Composable
private fun GalleryEntry(count: String, onOpen: () -> Unit) {
    WcmCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                icon = R.drawable.ic_image,
                brand = true,
                description = stringResource(R.string.gallery_open),
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.gallery_card_title),
                    style = MaterialTheme.typography.bodyLarge,
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
            IconTile(icon = R.drawable.ic_folder, brand = false)
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.crawl_dir_title),
                    style = MaterialTheme.typography.bodyLarge,
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
                        shape = WcmShape.pill,
                        color = colorResource(R.color.warn_soft),
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .wcmFocusRing(WcmShape.pill)
                            .clickable { onPickDir() },
                    ) {
                        Text(
                            text = state.dirStatus,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(R.color.warn_text),
                        )
                    }
                }
            }
            WcmOutlinedButton(onClick = onPickDir) {
                Text(stringResource(R.string.crawl_pick_dir))
            }
        }
    }
}

/** 卡内图标托：34dp / 圆角 10dp（`WcmShape.inner`），品牌软底或中性底两种。 */
@Composable
private fun IconTile(icon: Int, brand: Boolean, description: String? = null) {
    Surface(
        shape = WcmShape.inner,
        color = if (brand) colorResource(R.color.brand_soft) else colorResource(R.color.surface_2),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = if (brand) colorResource(R.color.brand_strong) else colorResource(R.color.ink_2),
            modifier = Modifier.padding(9.dp).size(18.dp),
        )
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
                color = colorResource(R.color.ink),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.crawl_target_hint_short),
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
            )
        }
        if (state.targets.isEmpty()) {
            Text(
                text = state.targetsEmptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
        } else {
            Spacer(Modifier.height(4.dp))
            state.targets.forEachIndexed { index, target ->
                if (index > 0) WcmDivider()
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
    val moreFocus = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wcmFocusRing(WcmRowRingShape)
            .wcmFocusNextTo(moreFocus)
            .clickable { menu = true }
            .padding(vertical = WcmSpace.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = target.name,
                style = MaterialTheme.typography.bodyLarge,
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
            IconButton(
                onClick = { menu = true },
                modifier = Modifier.wcmFocusRing(WcmShape.button).focusRequester(moreFocus),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.cd_more),
                    tint = colorResource(R.color.ink_3),
                    modifier = Modifier.size(18.dp),
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
            color = colorResource(R.color.ink),
        )
        Spacer(Modifier.height(6.dp))
        SwitchRow(
            label = state.modeLabel,
            checked = state.modeAlbum,
            enabled = true,
            onToggle = onModeToggle,
        )
        WcmDivider()
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
                modifier = Modifier.fillMaxWidth().height(3.dp),
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
                WcmTextButton(onClick = onRetryFailed) {
                    Text(stringResource(R.string.crawl_retry_action))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.running) {
            WcmButton(
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
            WcmButton(
                onClick = onStart,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.crawl_start))
            }
            Spacer(Modifier.height(8.dp))
            WcmOutlinedButton(
                onClick = onFull,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.crawl_full_all))
            }
        }
        Spacer(Modifier.height(8.dp))
        WcmOutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.browse_entry))
        }
        WcmTextButton(
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
            .wcmFocusRing(WcmRowRingShape)
            // toggleable(role = Switch) 而不是 clickable：整行才是那个"开关"，
            // 读屏要播报「开关 / 已开启」，clickable 只会说成「按钮」。
            // 同时整行只有一个焦点目标，Tab 不会在同一个开关上停两次
            // （之前 clickable + Switch(onCheckedChange=...) 就是停两站）。
            .toggleable(
                value = checked,
                role = Role.Switch,
                enabled = enabled,
                onValueChange = onToggle,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colorResource(R.color.ink),
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            // 行上的 toggleable 已负责切换与语义；开关自己不参与交互，
            // 否则同一个开关既是焦点目标又是点击目标，语义与焦点都会重复
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}
