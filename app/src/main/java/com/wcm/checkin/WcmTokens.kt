package com.wcm.checkin

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * # UI 系统 v3.0 令牌（间距 / 圆角 / 字体）
 *
 * 规范见 `docs/design/ui-system-v3.0.md`，可视化对照见 `docs/design/preview-v3.0.html`。
 *
 * ## 为什么要有这个文件
 *
 * v2.0 把令牌写在 `res/values/dimens.xml` 与 `type.xml` 里，但界面迁到 Compose 之后：
 * 圆角在各文件里写成 `RoundedCornerShape(18.dp)` / `14.dp` / `16.dp` 三种，
 * 间距散落成 18/16/14/12/10 五档，字号则**根本没接上** —— `WcmTheme` 只传了
 * `colorScheme`，全站跑的是 Material 3 默认刻度（标题 28sp）。
 *
 * 这里做两件事：
 *
 * 1. **把刻度收窄并集中**（间距 3 档、圆角 3 档 + pill、字号 8 档），
 *    代码里不再出现裸的 14/18dp 与 `RoundedCornerShape(14.dp)`；
 * 2. **覆盖 M3 的角色值**（[WcmTypography] / [WcmShapes]），
 *    这样已有调用点（`MaterialTheme.typography.titleMedium` 之类）**一行不改也自动生效**。
 *
 * ## 改字号时注意
 *
 * 中文只有 400 / 500 两档字重可辨，所以 `FontWeight` 只用 `Normal` / `Medium`；
 * 字距只收紧不放开（标题 -0.4sp，正文 0）；数字一律开 `tnum`，刷新时不会左右跳。
 */

/** 三档语义间距。页面里不要再拼裸 `dp`。 */
object WcmSpace {
    /** 区块之间（标题组 ↔ 内容组 ↔ 下一区块） */
    val section = 20.dp

    /** 同级卡片之间 */
    val cardGap = 12.dp

    /** 卡片内边距（唯一值） */
    val cardPad = 16.dp

    /** Hero 卡内边距（比普通卡多 4dp，给进度环留呼吸） */
    val heroPad = 20.dp

    /** 列表行垂直内边距 */
    val rowV = 12.dp

    /** 列表页的行（比首页更紧，一屏多读一行） */
    val rowVTight = 10.dp

    /** 内容区顶部留白（顶栏已自带 10dp 呼吸） */
    val pageTop = 8.dp

    /** 滚动末端留白，替代「贴边结束」 */
    val pageBottom = 24.dp
}

/** 三档圆角 + pill。4 / 6 / 8 / 12 / 14 / 18 一律不再新增。 */
object WcmShape {
    /** 所有卡片（含 Hero） */
    val card = RoundedCornerShape(16.dp)

    /** 卡内嵌块：图标托、终端块、缩略图、输入框 */
    val inner = RoundedCornerShape(10.dp)

    /** 徽章（等级 Lv.N） */
    val chip = RoundedCornerShape(8.dp)

    /** 按钮 */
    val button = RoundedCornerShape(12.dp)

    /** 状态胶囊 */
    val pill = RoundedCornerShape(999.dp)
}

/**
 * 覆盖 M3 默认 shape。
 *
 * `Card` 的默认形状取 `shapes.medium`，所以设成 16dp 之后，**裸 `Card()` 也会自动归一**——
 * 这是防回归的安全网：以后谁再写 `Card(modifier = …)` 不带 shape，也不会冒出 12dp 圆角。
 */
val WcmShapes = Shapes(
    extraSmall = WcmShape.chip,
    small = WcmShape.inner,
    medium = WcmShape.card,
    large = WcmShape.card,
    extraLarge = WcmShape.card,
)

/**
 * 产品字号刻度：22 / 20 / 15 / 14 / 12 / 11。
 *
 * 覆盖 M3 角色而不是改调用点，原因是全站有 240+ 处 `MaterialTheme.typography.xxx`，
 * 逐个改字号既容易漏，也会让「同一角色的语义」在不同页面漂移。
 *
 * 与 v2.0 文档的差异：页面标题取 **22sp**（文档写 20sp）。20sp 与卡片标题 15sp 只差
 * 1.33 倍，5 个 Tab 的顶栏会显得「没有标题」；22sp 是 1.47 倍，层级明确，且比 28sp
 * 省下两行高度。若想更保守，只改本文件的 `headlineMedium` 一处即可回落 20sp。
 */
val WcmTypography = Typography(
    // 页面标题：Tab 页与二级页共用同一档，切页时标题不跳变
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.4).sp,
    ),
    // 指标数字（概览条、三列指标）
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = "tnum",
    ),
    // 弹窗标题、详情页主实体名
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    // 卡片标题
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    // 小节标题
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    // 行标题（账号名、超话名、开关名）
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    // 正文
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    // 辅助说明、UID、时间
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    // 按钮文案
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // chip 文字
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // pill、角标、键名
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
)

/**
 * M3 角色覆盖不到的三档，按需直接引用 `WcmType.xxx`。
 *
 * 为什么不塞进 [WcmTypography]：`Typography` 只有 15 个固定角色，
 * 硬把「指标数字」塞进 `displaySmall` 之类的角色，语义会越来越乱。
 */
object WcmType {
    /** 进度环中心数字 */
    val metricLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum",
    )

    /** 卡内次级数字（待签数、剩余天数） */
    val metric = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontFeatureSettings = "tnum",
    )

    /** 卡外分组标题（表单页 / 详情页签名） */
    val sectionLabel = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.4.sp,
    )

    /** 终端日志 */
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 18.sp,
    )
}
