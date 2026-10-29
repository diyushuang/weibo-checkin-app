# UI 系统设计规范（v0.7）

> ⚠️ **已废弃（v2.0 取代）**：本文描述的是「暖灰底 + 粉色 Hero 卡」方案，
> 视觉偏闷、红色面积过大，已被 [`ui-system-v2.0.md`](ui-system-v2.0.md) 替换。
> 保留本文仅为记录当时的取舍与对比度实算过程。

> 适用工程：`com.wcm.checkin`（微博签到助手，Kotlin / View，minSdk 23 / targetSdk 36）
> 落地范围：**只改呈现层**（`res/` 令牌与布局、Activity/Fragment 的视图绑定），
> 抓取内核、签到逻辑、面板同步、Cookie 存储一行未改。
> 配套：`docs/design/ui-refresh-rationale.md`（v0.6，本轮在其之上重建层级）

---

## 0. 一句话定位

**克制的暖中性底 + 单一珊瑚红强调 + 一眼看懂的主行动。**

工具类 App 的美观不来自装饰，来自「不用想」：每一屏只有一件事是"现在该做的"，
其余信息按可扫读的层级退后。所以本轮做的是**减法**——去掉卡片墙、去掉等权重按钮、
去掉到处一致的 12sp 灰字，换成有主次的四段结构。

---

## 1. 视觉风格与设计规范

### 1.1 配色

三层结构，改色只改 `values/colors.xml`（+ `values-night/colors.xml`）。

#### 品牌层

| 令牌 | 浅色 | 深色 | 用途与约束 |
|---|---|---|---|
| `brand` | `#F54868` | `#F54868` | 图标 tint / 描边 / 恒黑预览页强调色。**禁止**当正文或按钮文字色（压浅表面仅 3.32:1） |
| `brand_dark` | `#C4344E` | — | 浅色主题的填充容器底（白字 5.32:1，达 WCAG AA） |
| `brand_container` | `#FFDDE3` | `#4E1220` | Hero 卡底 / 图标底托 / 头像底 |
| `on_brand_container` | `#43000F` | `#FFD9E0` | 压在上面那层容器上的文字 |

> 两档珊瑚的分工是**对比度决定的，不是审美决定的**：亮珊瑚上的白字只有 3.49:1，
> 所以浅色主题的填充按钮必须用深珊瑚；深色主题反过来，深珊瑚压在 `#1C1B1F` 上只有
> 3.22:1，必须换回亮珊瑚并把文字改成近黑（4.90:1）。

#### 中性表面层（微暖灰五档）

| 令牌 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `surface` | `#FCF8F8` | `#131112` | 页面底色 |
| `surface_container_lowest` | `#FFFFFF` | `#0E0C0D` | Primary 卡（需要"浮起来"的一页一处） |
| `surface_container_low` | `#F7F1F2` | `#1B1919` | 预留层（未用：与页面底只差 5 灰阶，卡片边界看不清） |
| `surface_container` | `#F2EBEC` | `#1F1D1D` | Standard 卡（常规信息）、底部导航栏 |
| `surface_container_high` | `#ECE5E6` | `#2A2728` | 可点横幅（图库入口） |
| `on_surface` | `#1C1A1B` | `#E8E2E3` | 正文 |
| `on_surface_variant` | `#514547` | `#D3C0C2` | 说明文字 / 图标 |
| `outline_variant` | `#D6C3C6` | `#524345` | 分隔线 / 描边卡 |

> 为什么不直接用 M3 默认中性色：默认浅色表面带紫调（`#FFFBFE` 一族），
> 和珊瑚红放在一起会发"脏"。整体挪到极淡暖红灰后，珊瑚红才是干净的强调色。

#### 语义状态色（浅 / 深各自一套）

| 语义 | 浅色 容器 / 前景 | 深色 容器 / 前景 |
|---|---|---|
| 成功（已签） | `#DCF2E1` / `#17512C` | `#1E3B28` / `#A8DCB8` |
| 待办（待签） | `#FDEED2` / `#6E4500` | `#3D2E12` / `#F0C67E` |

