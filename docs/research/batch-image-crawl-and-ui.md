# 调研报告：批量抓图落盘与主题/按钮层级设计

> 调研对象：`com.wcm.checkin`（微博超话签到 + 按博主批量抓原图 + 图库浏览，Kotlin/View，minSdk 23 / targetSdk 36）
> 调研时间：2026-10-27　调研方式：GitHub API（star 数为当日快照）+ 官方文档原文抓取 + 关键仓库源码抽查
> 说明：所有条目均给出可点开的 URL；标注「二手」的是基于源码的第三方整理（DeepWiki），未逐行复核的会明确写「未验证」。

---

## 一、结论摘要

1. **最该改的第一件事是「别加权限」**：要「存进系统相册」不需要任何存储权限 —— `MediaStore` 插入自己的媒体在 Android 10+ 零权限即可（`IS_PENDING` → `RELATIVE_PATH`）。反过来 `MANAGE_EXTERNAL_STORAGE` 只对文件管理器/备份/杀毒等类别开放，本 App 申请必被拒。
2. **第二件事是 SAF 的性能债**：`Crawler.ContentSink.loadExisting()` 每次开跑都 `query` 整个用户目录、每个文件一次 `DocumentsContract.createDocument` IPC；真实项目（LoliSnatcher）因为同类做法「文件多时巨大延迟」直接把 SAF 存在性检查注释掉了。应以 `CrawlIndex` 为权威、目录扫描降级为兜底。
3. **第三件事是 UI 收尾而不是重做**：调研起始时项目已有 v0.6 令牌层（`styles.xml` 卡片/按钮/列表/chip 全套 + `dimens.xml` 4dp 栅格 + `touch_min=48dp`），缺口只有 4 处：`fragment_settings.xml` 未令牌化、`colorPrimary=#F54868` 上白字对比 **3.49:1**（M3 filled 按钮 14sp，要求 4.5:1）、图库页「开始/停止」双按钮竞争、区块标题与正文仅差 1.14×。**这 4 处已由 v0.6 UI 刷新落地（见 §四「本轮实施状态」）**；唯一遗留是「动态取色做成可开关」未做（改默认关会闪一次界面，收益仅是保住品牌红）。
4. 抓取侧的健壮性缺口集中在「失败不可解释」：`ContentSink.save()` 吞掉所有异常只返回 `false`；退避是线性 `1200ms×(n-1)`；`RunState` 只有粗状态，进度只能靠翻日志。
5. 顺手提醒：`KurtBestor/Hitomi-Downloader`、`FooIbar/EhViewer` 目前被 GitHub DMCA 屏蔽（API 返回 `reason: dmca`），文档里引用了它们的请换活链或用 `xiaojieonly/Ehviewer_CN_SXJ`、`GopeedLab/gopeed` 替代。
6. 全部 22 条建议中 **P0 共 7 条**：S01（零权限基线书面化，不动逻辑）、S04/S05/S10/S16（4 条动抓取内核：授权探活、清单缓存、单写者锁、删除同步）、S18/S19（资源与主题，**已完成**）。也就是说剩下真正待决策的 P0 只有 5 条，且都集中在「落盘正确性」而非「功能新增」。

---

## 二、GitHub 项目与资料调研

### A. 批量抓取图片并保存到本地（PC 视角）

| 项目 | URL | star 量级 | 语言/平台 | 与本项目相关的关键实现点 |
|---|---|---|---|---|
| `mikf/gallery-dl` | https://github.com/mikf/gallery-dl | ≈2.0 万（20,256，存续 12 年） | Python / CLI 跨平台，GPL-2.0 | ①站点适配器（extractor）与下载器分离，站点表含 **Weibo**；②`archive` 机制把已完成项记入归档（file / sqlite3 / **PostgreSQL** 三种后端）避免重复下载；③文件名模板 `{category}_{id}_{title}.{extension}`，目录也模板化。注：文档站 readthedocs 已 404，主仓已迁 Codeberg（https://codeberg.org/mikf/gallery-dl，实测 200） |
| `KurtBestor/Hitomi-Downloader` | https://github.com/KurtBestor/Hitomi-Downloader | ≈2.2 万（README 快照；**当前被 DMCA 屏蔽**，API 返回 `reason: dmca`，2026-03-30） | Python + GUI（桌面） | ①单任务最多 **24 线程**下载加速，另有全局限速；②任务可「易组织/批量」管理，剪贴板监听自动建任务；③站点表同样含 Weibo（说明微博图站抓取模式已被成熟工具覆盖） |
| `GopeedLab/gopeed` | https://github.com/GopeedLab/gopeed | ≈2.7 万（27,204） | Go 内核 + Flutter UI，含 **Android** 端 | ①任务模型完整：暂停/续传/**重试**/批量操作/按状态筛选/**重启后恢复任务**；②多连接 HTTP 分片 + 并发任务；③Android 通过 `gomobile bind` 复用同一内核（Go 逻辑 + Flutter UI 的分层对「抓取内核与 UI 解耦」有参考价值） |

### A'. 批量抓取图片并保存到本地（Android 视角）

