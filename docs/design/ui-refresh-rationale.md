# UI 刷新改动说明（v0.6 · 主题美化 + 布局 / 按钮层级 / 操作逻辑优化）

本文件说明本轮 UI 改造**每一项改动的目的、做法、预期效果与回退方式**。
原则：只动呈现层（布局 / 样式 / 视图绑定），**业务逻辑文件一行未改**。

- 未改动：`Crawler.kt`、`CrawlRunner.kt`、`CrawlIndex.kt`、`CrawlPlanner.kt`、`CrawlCursor.kt`、
  `WeiboApi.kt`、`PanelApi.kt`、`CheckInWorker.kt`、`LivePhotoConverter.kt`、`GifEncoder.kt`、
  `Hashing.kt`、`AssetName.kt`、`Takeover.kt`、`AccountStore.kt`（连偏好项都没新增）。
- 只改视图绑定/呈现：`HomeFragment.kt`、`GalleryFragment.kt`、`SettingsFragment.kt`、
  `GalleryBrowseActivity.kt`（网格列数/单元格尺寸）；`ImagePreviewActivity.kt`、`MainActivity.kt`
  无需改代码（仅布局调整），`LoginWebViewActivity.kt` 仅布局微调。

---

## 一、A. 主题令牌化（先立规矩，再改页面）

