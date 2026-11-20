package com.wcm.checkin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * # 全站唯一的卡片材质
 *
 * v3.0 之前同一屏里能同时出现三种卡片：`surface + 1dp 描边 + 16dp 圆角`（首页）、
 * `surface + 1dp 描边 + 14dp 圆角`（超话 / 图库）、M3 默认（`surfaceContainerLow` +
 * 12dp 圆角 + 无描边，任务页的 8 处裸 `Card()`）。用户说不出哪里不对，但会觉得
 * 「不是一个东西做的」。
 *
 * 现在的材质规则只有一条：**白面 + 1dp 发丝边 + 0 投影 + 16dp 圆角**。
 * 层次靠留白（`WcmSpace`）而不是靠投影或填充色，所以 `elevation` 恒为 0 ——
 * 投影在深色主题下会变成"发亮的边"，比描边脏得多。
 *
 * ## 为什么把 padding 内建
 *
 * 之前 `Card { Column(Modifier.padding(16.dp)) { … } }` 这种写法在各文件里重复了十几次，
 * 于是出现了 18 / 16 / 14 / 12 / 10 五种内边距，同屏相邻卡片的文字左边线能差 8dp。
 * 现在 padding 由 [WcmCard] 统一给，调用方只写内容。
 *
 * @param padding 只有 Hero 卡需要覆写（[WcmSpace.heroPad]，给进度环多留 4dp）。
 */
@Composable
fun WcmCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = WcmSpace.cardPad,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = colorResource(R.color.surface))
    val border = BorderStroke(1.dp, colorResource(R.color.line))
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = WcmShape.card,
            colors = colors,
            border = border,
            elevation = elevation,
        ) {
            Column(Modifier.padding(padding), content = content)
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = WcmShape.card,
            colors = colors,
            border = border,
            elevation = elevation,
        ) {
            Column(Modifier.padding(padding), content = content)
        }
    }
}

/**
 * 卡外分组标题（表单页与详情页的签名）。
 *
 * 放在卡片**外面**是刻意的：卡内既有「卡片标题」又有「字段」时，两者视觉平级，
 * 用户扫视时找不到「从哪开始填」。标题搬到卡外之后，卡片本身变成纯粹的
 * 「一块可填区域」——这也是用户分辨「这是表单页」的主要线索。
 *
 * 距下一张卡 8dp、距上方内容 20dp（由调用方的 `WcmSpace.section` 给）。
 */
@Composable
fun WcmSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
        style = WcmType.sectionLabel,
        color = colorResource(R.color.ink_2),
    )
}

/**
 * 卡内分隔线。
 *
 * 用 `line_soft` 而不是 `line`：卡片外框已经是 `line`，内部再用同样重的线，
 * 一条卡里就出现了两级一样重的边界，读起来像表格。
 */
@Composable
fun WcmDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colorResource(R.color.line_soft)),
    )
}