状态色**不进主题调色板**，因此 Android 12+ 壁纸取色不会把它们换成荧光色。

### 1.2 字体

中文排版的两条硬约束决定了这套阶梯：

1. **中文没有可用的字重阶梯**。系统 CJK 只有 Regular / Medium / Bold 三档能分辨，
   层级只能靠「字号 + 颜色」拉开，不靠堆字重。
2. **中文字距不能加**。Material 西文默认的 letterSpacing 会在汉字之间插进半个字的空隙，
   整段文字显得"散"。全部显式设为 `0`。

| 层级 | 令牌 | 字号 / 行高 | 用途 |
|---|---|---|---|
| Display | `TextAppearance.Wcm.Display` | 28sp / 1.2× | 页面大标题（一屏一处） |
| Section | `TextAppearance.Wcm.Section` | 17sp / 1.25× | 卡片标题 |
| Row | `TextAppearance.Wcm.Row` | 15sp / 1.25× | 账号昵称、开关标题 |
| Body | `TextAppearance.Wcm.Body` | 14sp / 1.45× | 正文、说明主体 |
| Hint | `TextAppearance.Wcm.Body.Small` | 13sp / 1.45× | 辅助说明（次要色） |
| Label | `TextAppearance.Wcm.Label` | 12sp | 状态 chip |
| Mono | `TextAppearance.Wcm.Mono` | 12sp / 1.4× | 日志（等宽，按列扫读） |

标题/正文比 = 17/14 ≈ 1.21×（v0.6 是 1.14×，层级几乎看不出来）；大标题/正文 = 2×。
行高用 `lineSpacingMultiplier`（API 1）而非 `android:lineHeight`（API 28+），
避免低版本被 `NewApi` 硬规则拦下。

### 1.3 间距

4dp 栅格：`2 / 4 / 8 / 12 / 16 / 20 / 24 / 32`。

| 令牌 | 手机 | 平板 sw600 | 桌面 w840 | 说明 |
|---|---|---|---|---|
| `page_padding_horizontal` | 20dp | 24dp | 40dp | 比 16dp 更透气；360dp 窄屏仍留 320dp 有效宽度 |
| `card_padding` | 16dp | 20dp | 24dp | 卡片内边距 |
| `card_margin_top` | 12dp | 12dp | 12dp | 卡片之间 |
| `section_gap` | 20dp | 24dp | 28dp | 区块之间（大于卡片间距，避免"卡片墙"） |
| `touch_min` | 48dp | 48dp | 48dp | 无障碍最小触控 |
| `list_row_min_height` | 56dp | 56dp | 56dp | 行高（v0.6 是 48dp，偏挤） |

### 1.4 圆角

| 令牌 | 值 | 用在哪 |
|---|---|---|
| `radius_s` | 12dp | 缩略图、图标底托、Material 小组件 |
| `radius_m` | 16dp | 输入框、中等组件 |
| `radius_l` | 20dp | 卡片 |
| `radius_xl` | 28dp | 弹窗（挂在 `materialAlertDialogTheme` 上） |
| `radius_full` | 999dp（自动收敛为胶囊） | 状态 chip、头像、导航指示器 |
| `button_corner_radius` | 14dp | 按钮（不用全圆：工具类界面要"稳"，全圆显轻浮） |

Material 组件基准同步上调：`shapeAppearanceSmallComponent` 12dp / Medium 16dp / Large 20dp
（M3 默认 8/12/16，配 20dp 卡片会显得方正）。

### 1.5 图标

- **尺寸**：独立操作 24dp（触控区 48dp）；行内与文字齐平 20dp；图库徽标 11sp 文字无图标。
- **着色**：一律 `app:tint` 走主题属性（`?attr/colorPrimary` / `?attr/colorOnSurfaceVariant`），
  不写死色值——深色模式与壁纸取色自动跟随。
