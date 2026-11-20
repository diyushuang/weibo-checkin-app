package com.wcm.checkin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 设置页：面板地址与令牌、打开即签到、GIF 转换、自动重登总开关。
 *
 * 纯渲染：状态来自 `SettingsViewModel`，每个控件把改动直接发回 ViewModel 落盘
 * （开关是即时生效的，没有「保存」这一步）；只有面板地址与令牌需要显式保存，
 * 保存失败的文案由 ViewModel 回写到 `SettingsUiState` 里，本页只负责显示。
 *
 * ## 表单页的签名（v3.0）
 *
 * 分组标题 **放在卡片外面**（[WcmSectionLabel]）。原来标题写在卡内，
 * 与卡内的字段平级，用户扫视时找不到「从哪开始填」；搬到卡外之后，
 * 卡片本身变成纯粹的「一块可填区域」——这也是用户分辨「这是表单页」的主要线索。
 *
 * 主题不在这里 —— `WcmTheme` 已抽到同包的 `Theme.kt`。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onAutoOnOpenChange: (Boolean) -> Unit,
    onCrawlGifChange: (Boolean) -> Unit,
    onAutoReloginChange: (Boolean) -> Unit,
) {
    val pagePad = dimensionResource(R.dimen.page_padding_horizontal)
    WcmPage(
        title = stringResource(R.string.page_settings_title),
        subtitle = stringResource(R.string.page_settings_subtitle),
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
        ) {
            Spacer(Modifier.height(WcmSpace.section))
            // 分组标题在卡外；「可选」这类补充说明跟在标题行右侧，不占卡片首行
            Row(verticalAlignment = Alignment.CenterVertically) {
                WcmSectionLabel(
                    text = stringResource(R.string.settings_panel_title),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.settings_panel_tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorResource(R.color.ink_3),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            PanelCard(state, onUrlChange, onTokenChange, onSave)

            Spacer(Modifier.height(WcmSpace.section))
            WcmSectionLabel(stringResource(R.string.settings_behavior_title))
            WcmCard {
                SwitchRow(
                    title = stringResource(R.string.auto_on_open),
                    desc = stringResource(R.string.settings_auto_on_open_desc),
                    checked = state.autoOnOpen,
                    onCheckedChange = onAutoOnOpenChange,
                )
                WcmDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_live_gif),
                    desc = stringResource(R.string.settings_live_gif_desc),
                    checked = state.crawlGif,
                    onCheckedChange = onCrawlGifChange,
                )
                WcmDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_auto_relogin),
                    desc = stringResource(R.string.settings_auto_relogin_desc),
                    note = stringResource(R.string.settings_auto_relogin_note),
                    checked = state.autoRelogin,
                    onCheckedChange = onAutoReloginChange,
                )
            }

            Spacer(Modifier.height(WcmSpace.section))
            WcmSectionLabel(stringResource(R.string.settings_about_title))
            WcmCard {
                Text(
                    text = state.about,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_2),
                )
                Text(
                    text = stringResource(R.string.settings_appearance_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(WcmSpace.cardGap))
        }
    }
}

@Composable
private fun PanelCard(
    state: SettingsUiState,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    WcmCard {
        val error = state.urlError
        OutlinedTextField(
            value = state.panelUrl,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.settings_panel_url_hint)) },
            singleLine = true,
            isError = error != null,
            supportingText = if (error != null) {
                { Text(error) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(WcmSpace.cardGap))
        OutlinedTextField(
            value = state.panelToken,
            onValueChange = onTokenChange,
            label = { Text(stringResource(R.string.settings_panel_token_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(WcmSpace.cardGap))
        // 保存：独占一行、紧跟字段，不贴底也不浮动
        WcmButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_save))
        }
        Text(
            text = stringResource(R.string.settings_panel_note),
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(R.color.ink_3),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    note: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wcmFocusRing(WcmRowRingShape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colorResource(R.color.ink),
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
                modifier = Modifier.padding(top = 1.dp),
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
