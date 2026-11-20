package com.wcm.checkin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 首页 Cookie 有效期卡：逐账号展示登录态还剩几天，剩余 ≤2 天 / 已过期 / 已判失效整行标红。
 *
 * 寿命只从真实登录时刻或微博真正换发关键凭据起算，同步与探活只更新「登录于 / 同步于」，
 * 不再按同步时间续命 —— 早先按同步时间估算，导致长期不登录的账号一直显示「还剩 7 天」。
 *
 * v3.0：卡片材质走 [WcmCard]、状态胶囊走 [WcmStatusPill]。
 * `PillTone` 仍是全站唯一的四态语义（OK / WARN / DANGER / NEUTRAL），
 * 账号行、图库、超话页共用它，不再各造一套颜色映射。
 */
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
    WcmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.sync_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = colorResource(R.color.ink),
                modifier = Modifier.weight(1f),
            )
            WcmStatusPill(text = state.pillText, tone = state.pillTone)
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
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            state.rows.forEachIndexed { index, row ->
                CookieRow(row)
                if (index < state.rows.lastIndex) WcmDivider()
            }
        }
        if (state.showConfig) {
            Text(
                text = stringResource(R.string.sync_card_config),
                style = MaterialTheme.typography.labelLarge,
                color = colorResource(R.color.brand_strong),
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clickable { onConfig() },
            )
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
            .padding(vertical = 10.dp),
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
        WcmStatusPill(text = row.pillText, tone = row.pillTone)
    }
}
