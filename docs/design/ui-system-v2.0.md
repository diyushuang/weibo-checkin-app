# UI 系统规范 v2.0（对齐面板端 `tokens.css`）

> 工程：`com.wcm.checkin`（微博签到助手，Kotlin / View，minSdk 23 / targetSdk 36）
> 参考基线：`E:/项目/微博签到/docs/ui-redesign/tokens.css`（面板端 UI 系统 v2.0）
> 落地范围：**只改呈现层**（`res/` 令牌与布局、Fragment 的视图绑定）。
> 抓取内核、签到逻辑、面板同步、Cookie 存储一行未改。
> 取代：`ui-system-v0.7.md`（暖灰 + 粉卡方案，视觉过闷，已废弃）

---

## 0. 这次改了什么（一句话）

上一版是「暖灰底 + 粉色 Hero 卡」，问题有三个：**卡片靠色块堆层级显得闷**、
**28sp 大标题在小屏上"喊"**、**红色面积太大（整张 Hero 卡都是粉的）**。
v2.0 改成参考稿的语言：**冷灰底 + 纯白微描边卡 + 玫红只占很小面积 + 更紧凑的字号 + 数据化组件**。

---

## 1. 视觉规范

### 1.1 配色（三层，改色只改 `colors.xml` / `values-night/colors.xml`）

| 令牌 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `bg` | `#F5F6F8` | `#0B0D10` | 页面底（略偏冷，衬托白卡） |
| `surface` | `#FFFFFF` | `#15181E` | 卡片 / 底栏（深色下**比底亮**，浮起方向不变） |
| `surface_2` | `#F7F8FA` | `#1B1F26` | 输入框、图标托、凹面 |
| `surface_3` | `#EFF1F4` | `#22272F` | 进度槽、更深凹面 |
| `ink` | `#14161A` | `#E8EAEE` | 主文本（18.1:1） |
| `ink_2` | `#5B616B` | `#A2A9B4` | 次要文本（6.2:1） |
| `ink_3` | `#6E7681` | `#8A929E` | 辅助 / 占位（4.6:1） |
| `line` | `#EAECF0` | `#262B34` | 卡片描边、分隔线 |
| `line_2` | `#DFE3E8` | `#333944` | 输入框描边、需要更明确边界处 |
| `brand` | `#E0245E` | `#FF5C7A` | 主按钮 / 选中 / 关键数字（4.58:1，可作正文强调） |
| `brand_strong` | `#B41246` | `#FF8AA0` | 软底上的小字（6.74:1） |
| `brand_soft` | `#FDECF1` | `#3A1720` | 品牌软底（头像、pill、选中指示器） |
| `ok` / `ok_text` / `ok_soft` | `#0E9F6E` / `#087F5B` / `#E7F7F1` | `#34D399` / `#34D399` / `#10312A` | 已签 / 正常 |
| `warn` / `warn_soft` | `#C2410C` / `#FDF1E3` | `#FBBF24` / `#322612` | 待签 / 临期 |
| `danger` / `danger_soft` | `#B42318` / `#FDECEA` | `#F87171` / `#3A1818` | 失效 / 删除 |
| `console_*` | 恒定深色 `#0E1116` | 同左 | 日志终端块（两主题共用） |

**用法约束（勿破）**

1. 一屏里红色面积要小：只有「主按钮 + 选中 Tab + 关键数字 + 状态点」用品牌色。
   上一版把整张 Hero 卡涂成粉色，是"廉价感"的主要来源。
2. 卡片靠「白面 + 1dp 描边 + 1dp 投影」浮起，**不用色块**分层。
3. `brand` 可以作正文级强调（4.58:1），但软底上的小字用 `brand_strong`。
4. 深色下 `brand` 提亮到 `#FF5C7A`，且 `onPrimary` 用近黑（提亮后的玫红压白字只有 2.9:1）。

### 1.2 字体（参考稿的紧凑刻度）

| 令牌 | 字号 / 行高 | 用途 |
|---|---|---|
| `TextAppearance.Wcm.Cap` | 10.5sp | 角标、键名（「今日已签」「待签超话」） |
| `TextAppearance.Wcm.Xs` | 11.5sp / 1.5 | 辅助说明、副标题 |
| `TextAppearance.Wcm.Sm` | 12.5sp / 1.55 | 次要正文 |
| `TextAppearance.Wcm.Base` | 13.5sp / 1.55 | 正文 |
| `TextAppearance.Wcm.RowTitle` | 13.5sp Medium / -0.01em | 行标题（账号名、开关标题） |
| `TextAppearance.Wcm.CardTitle` | 14sp Medium | 卡片标题 |
| `TextAppearance.Wcm.AppBar` | 20sp Medium / -0.02em | 应用栏大标题 |
| `TextAppearance.Wcm.Metric` | 19sp Medium / tnum | 关键数字 |
| `TextAppearance.Wcm.MetricHero` | 23sp | 进度环中心数字 |
| `TextAppearance.Wcm.Pill` | 11.5sp Medium | 状态胶囊 |
| `TextAppearance.Wcm.Mono` | 11sp / 1.7 | 日志（等宽） |