- **语义**：主色图标只用于"可操作"；次要色图标用于"装饰/指示"；破坏性动作用系统 `colorError`。
- **一致性**：全部为 Material 实心/线性混合风格，24dp viewbox，视觉重量统一；
  本轮不重绘图标（现有多为 Material 官方路径），只统一尺寸与着色规则。

### 1.6 动效

| 场景 | 时长 / 曲线 | 说明 |
|---|---|---|
| Tab 切页入场 | 180ms / `cubic-bezier(0.2,0,0,1)`，淡入 + 上移 8dp | 只做入场不做出场：页面常驻隐藏，出场会让上一步多停一帧，显得迟疑 |
| 键盘弹出时底栏 | 淡出 120ms / 淡入 150ms | 比立刻消失更不"跳" |
| 点击反馈 | 系统水波纹（`selectableItemBackground` / CardView `rippleColor`） | 不自定义，跟随系统预置动画 |
| 减弱动效 | 系统 `ANIMATOR_DURATION_SCALE == 0` 时**全部跳过** | 尊重开发者选项与无障碍「移除动画」 |

---

## 2. 核心界面与组件

### 2.1 页面骨架（三个 Tab 统一）

```
┌ 页头（非卡片）──────────────┐   Display 28sp 大标题 + 13sp 日期/说明
│ 签到                       │
│ 10月28日 星期三             │
└───────────────────────────┘
┌ Hero / 主卡（20dp 圆角）────┐   一页最多一张，承载"现在该做的事"
│  状态摘要                  │
│  [   立即签到（56dp）   ]  │   PrimaryLarge，整行拉满
│  [仅同步面板] [⟳ 刷新超话] │   次级：outlined + text
└───────────────────────────┘
┌ 信息卡（filled）───────────┐
│  区块标题            [＋]  │
│  ○ 行 1                    │
│  ○ 行 2                    │
└───────────────────────────┘
┌ 弱卡（outlined，默认收起）─┐
│  运行日志            ⌄     │
└───────────────────────────┘
```

**为什么页头不做成卡片**：页头是"我在哪"的定位器，不是内容。做成卡片会和下面的真内容
抢视觉重量，还会在 360×640 的首屏白吃一张卡的高度。省下的空间正好让主行动按钮落在
拇指自然区。预期效果：**打开 App 的第一眼就能看到「立即签到」和今天的日期**。

### 2.2 首页 · 签到

| 区域 | 内容 | 交互 | 预期效果 |
|---|---|---|---|
| 页头 | `签到` + 当日日期（`M月d日 EEEE`） | 每次 `render()` 重算，跨天回前台自动更新 | 首屏有"今天"的锚点，替代原来那句泛泛的问候 |
| Hero 卡 | 账号数/面板状态摘要 + 「立即签到」+「仅同步面板」+「刷新超话」 | 主按钮 56dp 整行；次级两个按钮权重明显更低 | 打开就能签到，不用先找按钮 |
| 账号卡 | 标题带计数 +「＋」；每行：头像 / 昵称 / UID / 状态 chip / 说明 / 溢出菜单 | 整行点按看超话清单；长按或点「⋮」弹删除菜单 | 多账号时每行有独立视觉锚点（首字头像），不必逐行读昵称 |
| 日志卡 | 标题 + 最近一行摘要，展开后是 220dp 等宽日志窗 | 整行卡头可点；运行任务时自动展开 | 首屏不被日志占满，出问题时能看到"最近发生了什么" |

### 2.3 图片页 · 抓取

六段，按"我现在想干什么"排序：图库入口横幅（可点卡片，图标装进 40dp 圆角底托）
→ 存储位置（ListRow：图标 + 标题 + 路径 + 文字按钮）→ 抓取目标（计数 + 添加 + 列表）
→ 抓取运行（Primary 卡：开关 + 主按钮，运行时主按钮换成「停止抓取」）
→ 抓取日志（outlined，默认收起）。

预期效果：**"看图"和"开抓"两条路径都在首屏半径内**，四个配置项退到它们该在的位置。