| 编号 | 改动点 | 目的（解决什么用户问题） | 具体改动 | 涉及文件 | 预期效果 | 风险与回退方式 |
|---|---|---|---|---|---|---|
| A1 | 次要文字改走主题语义色 | 原 `#8A8F99` 为浅色写死，深色模式下深底浅字对比不足（约 3.2:1），说明文字看不清 | 布局内所有 `@color/text_secondary` 改为 `?attr/colorOnSurfaceVariant`；`@color/brand` 引用改为 `?attr/colorPrimary` / `?attr/colorError` | `layout/*.xml`（全部） | 深浅两态自动取正确的中性色，对比度达标；动态取色（壁纸取色）时也自动跟随 | 若某处需要固定色，改回具体 `@color/xxx` 即可，不影响逻辑 |
| A2 | 新增语义色 + 深色覆盖文件 | 原来只有 4 个颜色、无深色版本，状态色无法区分"成功/待办" | `values/colors.xml` 增加 `status_success_container` / `status_on_success_container` / `status_warning_container` / `status_on_warning_container` / `scrim_image` / 预览页固定前景色；新建 `values-night/colors.xml` 覆写状态色为深底浅字；删除已无引用的 `text_secondary` | `values/colors.xml`、`values-night/colors.xml`（新建） | 深色模式下 chip 不再是刺眼的浅色块，文字对比 ≥4.5:1 | 颜色是纯资源，回退只需改色值 |
| A3 | 新增尺寸令牌 `dimens.xml`、横屏覆写 | 布局里到处是 `12dp / 16dp / 220dp` 魔法数，横屏时日志窗固定 220dp 会把一屏占满 | 建立 4dp 栅格间距（2/4/8/12/16/24）、卡片圆角 16dp、触控 48dp、列表行 48dp、日志窗高度等令牌；`values-land/dimens.xml` 把日志窗压到 132dp / 120dp | `values/dimens.xml`（新建）、`values-land/dimens.xml`（新建） | 全 App 间距节奏一致；横屏下"主行动 + 日志"能在同一屏内看到 | 全部是资源引用，回退=改回字面值 |
| A4 | 新增复用样式 `styles.xml` | 上一版没有样式体系，同一个"卡片标题"在 5 个布局里各写一遍，改一次要改五处 | 文字五档（区块标题/行标题/正文/说明/日志）、卡片三级（Primary elevated / Standard filled / Subtle outlined）、按钮三档 + 图标按钮（Primary / Secondary / Text / 48dp 图标按钮）、列表行、状态 chip、日志卡头、空态文字 | `values/styles.xml`（新建） | 视觉层级由样式集中定义，页面结构可读；后续改版只改一处 | 样式是新增，不覆盖既有样式；回退=删除引用 |
| A5 | 字号层级统一并拉开梯度 | 原布局 `textSize` 有 10/11/12/13/14/15/16sp 七种，且用 `textStyle="bold"` 手工加粗；标题与正文只差 1.14×，层级压不住 | 区块标题 **TitleLarge 18sp**、行标题 TitleSmall 14sp、正文 BodyMedium 14sp、说明 BodySmall 12sp、日志等宽 12sp，全部由 `textAppearance` 指定（标题:正文 = 1.28×） | 所有布局、`values/styles.xml` | 标题→正文→说明三档清晰，扫读时不会"每行字都差不多大" | 纯样式，逐处可回退 |
| A6 | 动态取色沿用既有实现，并让全部颜色改为跟随主题 | 品牌色与"Android 12+ 跟随壁纸取色"两种调色板此前会与布局里写死的 `@color/brand` 打架 | 确认 `WcmApp` 已有 `DynamicColors.applyToActivitiesIfAvailable()`，因此**不做**新的取色开关；改为把布局里的固定色整体换成 `?attr/` 主题属性 | `WcmApp.kt`（未改）、全部布局 | 品牌色模式与壁纸取色模式下，按钮/图标/文字都自动协调，不会出现"壁纸是紫色、删除按钮还是品牌红" | 见「未采纳与原因」第 1 条 |
| A7 | 修正品牌"填充色 + 白字"的对比度（对齐调研报告 S19） | `colorPrimary` 原为 `#F54868`，白字压在其上只有 **3.49:1**，低于 WCAG AA 对正常字号（填充按钮 14sp Medium 属正常字号）要求的 4.5:1 —— 亮红按钮上的白字在强光下糊成一片 | 浅色 `values/themes.xml`：`colorPrimary` → `@color/brand_dark`（白字 **5.32:1**，作前景压 M3 浅色表面 `#FEF7FF` 为 **5.06:1**，一个色值满足两个方向）。**深色主题与"统一改 brand_dark"的建议不同**：保持 `@color/brand`（亮红压 `#1C1B1F` 表面 **4.90:1**，达标；若换深红只剩 **3.22:1**，深色下的 TextButton 文字会几乎看不清），并补 `colorOnPrimary = #1C1B1F`（M3 深色默认 onPrimary 是深紫调 `#381E72` 一类，压在亮红上仅 **3.76:1** 且偏色；换近黑后 **4.90:1**）. 预览页背景恒黑，`Theme.Wcm.Preview` 单独固定 `colorPrimary = @color/brand`（亮红压纯黑 **6.01:1**） | `values/themes.xml`、`values-night/themes.xml`、`values/colors.xml` | 浅色/深色两态的填充按钮文字都 ≥4.5:1；浅色下按钮由"亮粉红"变"深红"，深色下仍是亮红 | 回到原样只需把两处 `colorPrimary` 改回 `@color/brand`；`#F54868` 未删除，仍是图标 tint / 深色主题 / 预览页的强调色 |
| A7-1 | 改色后逐一核对受 `colorPrimary` 影响的控件（深浅两态） | 换主色最容易把别的控件"带歪" | 核对结果：① **底部导航**选中态用的是 M3 的 `colorSecondaryContainer` / `colorOnSecondaryContainer`，不受 `colorPrimary` 影响，观感不变；② **MaterialSwitch** 选中轨道 = `colorPrimary`、滑块 = `colorOnPrimary` —— 浅色：深红轨道 + 白滑块（5.32:1），深色：亮红轨道 + 近黑滑块（4.90:1，这正是深色补 `colorOnPrimary` 的原因）；③ **状态 chip** 用的是自定义固定色对，不读 `colorPrimary`，不受影响；④ **进度条**：全 App 无 `ProgressBar`（进度以日志文本 + 计数 chip 呈现），无影响；⑤ 图标 tint / TextButton 文字（`?attr/colorPrimary`）：浅色 **5.06:1**（压 `#FEF7FF`）、深色 **4.90:1**，均达标 | 同上 | 换主色后各处观感与对比度仍协调，不会出现"某个控件变糊" | 无需回退；核对结论本身可复核（色值 + WCAG 公式都在本表） |

---

### 对比度实算表（WCAG 公式，sRGB 线性化后 `(L_hi+0.05)/(L_lo+0.05)`）

这份表是本轮唯一"改颜色"改动的依据，全部实算复核过（含深浅两态，单态结论不能套到另一态）：

