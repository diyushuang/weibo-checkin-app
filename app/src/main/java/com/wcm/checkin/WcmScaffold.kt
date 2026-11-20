package com.wcm.checkin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * # 页面骨架：一条顶栏，全站共用
 *
 * v3.0 之前每个页面各写一套顶栏：首页 28sp、二级页 22sp、设置页只有标题、
 * 任务页干脆没有标题，左右留白也有 16 / 8 / 16+12 三种。切 Tab 时标题会「跳」，
 * 同一层级的页面看起来不像同一层。
 *
 * 这里把骨架收成一个：
 *
 * ```
 * ┌───────────────────────────────────────┐
 * │  [←]  标题 22sp               [ ][ ]  │  内容高 48dp + 上下 10dp 呼吸
 * │       副标题 12sp                     │
 * ├───────────────────────────────────────┤
 * │  内容区（page_padding_horizontal）    │  首块距顶栏 20dp（WcmSpace.section）
 * ```
 *
 * ## 为什么不用 M3 的 `TopAppBar`
 *
 * `TopAppBar` 固定 64dp 高、标题走 `titleLarge` 且自带折叠滚动行为，与本产品的
 * 「48dp 内容 + 12sp 副标题上下叠放」对不上（硬改要重写 `TopAppBarDefaults` 三个参数，
 * 还不如自己写这 40 行）。**没有用 `Scaffold`** 也是同理：底部导航由 `MainActivity`
 * 统一持有，页面只负责「顶栏 + 内容」这一段。
 *
 * ## 分隔线
 *
 * 故意不加。顶栏与首块之间靠 20dp 留白分组，加一条线会把留白切碎，
 * 反而更像「Old Android」。
 */
@Composable
fun WcmPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg)),
    ) {
        WcmTopBar(title = title, subtitle = subtitle, onBack = onBack, actions = actions)
        content()
    }
}

/**
 * 顶栏。带 [onBack] 时左侧出现返回箭头（二级页），不带时是 Tab 页。
 *
 * 标题与副标题都单行省略：中文标题一换行，顶栏高度就会跳，切页时会看到内容上下抖。
 */
@Composable
fun WcmTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val pad = dimensionResource(R.dimen.page_padding_horizontal)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = pad, vertical = 10.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            val backDesc = stringResource(R.string.cd_back)
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = backDesc,
                    tint = colorResource(R.color.ink_2),
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = colorResource(R.color.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/**
 * 顶栏图标钮：视觉 40dp，可点区 48dp（`IconButton` 自带最小触控）。
 *
 * [description] 传 null 表示纯装饰；需要读屏播报时一定传文案——
 * 之前用文字字符当图标时，好几处忘了挂 contentDescription，读屏用户点上去没有任何反馈。
 */
@Composable
fun WcmTopBarAction(
    icon: Int,
    description: String?,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = colorResource(R.color.ink_2),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 顶栏里的「主行动」图标钮（如首页的「添加账号」）。
 *
 * 用 `brand_soft` 软底而不是 `brand` 实底：v2.0 的规范写「一屏里红色面积要小」，
 * 但首页同时出现「＋ 实底方块 + 立即签到实底按钮」，主行动不再是唯一焦点。
 * 降成软底之后，一屏只剩主按钮一块实底。
 */
@Composable
fun WcmTopBarAccentAction(
    icon: Int,
    description: String?,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = colorResource(R.color.brand_strong),
            modifier = Modifier
                .size(34.dp)
                .background(colorResource(R.color.brand_soft), WcmShape.button)
                .padding(7.dp),
        )
    }
}