### 2.4 设置页 · 配置

页头 → 面板同步（Primary 卡：两个输入框 + 右对齐的「保存」）→ 行为（开关行，
行间用**缩进分隔线**：从文字起始位置开始画，不和左侧内容打架）→ 关于（outlined 弱卡）。

预期效果：全页只有一个填充按钮（保存），开关行整行可点（点标题也能切），
键盘弹出时表单被顶到键盘上方。

### 2.5 图库浏览

MaterialToolbar（左返回 + 标题 + 数量副标题 + 刷新）+ 正方形网格 + 空态。

- 单元格用 `MaterialCardView` 包住图片：**只为了裁 12dp 圆角**，不用 BitmapShader，
  也不额外开销（outline 由框架负责）；水波纹走 CardView 的 `rippleColor`，会被裁进圆角。
- GIF 徽标压在右上角，深色半透明 + 6dp 圆角，任何图片上都可读。
- 空态：72dp 图标底托 + 说明 + 「去配置抓取目标」，避免"页面挂了"的错觉。

### 2.6 全屏预览 / 登录页

- 预览：恒定纯黑背景，系统栏图标**恒为浅色**（`SystemBarStyle.dark`，不用 auto——auto
  在浅色模式下会把图标切成深色，黑底上就看不见了）；底部信息条内三个 48dp 图标按钮。
- 登录：WebView 高度改 `0dp + weight=1`（`match_parent` 在有兄弟节点时会溢出，
  键盘弹出会顶掉页面底部一截），顶部两行提示让出状态栏。

---

## 3. 适配方案

### 3.1 宽度档矩阵（资源限定符，代码零判断）

| 档位 | 限定符 | 页面留白 | 卡片内边距 | 图库列数 | 日志窗高 |
|---|---|---|---|---|---|
| 手机竖屏 | `values` | 20dp | 16dp | 3 | 220dp |
| 手机横屏 | `values-land` | 24dp | 16dp | 4 | 132dp |
| 平板 / 折叠屏展开 | `values-sw600dp` | 24dp | 20dp | 4 | 300dp |
| 桌面模式 / 12 寸+ | `values-w840dp` | 40dp | 24dp | 5 | 360dp |

同类限定符交叉命中时系统按「更具体者优先」选择（`sw600dp` 覆盖 `land`），不需要代码分支。
配置变更（旋转、折叠屏展开、分屏拖拽）由系统重建 Activity，资源自动切换。

### 3.2 图库网格：正方形永远成立

```kotlin
cellSize = (可用宽度 - gutter × (列数 - 1)) / 列数
itemMargin = gutter / 2     // 相邻两格各让一半，正好拼出 gutter
```

列数取 `@integer/gallery_columns`，不再写死常量。单元格边长在 `recycler.post{}` 里算，
带「span 未变 && 尺寸未变」双重守卫，一次收敛、不会自激抖动。

### 3.3 大字体（字体缩放 200%）

- 全部文字用 sp；容器用 `wrap_content + minHeight`，不做固定高。
- 头像用 `AppCompatTextView + autoSizeTextType`：单个汉字在 40dp 圆里会被自动压回去，
  而不是裁掉一半。
- 日志窗/超话清单是**固定高 + 可滚动**：字号变大只是行数变少，不会撑破弹窗。

### 3.4 横屏

横屏可视高度约为竖屏一半：日志窗 220→132dp、超话清单 320→200dp、页头上下留白压缩，
保证「主行动 + 日志」在同一屏内可见。

---

## 4. 系统功能兼容

### 4.1 边到边 + 状态栏 + 导航栏 + 挖孔

唯一入口 `MainActivity.applySystemBarInsets()`：

| inset 来源 | 落到哪 | 覆盖的场景 |
|---|---|---|
| `systemBars \| displayCutout` 的 `top` | root 上内边距 | 状态栏、中间打孔、刘海 |
| 同上的 `left/right` | root 左右内边距 | 横屏侧挖孔、侧边手势区 |
| 同上的 `bottom` | `bottomBar` 下内边距 | 手势条（约 24dp）与三键导航（约 48dp）自动各就各位 |
| `ime` 的 `bottom` | 内容区下内边距 | 键盘弹出：内容区缩到键盘顶，底栏淡出 |