| 前景 / 背景 | 比值 | AA（正常字号 4.5:1） | 用途 |
|---|---|---|---|
| `#FFFFFF` / `#F54868` | **3.49:1** | ✗ 不达标 | 改造前的填充按钮白字（这就是要修的原因） |
| `#FFFFFF` / `#C4344E` | **5.32:1** | ✓ | 改造后浅色主题的填充按钮白字 |
| `#C4344E` / `#FEF7FF` | **5.06:1** | ✓ | 浅色主题里图标 tint / TextButton 文字压 M3 表面 |
| `#C4344E` / `#1C1B1F` | **3.22:1** | ✗ 不达标 | **反例**：深红压深色表面 —— 所以深色主题不能沿用 brand_dark |
| `#F54868` / `#1C1B1F` | **4.90:1** | ✓ | 深色主题里图标 tint / TextButton 文字 |
| `#1C1B1F` / `#F54868` | **4.90:1** | ✓ | 深色主题的填充按钮文字（`colorOnPrimary`） |
| `#381E72` / `#F54868` | **3.76:1** | ✗ 不达标 | **反例**：M3 深色默认 onPrimary（深紫调）压亮红 —— 所以深色必须覆写 onPrimary |
| `#F54868` / `#000000` | **6.01:1** | ✓ | 预览页（恒黑背景）上的强调色 |
| `#F54868` / `#FEF7FF` | **3.32:1** | ✗（仅过图形门槛 3:1） | **约束条**：品牌亮红压浅色表面只够当图标，不能当文字 |

**使用约束（新增颜色/新用法前必须遵守）**

> `@color/brand`(`#F54868`) 仅可用于图标着色 / 描边 / 深色主题强调色 —— 压 `#FEF7FF` 为 **3.32:1**，只刚过 WCAG 1.4.11 对图形元素的 3:1（余量 0.32）。**禁止**用作正文或按钮文字色（文字要求 4.5:1）。
> 当前代码中 `?attr/colorPrimary` 只剩 **2 处图标 tint**（`fragment_home.xml`、`fragment_gallery.xml`），无文字用途，符合本条；预览页分享图标已改为固定色 `@color/brand`（该页恒黑背景，不吃主题，见 A7）。新增用法前请先用本表的方法实算。

**关于 Android 12+ 动态取色与上面的 AA 结论（避免误读）**

上面的 5.32 / 4.90 是**品牌色模式**（未启用动态取色，或设备低于 API 31）下的保证值。API 31+ 启用 `DynamicColors` 后，`colorPrimary` / `colorOnPrimary` 整套被壁纸种子色生成的 **M3 tonal palette** 替换，此时对比度由该调色板的生成规则保证：浅色 `primary` = tone 40 配 `onPrimary` = tone 100（白），相对亮度 0.1125 vs 1.0 → **6.46:1**；深色 `primary` = tone 80 配 `onPrimary` = tone 20 → **7.72:1**（`Y` 只由 L\* 决定、与色相无关，所以对任意壁纸都成立）。

因此准确表述是：**品牌模式的 AA 由本项目两套色值保证，动态取色模式由 M3 tonal palette 保证 —— 差异是"用哪套色"，不是"有没有无障碍"**。（前提：M3 默认对比度档位；若将来启用高/低对比档，tone 会变，本组数需重算。）

---

## 二、B. 首页（签到主战场）

