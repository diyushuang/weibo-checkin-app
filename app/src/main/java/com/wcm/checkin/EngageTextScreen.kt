package com.wcm.checkin

import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

/**
 * 文案库子页：手动录入 + 批量粘贴导入。
 *
 * 关于「从百度输入法圈子获取」：圈子没有公开接口，这里**不做任何抓取**，
 * 只提供「用户自己复制 → 粘贴 → 拆分入库」。界面上明写这一点，
 * 免得用户以为应用会去联网取文案。
 */
@Composable
fun EngageTextScreen(viewModel: EngageViewModel, onBack: () -> Unit) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(stringResource(R.string.engage_texts_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.engage_texts_count, state.availableTexts, state.texts.size),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---- 手动新增 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.engage_texts_add), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newBody,
                    onValueChange = { newBody = it },
                    label = { Text("文案正文") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            viewModel.addText(newBody)
                            newBody = ""
                        },
                        enabled = newBody.isNotBlank(),
                    ) { Text("保存") }
                }
            }
        }

        // ---- 批量导入 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
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
                    label = { Text("粘贴文案") },
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
                            lastResult = viewModel.importTexts(importRaw, mode, separator)
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

        // ---- 列表 ----
        if (state.texts.isEmpty()) {
            Text(
                stringResource(R.string.engage_texts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.texts.forEach { text ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
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
                            stringResource(R.string.engage_texts_used, text.usedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        if (!text.deleted) {
                            OutlinedButton(onClick = {
                                viewModel.deleteText(text.id)
                                Toast.makeText(context, "已停用该文案", Toast.LENGTH_SHORT).show()
                            }) { Text(stringResource(R.string.engage_texts_delete)) }
                        }
                    }
                }
            }
        }
    }
}
