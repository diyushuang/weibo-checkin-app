package com.wcm.checkin

import android.widget.Toast
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 文案库子页：手动录入 + 批量粘贴导入。
 *
 * 0.14.0 起按 [kind] 区分三套库。评论与转发库的单条上限是 140 字（平台硬限），
 * 所以在录入、导入、列表展示三处都按各自上限处理；发帖库沿用配置里的正文长度上限。
 *
 * 关于「从百度输入法圈子获取」：圈子没有公开接口，这里**不做任何抓取**，
 * 只提供「用户自己复制 → 粘贴 → 拆分入库」。界面上明写这一点，
 * 免得用户以为应用会去联网取文案。
 */
@Composable
fun EngageTextScreen(viewModel: EngageViewModel, kind: TextKind, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var newBody by remember { mutableStateOf("") }
    var importRaw by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(EngageTextPool.ImportMode.BLANK_LINE) }
    var separator by remember { mutableStateOf("###") }
    var lastResult by remember { mutableStateOf("") }

    // 文案页自己有「保存」按钮，提示要在这页显示（否则只在任务页弹一次残影）
    LaunchedEffect(state.message) {
        if (state.message.isNotEmpty()) {
            Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    val limit = viewModel.maxLenOf(kind)
    val list = state.textsOf(kind)

    val pagePad = dimensionResource(R.dimen.page_padding_horizontal)
    WcmPage(
        title = stringResource(R.string.engage_texts_of_kind_title, kind.label),
        subtitle = stringResource(R.string.engage_texts_count, state.availableTexts(kind), list.size),
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

            // ---- 取用方式 ----
            WcmCard {
                Column {
                    Text(stringResource(R.string.engage_texts_pick_title), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextPickMode.entries.forEach { m ->
                            FilterChip(
                                selected = state.config.textModeOf(kind) == m,
                                onClick = { viewModel.setPickMode(kind, m) },
                                label = { Text(m.label) },
                            )
                        }
                    }
                    Text(
                        state.config.textModeOf(kind).hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.engage_texts_limit_note, limit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 手动新增 ----
            WcmCard {
                Column {
                    Text(stringResource(R.string.engage_texts_add), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newBody,
                        onValueChange = { newBody = it.take(limit) },
                        label = { Text(stringResource(R.string.engage_texts_body)) },
                        supportingText = { Text("${newBody.length} / $limit") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                viewModel.addText(kind, newBody)
                                newBody = ""
                            },
                            enabled = newBody.isNotBlank(),
                        ) { Text(stringResource(R.string.engage_texts_save)) }
                    }
                }
            }

            // ---- 批量导入 ----
            WcmCard {
                Column {
                    Text(stringResource(R.string.engage_texts_import), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.engage_texts_import_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == EngageTextPool.ImportMode.BLANK_LINE,
                            onClick = { mode = EngageTextPool.ImportMode.BLANK_LINE },
                            label = { Text(stringResource(R.string.engage_texts_import_mode_blank)) },
                        )
                        FilterChip(
                            selected = mode == EngageTextPool.ImportMode.SINGLE_LINE,
                            onClick = { mode = EngageTextPool.ImportMode.SINGLE_LINE },
                            label = { Text(stringResource(R.string.engage_texts_import_mode_line)) },
                        )
                        FilterChip(
                            selected = mode == EngageTextPool.ImportMode.CUSTOM,
                            onClick = { mode = EngageTextPool.ImportMode.CUSTOM },
                            label = { Text(stringResource(R.string.engage_texts_import_mode_custom)) },
                        )
                    }
                    if (mode == EngageTextPool.ImportMode.CUSTOM) {
                        OutlinedTextField(
                            value = separator,
                            onValueChange = { separator = it.take(8) },
                            label = { Text(stringResource(R.string.engage_texts_import_separator)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = importRaw,
                        onValueChange = { importRaw = it },
                        label = { Text(stringResource(R.string.engage_texts_paste_here)) },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            val text = clipboard.getText()?.text.orEmpty()
                            if (text.isNotEmpty()) importRaw = text
                        }) { Text(stringResource(R.string.engage_texts_paste)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                lastResult = viewModel.importTexts(kind, importRaw, mode, separator)
                                importRaw = ""
                            },
                            enabled = importRaw.isNotBlank(),
                        ) { Text(stringResource(R.string.engage_texts_import_do)) }
                    }
                    if (lastResult.isNotEmpty()) {
                        Text(lastResult, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---- 从发帖库补齐（评论 / 转发专用）----
            if (kind != TextKind.POST) {
                WcmCard {
                    Column {
                        Text(
                            stringResource(R.string.engage_texts_fill_from_post),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.engage_texts_fill_from_post_hint, EngageTextPool.COMMENT_LIMIT),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            lastResult = viewModel.importFromPostLibrary(kind)
                            Toast.makeText(context, lastResult, Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.engage_texts_fill_do)) }
                    }
                }
            }

            // ---- 列表 ----
            if (list.isEmpty()) {
                WcmEmptyState(
                    icon = R.drawable.ic_nav_tasks,
                    title = stringResource(R.string.engage_texts_empty),
                )
            }
            list.forEach { text ->
                WcmCard {
                    Column {
                        Text(
                            text = text.body,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (text.deleted) 1 else 4,
                            overflow = TextOverflow.Ellipsis,
                            color = if (text.deleted) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.engage_texts_used, text.usedCount) +
                                    " · ${text.body.length} / $limit",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            if (!text.deleted) {
                                WcmOutlinedButton(onClick = {
                                    viewModel.deleteText(kind, text.id)
                                    Toast.makeText(context, R.string.engage_texts_deleted, Toast.LENGTH_SHORT).show()
                                }) { Text(stringResource(R.string.engage_texts_delete)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
