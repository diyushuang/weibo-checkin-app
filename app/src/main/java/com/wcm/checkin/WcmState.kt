package com.wcm.checkin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * # 状态与空态组件
 *
 * v3.0 之前「没有数据」这一件事有三种写法：首页账号空态是一行灰字（没有行动入口）、
 * 超话空态是卡内文字 + 按钮、图库空态是居中图标圆托 + 文字 + 按钮。
 * 同一个语义三种视觉，用户每换一页都要重新判断「这是不是坏了」。
 *
 * 这里统一成一套：**圆托图标 + 一句话结论 + 一句下一步 + 一个有名字的动作**。
 * 位置也统一成「内容区居中」，不再卡里卡外不一致。
 */

/**
 * 状态胶囊。`PillTone` 定义在 `HomeCookieCard.kt`，四个页面共用同一套语义，
 * 所以这里不新造枚举——多一套枚举就会出现「同一个绿色有两种取法」。
 */
@Composable
fun WcmStatusPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
) {
    // 空文案不画：胶囊只剩一个带色的空壳，看起来像"渲染坏了"。
    // 卡头 pill 的来源是 ViewModel 拼出来的状态串，某些分支（未配置面板、加载中）会是空串。
    if (text.isBlank()) return
    val (bg, fg) = when (tone) {
        PillTone.OK -> colorResource(R.color.ok_soft) to colorResource(R.color.ok_text)
        PillTone.WARN -> colorResource(R.color.warn_soft) to colorResource(R.color.warn_text)
        PillTone.DANGER -> colorResource(R.color.danger_soft) to colorResource(R.color.danger_text)
        PillTone.NEUTRAL -> colorResource(R.color.surface_2) to colorResource(R.color.ink_2)
    }
    Surface(shape = WcmShape.pill, color = bg, modifier = modifier) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

/**
 * 空态。
 *
 * @param icon 圆托里的图标；装饰用，不参与读屏（语义由 [title] 承担）。
 * @param title 一句话结论（「还没有账号」），不要写成「暂无数据」。
 * @param message 可空的补充说明（「先到首页添加账号」）。
 * @param actionText 有名字的动作；传 null 时不显示按钮，但**不要**在「空态 + 可行动」时省略。
 * @param compact 卡片内的空态（不是整页空态）用紧凑版：图标托小一圈、上下留白 40→20。
 *   整页空态保持 40dp，卡片内的用紧凑版，否则一张卡里能塞进半个屏的留白。
 * @param primaryAction 动作按钮是否是品牌实底。**卡片内要传 false**（改用描边按钮）：
 *   一屏只允许一块品牌实底，首页「立即签到」已经占了那个位置，
 *   空态里再放一块实底就成了两个同等重量的主行动。
 */
@Composable
fun WcmEmptyState(
    icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    compact: Boolean = false,
    primaryAction: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 24.dp,
                vertical = if (compact) 20.dp else 40.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = colorResource(R.color.brand_soft)) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = colorResource(R.color.brand_strong),
                modifier = Modifier
                    .padding(if (compact) 13.dp else 18.dp)
                    .size(if (compact) 20.dp else 26.dp),
            )
        }
        Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = colorResource(R.color.ink),
            textAlign = TextAlign.Center,
        )
        if (!message.isNullOrEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.ink_3),
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
            if (primaryAction) {
                WcmButton(onClick = onAction) { Text(actionText) }
            } else {
                WcmOutlinedButton(onClick = onAction) { Text(actionText) }
            }
        }
    }
}

/**
 * 空态（文案自带换行的重载）。
 *
 * 本工程的空态文案有两段式的约定：`结论\n下一步`（如「还没有账号\n先到首页添加账号」）。
 * 直接整段当标题渲染，两行会用同样的 15sp/500，第二行读起来像第二句结论；
 * 这里统一拆成「标题 + 12sp 辅助说明」，页面不用各写一遍 split。
 */
@Composable
fun WcmEmptyState(
    icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    compact: Boolean = false,
    primaryAction: Boolean = true,
) {
    val lines = text.split("\n", limit = 2)
    WcmEmptyState(
        icon = icon,
        title = lines[0],
        modifier = modifier,
        message = lines.getOrNull(1),
        actionText = actionText,
        onAction = onAction,
        compact = compact,
        primaryAction = primaryAction,
    )
}

/** 概览条的一格。 */
data class WcmMetric(val label: String, val value: String, val highlight: Boolean = false)

/**
 * 概览条（列表页签名）。
 *
 * 与「一张带标题的卡片 + 三列数字」的区别：**没有卡片标题**。列表页的顶部要回答
 * 「现在什么状态」，多一个「概览」标题等于多一行视觉噪音；列间用 1dp 竖线分组，
 * 数字 20sp 等宽，一眼可扫。
 */
@Composable
fun WcmMetricStrip(
    metrics: List<WcmMetric>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colorResource(R.color.surface), WcmShape.card)
            .border(1.dp, colorResource(R.color.line), WcmShape.card),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        metrics.forEachIndexed { index, metric ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(colorResource(R.color.line)),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = metric.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorResource(R.color.ink_3),
                )
                Text(
                    text = metric.value,
                    style = WcmType.metric,
                    color = if (metric.highlight) colorResource(R.color.warn_text)
                    else colorResource(R.color.ink),
                    // 三列各占 1/3，四位数在 360dp 上会折行把整条撑高
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