| 项目 | URL | star 量级 | 语言/平台 | 与本项目相关的关键实现点 |
|---|---|---|---|---|
| `NO-ob/LoliSnatcher_Droid` | https://github.com/NO-ob/LoliSnatcher_Droid | ≈700（699，AGPL-3.0） | Dart/Flutter，Android | ①`snatch_handler.dart`：`queuedList` 队列 + 每任务 `SnatchItem(cooldown)` 限速 + `failedItems/existsItems/cancelledItems` 三态重试清单 + `_progressStuckTimer`（进度卡住即判定停滞）；②`image_writer.dart`：**先看文件是否存在/DB `isSnatched` 标记**再下载，命中直接跳过；③`services/saf_file_cache.dart` + SAF 旁挂 json，且源码里把「SAF 目录存在性检查」显式注释掉，注释原文：*"disabled for now, because it causes huge delays if user has a lot of saved files"* —— 这是「不要靠列目录判重」最硬的实证 |
| `deckerst/aves` | https://github.com/deckerst/aves | ≈5,500（5,471，BSD-3） | Dart/Flutter + Kotlin，Android 相册 | ①媒体来源分三类：MediaStore 内容、App 私有 vault、文件直读；②`ImageProvider` 文件操作有**撞名三策略 RENAME / REPLACE / SKIP**（`resolveTargetFileNameWithoutExtension`）；③按 API 分级权限矩阵：21–28 用 `WRITE_EXTERNAL_STORAGE`、30–32 用 `MediaStore.createWriteRequest()`、33+ 用 `READ_MEDIA_*`（来源：DeepWiki 对源码的整理，二手） |
| `EhViewer-NekoInverter/EhViewer` | https://github.com/EhViewer-NekoInverter/EhViewer | ≈3,700（3,743） | Kotlin/Java，Android | ①下载任务按画廊组织、断点续传；②`libarchive` 支持压缩包内浏览；③**M3 + Dynamic Color** 已在长期维护分支落地（与本项目主题改造直接相关） |
| `xiaojieonly/Ehviewer_CN_SXJ` | https://github.com/xiaojieonly/Ehviewer_CN_SXJ | ≈2.8 万（28,308） | C/Java，Android | 活跃的 EhViewer 中文分支（原 `seven332/EhViewer` 已归档：9,879★、2019 年后停更），可作为「活跃维护的下载型客户端」参考样本 |
| `seven332/EhViewer`（原始版，已归档） | https://github.com/seven332/EhViewer | ≈9,900（9,879，archived） | Java | 下载目录与「已下载」状态持久化的早期范式，仅作历史参考，不宜照抄 |

### B. 去重策略

| 项目/资料 | URL | 规模 | 做法 | 对本项目的意义 |
|---|---|---|---|---|
| `immich-app/immich` | https://github.com/immich-app/immich ／ 文档 https://immich.app/docs/guides/database-queries/ | ≈11.7 万（117,362） | ①每张资产存 **SHA-1 checksum**，同库内唯一，重复检测就是 checksum 相等；②上传 API 支持 `x-immich-checksum` 头，**在上传前**就能判「已存在」返回 `status: duplicate`；③重复项可分组（`duplicateId`），检测范围按库隔离 | 与本项目 L3（`Hashing.sha256Hex128`）同思路，但 Immich 把 hash **前置**到传输之前；本项目现在是「下完才算」，可把已知 picId→hash 存进索引，实现「先判后下」 |
| `idealo/imagededup` | https://github.com/idealo/imagededup | ≈5,700（5,678，Apache-2.0） | 提供 **PHash / DHash / AHash / WHash / CNN** 五种方法（README 已核实关键词），输出相似对 | 感知哈希的代表实现；对本项目只在「同图重压缩/裁切/长图切分」场景有价值，见建议 S07 |
| `mikf/gallery-dl` archive | 同 A 组链接 | — | 已下载项写入归档文件/DB，二次运行直接跳过（比「扫目录」快且不受撞名影响） | 与本项目 `CrawlIndex`（NDJSON 追加 + 压缩重写）近似，说明「本地索引优于目录枚举」是业界共识 |
| SQLite WAL 官方说明 | https://sqlite.org/wal.html | — | WAL 模式读写并发、`synchronous=NORMAL` 的取舍 | 若后续把 NDJSON 索引换成 SQLite（唯一索引 + WAL），这是权威依据；当前 NDJSON 方案已够用，不建议本次改 |

### C. 保存到系统相册（Android）

| 资料 | URL | 关键点（原文已核验） |
|---|---|---|
| 官方：访问共享存储中的媒体 | https://developer.android.com/training/data-storage/shared/media | ①写入用 `ContentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)`，`values` 带 `DISPLAY_NAME` / `MIME_TYPE` / `RELATIVE_PATH`；②写入期间置 `MediaStore.Images.Media.IS_PENDING = 1`（其他 App 不可见），写完置 0 发布；③App **只写自己创建的媒体**时不需任何存储权限；④要改/删**别人**的媒体才需要 `MediaStore.createWriteRequest()` / `createDeleteRequest()` 让用户确认 |
| 官方：Android 14 部分照片访问 | https://developer.android.com/about/versions/14/changes/partial-photo-video-access | ①manifest 三件套：`READ_EXTERNAL_STORAGE(android:maxSdkVersion="32")` + `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` + `READ_MEDIA_VISUAL_USER_SELECTED`；②用户选「选择照片」后 App 只拿到被选中的 URI，且**授权会过期**（"your app's access to the selected photos and videos expires eventually"），必须随时处理「拿不到任何 URI」；③官方明确要求：不要缓存 MediaStore 查询结果，回前台（`onResume`）重新查 |
| 官方：SAF 与持久化授权 | https://developer.android.com/training/data-storage/shared/documents-files | ①`takePersistableUriPermission()` 后**目录被移动/删除仍会失效**，需要重新申请；②Android 11+ 用 `ACTION_OPEN_DOCUMENT_TREE` **不能选**：内部存储根目录、厂商认定可靠 SD 卡根目录、**Download 目录**；③`ACTION_CREATE_DOCUMENT` 不会覆盖同名文件，系统会自动加 ` (1)`；④文档明示「通过 SAF 访问时 App 性能可能下降」 |
| `deckerst/aves` 权限实现 | https://deepwiki.com/deckerst/aves/3-media-management （二手，基于源码整理） | 按 API 分级：21–28 传统权限 / 29 分区存储 + legacy 标记 / 30–32 `MediaStore.createWriteRequest()` 批量写 / 33+ 细分媒体权限；受限目录（`Download`、`Android`）单独处理 |

### D. 权限处理

