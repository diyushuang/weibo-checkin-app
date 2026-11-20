package com.wcm.checkin

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource

/**
 * # Compose 主题（v2.0 设计令牌 → Material 3 色彩角色）
 *
 * **令牌只有一个来源**：`res/values/colors.xml`（浅色）与 `res/values-night/colors.xml`
 * （深色）。本文件不定义任何色值，只做「令牌名 → M3 角色」的映射，所以两边的色值
 * 都不需要来这里改。XML 页面（登录 WebView）走的是 `values/themes.xml` 的 `Theme.Wcm`，
 * 它做的是同一件事的另一半 —— **改色值只改 colors.xml，两边会自动一起变**；
 * 但新增一个颜色角色时，`themes.xml` 与本文件都要补，漏一处就会出现
 * 「Compose 页是新色、登录页是旧色」。
 *
 * ## 为什么构造两套 ColorScheme 而字段一模一样
 *
 * 因为 `colorResource` 会按当前 configuration 取值：深色模式下它读到的就是
 * `values-night/colors.xml` 的深色令牌，函数体本身不需要分支。但仍然要分别用
 * `darkColorScheme` / `lightColorScheme` 两个 builder，原因是**这里没有显式覆盖的
 * M3 角色**（`inverseSurface` / `inverseOnSurface` / `scrim` / `tertiary` 等）需要
 * 各自的浅深默认值，写成一个 builder 会让深色页里那几个角色拿到浅色默认值
 * （表现为「深色下弹窗遮罩发白」这类局部异常）。
 *
 * ## 与 XML 页面的关系
 *
 * `WcmApp.onCreate` 里还调了 `DynamicColors.applyToActivitiesIfAvailable`，那只对
 * 残留的 XML 页面（登录 WebView）生效；Compose 页面一律走本主题，**不跟随系统取色** ——
 * 品牌色是产品的识别部分，跟随取色会让四档宽度下的对比度不可控。
 *
 * ## `error` 系列为什么现在映射了（v3.0）
 *
 * 早先这里**故意不映射** `error` / `errorContainer`：业务页面出错时直接取
 * `R.color.danger*`，而 M3 默认红是 `#B3261E`、品牌语义红是 `#B42318`，两套红同时存在。
 * 当时的结论是「不要在 Compose 里写 `colorScheme.error`」。
 *
 * 问题在于**组件自己会写**：`OutlinedTextField(isError = true)` 的边框、标签、错误文案
 * 取的正是 `colorScheme.error`。设置页校验面板地址失败时，那一行是 M3 红，
 * 而旁边 Cookie 卡片的失效行是品牌红 —— 同一个 App 里两个红。
 *
 * 所以 v3.0 把四个 error 角色映射到 `danger*` 令牌，并清掉了页面里 19 处
 * 直接写 `colorScheme.error` 的调用点（统一改成 `R.color.danger_text`）。
 * 现在两种写法得到同一个颜色，M3 组件与业务代码也不会再分叉。
 *
 * @param darkTheme 仅供预览/测试强制指定；运行时跟随系统。
 */
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
            // 与业务侧 `R.color.danger*` 同源：M3 组件（输入框校验态等）才不会出现另一个红
            error = colorResource(R.color.danger),
            onError = colorResource(R.color.brand_on),
            errorContainer = colorResource(R.color.danger_soft),
            onErrorContainer = colorResource(R.color.danger_text),
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
            // 与业务侧 `R.color.danger*` 同源：M3 组件（输入框校验态等）才不会出现另一个红
            error = colorResource(R.color.danger),
            onError = colorResource(R.color.brand_on),
            errorContainer = colorResource(R.color.danger_soft),
            onErrorContainer = colorResource(R.color.danger_text),
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        // v3.0：字号与圆角在这里一次接上（令牌定义在 WcmTokens.kt）。
        // 之前只传了 colorScheme，全站跑的是 M3 默认刻度（标题 28sp），
        // 每个 Text 都在用「默认值」而不是「产品刻度」。
        typography = WcmTypography,
        shapes = WcmShapes,
        content = content,
    )
}