| 编号 | 改动点 | 目的（解决什么用户问题） | 具体改动 | 涉及文件 | 预期效果 | 风险与回退方式 |
|---|---|---|---|---|---|---|
| B1 | 主行动唯一化 | 原概览卡里"立即签到"(Filled 全宽) 与"仅同步面板"(Tonal 全宽) 两个大按钮上下堆叠，视觉权重接近，用户每次都要先分辨该点哪个 | 「立即签到」保持 Filled 全宽；「仅同步面板」降为 **Outlined 并塞进次级操作行**；「刷新超话」从账号卡标题行移出，与该行并列（TextButton + 同步图标） | `fragment_home.xml`、`HomeFragment.kt`（仅绑定不变） | 首屏只有一个"重"按钮，主路径（签到）不再被分流；两个低频动作仍在一步之内 | 控件 id 全保留（`btnSync` / `btnRefreshTopics`），回退只需调回样式 |
| B2 | 账号卡标题行去拥挤 | 原一行挤了「账号」+「刷新超话」+「＋ 添加账号」三个元素，最常用的"添加"反而最靠右、最小，且文字被压得换行 | 标题改为 `账号 (N)`（带数量）+ 行尾**单个 48dp 图标按钮**（`ic_add`，primary 色，contentDescription 添加账号） | `fragment_home.xml`、`HomeFragment.kt`（`tvAccountsTitle` 文案） | 标题行只剩"信息 + 一个动作"，用拇指更容易命中；数量一眼可见 | 新增 id `tvAccountsTitle`；`btnAdd` 行为完全不变 |
| B3 | 账号行重构 + 删除收进溢出菜单 | 每行常驻一个"删除"文字按钮：① 误触直接弹删除确认；② 每行都有一块红色文字，视觉噪音大；③ 真正的行操作（看超话）反而没有视觉提示 | 行内改为「昵称 / UID / 状态 chip / 结果说明」四层；行尾改为 `ic_more_vert` 溢出菜单（查看超话清单 / 删除账号），**长按整行也开同一菜单**；整行点按仍进超话清单 | `item_account.xml`、`menu/item_account_actions.xml`（新建）、`HomeFragment.kt` | 误触删除的概率大幅下降；行内信息结构化，昵称/UID/状态各归其位 | 删除只换了入口，确认对话框与 `AccountStore.remove` 调用完全一致；回退=恢复 `btnDelete` |
| B4 | 状态 chip 化 | 原状态是「签到 3/5 · 超话 12 个 · 已签 10 · 待签 2 · 12:04」一条逗号长句，扫读时要数标点 | 已签/待签改成两个状态 chip（绿=已签、琥珀=待签，深色模式自动换深底浅字）；状态未知（未刷新/刷新中/失败/无超话）时退化成一行说明，并把"更新时间"单独成句 | `item_account.xml`、`values/styles.xml`、`drawable/bg_chip_done.xml`、`drawable/bg_chip_pending.xml`、`HomeFragment.kt`（`bindAccountStatus`） | "还有几个没签"变成 0.2 秒能看出的信息；异常状态不再伪装成正常数据 | 纯渲染逻辑；文案 key 新增、旧的保留 |
| B5 | 日志卡默认收起 | 固定 220dp 的日志窗始终占位，首屏一半被"没内容时的空日志"吃掉；但用户又不常看日志 | 卡头「运行日志 + 展开箭头 + 最近一行摘要」，默认收起；**任务开始时自动展开**（手动签到/自动签到/仅同步面板/查超话/补签/手动刷新超话）；页面打开时的自动刷新**不**展开 | `fragment_home.xml`、`HomeFragment.kt`（`setLogExpanded` / `renderLog`） | 首屏让位给主行动；真要看日志时点一下就有，且从不打断（仍只在贴底时自动滚动） | 滚动策略保持原语义（仅贴底才 `fullScroll`）；收起态下跳过滚动，回退=删掉 `logBody` 的 `gone` |
| B6 | 溢出菜单补"查看超话清单" | 原"点整行查超话"是隐藏手势，新用户不知道能点 | 菜单里显式提供"查看超话清单"，与点按整行等价 | `menu/item_account_actions.xml`、`HomeFragment.kt` | 功能可发现性提升，仍不增加常驻按钮 | 菜单项调用同一个 `showTopics(uid)` |

---

## 三、C. 图片页（信息密度最大的一页）