- 透明系统栏与图标明暗交给 `enableEdgeToEdge()`：它按系统 `uiMode` 自动切换深浅图标，
  并在三键导航栏上补一层对比度衬底。主题里不再写 `statusBarColor`（写了也会被覆盖）。
- `android:windowLayoutInDisplayCutoutMode=shortEdges`：内容延伸进挖孔区渲染，
  由 insets 让位，而不是在挖孔侧留黑边。
- `ImagePreviewActivity` 单独用 `SystemBarStyle.dark(...)`：黑底恒浅色图标。
- 各二级页（图库 / 预览 / 登录）都各自调 `enableEdgeToEdge()` + 应用 insets，
  不依赖主题里的一次性设置。

### 4.2 深色模式

- `Theme.Material3.DayNight.NoActionBar` + `values-night/` 覆盖（主题、中性表面、
  状态色、品牌容器）。
- 正文/次要文字/描边走 `?attr/colorOnSurface*`、`?attr/colorOutlineVariant`，
  不自己维护第二套色值——深底浅字由主题保证。
- 状态 chip 与品牌容器**必须**有深色版本：浅色容器在深底上会变成刺眼的荧光块。

### 4.3 动态取色（Android 12+）

`WcmApp` 保留 `DynamicColors.applyToActivitiesIfAvailable()`：壁纸取色会替换
`colorPrimary` / `colorPrimaryContainer` / 中性表面。因此**布局里不写死品牌色**
（全部走 `?attr/`），Hero 卡底色用 `colorPrimaryContainer` 而不是固定色块，
壁纸换色时整页仍然协调。

### 4.4 其它

| 系统能力 | 处理 |
|---|---|
| 键盘 | `windowSoftInputMode=adjustResize` + IME inset → 内容区内边距；底栏淡出 |
| 手势导航 | 底部 inset 已覆盖；导航栏透明，内容延伸 |
| 三键导航 | `enableEdgeToEdge` 自动加对比衬底，图标可读 |
| 深色 / 浅色切换 | DayNight，无需重启（配置变更重建） |
| 减弱动效 | `ANIMATOR_DURATION_SCALE == 0` 时跳过切页与底栏动画 |
| RTL | 全部用 `start/end`、`marginStart`、`gravity=top|end` |
| 无障碍 | 触控 ≥48dp；装饰图标 `importantForAccessibility=no`；可点容器带 `contentDescription`（日志卡头会播报展开/收起状态） |
| 启动页 | Android 12 默认闪屏：应用图标 + 页面底色，无需额外配置 |

---

## 5. 落地清单

### 新增

| 文件 | 内容 |
|---|---|
| `values/type.xml` | 7 档文字令牌（中文字重/字距/行高的处理写在这里） |
| `values/shape.xml` | 形状令牌（12/16/20/28 + 胶囊） |
| `values/integers.xml` + `land` + `sw600dp` + `w840dp` | 图库列数分档 |
| `values-sw600dp/dimens.xml`、`values-w840dp/dimens.xml` | 平板/桌面尺寸覆写 |
| `drawable/bg_avatar.xml`、`bg_icon_tile.xml`、`bg_gif_badge.xml` | 头像圆底、图标底托、GIF 徽标 |

### 改写