| 资料 | URL | 关键结论（已核验） |
|---|---|---|
| Google Play：所有文件访问权限政策 | https://support.google.com/googleplay/android-developer/answer/10467955?hl=zh-Hans ／ 英文：https://goo.gle/policy-storage-usecases | ①`MANAGE_EXTERNAL_STORAGE` 仅限**文件管理器 / 备份恢复 / 杀毒 / 文档管理 / 设备端搜索 / 磁盘加密 / 换机迁移**等类别；②「只有在无法有效使用 SAF 或 MediaStore 时才可申请」；③需填权限声明表并经审核，不符合即下架 |
| 官方：管理存储设备上的所有文件 | https://developer.android.com/training/data-storage/manage-all-files | 同上，并给出 lint 警告与 `adb shell appops set --uid <pkg> MANAGE_EXTERNAL_STORAGE allow` 的调试方式（仅供本地测试，不能替代审核） |
| 官方：Android 14 部分照片访问 | 同 C 组 | 未做部分授权适配时系统会把 App 跑在**兼容模式**（表：SDK 33 未声明 → 由系统控制；SDK 34 声明 `READ_MEDIA_VISUAL_USER_SELECTED` → 可控） |
| 官方：Android 15 行为变更（边到边） | https://developer.android.com/about/versions/15/behavior-changes-15 | 「Apps are edge-to-edge by default on devices running Android 15 if the app is targeting Android 15 (API level 35)」，`setNavigationBarColor` / `navigationBarColor` 已废弃 —— 本项目 targetSdk 36 已强制边到边，`MainActivity.applyEdgeToEdgeInsets()` 是对的方向 |
| 官方：Android 16 行为变更 | https://developer.android.com/about/versions/16/behavior-changes-all | ①**JobScheduler 配额收紧**，明确「This change impacts tasks scheduled using WorkManager, JobScheduler, and DownloadManager」，可用 `WorkInfo.getStopReason()` / `JobParameters.getStopReason()` / `JobScheduler#getPendingJobReasonsHistory` 排查 —— 直接影响本项目「每 6 小时保活轮换 Cookie」的 worker；②**Android 16 QPR2 起系统自动给 App 图标上主题**，App 可在自适应图标里加 `monochrome` 层控制效果 |
| 官方：无障碍（触控目标） | https://developer.android.com/guide/topics/ui/accessibility/apps | 原文：「we recommend that each interactive UI element have a focusable area, or touch target size, of **at least 48dp×48dp**」 |

**本项目的选择评估（「零存储权限 + SAF 目录」）**

- 优势：Play 审核零风险（无 `WRITE_EXTERNAL_STORAGE`、无 `MANAGE_EXTERNAL_STORAGE`、无 `READ_MEDIA_*`）；用户能自选落盘位置（含 SD 卡）；卸载 App 不带走用户图；不需要处理 MediaStore 的 `IS_PENDING` 与厂商 ROM 刷新差异。
- 劣势：①**系统相册看不到**，微信/小红书选图时无法直接选到；②SAF 每次 `createDocument` 是一次 IPC，几万文件时「列目录 + 单文件写」都变慢（LoliSnatcher 已踩坑）；③授权会失效且用户不易理解；④Android 11+ 选不到 `Download` 根目录。
- 若要「真正进系统相册」，需要补的只有一件事：实现一个 `MediaStore` 写入实现（`IS_PENDING`+`RELATIVE_PATH`），**不需要补任何权限**。只有当 App 还想「把相册里别的图读回来浏览」时，才需要 `READ_MEDIA_IMAGES` + `READ_MEDIA_VISUAL_USER_SELECTED`，并处理部分授权。

### E. 页面按钮与主题设计相关资源

**E1 官方规范与实现范式**

| 资料 | URL | 可直接借鉴的条目 |
|---|---|---|
| M3 全部按钮总览（层级规则） | https://material.io/components/all-buttons | ①高强调（FAB/filled）→ 中强调（tonal/elevated/outlined）→ 低强调（text/icon button）三级；②原文：「**Each screen should contain a single prominent button for the primary action**」；③原文：「Avoid placing a button below another button if there's space to place them side-by-side」 |
| M3 按钮规范细则 | https://m3.material.io/components/buttons/guidelines | ①filled「should be used sparingly, ideally for only one action on a page」；②tonal 用 secondary 色映射，「lower-priority button requires slightly more emphasis than an outline」；③outlined 是「medium-emphasis」，与 filled 搭配表示替代动作；④text「lowest priority actions」，常用于卡片与对话框右下 |
| `material-components-android` | https://github.com/material-components/material-components-android | ≈1.7 万（17,434） | `Widget.Material3.Button.*` / `Widget.Material3.CardView.{Elevated,Filled,Outlined}` 的现成父样式 —— 本项目 `styles.xml` 已经在正确继承它们，继续沿用即可 |
| `material-color-utilities` | https://github.com/material-foundation/material-color-utilities | ≈2,300（2,295，Apache-2.0） | 目录里含 **`kotlin/`**（另有 dart/java/swift/cpp/typescript），可直接在 Kotlin 里由种子色算 tonal palette，用于「检查/生成满足对比度的 colorPrimary」 |
| Material Theme Builder | https://material-foundation.github.io/material-theme-builder/ | — | 输入 `#F54868` 即可导出 M3 全套 color roles 与 XML/Compose 代码，用来定「brand 只做 accent，colorPrimary 取深一档」的方案 |
| `android/nowinandroid` | https://github.com/android/nowinandroid | ≈2.2 万（21,970，Kotlin） | ①`NiaTheme(darkTheme, dynamicColor, ...)`：动态取色做成**可选参数**（默认关）；②`Type.kt` 自定义 type scale（labelLarge 14sp 给按钮、labelMedium 12sp 给导航、titleLarge 22sp 给页头）；③卡片/按钮全部走 design-system 模块组件，业务页面不出现魔法数 —— 与本项目 `styles.xml` 的思路完全一致 |

**E2 设计技能/提示词资源（GitHub 真实存在）**