中文三条约束：**只用 400 / 500 两档字重**（CJK 只有这两档可辨）、
**字距只收紧不放开**（标题 -0.01~-0.02em）、**行高用 `lineSpacingMultiplier`**
（`android:lineHeight` 是 API 28+，会被 `NewApi` 硬规则拦下）。
数字一律 `fontFeatureSettings="tnum"`：刷新时不会左右跳。

### 1.3 间距 / 圆角 / 高度

| 令牌 | 值 | 说明 |
|---|---|---|
| `page_padding_horizontal` | 16dp（手机）/ 24（平板）/ 32（桌面） | 360dp 屏上有效宽度 328dp |
| `card_padding` | 14dp（Hero 18dp） | 卡片内边距 |
| `card_gap` | 10dp | 卡片间距 |
| `radius_xs / s / m / l / xl` | 6 / 10 / 14 / 18 / 24 dp | 小徽标 / 输入·按钮 / 卡片 / Hero / 弹窗 |
| `appbar_height` | 48dp | 应用栏 |
| `btn_height` | 44dp（大 48 / 小 32） | 视觉高度；可点区恒 ≥48dp |
| `avatar_size` | 38dp（圆角 12dp，方形不是圆形） | 与卡片圆角同族，整体更"整" |
| `icon_tile` | 32dp（圆角 9dp） | 列表行左侧图标托 |

---

## 2. 核心界面（四屏一套骨架）

**骨架**：`应用栏 48dp（20sp 标题 + 11.5sp 副标题 + 右侧 34dp 图标钮）→ 内容卡 → 底部导航 56dp`。
切 Tab 时骨架不动，只有内容区变化 —— 这是"流畅感"的主要来源。

> 底部导航自 v0.9.0 起是 **4 项**（首页 / 超话 / 图片 / 设置）：超话页单独成页后，
> 原「三屏」口径作废。§1 的令牌与 §3 的适配档位不受影响。

### 2.1 首页 · 签到

| 区块 | 内容 | 预期效果 |
|---|---|---|
| 应用栏 | 「早上好」+「10月28日 · 周三 · 3 个账号」；右侧刷新、品牌方块「＋」 | 一眼知道现在几点、今天几个号 |
| Hero 卡（18dp 圆角） | 左：**88dp 进度环**（已签/总数 + 「今日已签」）；右：「待签超话」大数字 + 两个 pill（账号数 / 面板状态） | **结论先行**：3 秒内回答"今天签完没有、还差几个" |
| 主行动 | 并列两枚（48dp）：品牌实底「立即签到」+ 描边「Cookie 同步」（v0.9.10 起，原来是藏起来的文字按钮） | 签到保持最高对比度；同步是每 7 天一次的必做动作，不能藏 |
| Cookie 卡（v0.9.10） | 逐账号「还剩几天 / 何时到期」；**失效 / 已过期 / 剩余 ≤2 天整行红字 + 红 pill** 并置顶，正常行绿 pill | 到期前就被看见，而不是整屏账号一起掉登录才发现 |
| 账号卡 | 行：38dp 头像（**按状态变色**：绿=全签完 / 琥珀=有待签 / 玫红=状态未知）+ 昵称 + 状态 pill + UID + 说明 | 不读字也能看出哪个号要处理 |
| 日志卡 | 默认收起；展开是**恒深色终端块**（11sp 等宽） | 深色终端块与界面其余部分明确区分，日志"像日志" |

进度环只统计**状态已知**的账号（加载中/失败的不进分母），一个都没有时显示 `0/0` 与 `—`，
绝不给假 0%。

### 2.2 图片页 · 抓取

`图库入口卡（图标托 + 张数 + chevron）→ 存储位置 ListRow → 抓取目标卡（行式）→
抓取运行卡（开关行 + 主按钮，运行中换成危险色「停止」）→ 日志卡`。

预期：**"看图"与"开抓"两条路径都在首屏半径内**；四个配置项退到它们该在的位置；
一屏只有一个品牌实底按钮。