| 文件 | 变化 |
|---|---|
| `values/colors.xml` / `values-night/colors.xml` | 三层颜色体系 + 深色中性表面 |
| `values/dimens.xml` / `values-land/dimens.xml` | 新增页头、圆角、按钮高、头像、分隔线等令牌 |
| `values/themes.xml` / `values-night/themes.xml` | 完整 M3 色彩角色 + 形状基准 + 弹窗主题覆盖 |
| `values/styles.xml` | 新增 Hero 卡、大按钮、页头、头像、工具栏、缩进分隔线（已签/待签 chip 沿用） |
| `layout/activity_main.xml` | 底栏包进 `bottomBar`（发丝线 + 导航），键盘时整块淡出 |
| `layout/fragment_home.xml` | 页头 + Hero 卡 + 账号卡 + 日志卡四段 |
| `layout/fragment_gallery.xml` / `fragment_settings.xml` | 页头 + 卡片层级重排 |
| `layout/item_account.xml` | 首字头像（autoSize）+ 行结构 |
| `layout/item_gallery_grid.xml` | 卡片裁圆角 + 圆角 GIF 徽标 |
| `layout/activity_gallery_browse.xml` / `activity_login.xml` | 工具栏样式、空态、WebView 高度修正 |
| `MainActivity.kt` | `enableEdgeToEdge` + insets/IME + Tab 入场动效 + 减弱动效尊重 |
| `GalleryBrowseActivity.kt` | 列数/间隙读资源，正方形边长 + 均分外边距 |
| `ImagePreviewActivity.kt` / `LoginWebViewActivity.kt` | 边到边 + 恒浅色系统栏 + 四边 insets |

### 业务零改动

`Crawler.kt`、`CrawlRunner.kt`、`CrawlIndex.kt`、`CrawlPlanner.kt`、`CrawlCursor.kt`、
`WeiboApi.kt`、`PanelApi.kt`、`CheckInWorker.kt`、`LivePhotoConverter.kt`、`GifEncoder.kt`、
`Hashing.kt`、`AssetName.kt`、`Takeover.kt`、`AccountStore.kt`（未新增任何偏好项）。

`HomeFragment.kt` 仅新增两处**呈现**逻辑：页头日期格式化、头像首字取值。

---

## 6. 验收清单（真机逐条核对）

**尺寸与系统**

- [ ] 360×640（小屏）：首屏可见页头 + 主按钮 + 第一个账号行
- [ ] 412×915 / 1080×2400：卡片左右留白一致，无横向滚动
- [ ] 横屏：日志窗 132dp，主行动与日志同屏
- [ ] 平板 / 折叠屏展开：留白 24dp，图库 4 列，格子仍是正方形
- [ ] 分屏 / 自由窗口拖拽：列数与留白随宽度切换，无错位
- [ ] 挖孔屏（竖屏 + 横屏）：状态栏、侧挖孔不压内容
- [ ] 手势导航与三键导航：底栏不被手势条/导航键压住，三键模式下导航栏有对比衬底

**主题与无障碍**

- [ ] 浅色 / 深色切换：卡片、chip、头像、日志、预览页全部可读
- [ ] Android 12+ 换壁纸：Hero 卡与按钮跟随取色，不与品牌红打架
- [ ] 系统字体 200%：按钮文字不截断，头像不撑破，弹窗仍可滚动
- [ ] 开发者选项「动画时长 0.0x / 关闭」：切页无动画、无闪白
- [ ] TalkBack：日志卡头播报「已收起/已展开」，装饰图标不抢焦点

---

## 7. 已知限制

1. **未在真机/模拟器上做像素级验收**（本机无可用设备）。本轮验证为：资源编译、
   Kotlin 编译、单元测试、lint（`NewApi` 硬规则）全部通过；视觉结论基于代码与令牌，
   真机差异（尤其是系统默认字体的字重观感、动态取色下的具体色值）需要在设备上确认。
2. **图标未重绘**：仍用工程既有的 Material 路径图标，只统一了尺寸与着色规则。
3. **未做平板双栏布局**：本 App 的操作都是线性流程（签到 → 看图 → 配置），
   分栏只会让视线左右跳；大屏用"加留白、加列数"代替分栏。
4. **动态取色与品牌色的取舍**：开启壁纸取色后 `colorPrimary` 会被替换，
   品牌红只保留在图标与恒黑页。若希望「品牌红恒定」，需要在 `WcmApp` 关掉
   `DynamicColors`（会牺牲系统一致性，本轮不做）。