| 仓库 | URL | star 量级 | 语言 | 能直接借鉴到本项目的条目 |
|---|---|---|---|---|
| `BehiSecc/awesome-claude-skills` | https://github.com/BehiSecc/awesome-claude-skills | ≈1.0 万（10,312） | Markdown | 设计/AI 编码类 skill 的索引清单，用来找「设计评审」「UI 规范」类 skill 的入口 |
| `plugin87/ux-ui-agent-skills` | https://github.com/plugin87/ux-ui-agent-skills | ≈3,000（3,033，MIT） | JS | ①**52 条客观门禁**（含 target size、WCAG 各状态对比度、reduced motion、280px 横向溢出、token-by-intent）；②「slop 体检」反例清单：*单一圆角 + 单一阴影 + 破坏性动作却用主色填充*；③原文门禁示例：`button.icon-btn is 15.3x16 (min 24x24)`、*biggest heading 24px vs 14px body = 1.7x, not a display scale* |
| `Owl-Listener/designer-skills` | https://github.com/Owl-Listener/designer-skills | ≈3,100（3,126） | Markdown | 273 个设计类 skill、33 个插件包（含设计评审、排版、组件规格类），可按需只装其一 |
| `carmahhawwari/ui-design-brain` | https://github.com/carmahhawwari/ui-design-brain | ≈900（902） | — | 面向 AI agent 的组件最佳实践知识库，适合做「卡片/列表/表单」的对照检查表 |

---

## 三、与本项目的差距分析

| 维度 | 业界主流做法 | 本项目现状（已核实到源码/资源） | 差距等级 |
|---|---|---|---|
| 落盘目标 | 相册类 App 走 MediaStore（可见）+ SAF（可选自定义位置），二者可切换 | 只有 SAF 单目录，零权限（`GalleryFragment.pickDir` → `OpenDocumentTree` + `takePersistableUriPermission`），相册不可见 | 大（功能缺口） |
| 权限模型 | 按需最小化：写入用 MediaStore 零权限；读取才申请 `READ_MEDIA_*` + 处理部分授权 | manifest 仅 `INTERNET` + `ACCESS_LOCAL_NETWORK`，零存储权限 —— 方向正确，但没有「读取侧」预案 | 小（做对了，缺文档与预案） |
| 去重 | 归档/DB 记录 + 内容 hash（Immich 甚至上传前用 `x-immich-checksum` 预判）+ 可选感知哈希 | 三层：L1 文件名规范化（`AssetName.canonical`）、L2 索引 picId/postId/slot/kind（`CrawlIndex`，NDJSON）、L3 流式 SHA-256 取 128bit | 中（已有骨架，缺「先判后下」与 hash 反查） |
| 目录列清单 | 以本地索引为权威，目录枚举只在「导入/校验」时做 | `ContentSink.loadExisting()` 每次开跑全量 `query` 目录，并维护 `names` + `canonicals` 两份 HashMap | 大（性能债，已被同类项目实证） |
| 断点续跑 | 游标 + 覆盖区间 + 待重试队列 + 任务可恢复 | `CrawlCursor` / `CrawlPlanner`（oldestCoveredAt/newestAt/reachedBottom/since/待重试），`Budget.NEW`/`Budget.RETRY` 预算 | 小（设计已到位） |
| 失败可解释性 | 写入结果分类（撞名/空间不足/IO）+ 指数退避 + 停滞检测 | `save()` catch 全部异常返 `false`；退避线性 `1200ms×(n-1)`，`DOWNLOAD_ATTEMPTS=3`；无停滞检测 | 中 |
| 并发与限速 | 有限并发（2–4）+ 请求间随机间隔 + 单写者串行落盘 | 逐张串行；请求间 `delay(Random(600,900))` 已有；写目录无显式互斥（增量任务与「抓取单条」可能并发进入同一 treeUri） | 中 |
| 可观测性 | 结构化进度（计数/字节/耗时/当前目标）+ 系统侧停止原因 | `CrawlRunner._state: StateFlow<RunState>` 仅粗状态，细节靠日志文本 | 中 |
| 图库渲染 | LruCache 按可用内存比例 + 分页 + 预取窗口 | `ThumbLoader` 采样解码 + LruCache（上限未按内存比例），`GalleryFiles.listImages` 全量遍历 | 中 |
| 主题令牌 | design-system 层统一 token，页面零魔法数 | `styles.xml`（卡片三级、**按钮三档 Primary/Secondary/Text** + 48dp 图标按钮、列表行、chip、日志头）+ `dimens.xml`（4dp 栅格、`touch_min=48dp`）**已建立，三页已全覆盖** | 无（已达标） |
| 设置页令牌化 | 同上级 | 调研起始时 `fragment_settings.xml` 未令牌化（硬编码 dp/sp、`materialCardViewFilledStyle` ×3）—— **本轮已修复**，现 grep 裸 dp / `textSize=` / `@color/text_secondary` / `materialCardViewFilledStyle` 均零命中 | 无（已修复） |
| 对比度 | 正文 ≥4.5:1，大字号 ≥3:1 | 调研起始 `colorPrimary=#F54868` 白字 **3.49:1**（不达标）—— **本轮已按「日夜分离」修复**：浅色 `brand_dark #C4344E`（白字 **5.32:1**），深色保持 `brand` + `colorOnPrimary=#1C1B1F`（亮红压深底 **4.90:1**）。深色**不能**改深红：`#C4344E` 压 `#1C1B1F` 仅 **3.22:1**，TextButton 文字会糊 | 无（已修复，且修正了「两态都压深」的初版判断） |
| 动态取色 | Android 12+ 可选开关，默认关 | **调研起始即已无条件启用**（`WcmApp.onCreate` → `DynamicColors.applyToActivitiesIfAvailable`）。改为「开关 + 默认关」需新增偏好项 + Application 装配 + 4 个 Activity 主题重建，成本大于收益，**本轮不做**（理由见 `docs/design/ui-refresh-rationale.md` §7） | 小（有意保留现状） |
| 按钮层级 | 一页一个高强调主行动，其余降级 | 首页已达标（filled 签到 + outlined 同步 + text 刷新）；**图库页**有 3 个 TextButton + 「开始/停止」同权重横排竞争 | 中 |
| 空态/加载态 | 空态给「下一步行动」，加载给骨架/占位 | 有 `Widget.Wcm.Text.Empty` 文本空态，无图标+行动、无加载骨架 | 小 |

---

## 四、针对本项目的优化建议

> 约定：「改动业务逻辑」= 是否触及签到/抓取/索引的既有行为；标「否」的可独立施工、灰度发布。
> 优先级：P0 必须做（含正确性/无障碍硬指标）；P1 应该做；P2 可延后。工作量 S ≤ 0.5 天，M ≈ 1–2 天，L ≥ 3 天。