| 编号 | 改动点 | 目的（解决什么用户问题） | 具体改动 | 涉及文件 | 预期效果 | 风险与回退方式 |
|---|---|---|---|---|---|---|
| C1 | 6 张同权重卡 → 有主次的 5 段 | 原页面"图库/目录/目标/运行/日志"5~6 张一模一样的 filled 卡平铺，页面很长，首屏看不到"开始抓取" | 顺序固定为：① 图库入口横幅 ② 存储位置 ③ 抓取目标 ④ **抓取运行** ⑤ 日志；顶部保留一句说明 | `fragment_gallery.xml` | 首屏就能看到"目标 + 开始"，配置类信息上移但视觉变轻 | 控件 id 全部保留，回退=调整节点顺序 |
| C2 | 卡片视觉分级 | 6 张卡视觉权重相同，用户无法判断"哪里该动手、哪里只是设置" | 运行卡 → `Card.Primary`(elevated 带阴影)；任务卡 → `Card.Standard`(filled)；目录/日志 → `Card.Subtle`(outlined) | `fragment_gallery.xml`、`values/styles.xml` | "需要你操作"的卡自然浮起，设置项退到背景层 | 纯样式，逐卡可回退 |
| C3 | 图库入口改整行横幅 | 原来是一个 `Tonal` 小按钮"打开图库"，与页面里其它 4 个 Tonal/Outlined 按钮同权重 | 整行可点卡片：图片图标 + 「图库预览 / N 张图片」 + `chevron` 指示；点击热区从按钮扩大到整行 | `fragment_gallery.xml`、`GalleryFragment.kt`（`btnOpenGallery` 绑到整行卡片） | 更符合"列表→详情"的操作习惯，点起来更省力 | id 仍是 `btnOpenGallery`；回退=改回按钮 |
| C4 | 存储位置改 ListRow | 原"保存到相册目录"标题与路径和按钮挤在一行，长路径把按钮挤出屏幕 | `ListRow`：文件夹图标 + 标题「存储位置」+ 路径（次要文字，单行省略） + 「选择目录」TextButton | `fragment_gallery.xml`、`values/styles.xml` | 长路径不再挤压按钮；语义从"标题"变成"当前状态" | `tvDirPath` / `btnPickDir` 行为不变 |
| C5 | 目标行两个按钮 → 一个溢出菜单 | 原一行里有「起始日期」「移除」两个文字按钮 + 整行信息，一行 3 个点击目标，误触率很高 | 行内只留 昵称/UID/进度；行尾 `ic_more_vert` 菜单提供「起始日期 / 移除」；目标卡标题带数量 `抓取目标 (N)`，添加入口变成标题行的 TextButton | `item_crawl_target.xml`、`menu/item_crawl_target_actions.xml`（新建）、`fragment_gallery.xml`、`GalleryFragment.kt`（`showTargetMenu`） | 误触"移除"的概率显著下降；卡片标题直接告诉你配了几个目标 | 两个动作的代码路径（`pickSince` / 确认删除）完全沿用；回退=恢复两个按钮 |
| C6 | 运行区主次重排 | `switch` + 「抓取单条」Tonal + 「开始抓取」Filled 与「停止」Outlined **两个按钮并排**：主次不分，且"开始"和"停止"同时亮着，用户要判断该点哪个 | `开始抓取` 保持 Filled **全宽、独占一行**；**运行时该按钮隐藏、"停止"（同位置、独占一行、带 `ic_stop` 图标）显形**（`setRunningUi`）；「抓取单条微博」降为 TextButton（文案用「单条抓取」）；"签到后自动抓图"改成整行可点的 ListRow | `fragment_gallery.xml`、`drawable/ic_stop.xml`（新建）、`GalleryFragment.kt`（`setRunningUi`） | 任何时刻只有一个明确的下一步动作，不会出现"两个都能点"的犹豫；单条抓取作为低频功能不再抢主流程 | 两个按钮 id 保留，`crawlJob?.cancel()` 语义不变；回退=去掉 `setRunningUi` 调用 |
| C7 | 抓取日志默认收起 | 与 B5 同因：240dp 固定日志窗长期占位 | 卡头 + 最近一行摘要，默认收起；`start()` / `startSingle()` 时自动展开 | `fragment_gallery.xml`、`GalleryFragment.kt`（`setLogExpanded`） | 首屏更短，主行动更靠上 | 滚动策略不变（仅贴底自动滚） |

---

## 四、D. 设置页

| 编号 | 改动点 | 目的（解决什么用户问题） | 具体改动 | 涉及文件 | 预期效果 | 风险与回退方式 |
|---|---|---|---|---|---|---|
| D1 | 「保存」不再拉满整行 | 全宽 Filled 按钮在中文语境里像"下一步/继续"这类流程推进，而这里只是提交一个可选配置，容易误以为必填 | 仍是全页唯一 Filled（层级正确），但改为 `wrap_content` + 右对齐（`layout_gravity="end"`），最小高度 48dp | `fragment_settings.xml` | 提交动作明确、不抢"必须完成"的暗示；右对齐符合表单提交习惯 | 只是布局属性，回退=改回 `match_parent` |
| D2 | 表单校验保留 | 配置错会直到同步时才失败，很难定位 | 未改：`etUrl.error = e.message` 全部保留 | `SettingsFragment.kt`（未改） | 输入即校验的行为不变 | 不涉及 |
| D3 | 行为开关改 ListRow | 原来开关文案（含括号补充说明）整句塞在 `MaterialSwitch` 上，一行长句、开关被推得很远，扫读困难 | 每个开关一行：标题（BodyLarge）+ 说明（BodySmall 次要色）+ 右侧 `MaterialSwitch`；**整行可点即可切换**（`rowAutoOnOpen` / `rowCrawlGif`） | `fragment_settings.xml`、`SettingsFragment.kt` | 说明文字下沉为次要层级，"开关状态"与"它管什么"分成两行，扫读更快；不用精确点到滑块 | 开关 id 与 `setOnCheckedChangeListener` 逻辑不变；`toggle()` 会走同一监听器 |
| D4 | 关于卡补"配色说明" | 用户不知道深浅色/取色从哪来，也不知道 App 只申请了哪些权限 | 关于卡用 `Card.Subtle` + 版本/权限（沿用 `settings_about_body`）+ 新增一行「界面配色跟随系统深浅色；Android 12 及以上支持跟随壁纸取色」 | `fragment_settings.xml`、`values/strings.xml` | 减少"为什么我的界面变色了"的疑问；信息集中在一张弱化卡里 | 纯文案新增（`settings_appearance_note`） |

