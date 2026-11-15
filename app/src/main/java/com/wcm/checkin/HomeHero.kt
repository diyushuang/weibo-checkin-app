package com.wcm.checkin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp

/** 首页 Hero 卡的渲染状态，由 `HomeViewModel` 每次状态变化时整体重建。 */
data class HomeHeroUiState(
    val ringValue: String = "",
    val pendingValue: String = "",
    val pillAccounts: String = "",
    val pillPanel: String = "",
    val summary: String = "",
    val progress: Float = 0f,
)

/** 首页 Hero：进度环 + 待签数 + 两个 pill + 一句话结论。 */
@Composable
fun HomeHeroCard(state: HomeHeroUiState, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(R.color.surface)),
        border = BorderStroke(1.dp, colorResource(R.color.line)),
        // 点击卡片默认带 elevation，会把白底叠成品牌色调；与 XML 白卡一致用 0
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(88.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxSize(),
                        color = colorResource(R.color.brand),
                        trackColor = colorResource(R.color.surface_3),
                        strokeWidth = 8.dp,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.ringValue,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colorResource(R.color.ink),
                        )
                        Text(
                            text = stringResource(R.string.home_ring_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(R.color.ink_2),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_pending_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(R.color.ink_2),
                    )
                    Text(
                        text = state.pendingValue,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorResource(R.color.ink),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        HeroPill(state.pillAccounts, neutral = true)
                        HeroPill(state.pillPanel, neutral = false)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.summary,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
            )
        }
    }
}

@Composable
private fun HeroPill(text: String, neutral: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (neutral) colorResource(R.color.surface_3) else Color.Transparent,
        border = if (neutral) null else BorderStroke(1.dp, colorResource(R.color.line_2)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(R.color.ink_2),
        )
    }
}