### 4.1 权限与存储目标

| 编号 | 建议 | 现状问题 | 具体做法（类名/API/文件落点） | 预期收益 | 优先级 | 风险/工作量 | 改动业务逻辑 |
|---|---|---|---|---|---|---|---|
| S01 | 把「零存储权限 + SAF 目录」定为设计基线，明确不引入 `MANAGE_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | 方向对但无书面约束，后续容易被「想存相册」的诉求带偏 | 在 `README.md` 与设置页「关于」写清权限清单与理由；`AndroidManifest.xml` 保持只声明 `INTERNET` + `ACCESS_LOCAL_NETWORK`，并在 PR checklist 加「新增权限需说明」 | 规避 Play 下架风险（该权限仅对文件管理器等类别开放） | P0 | 低/S | 否 |
| S02 | 可选「同时存入系统相册」开关：新增 `MediaStoreSink` 与 SAF 并存，**默认关** | 用户在图库/微信选图时看不到抓下来的图 | 抽 `interface AssetSink { fun save(name, mime, bytes): SaveResult }`，`Crawler.ContentSink` 与新增 `MediaStoreSink` 各实现一份；`MediaStoreSink.save` 流程：`ContentValues{ DISPLAY_NAME, MIME_TYPE, RELATIVE_PATH = "Pictures/WeiboCheckin/<yyyy-MM>" , IS_PENDING=1 }` → `resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)` → `openOutputStream` 写入 → `IS_PENDING=0` 更新；任一步失败则 `resolver.delete(uri)` 回滚。开关放 `fragment_settings.xml` 行为卡，落 `AccountStore` | 抓下来的原图在系统相册可见、可分享；**零新增权限** | P1 | 中/M | 是（新增 sink 分支，签到流程不动） |
| S03 | SAF 目录选择引导：`EXTRA_INITIAL_URI` + 文案说明 | Android 11+ 选不到内部存储根与 `Download`，用户乱选会得到不可写目录 | `GalleryFragment.pickDir` 里给 `Intent` 加 `DocumentsContract.EXTRA_INITIAL_URI`（初值 `Environment.DIRECTORY_PICTURES` 对应 URI）；选择后在 `takePersistableUriPermission` 之外做一次可写探活（尝试 `createDocument` 一个 `.probe` 再删）；文案改成「请选择 Pictures 下的子目录（Download 目录无法授权）」 | 首次配置成功率提升，减少「抓了半天全失败」 | P1 | 低/S | 是（仅 UI 层） |
| S04 | 授权失效检测 + 一键重选，索引不丢 | `takePersistableUriPermission` 在目录被移动/删除/拔卡后失效，现在只会在写入时表现为「全部失败」 | 启动时对 `AccountStore.getCrawlTreeUri` 做一次 `DocumentsContract.buildChildDocumentsUriUsingTree` + `query` 探活；失败则首页 `tvSummary` 显示「目录授权已失效，点此重选」，点击跳 `pickDir`。`CrawlIndex` 已按 `treeTag = sha256(treeUri).take(8)` 分文件（`CrawlIndex.kt:591`），重选同目录可复原 | 明确失败原因，不用重抓 | P0 | 低/S | 是 |

### 4.2 去重与落盘正确性

| 编号 | 建议 | 现状问题 | 具体做法 | 预期收益 | 优先级 | 风险/工作量 | 改动业务逻辑 |
|---|---|---|---|---|---|---|---|
| S05 | 目录清单从「每次全量枚举」改为「索引权威 + 按需兜底」 | `ContentSink.loadExisting()` 每次开跑 `query` 整个 childrenUri，且 `names`/`canonicals` 两份 HashMap 常驻 | 以 `CrawlIndex` 内 `t=d`（文件名记录）为权威判重；目录扫描降级为：①用户点「校验目录」时；②索引文件缺失（`LoadReport` 异常）时；内存只留 `canonicals`（规范化名）一份 | 冷启动开跑时间与内存双降（万级目录收益最明显） | P0 | 中/M | 是 |
| S06 | 内容 hash 反查表，实现「先判后下」 | L3 现在是「下完才 `sha256Hex128`」（`Crawler.kt:736`），同一张图换 picId 会白下一遍 | `CrawlIndex` 增 `Map<hash, name>`；下载前若已知该 picId 的历史 hash 且已存在同 hash 文件，直接记 index 跳过；新图下完写 hash | 省流量与时间，去重更彻底 | P2 | 中/M | 是 |
| S07 | 感知哈希近似去重（限场景使用） | 现无；但「同图重压缩/裁切/长图重发」纯 hash 抓不到 | 新增 `DHash.kt`（`Hashing.kt` 旁）：解码到 9×8 灰度算 dHash；仅对「同一 uid 跨 post 且文件大小差 <10%」的候选做比对，汉明距离 ≤5 判近似；开关与阈值放设置页 | 覆盖微博同图多次发布的场景 | P2 | 高/L | 是（可全部放在独立分支，默认关） |
| S08 | 写入失败分类 | `ContentSink.save()` catch 所有异常返 `false`，上层分不清「撞名/空间不足/IPC 失败」 | 返回 `sealed class SaveResult { Ok, Exists, NoSpace, IoError(msg) }`；`NoSpace` 立即中止本轮并在首页提示「存储空间不足」；`IoError` 重试 1 次后记 `failedSlots` | 失败可解释，可运维 | P1 | 低/S | 是 |
| S09 | 写后回读抽样校验 | 只在「已存在文件」路径上算 hash（`Crawler.kt:343`），新写文件不校验，SAF 写入中断会留半截文件 | 每 N 个（如 20）新文件抽 1 个 `openInputStream` 重算 `sha256Hex128` 比对，不一致则删除该文件 + 记失败 | 早发现截断文件，避免「看起来有其实打不开」 | P2 | 中/M | 是 |
| S10 | 单写者串行化同一目录 | `runIncremental` 自身串行，但「抓取单条」与增量任务可能并发进入同一 treeUri，`createDocument` 竞态会产生大量 ` (1)` 副本 | 新增 `object RunGate { val mutex = Mutex() }`；`Crawler.withCrawlContext` 内 `tryLock`，拿不到直接返回「已有抓取任务在进行」；`crawlSinglePost` 同样过闸 | 消除撞名副本与索引脏数据 | P0 | 低/S | 是 |

### 4.3 抓取健壮性与可观测性

| 编号 | 建议 | 现状问题 | 具体做法 | 预期收益 | 优先级 | 风险/工作量 | 改动业务逻辑 |
|---|---|---|---|---|---|---|---|
| S11 | 退避改「指数 + 抖动」 | `delay(1200L * (attempt - 1))`（`Crawler.kt:812`）线性且固定，易与 CDN 限流同频 | `fetchBytes` 内改 `base * 2^(attempt-1) + Random(0, 400)`，base=800ms，上限 8s；尝试次数仍 3 | 连续 403/超时恢复率提升 | P1 | 低/S | 是 |
| S12 | 有限并发下载（可选） | 逐张串行，9 图帖 ≈ 9×(下载 + 600~900ms) | `savePost` 内 `coroutineScope { Semaphore(2) }`，保持请求间随机 600–900ms 限速不变，落盘仍走 S10 的单写者；并发数做成常量便于回退 | 吞吐约提升 1.5–1.8×，对源站压力可控 | P2 | 中/M | 是 |
| S13 | 进度结构化并双页复用 | `RunState` 只有粗状态，用户只能翻日志 | `RunState.Running` 扩为 `data class(discovered, downloaded, skipped, failed, bytes, elapsedMs, currentTarget)`；首页与图库页共用 `CrawlRunner.state`，图库「运行卡」用 `LinearProgressIndicator` + chip 呈现 | 不用翻日志就知道进展 | P1 | 中/M | 是（仅新增字段） |
| S14 | 系统侧停止原因落日志 | 后台任务被系统停掉时无记录 | Worker 内读 `WorkInfo.getStopReason()`（Android 16 明确推荐），写入现有日志与 `RunState`；`CheckInWorker` 的 6 小时保活同理 | Android 16 配额收紧后可定位「为什么没跑」 | P1 | 低/S | 是（仅日志） |

### 4.4 图库浏览性能

| 编号 | 建议 | 现状问题 | 具体做法 | 预期收益 | 优先级 | 风险/工作量 | 改动业务逻辑 |
|---|---|---|---|---|---|---|---|
| S15 | 缩略图内存上限按可用内存比例 + 预取窗口 | `ThumbLoader` LruCache 上限固定；网格一次性 `listImages` 全量 | LruCache 上限取 `Runtime.getRuntime().maxMemory() / 8`；网格改分页（每页 120，`addOnScrollListener` 触底续载）+ 可见范围 ±1 屏预取；`inSampleSize` 按目标列宽算 | 大目录不 OOM，滚动更稳 | P1 | 中/M | 是 |
| S16 | 删除后同步索引与缩略图缓存 | 预览页删除后，索引与 `ThumbLoader` 可能仍认为文件存在（下次抓取会跳过） | 统一 `AssetDeleter.delete(uri, name)`：删 `DocumentFile` → `CrawlIndex.onFileDeleted(name)` → `ThumbLoader.evict(uri)` → 通知网格刷新；`ImagePreviewActivity` 与图库长按删除都走它 | 避免「删了还在」「删了不再抓」 | P0 | 低/S | 是 |
| S17 | 空态/加载态/错误态三件套 | 只有纯文本空态 | 新增 `EmptyState`（图标 `ic_image` + 一句话 + 主行动「去设置目录」/「添加博主」）复用 `Widget.Wcm.Text.Empty` 体系；网格加载时给 6 个占位格；目录读取失败给「重试」按钮 | 首次使用不迷路 | P2 | 低/S | 否 |

### 4.5 主题与按钮层级（可直接施工）

| 编号 | 建议 | 现状问题 | 具体做法 | 预期收益 | 优先级 | 风险/工作量 | 改动业务逻辑 |
|---|---|---|---|---|---|---|---|
| S18 | `fragment_settings.xml` 全面令牌化并对齐卡片层级 | 145 行内硬编码 `16dp/12dp/8dp/4dp`、`textSize 12sp/13sp/14sp`、`@color/text_secondary`；3 张卡同为 `?attr/materialCardViewFilledStyle`，没有主次 | 逐项替换：`padding="16dp"` → `@dimen/card_padding`、`layout_marginTop="12dp"` → `@dimen/card_margin_top`、`8dp` → `@dimen/space_s`、`4dp` → `@dimen/space_xs`；`textSize` → `@style/TextAppearance.Material3.BodySmall/BodyMedium`，`textColor` → `?attr/colorOnSurfaceVariant`；卡片：面板卡 → `@style/Widget.Wcm.Card.Primary`，行为卡 → `Widget.Wcm.Card.Standard`，关于卡 → `Widget.Wcm.Card.Subtle`；保存按钮 → `Widget.Wcm.Button.Primary`（本页唯一主行动），并去掉 `textSize="14sp"` | 三页层级终于一致；深色下文字自动适配（不再依赖写死的 text_secondary） | P0 | 低/S | 否 |
| S19 | 修正品牌色对比（浅色 3.49:1 → ≥4.5:1；**深色必须反向处理**） | 调研起始 `colorPrimary=#F54868` 白字仅 **3.49:1**（M3 filled 按钮 label 14sp Medium 属正常字号，需 4.5:1）。初版建议「日夜都压深到 `#C4344E`」**是错的**：`#C4344E` 压深色表面 `#1C1B1F` 只有 **3.22:1**，深色下所有 TextButton 文字都会糊 | 已落地为日夜分离：①浅色 `values/themes.xml` `colorPrimary=@color/brand_dark`（白字 **5.32:1**）；②深色 `values-night/themes.xml` `colorPrimary=@color/brand`（亮红压深底 **4.90:1**）+ 新增 `colorOnPrimary=#1C1B1F`（近黑压亮红 **4.90:1**；M3 深色默认 onPrimary 偏紫且仅约 3.4:1）；③`brand` 仍可作图标 tint/描边。后续改色请用 WCAG 公式双态各算一次，勿套用单态结论 | 无障碍达标，且深色不劣化 | P0 | 低/S | 否 |
| S20 | 图库页「单一主行动」重构 | `fragment_gallery.xml` 有 3 个 `Button.Text` + 「开始抓取/停止」两个按钮横排；运行时「开始」与「停止」同权重竞争（违反 M3「一页一个高强调主行动」与「并列放置」原则） | 改成单按钮状态机：空闲 → `Widget.Wcm.Button.Primary`「开始抓取（增量）」；运行中 → 同一位置变 `Widget.Wcm.Button.Secondary` 文本「停止」+ `app:icon=@drawable/ic_delete`；三个 TextButton 收进「更多」（`ic_more_vert` + `PopupMenu`），与 `item_crawl_target.xml` 的溢出菜单风格统一 | 视线不分裂，误触停止的概率大幅下降 | P1 | 低/S | 否（仅 layout + 绑定） |
| S21 | 字号层级拉开梯度 | `SectionTitle` 16sp vs `Body` 14sp = 1.14×，标题「压不住」内容（外部 skill 的反例门禁正是「1.7× 才叫 display scale」） | `Widget.Wcm.Text.SectionTitle` 改 `TextAppearance.Material3.TitleLarge`（18sp，1.28×）；页面级大标题（如「图库」）用 `HeadlineSmall`（24sp）；日志/说明保持 12sp；`Widget.Wcm.SwitchRowTitle` 的 `BodyLarge` 保持 | 扫读时层级一眼可辨 | P2 | 低/S | 否 |
| S22 | 动态取色做成「开关 + 默认关」；Android 16 主题图标 | ①动态取色**已在无条件启用**（不是未启用）；②`monochrome` 层**已存在**（`mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml` 均带 `<monochrome>`，前景是透明底白色对勾），Android 16 QPR2 的自动上色已有可控效果，无需改造 | ①若要默认关：`WcmApp.onCreate` 改为读偏好后再 `DynamicColors.applyToActivitiesIfAvailable`，并在 4 个 Activity 的 `recreate()` 路径处理主题切换（会闪一次界面）；②图标维持现状。本条结论：**收益＜成本，本轮不做**，仅记录为可选事项 | 保住品牌红 | P2 | 低/S（若做则 M） | 否 |

