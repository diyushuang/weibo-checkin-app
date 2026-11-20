package com.wcm.checkin

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * # 焦点遍历顺序
 *
 * 桌面模式（DeX / 平板接键盘 / 电视盒子）下，焦点靠 Tab（1D）与方向键（2D）移动。
 * 默认规则是「按布局顺序 + 几何就近」，在本工程里有两个具体问题：
 *
 * 1. **复合控件会重复停靠**：`SwitchRow` 里「行」可点、`Switch` 也可点，
 *    Tab 会在同一个开关上停两次；用户以为有两个开关。
 * 2. **行与行内按钮的顺序靠几何猜**：账号行（可点）里有 ⋮（可点），
 *    默认按布局最近猜，一旦行内换布局（比如把 ⋮ 挪到第二行）顺序就跟着变。
 *
 * 这里提供三个语义化修饰符，把「意图」写在代码里，而不是依赖几何推断：
 *
 * - [wcmFocusGroup]：把一组子控件当成 **1D 遍历的一个停靠点**（Tab 进一组，
 *   方向键在组内走）。用于条目多的横排 —— 底部 5 个 Tab 值得这么处理，
 *   否则从内容区到底栏要按 5 次 Tab 才能穿过去；2 个按钮的顶栏不值得（多按一次没负担）。
 * - [wcmSkipFocus]：让某个子控件**不参与焦点**，但**保留语义**。
 *   典型场景：`Switch` 挂在可点的行里，行已经负责切换，开关不该再停一次。
 *   注意它只关焦点、不关 TalkBack —— 读屏用户仍然能听到「已选中/未选中」。
 * - [wcmFocusNextTo]：显式指定 Tab 从这个控件走到的下一个控件。
 *
 * ## 必须注意的顺序
 *
 * 三个都只对**修饰符链里它之后**的焦点目标生效（与 `onFocusChanged` 同一规则）。
 * 所以必须写在 `clickable` / `combinedClickable` / `toggleable` **之前**；
 * 而 `Card(onClick)` / `Button` / `IconButton` 的 clickable 是组件内部追加的，
 * 放进 `modifier` 参数就是对的位置。
 *
 * ## 为什么不给每个页面写满 direction 定序
 *
 * 显式 `down` / `up` 需要知道「内容区第一个可聚焦项」的 `FocusRequester`，
 * 而它由每个页面的数据决定（账号行、超话行、抓取目标…），要么把 requester
 * 一路透传到各页面，要么在 CompositionLocal 里维护一份 —— 前者侵入每页签名，
 * 后者容易拿到已经离屏的 requester 并抛 `IllegalStateException`。
 * 收益（Tab 顺序本来就与布局顺序一致）远小于成本，**故不做全量 2D 定序**。
 */

/** 一组控件 = 1D 遍历的一个停靠点，组内用方向键移动。 */
fun Modifier.wcmFocusGroup(): Modifier = this.focusGroup()

/**
 * 不参与焦点，但保留语义（读屏仍能播报）。
 *
 * 用在「外层已可点」的子控件上，消除重复停靠点。
 */
fun Modifier.wcmSkipFocus(): Modifier = this.focusProperties { canFocus = false }

/**
 * 显式指定 Tab 顺序里的下一个控件。
 *
 * 只在「外层可点 + 内层还有按钮」的复合行里用：让「行 → ⋮」是定死的，
 * 不随行内布局调整而改变。
 */
fun Modifier.wcmFocusNextTo(next: FocusRequester): Modifier =
    this.focusProperties { this.next = next }