---

## 五、E. 图库与预览

| 编号 | 改动点 | 目的（解决什么用户问题） | 具体改动 | 涉及文件 | 预期效果 | 风险与回退方式 |
|---|---|---|---|---|---|---|
| E1 | 空态从"一行灰字"升级 | 原空态只有一句居中的灰字"目录里还没有图片\n先在下方配置目标并开始抓取"，用户不知道该回哪 | 图标 + 说明 + 明确主行动「去配置抓取目标」（点击 `finish()` 回到图片页配置） | `activity_gallery_browse.xml`、`GalleryBrowseActivity.kt` | 空页面不再像"加载失败"，给出唯一明确的下一步 | 不新增跨 Activity 行为，只是结束当前页 |
| E2 | 网格单元格等比 + 自适应列数 | `item_gallery_grid` 固定 120dp 高，平板/横屏会被拉成细长条，缩略图被裁得只剩一条 | 按 RecyclerView 实测宽度算列宽（≥600dp 用 4 列，否则 3 列），单元格高度 = 列宽 − 两侧 margin → 正方形；XML 里的 96dp 仅作首帧兜底 | `item_gallery_grid.xml`、`GalleryBrowseActivity.kt`（`applyGridMetrics`）、`values/dimens.xml` | 任意屏幕比例下都是正方形缩略图，一行能放的信息量随屏幕变大 | 缩略图仍由 `ThumbLoader` 后台采样解码 + LruCache，未改并发/缓存；回退=去掉 `applyGridMetrics` |
| E3 | 预览页文字按钮 → 图标按钮 | 底部三个文字按钮（分享/系统打开/删除）在横屏或长文件名时互相挤，且三个同权重，删除这种破坏性动作和"分享"长得一样 | 48dp 图标按钮：分享（primary 色）/ 打开（白）/ 删除（浅红 `on_preview_danger`）；文案转 `contentDescription`；删除确认对话框、分享失败提示、删除后回退索引语义全部不动 | `activity_image_preview.xml`、`values/colors.xml` | 图片可视面积变大，破坏性动作有颜色预警，TalkBack 仍可读 | `btnShare` / `btnOpen` / `btnDelete` id 与 `ImagePreviewActivity` 代码零改动 |
| E4 | 浏览页工具栏不新增菜单项 | 避免引入跨应用 Intent（权限/兼容风险） | `menu/gallery_browser.xml` 保持仅「刷新」；只把刷新图标沿用既有 `ic_refresh` | `menu/gallery_browser.xml`（未改） | 行为与权限面和改版前完全一致 | 不涉及 |

---

## 六、F. 可用性基线

