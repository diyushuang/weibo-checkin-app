package com.wcm.checkin

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * # 按钮
 *
 * 为什么要有这两个包装：M3 的 `Button` / `OutlinedButton` **不读 `MaterialTheme.shapes`**，
 * 圆角写死在 `ButtonDefaults.shape`（20dp）。所以把 `WcmShape.button = 12dp` 放进
 * `WcmShapes` 是没用的 —— 全站按钮仍然是 20dp，与 16dp 的卡片、10dp 的输入框不一个调子。
 *
 * 包装只补一个 `shape`，其余参数原样透传，调用点把 `Button(` 换成 `WcmButton(` 即可。
 *
 * 12dp 而不是 pill(999)：44dp 高的胶囊按钮在「设置 / 图库 / 任务」这类工具页里
 * 会把视线吸走；12dp 与卡片同族，按钮仍然可辨（实底 + 字重），但不抢。
 *
 * 描边按钮的 `border` 不暴露：M3 1.2 里 `ButtonDefaults.outlinedButtonBorder` 是个常量属性，
 * 禁用态的描边由组件内部处理，外面透传反而会把禁用态的颜色锁死。
 */

@Composable
fun WcmButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = WcmShape.button,
        colors = colors,
        content = content,
    )
}

@Composable
fun WcmOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = WcmShape.button,
        colors = colors,
        content = content,
    )
}