### 4.6 本轮实施状态（v0.6 UI 刷新回填，2026-10-27）

上表 S17–S22 中与主题/按钮层级相关的 4 条已在 v0.6 UI 刷新中落地并通过构建与 lint，详细改动说明见 `docs/design/ui-refresh-rationale.md`：

| 条目 | 状态 | 验收实测 |
|---|---|---|
| S18 设置页令牌化 | 已完成 | `fragment_settings.xml` grep 裸 `dp`/`textSize=`/`@color/text_secondary`/`materialCardViewFilledStyle` 全部零命中；三卡分级（Primary/Standard/Subtle）+ 保存按钮降为 `Button.Primary` |
| S19 对比度 | 已完成（日夜分离） | 浅色 `#C4344E` + 白字 **5.32:1**；深色 `#F54868` + `#1C1B1F` **4.90:1** |
| S20 图库单主行动 | 已完成 | `fragment_gallery.xml` 为 `btnCrawlStart`(Primary 全宽) / `btnCrawlStop`(Secondary 全宽)，`GalleryFragment.kt:365-368` 互斥切 visibility；3 个 TextButton 收进 `ic_more_vert` |
| S21 字号梯度 | 已完成 | `Widget.Wcm.Text.SectionTitle` → `TextAppearance.Material3.TitleLarge`(18sp)，与正文 14sp 拉开 1.28×；**未加**页面级 HeadlineSmall（三页均无 AppBar，加了只会白占纵向空间） |
| S17 空态/加载态 | 未做 | 仍为文本空态；建议排入下一轮 |
| S22 动态取色默认关 | 有意不做 | 见上表理由 |