| 编号 | 改动点 | 目的（解决什么用户问题） | 具体改动 | 涉及文件 | 预期效果 | 风险与回退方式 |
|---|---|---|---|---|---|---|
| F1 | 触控目标 ≥ 48dp | 图标按钮、文字按钮高度不足 48dp，单手操作时容易点空 | `Widget.Wcm.IconButton` 固定 48×48；所有按钮样式 `minHeight=48dp` 且 `insetTop/insetBottom=0`（视觉高度=触控高度）；列表行 `minHeight=48dp`（账号行、目标行、开关行、日志卡头） | `values/styles.xml` + 所有布局 | 所有可点元素都满足 Material 无障碍基线 | 只是尺寸，回退=删掉 `minHeight` |
| F2 | 深色模式逐项核对 | 改造前"深底深字"风险点：次要文字（写死灰）、chip、日志等宽文本、卡片标题 | 全部文字改用 `?attr/colorOnSurface` / `?attr/colorOnSurfaceVariant`；chip 有 `values-night` 深底浅字；日志 TextView 用 `?attr/colorOnSurfaceVariant`；预览页是恒黑背景，因此用固定浅色前景（`on_preview_surface*`）而不是主题色 | `values/styles.xml`、`values-night/colors.xml`、全部布局 | 深色下没有"深底深字"；预览页在浅色模式下图标也不会变黑看不见 | 逐项可回退到具体颜色 |
| F3 | 横屏/大屏不塌 | 横屏时卡片全宽 + 220dp 固定日志窗 ⇒ 一屏只能放一张卡；平板网格被拉长 | `values-land/dimens.xml` 压缩日志窗（220→132dp、240→120dp、超话清单 320→200dp）；网格自适应列数（E2）；卡片本身 `match_parent` 不设固定高度 | `values-land/dimens.xml`（新建）、`fragment_home.xml`、`fragment_gallery.xml`、`dialog_topic_list.xml`、`item_gallery_grid.xml` | 横屏/平板上"主行动 + 日志"同屏可见，缩略图不变形 | 纯资源覆写，回退=删除 `values-land` |

---

## 七、与产品经理调研报告（`docs/research/batch-image-crawl-and-ui.md`）的对齐情况

只对齐其中的 UI 条目（抓取/落盘/权限类建议本轮不施工）：

| 报告编号 | 报告建议 | 本轮处置 |
|---|---|---|
| S18 | `fragment_settings.xml` 全面令牌化 + 卡片分级 + 保存按钮降权 | **已做**：见 D1/D3/D4；该文件已无裸 `dp`/`sp` 数字（分隔线 1dp 除外） |
| S19 | 修正 `colorPrimary` 白字对比 3.49:1 | **已做（采方案 A 并细化深色态）**：见 A7。注意调研报告的初版数值偏乐观（3.56 / 5.27），本文件已统一为实算值 3.49 / 5.32 |
| S20 | 图库页「单一主行动」+ 运行中单按钮状态机 | **已做**：见 C6（开始/停止互斥显示），三个 TextButton 已按主次重排，删减型操作统一走溢出菜单 |
| S21 | 字号层级拉到 1.28×（SectionTitle → TitleLarge 18sp） | **已做**：见 A5（`Widget.Wcm.Text.SectionTitle` → `TextAppearance.Material3.TitleLarge`）。页面级大标题本轮不适用：三个页面都是"卡片堆叠"结构，没有独立的页面标题栏（顶部没有 AppBar），加 HeadlineSmall 只会多一层占位 |
| S22 | 动态取色开关 + Android 16 主题图标 monochrome 层 | ①**monochrome 层已存在**：`mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml` 里本来就有 `<monochrome android:drawable="@drawable/ic_launcher_foreground"/>`（前景是透明底上的白色对勾，正好可作单色遮罩），无需改动；②**动态取色开关不做**：`WcmApp` 早就在无条件调用 `DynamicColors.applyToActivitiesIfAvailable(this)`（见 A6），把它改成"设置页开关 + 默认关"需要新增偏好项、改 `Application` 装配、并在 4 个 Activity 里处理主题重建（`recreate()` 会带来一次界面重建/闪烁），收益是"保住品牌红"，风险是给一个纯外观项引入装配链改动 —— 判定不做本轮的取舍。并且 M3 动态调色板本身即按对比度生成，与 A7 的无障碍修复不冲突 |

---

## 八、未采纳与原因