### 2.3 设置页 · 配置

`面板同步卡（OutlinedBox 输入框 + 整行「保存」+ 结果说明）→ 行为卡（开关行 + 发丝线）→
关于卡`。键盘弹出时由 `MainActivity` 把 IME inset 转成内容区底部内边距，表单被顶到键盘上方。

### 2.4 二级页

- **图库浏览**：48dp 工具栏（返回 + 标题 + 数量副标题 + 刷新）+ 正方形网格（12dp 圆角裁切）+ 空态。
- **全屏预览**：恒黑背景，系统栏图标恒浅色（`SystemBarStyle.dark`），底部 44dp 图标按钮。
- **登录页**：WebView `0dp + weight=1`（`match_parent` 在有兄弟节点时会溢出）。

---

## 3. 适配方案

| 档位 | 限定符 | 留白 | 卡内边距 | 进度环 | 图库列数 | 日志窗 |
|---|---|---|---|---|---|---|
| 手机竖屏 | `values` | 16dp | 14dp | 88dp | 3 | 200dp |
| 手机横屏 | `values-land` | 20dp | 14dp | 76dp | 4 | 140dp |
| 平板 / 折叠展开 | `values-sw600dp` | 24dp | 16dp | 96dp | 4 | 280dp |
| 桌面模式 | `values-w840dp` | 32dp | 20dp | 104dp | 5 | 340dp |

图库网格：`cellSize = (可用宽度 - gutter×(列数-1)) / 列数`，列数读 `@integer/gallery_columns`，
外边距按 `gutter/2` 均分 —— 换列数、换屏幕，格子永远是正方形。

大字体（系统 200%）：全部 sp + `wrap_content` + `minHeight`；头像用 `autoSizeTextType`
把单字压回 38dp 方块内；日志/清单是固定高 + 可滚动，字号变大只是行数变少。

---

## 4. 系统兼容

| 能力 | 处理 |
|---|---|
| 边到边 | `enableEdgeToEdge()`；`systemBars\|displayCutout` 的 top/left/right → 根布局 padding |
| 导航栏 | inset bottom → `bottomBar` 内边距；手势条 / 三键导航自动各就各位 |
| 键盘 | `adjustResize` + IME inset → 内容区底部内边距，底栏淡出 |
| 深色模式 | `values-night` 全套覆盖（底、面、线、软底、语义色） |
| 动态取色 | 保留 `DynamicColors`（壁纸取色会替换主色与中性表面） |
| 挖孔 | `windowLayoutInDisplayCutoutMode=shortEdges` + insets 让位 |
| 减弱动效 | `ANIMATOR_DURATION_SCALE==0` 时跳过切页与底栏动画 |
| RTL | 全部 `start/end` |
| 无障碍 | 可点区 ≥48dp；装饰图标 `importantForAccessibility=no`；卡头带展开状态播报 |

---

## 5. 与参考稿的映射（改版查表用）

| `tokens.css` | 本工程 |
|---|---|
| `--bg / --surface / --surface-2 / --surface-3` | `@color/bg / surface / surface_2 / surface_3` |
| `--ink / --ink-2 / --ink-3` | `@color/ink / ink_2 / ink_3` |
| `--line / --line-2` | `@color/line / line_2` |
| `--accent / --accent-strong / --accent-soft` | `@color/brand / brand_strong / brand_soft` |
| `--success / --warn / --danger (+ -soft / -text)` | `@color/ok_* / warn_* / danger_*` |
| `--r-xs … --r-xl / --r-full` | `@dimen/radius_xs … radius_xl / radius_full` |
| `--sp-1 … --sp-10` | `@dimen/sp_1 … sp_10` |
| `--fs-cap … --fs-h1` | `TextAppearance.Wcm.Cap … AppBar` |
| `--h-appbar / --h-tabbar / --h-btn / --touch-min` | `appbar_height / tabbar_icon+wrap / btn_height / touch_min` |
| `--sat / --sab`（env 安全区） | Android 用 `WindowInsetsCompat` 运行时取值，不写死 |

**有意偏离参考稿的三处**

1. **底部导航用 M3 组件**（56dp 视觉、11sp 标签）而不是 9.5px 自定义 tab：
   Android 无障碍对触控与字号有硬要求，9.5sp 中文在 360dp 屏上过细。
2. **不做 5 Tab**：本 App 只有四块内容（首页 / 超话 / 图片 / 设置），硬凑 5 个反而增加迷路成本。
3. **主行动不用 FAB**：FAB 会和底部导航抢空间，且本 App 的主行动是上下文相关的
   （签到 / 抓取 / 保存），整行主按钮更明确。