注：`styles.xml` 定稿时删除了 `Button.Tonal` / `Button.Danger` / `ListRow.Static` 三个无使用处的变体，现存按钮层级为 **Primary(filled) / Secondary(outlined) / Text** 三档 + `IconButton`（48dp 触控区）。破坏性操作（删账号/删图）当前走 `ic_delete` 图标 + `?attr/colorError` tint，若后续要文字按钮版删除，再补 `Button.Danger` 即可。

**关于「三个 P0 的无障碍/正确性口径」补充说明（便于验收）**

- S19 的对比度计算方式：`L = 0.2126R + 0.7152G + 0.0722B`（sRGB 先线性化，`c ≤ 0.04045 ? c/12.92 : ((c+0.055)/1.055)^2.4`），`C = (L_hi+0.05)/(L_lo+0.05)`。可用 https://webaim.org/resources/contrastchecker/ 复核。
- S18 验收：`fragment_settings.xml` 内不应再出现裸 `dp`/`sp` 数字（`0dp`、`match_parent`、`1dp` 分隔线除外）。
- S10/S16 验收：连续两次点「开始抓取（增量）」不会产生 `xxx (1).jpg`；在预览页删图后立即重抓，同一张会重新下载。

**品牌红对比度实算表（已与实施侧独立复算交叉验证，8 个组合全部一致）**

