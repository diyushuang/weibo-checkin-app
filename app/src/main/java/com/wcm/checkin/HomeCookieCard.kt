package com.wcm.checkin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class PillTone { OK, WARN, DANGER, NEUTRAL }

data class CookieRowUi(
    val name: String,
    val danger: Boolean,
    val expiryText: String,
    val pillText: String,
    val pillTone: PillTone,
)

data class CookieCardUi(
    val pillText: String = "",
    val pillTone: PillTone = PillTone.NEUTRAL,
    val hint: String = "",
    val showConfig: Boolean = false,
    val emptyText: String = "",
    val rows: List<CookieRowUi> = emptyList(),
)

/** 首页 Cookie 有效期卡：卡头 pill + 说明 + 逐账号行 + 配置入口。 */
@Composable
fun CookieCard(state: CookieCardUi, onConfig: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(R.color.surface)),
        border = BorderStroke(1.dp, colorResource(R.color.line)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.sync_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorResource(R.color.ink),
                    modifier = Modifier.weight(1f),
                )
                Pill(state.pillText, state.pillTone)
            }
            Text(
                text = state.hint,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
                modifier = Modifier.padding(top = 4.dp),
            )
            if (state.rows.isEmpty()) {
                Text(
                    text = state.emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(R.color.ink_3),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            } else {
                state.rows.forEachIndexed { index, row ->
                    CookieRow(row)
                    if (index < state.rows.lastIndex) RowDivider()
                }
            }
            if (state.showConfig) {
                Text(
                    text = stringResource(R.string.sync_card_config),
                    style = MaterialTheme.typography.labelLarge,
                    color = colorResource(R.color.brand_strong),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable { onConfig() },
                )
            }
        }
    }
}

@Composable
private fun CookieRow(row: CookieRowUi) {
    val primary = if (row.danger) colorResource(R.color.danger_text)
    else colorResource(R.color.ink)
    val secondary = if (row.danger) colorResource(R.color.danger_text)
    else colorResource(R.color.ink_3)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.expiryText,
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Pill(row.pillText, row.pillTone)
    }
}

@Composable
private fun Pill(text: String, tone: PillTone) {
    val (bg, fg) = when (tone) {
        PillTone.OK -> colorResource(R.color.ok_soft) to colorResource(R.color.ok_text)
        PillTone.WARN -> colorResource(R.color.warn_soft) to colorResource(R.color.warn_text)
        PillTone.DANGER -> colorResource(R.color.danger_soft) to colorResource(R.color.danger_text)
        PillTone.NEUTRAL -> colorResource(R.color.surface_2) to colorResource(R.color.ink_2)
    }
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colorResource(R.color.line)),
    )
}