---

## 6. 验收清单

- [ ] 360×640 首屏：应用栏 + 进度环 + 主按钮 + 第一个账号行可见
- [ ] 412×915 / 长屏：卡片左右留白一致，无横向滚动
- [ ] 横屏：进度环 76dp，主按钮与日志同屏
- [ ] 平板 / 折叠展开：留白 24dp、环 96dp、图库 4 列且格子正方形
- [ ] 深色：卡片比底亮、pill 不发荧光、日志终端块仍可读
- [ ] 动态取色（Android 12+）：Hero 与按钮跟随壁纸，不与状态色冲突
- [ ] 字体 200%：按钮文字不截断、头像不撑破
- [ ] 开发者选项动画 0：切页无动画、无闪白
- [ ] TalkBack：账号行播报昵称 + 状态，日志卡头播报展开/收起

---

## 7. 已知限制

1. 本机无设备/模拟器，**未做像素级真机验收**；验证为资源编译 + Kotlin 编译 + 单元测试 + lint。
2. 图标沿用工程既有 Material 路径，只统一了尺寸（24/20dp）与着色（`app:tint` 走主题）。
3. 未做平板双栏；未做动态取色开关（壁纸取色会替换主色，这是 M3 预期行为）。

---

## 8. v0.9.0 新增令牌（超话页 / 图库增强）

新增令牌全部沿用 v2.0 的口径（浅色底浅字深、深色低明度同色系），没有引入新字号。

### 8.1 超话等级徽章配色（`level_*_bg` / `level_*_fg`，浅深各一套）

| 档位 | 等级区间 | 浅色 底 / 字 | 深色 底 / 字 |
| --- | --- | --- | --- |
| `neutral` | 1-3 | `#EEF0F3` / `#4B5563` | `#262B34` / `#A2A9B4` |
| `blue` | 4-6 | `#E8F0FE` / `#1D4ED8` | `#16263F` / `#93B4FF` |
| `purple` | 7-9 | `#F1EAFE` / `#6D28D9` | `#2A2138` / `#C4A9FF` |
| `gold` | 10+ | `#FBF0D6` / `#8A5A00` | `#33280F` / `#F0C674` |

档位由 `WeiboApi.topicLevelTier(level)` 计算，颜色在代码里用 `backgroundTint` 施加，
样式 `Widget.Wcm.Badge.Level` 只描述"一枚徽章长什么样"，不写死档位色。

另有 `select_scrim`（多选遮罩，浅 `#66000000` / 深 `#99000000`）。

### 8.2 新增尺寸

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `topic_badge_height` | 18dp | 等级徽章高度（比 pill 矮，不与状态 pill 抢视觉） |
| `topic_badge_min_width` | 30dp | 放得下 `Lv.12` |
| `topic_selector_height` | 38dp | 账号 pill 横向滚动区 |
| `topic_filter_bar_height` | 44dp | 超话页筛选条 |
| `progress_thickness` | 6dp | 线性进度条 |
| `gallery_filter_bar_height` | 40dp | 图库筛选条 |
| `gallery_search_height` | 44dp | 图库搜索行 |
| `gallery_group_header_height` | 30dp | 跨行分组头 |
| `gallery_select_bar_height` | 56dp | 多选操作条 |
| `gallery_check_size` | 20dp | 多选角标 |

### 8.3 新增组件样式

| 样式 | 说明 |
| --- | --- |
| `Widget.Wcm.Badge.Level` | 等级徽章（18dp / 6dp 圆角 / `backgroundTint` 换档） |
| `Widget.Wcm.Pill.Filter` | 可点胶囊（默认线框，选中态在代码里换品牌软底） |
| `Widget.Wcm.Progress` | LinearProgressIndicator（6dp、3dp 圆角、品牌色） |
| `Widget.Wcm.GroupHeader` | 图库分组头（跨整行，`TextAppearance.Wcm.Cap`） |
| `Widget.Wcm.SelectBar` | 图库多选操作条（surface 底 + 56dp） |

### 8.4 布局约定

- 超话页顶部四块（应用栏 / 账号选择器 / Hero / 筛选条）**固定不滚动**：
  这一页的核心问题是"今天还差几个没签"，概览与筛选要一直在视野里。
- 图库分组头占满整行，靠 `GridLayoutManager.SpanSizeLookup` 实现，
  换列数时通过 `buildLayoutManager(span)` 重建 manager 一并重建 lookup。
- 多选态用常驻的遮罩 + 角标切换 `visibility`，长按时不 addView，避免布局跳动。

