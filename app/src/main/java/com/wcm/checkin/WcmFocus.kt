package com.wcm.checkin

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp

/**
 * # 键盘聚焦环
 *
 * 桌面模式（DeX / 平板接键盘 / 电视盒子）下，用户靠 Tab 与方向键在界面里走。
 * v3.0 之前**没有任何聚焦指示**：焦点走到哪，屏幕上完全看不出来，
 * 只能靠「按回车看看发生了什么」来试 —— 这是桌面模式下最严重的一类可用性缺陷。
 *
 * ## 画法
 *
 * 内容画完之后补一圈 **2dp 品牌色描边**，轮廓**整体外扩 2dp**：
 *
 * ```
 *   ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐   ← 2dp 描边，距组件 2dp
 *   │ ┌───────────────┐ │
 *   │ │   组件内容     │ │     不用 padding：加了 padding 会让布局在聚焦时「跳」一下
 *   │ └───────────────┘ │
 *   └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘
 * ```
 *
 * 用 `shape.createOutline()` 而不是 `drawRoundRect`：全站有 16dp 卡片、10dp 内嵌块、
 * 12dp 按钮、999dp 胶囊四种形状，写死圆角会在胶囊上画成「方角套圆角」。
 *
 * ## 为什么只在 isFocused 时画
 *
 * 触摸不会给组件焦点（Android 的 touch mode 会清掉焦点），所以手指操作时看不见这圈线；
 * 键盘 / 方向键移动焦点时才出现 —— 触控用户不该看到一堆描边。
 *
 * @param shape 必须与组件自身形状一致，否则描边与组件边缘不同心。
 */
@Composable
fun Modifier.wcmFocusRing(shape: Shape = WcmShape.card): Modifier {
    val ring = colorResource(R.color.brand)
    // 聚焦态只是视觉：状态留在修饰符内部，并且只在 draw 阶段读它
    // （读 State 的地方是 draw lambda，所以聚焦切换只触发重绘，不触发重组）
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (!focused) return@drawWithContent
            // 外扩 2dp + 描边 2dp：描边中心落在组件外侧 1dp 处，留出视觉间隙
            val gap = 2.dp.toPx()
            val outline = shape.createOutline(
                Size(size.width + gap * 2, size.height + gap * 2),
                layoutDirection,
                this,
            )
            // Outline 直接画没有公开 API，先转成 Path 再描边（胶囊/圆角都成立）
            val path = Path().apply { addOutline(outline) }
            translate(-gap, -gap) {
                drawPath(path = path, color = ring, style = Stroke(width = 2.dp.toPx()))
            }
        }
}

/** 行内小控件（列表行、开关行、网格格子）用的稍小圆角：大圆角卡片里套 16dp 的环会显得散。 */
val WcmRowRingShape: Shape = RoundedCornerShape(10.dp)