| 前景 | 背景 | 对比度 | AA 判定 | 用途/说明 |
|---|---|---|---|---|
| `#FFFFFF` | `#F54868`（brand） | **3.49:1** | ✗ 正常字号 / ✓ 大字 | 初版主题的问题所在：filled 按钮 label 14sp Medium 属正常字号，不达标 |
| `#FFFFFF` | `#C4344E`（brand_dark） | **5.32:1** | ✓ | 浅色主题 filled 按钮（现用） |
| `#C4344E` | `#FEF7FF`（M3 浅色表面） | **5.06:1** | ✓ | 深红作为**文字色**压在卡片面上的实际值（≠ 纯白 5.32） |
| `#C4344E` | `#1C1B1F`（M3 深色表面） | **3.22:1** | ✗ | **反例**：深色主题若照抄 brand_dark，所有文字按钮都会糊 |
| `#F54868` | `#1C1B1F` | **4.90:1** | ✓ | 深色主题的强调色/文字（现用） |
| `#1C1B1F` | `#F54868` | **4.90:1** | ✓ | 深色主题 `colorOnPrimary`（现用，替换 M3 默认深紫） |
| `#F54868` | `#000000` | **6.01:1** | ✓ | 全屏预览页（`Theme.Wcm.Preview` 恒定黑底）上的强调色 |
| `#381E72`（M3 深色默认 onPrimary） | `#F54868` | **3.76:1** | ✗ | **反例**：不覆写 `colorOnPrimary` 会不达标且偏紫 |
| `#F54868` | `#FEF7FF`（M3 浅色表面） | **3.32:1** | ✓ 非文字元素（≥3:1）/ ✗ 文字 | 本项目 `colorPrimary` 现仅作 3 处 `app:tint` 图标着色（`fragment_home:89`、`fragment_gallery:43`、`activity_image_preview:64`），按 WCAG 1.4.11 图形对象 3:1 达标；**但不得把 `colorPrimary` 用作正文文字色**（余量仅 0.32） |

**方法论（三条，供后续改色复用）**

1. 对比度必须**日夜各算一次**，单态结论套到两态会翻车（brand_dark 在浅色 5.32、在深色 3.22）。
2. 背景必须用**实际渲染的背景色**，不是想当然的纯白/纯黑：同一个深红压在纯白是 5.32，压在 M3 浅色表面 `#FEF7FF` 是 5.06。
3. 判 AA 前先看**字号是否属「大字号」**：≥18pt(24sp) 或 ≥14pt(18.66sp) 粗体只需 3:1；按钮 label 14sp Medium 不算大字号，必须 4.5:1 —— 这正是初版 `#F54868` + 白字被判不合格的原因。

---

## 五、待确认问题

1. **是否要「同时存入系统相册」（S02）？** 三个选项：①保持现状（只有 SAF 目录，靠 App 内图库浏览）；②新增开关默认关（推荐，用户自己决定）；③默认开（相册可见性最好，但会在用户相册里多出一个 `Pictures/WeiboCheckin` 目录，且删除 App 不会清理）。若选②③，还需确认目录命名：`Pictures/WeiboCheckin/<yyyy-MM>/` 还是平铺 `Pictures/WeiboCheckin/`。
2. **是否允许改抓取内核（S05/S10/S11/S12）？** 这几条会动 `Crawler.kt` 的落盘与重试路径。选项：①全做（收益最大，需回归「断点续跑 + 增量规划」）；②只做 S10 单写者锁 + S11 退避（风险最低）；③本轮完全不动抓取，只做 UI 与文档。
3. **动态取色（S22）是否要改成「开关 + 默认关」？** 现状是无条件启用（跟随壁纸）。改成默认关需要：新增偏好项 + `WcmApp` 装配改造 + 4 个 Activity 的主题重建路径（切换时会闪一次界面）。选项：①保持现状（推荐，Material You 体验完整）；②做开关但默认开（用户可关）；③做开关且默认关（保品牌红，代价是改造成本 + 闪屏）。
4. **是否允许改文案？** 现状文案较口语（「立即签到」「仅同步面板」「补签选中」）。若允许，建议：设置页「保存」→「保存面板配置」；图库「开始抓取（增量）」→「开始抓取」+ 副标题说明增量含义；`strings.xml` 统一加 `cd_` 前缀的 `contentDescription`（部分控件已有）。
5. **`colorPrimary` 的日夜方案是否认可「浅色压深、深色保持亮红」？**（已按此落地：浅色 `#C4344E` 白字 5.32:1，深色 `#F54868` + `#1C1B1F` 4.90:1；深色若压深只有 3.22:1，不可取。）如坚持两态统一用 `#F54868`，则浅色按钮改 `backgroundTint` 单独压深，代价是「按钮色 ≠ 主题主色」。

---

### 附：本次未能核实/需要留意的点

**更正记录（2026-10-27，与实施同学交叉核对后修订）**

- S19 初版建议「日夜两个主题都把 `colorPrimary` 压深到 `#C4344E`」**是错的**：深色下该组合只有 3.22:1。已改为日夜分离方案并给出双态实算值。
- S22 初版称「动态取色未启用」「adaptive icon 无 `monochrome` 层」，**与现状不符**：`WcmApp.onCreate` 早已无条件启用 `DynamicColors.applyToActivitiesIfAvailable`，`ic_launcher.xml` / `ic_launcher_round.xml` 均带 `<monochrome>`。
- 对比度数值由估算改为精算：白字 on `#F54868` 从 3.56 修正为 **3.49**，白字 on `#C4344E` 从 5.27 修正为 **5.32**；并补齐 9 组实算值与三条方法论（见 §4.6「品牌红对比度实算表」）。实施侧已用同一公式独立复算，8 个共同组合结果完全一致。
- `styles.xml` 定稿删除了 `Button.Tonal` / `Button.Danger` / `ListRow.Static` 三个未使用变体，故现存为「按钮三档 + IconButton」，文中已同步。

**其他未核实项**
- `KurtBestor/Hitomi-Downloader`、`FooIbar/EhViewer` 现被 GitHub DMCA 屏蔽（API `reason: dmca`），仓库页可能打不开；其 star 数与特性引用自可检索到的 README 快照，若需引用请改用替代项（`GopeedLab/gopeed`、`xiaojieonly/Ehviewer_CN_SXJ`）。
- `gallery-dl` 官方文档站（gallery-dl.readthedocs.io）已 404，主仓迁至 Codeberg；`archive` 的具体后端列表（file / sqlite3 / PostgreSQL）依据 README 与第三方特性页整理，未逐项读源码验证。
- `deckerst/aves` 的权限分级矩阵与撞名三策略来自 DeepWiki 对源码的整理（二手），未逐行核对 `PermissionManager.kt` 源码（该文件路径在当前主分支已变化，raw 取 404）。
- Android 16「JobScheduler 配额收紧」的**具体数值**官方只描述「按 standby bucket / 前台状态 / 前台服务并发计算」，未给固定秒数，故本报告不给具体配额数字。
