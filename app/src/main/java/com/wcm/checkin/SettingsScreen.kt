package com.wcm.checkin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 对齐 values/colors.xml 的 Material3 主题（与 XML 页面保持同一套品牌色）。 */
@Composable
fun WcmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colorResource(R.color.brand),
            onPrimary = colorResource(R.color.brand_on),
            surfaceTint = colorResource(R.color.surface),
            secondary = colorResource(R.color.brand),
            onSecondary = colorResource(R.color.brand_on),
            secondaryContainer = colorResource(R.color.surface_2),
            onSecondaryContainer = colorResource(R.color.ink),
            background = colorResource(R.color.bg),
            surface = colorResource(R.color.surface),
            surfaceVariant = colorResource(R.color.surface_2),
            surfaceContainer = colorResource(R.color.surface),
            surfaceContainerHigh = colorResource(R.color.surface),
            surfaceContainerHighest = colorResource(R.color.surface),
            surfaceContainerLow = colorResource(R.color.surface),
            surfaceContainerLowest = colorResource(R.color.surface),
            onSurface = colorResource(R.color.ink),
            onSurfaceVariant = colorResource(R.color.ink_2),
            outline = colorResource(R.color.line_2),
            outlineVariant = colorResource(R.color.line),
        )
    } else {
        lightColorScheme(
            primary = colorResource(R.color.brand),
            onPrimary = colorResource(R.color.brand_on),
            surfaceTint = colorResource(R.color.surface),
            secondary = colorResource(R.color.brand),
            onSecondary = colorResource(R.color.brand_on),
            secondaryContainer = colorResource(R.color.surface_2),
            onSecondaryContainer = colorResource(R.color.ink),
            background = colorResource(R.color.bg),
            surface = colorResource(R.color.surface),
            surfaceVariant = colorResource(R.color.surface_2),
            surfaceContainer = colorResource(R.color.surface),
            surfaceContainerHigh = colorResource(R.color.surface),
            surfaceContainerHighest = colorResource(R.color.surface),
            surfaceContainerLow = colorResource(R.color.surface),
            surfaceContainerLowest = colorResource(R.color.surface),
            onSurface = colorResource(R.color.ink),
            onSurfaceVariant = colorResource(R.color.ink_2),
            outline = colorResource(R.color.line_2),
            outlineVariant = colorResource(R.color.line),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.page_settings_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.page_settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PanelCard(state, onUrlChange, onTokenChange, onSave)

        WcmCard {
            SectionHead(R.string.settings_behavior_title)
            SwitchRow(
                title = stringResource(R.string.auto_on_open),
                desc = stringResource(R.string.settings_auto_on_open_desc),
                checked = state.autoOnOpen,
                onCheckedChange = onAutoOnOpenChange,
            )
            RowDivider()
            SwitchRow(
                title = stringResource(R.string.settings_live_gif),
                desc = stringResource(R.string.settings_live_gif_desc),
                checked = state.crawlGif,
                onCheckedChange = onCrawlGifChange,
            )
            RowDivider()
            SwitchRow(
                title = stringResource(R.string.settings_auto_relogin),
                desc = stringResource(R.string.settings_auto_relogin_desc),
                note = stringResource(R.string.settings_auto_relogin_note),
                checked = state.autoRelogin,
                onCheckedChange = onAutoReloginChange,
            )
        }

        WcmCard {
            SectionHead(R.string.settings_about_title)
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.about,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_appearance_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
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
        SectionHead(R.string.settings_panel_title, R.string.settings_panel_tag)
        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.panelToken,
            onValueChange = onTokenChange,
            label = { Text(stringResource(R.string.settings_panel_token_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_save))
        }
        Text(
            text = stringResource(R.string.settings_panel_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun WcmCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionHead(titleRes: Int, tagRes: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (tagRes != null) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = stringResource(tagRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
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
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