| 候选改动 | 为什么没做 |
|---|---|
| 1. 动态取色（Material You）开关 | **已经有实现**：`WcmApp.onCreate()` 里就是 `DynamicColors.applyToActivitiesIfAvailable(this)`，README 第 30 行的"Android 12+ 跟随系统取色"与代码一致，无需修改。再叠加一个"设置页开关"需要改 `Application`/主题装配链，收益低、风险高（易引发主题重建闪烁）。本轮改为**把布局里所有固定色换成 `?attr/` 主题属性**——这样品牌色模式与壁纸取色模式都能正确工作，比加开关更根本。 |
| 2. Compose / 新依赖 / 动画库 | 明确约束：不引入新依赖、不用 Compose。展开收起只用 `visibility` + 箭头旋转 180°，不加过渡动画（避免"为酷而酷"）。 |
| 3. 折叠动画（Transition / ValueAnimator） | 卡片高度动画会牵连 `ScrollView` 内的滚动位置计算，收益低于复杂度；当前即时切换在低端机上更稳。 |
| 4. 预览页加"在系统相册中打开" | 需要跨应用 Intent + SAF 授权，按约束保持"刷新"一个动作，避免权限与兼容风险。 |
| 5. 大屏双栏（列表-详情）/ 底部导航换 NavigationRail | 需要 Navigation 组件依赖与两套布局，超出"简洁流畅"的范围；当前用 `values-land` 的尺寸覆写 + 网格自适应已解决"塌陷"问题。 |
| 6. 顶部 AppBar / 折叠工具栏 | 三个页面都是"单一主行动 + 列表"，加 AppBar 只会多一条横线占位，反而降低首屏信息量。 |
| 7. 全局"深色模式手动开关" | 系统已有深浅色；再多一个 App 内开关会与系统设置冲突（且需要改 `Application` 主题装配）。 |

### 说明：四个既有字符串在重排后不再被引用
`btn_add`（「＋ 添加账号」→ 改为图标按钮 + `cd_add_account` 无障碍文案）、
`crawl_add_target`（长文案「＋ 添加博主（UID / 主页链接 / 个性域名）」→ 标题行「添加」+ 弹窗内提示）、
`single_btn`（长文案 → 按钮文案改用 `single_title`「单条抓取」）、
`home_topics_summary_fmt`（逗号长句 → 被两个状态 chip 取代）。
按"既有 key 语义不许改"的约束，**key 一律保留未删除**，仅不再引用；这些是 lint `UnusedResources` 的
Warning（非 error）。`crawl_stop` 继续复用（停止按钮文案），因此没有新增 `crawl_stop_short`。

---

## 九、全局一致性审查结论

1. **id ↔ Kotlin 对应**：`btnDelete`（账号行）、`btnTargetSince` / `btnTargetDelete`（目标行）三处旧 id 已随布局重排删除，对应 Kotlin 绑定同步改为 `btnAccountMore` / `btnTargetMore` + `PopupMenu`；其余 id 全部原样保留（`btnCheckin`、`btnSync`、`btnAdd`、`btnRefreshTopics`、`tvSummary`、`accountList`、`logScroll`、`logView`、`btnPickDir`、`btnAddTarget`、`btnSinglePost`、`btnCrawlStart`、`btnCrawlStop`、`btnOpenGallery`、`switchCrawlAuto`、`tvDirPath`、`crawlTargetList`、`tvGalleryCount`、`crawlLog`、`crawlLogScroll`、`etPanelUrl`、`etPanelToken`、`btnSavePanel`、`switchAutoOnOpen`、`switchCrawlGif`、`tvAbout`、`previewPager`、`btnShare`、`btnOpen`、`previewRoot`、`galleryGrid`、`galleryEmpty`、`galleryToolbar`）。
2. **无魔法数遗留**：全部 12 个布局文件中，`textSize="..sp"` 出现次数为 **0**（字号统一由 `textAppearance`/样式定义），`padding="..dp"` / `marginTop="..dp"` 仅剩 2 处 `layout_marginTop="0dp"`（用于抵消样式里默认的卡内间距，属于"清零"而不是"定值"）。
3. **深浅两态**：所有前景色走 `?attr/`；自定义状态色在 `values-night` 有覆盖；预览页因恒黑背景改成固定浅色前景（已在 F2 说明）。
4. **无未使用的新增资源**：新建的 11 个 vector（`ic_add` / `ic_more_vert` / `ic_expand_more` / `ic_chevron_right` / `ic_sync` / `ic_stop` / `ic_share` / `ic_open_in_new` / `ic_delete` / `ic_image` / `ic_folder`）逐个 grep 均有引用（1~2 处）；两个 shape（`bg_chip_done` / `bg_chip_pending`）各有引用；`dimens` 逐项统计无 0 引用项（候选的 `icon_size_small` 已删除）；`styles.xml` 中未被使用的变体（Tonal / Danger / ListRow.Static）已在定稿时删除；删除写死色后无引用的 `text_secondary` 已从 `values` 与 `values-night` 一并清除。
5. **emoji / 引号**：新增代码与文案无 emoji；字符串全部使用 ASCII 直双引号；用户可见文案全中文。
6. **构建与测试**：见下节。
