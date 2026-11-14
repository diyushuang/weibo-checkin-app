# 架构说明（架构现代化进行中）

> 状态：2026-11 架构现代化 **Phase 0–6 已完成**：四个 Tab + 浏览/选图/预览全部是
> NavHost route；仅登录 WebView 保留独立 Activity。
> 本文描述当前真实结构，不描述最终目标态的想象结构。

## 1. Gradle 模块与依赖方向

```
:app            Compose UI + Navigation 壳、WorkManager、Hilt 入口
 ├── :domain    签到 / 保活 / 抓取 / 重登的服务编排
 ├── :data      微博与面板网络、SharedPreferences、NDJSON 索引、SAF、Keystore
 └── :core      领域模型、纯算法、应用级协程作用域
```

依赖规则：

- `:app -> :domain, :data, :core`
- `:domain -> :data, :core`
- `:data -> :core`
- `:core` 不依赖任何上层模块；`domain` 不得引用 Android UI；`data` 不得引用 UI/domain。

> **包名仍全部是 `com.wcm.checkin`**：这是为了模块化迁移时零 import 改动。
> 模块边界目前由 Gradle 依赖强制，而不是由 package 强制。后续可把包名拆成
> `com.wcm.checkin.core/data/domain`，那是一次独立的机械迁移。

## 2. 各模块职责

| 模块 | 放什么 | 不放什么 |
| --- | --- | --- |
| `:core` | `AssetName`、`Hashing`、`CrawlCursor`/`Budget`/`Phase`/`StopReason`、`ReloginPolicy`、`AccountStatusHead`、`AppScope` | Android UI、SharedPreferences、OkHttp 业务 |
| `:data` | `WeiboHttp/Auth/Topic/Post/ImageUrl`、`PanelApi`、`GuestSession`、`WeiboPasswordLogin`、`AccountStore`、`PanelSettings`、`CrawlSettings`、`CrawlIndex`、`SafTreeStore`、`GalleryFiles`、`TopicStore`、`CredentialVault`、`GifEncoder`、`LivePhotoConverter` | View/Fragment、签到/抓取编排 |
| `:domain` | `CheckinService`、`AccountMaintenanceService`、`CrawlEngine`、`AlbumCrawler`、`TimelineCrawler`、`CrawlSaver`、`CrawlPlanner`、`CrawlProgress`、`CrawlService`、`PostAssets`、`AutoRelogin` | Android UI、资源 `R.*` |
| `:app` | `MainActivity`（Navigation 壳，全部页面为 route）、`LoginWebViewActivity`、四个 Tab 的 Screen/ViewModel、`ZoomPager`、`CheckInWorker`、`WcmApp` | 业务规则、网络/存储实现 |

## 3. 状态与并发

- **应用级长任务**挂在 `:core` 的 `AppScope`（`SupervisorJob + Dispatchers.IO`），
  不随页面销毁；ViewModel/页面只观察状态，不拥有任务。
- **首页 UI 状态与编排**：签到/同步/重登/超话清单/日志全部在 Hilt `HomeViewModel`；
  长任务仍挂 `AppScope`。`TopicViewModel` / `GalleryViewModel` 同理，切 Tab 不丢状态。
- **导航**：`MainActivity` 用 Navigation Compose + `NavigationBar`，四个目的地都是
  Compose；ViewModel 挂在 Activity 上，路由 `saveState/restoreState` 保留滚动与筛选。
- **超话快照**：`HomeViewModel.topicStates` 是 `StateFlow<Map<String, TopicState>>`，
  不可变 Map 整体替换；IO 线程写、主线程读，不会在遍历时被并发扩容打断。
- **抓取单闸门**仍在 `CrawlService`：全应用同一时刻只允许一个抓取任务，
  `StateFlow<RunState>` 向图库页/首页暴露进度；`requestStop` 对自动抓取同样生效。
- **签到编排**在 `CheckinService`：账号循环、探活、列超话、逐条签到、记账；
  `CheckinService.noteResult` 是唯一的“上次结果”写入口。
- **账号维护**在 `AccountMaintenanceService`：探活落库、补昵称、面板同步；
  首页“Cookie 同步”与 `CheckInWorker` 共用这一份实现。
- **超话快照**在 `:data` 的 `TopicStore`，`StateFlow<Map<String, Snapshot>>`；
  跨天失效由快照时间判断。
- 纯函数/纯模型（`CrawlPlanner`、`CursorLogic`、`CrawlProgress.reduce`、`AssetName`、
  `ReloginPolicy` 等）保持 JVM 可测，是当前 315 个单测的主体。

## 4. 依赖注入（Hilt）

- `WcmApp` 标注 `@HiltAndroidApp`，并实现 `Configuration.Provider`；
  `HiltWorkerFactory` 通过 `@Inject` 注入。
- `CheckInWorker` 标注 `@HiltWorker` + `@AssistedInject`；
  manifest 关闭 WorkManager 默认初始化，避免自定义 WorkerFactory 被覆盖。
- 现阶段 Hilt 已覆盖 **Application / Worker / ViewModel / 抓取单闸门**：
  `CheckInWorker` 是 `@HiltWorker`，`Home/Topic/Gallery/SettingsViewModel` 都是
  `@HiltViewModel`（Activity 作用域），`CrawlService` 由 `DomainModule` 以 `@Singleton`
  提供并注入 `HomeViewModel` / `GalleryViewModel` / 浏览 / 选图入口。
  `CheckinService` / `AccountMaintenanceService` / `AutoRelogin` 仍是 `object`，
  后续再逐步改为构造注入的类（见“待办”）。
- 版本：Hilt `2.51.1`、KSP `1.9.24-1.0.20`（与 Kotlin `1.9.24` 对齐）。

## 5. 构建与门禁

```bash
# 本地（JDK17 必须显式指定，机器上没有全局 java）
JAVA_HOME=C:/Users/adsm/dev/jdk17 ./gradlew --offline \
  :app:testDebugUnitTest :app:assembleDebug

# release（R8 + 资源收缩 + 固定签名）
JAVA_HOME=C:/Users/adsm/dev/jdk17 ./gradlew --offline :app:assembleRelease

# 真机/模拟器冒烟（需要已连接的设备或模拟器）
JAVA_HOME=C:/Users/adsm/dev/jdk17 ./gradlew :app:connectedDebugAndroidTest
```

当前基线（2026-11-14）：

- 315 个 JVM 单测，0 failures
- `assembleDebug` / `assembleRelease` 通过
- release APK ≈ 3.63MB（R8 开启前 6.55MB）
- `lint` 在本机 Windows 环境会长时间卡在 `lintAnalyzeDebug`；CI 的 `build-apk.yml`
  只跑单测 + assembleDebug，不依赖 lint；`build-apk.yml` 的 paths 已覆盖 `core/`、`data/`、`domain/`。
  已在本机用与 CI 相同的根命令（`testDebugUnitTest assembleDebug` / `assembleRelease --no-daemon`）验证通过

## 6. 迁移状态与待办

### 自动化冒烟

`app/src/androidTest/.../AppSmokeTest.kt`（UiAutomator）覆盖：
- 冷启动 + 首页渲染；
- 通过 `MainActivity.selectSettingsTab()` 打开设置页，断言面板同步/行为/关于；
- 切换「打开 App 即自动签到」，断言 `shared_prefs/wcm.xml` 持久化并恢复原值。

运行：`./gradlew :app:connectedDebugAndroidTest`（需要设备/模拟器；CI 未接入 emulator）。

### 运行时验证（2026-11-14，Android 14 模拟器 `see-audit`）

- 冷启动、首页/超话/图库/设置四 Tab 渲染正常，无 `FATAL EXCEPTION`。
- 设置页 Compose：浅色/深色都正常；品牌玫红与 XML 页面一致；
  开关切换后 `shared_prefs/wcm.xml` 即时落盘（`auto_on_open=false/true`）；
  公网 `http://` 面板地址被拒绝并显示红色错误文案。
- 登录页打开微博官方登录 WebView；浏览博主页打开空态页。
- WorkManager 周期任务已注册；`cmd jobscheduler run -f -n androidx.work.systemjobscheduler
  com.wcm.checkin 1` 强制触发后，`CheckInWorker` 由 Hilt 实例化并返回 SUCCESS。
- 首页 Compose Hero：白卡视觉与 XML 一致，点击可跳转「超话」页。
- 首页账号列表：空态、假账号行（头像/UID/状态）、⋮ 菜单（查看超话/重登/删除）均正常。
- 首页 Cookie 卡：假账号失效态（红字/「已失效」pill）与面板未配置提示正常。
- 首页日志卡：收起摘要与展开终端窗口（深色/等宽/贴底）正常。
- 首页删除确认：Compose AlertDialog 正常（假账号，未实际删除）。
- 超话清单 Compose 对话框：编译通过；需要有效微博账号才能触发运行时验证。
- 重登凭证 Compose 对话框：状态/账号/密码/说明/保存/删除渲染正常（未实际保存凭证）。
- 超话页 Compose：用假账号 + 本地快照验证 3 条超话的指标/筛选/排序/展开详情/
  行菜单/原始 JSON/字段说明；展开详情在假 cookie 下正确落到「加载失败，点按重试」。
- 图库抓取页 Compose：目录选择（SAF 授权 wcmtest）后计数 6 张、模式开关落盘、
  日志卡展开终端、空目标态正常。
- 图库浏览 Compose：`LazyVerticalGrid` 3 列正方形格子 + 分组头「更早 · 6 张」；
  长按进入多选（已选 1/分享/删除/取消）、排序菜单 5 项、筛选 chip 正常。
  真实图库（104 张）验证：单图删除 106→105、返回浏览页自动刷新；批量删除
  105→104 且退出多选；批量分享拉起系统 chooser 并显示图片预览。
- 浏览博主页 Compose：输入行/目标 chip/筛选/空态正常（假账号无网络时走空态）。
- 全屏预览 Compose：本地图库点图打开 `HorizontalPager`，底部计数/文件名/分享/
  打开/删除工具条正常，图片复用 `ZoomableImageView`；远程预览共用同一翻页器。
- 超话编排迁入 Hilt `TopicViewModel`：切 Tab 后筛选/排序/展开态保留；
  只读弹窗改 Compose AlertDialog，字段说明渲染正常。
- 图库编排迁入 Hilt `GalleryViewModel`：添加目标/全量确认/删除/重试/单条抓取
  均为 Compose 对话框；实测添加目标弹窗与运行开关正常。
- Navigation Compose 壳：四 Tab 切换、底部 Badge、主题色与旧底栏一致；
  超话筛选切 Tab 往返后仍保持（Activity 作用域 ViewModel）；设置页输入时
  底栏隐藏且内容被 IME 顶起。
- 叶子 route：图库抓取页 → 图库浏览（6 张网格）→ 本地预览（1/6 翻页、底栏隐藏）
  整条链路跑通；浏览博主 → 选图/远程预览已切路由（无有效 cookie/网络时只验证空态）。
- **抓取/图库端到端（游客态）**：release APK 全新安装，添加公开目标「人民日报」，
  相册模式增量抓取下载 29 张，图库显示 36 张、按日期分组 28 张，预览 1/55 正常；
  继续增量后图库 106 张；`CheckInWorker` 强制触发返回 SUCCESS。
- **同签名覆盖安装**：release APK `install -r` 后目录授权、抓取目标、图库 106 张全部保留；
  release 冷启动 864ms。
- UI→data：Compose 页已无 `CrawlSettings`/`PanelUrl`/`GalleryFiles` 等 data 直接引用，
  浏览/超话清单均改为 UI 模型映射；仅登录 Activity 直接调认证与账号存储（刻意保留）。
- 未覆盖：签到/自动重登/浏览博主需要真实登录账号（当前无可用的授权凭据），
  只验证了假账号与游客态链路。

已完成：

- `WeiboApi` 1114 行 → `WeiboHttp/Auth/Topic/Post/ImageUrl`
- `Crawler` 1681 行 → `CrawlEngine` + `CrawlSaver` + `AlbumCrawler`（428 行）+
  `TimelineCrawler`（307 行）+ `PostAssets`
- `AccountStore` 933 行 → `AccountStore`（643 行）+ `PanelSettings` + `CrawlSettings`
- 签到编排/保活/重登从 UI 与 Worker 收敛到 `:domain` 服务
- 4 模块 + Hilt + R8；所有改动行为保持，数据格式零迁移
- **抓取单闸门类化**：`CrawlRunner` object → `CrawlService`，由 Hilt `@Singleton` 提供
- **四个 Tab 纯 Compose**：设置/首页/超话/图库全部由 Compose 渲染，
  `MainActivity` 使用 Navigation Compose + `NavigationBar`，旧 Fragment 与
  `activity_main.xml`/`bottom_nav.xml` 已删除
- **首页编排 ViewModel 化**：签到/同步/重登/超话清单/日志/昵称补拉全部在
  `HomeViewModel`；对话框由 Compose 渲染，页面跳转走事件
- **超话/图库编排 ViewModel 化**：`TopicViewModel` / `GalleryViewModel` 持有筛选/
  展开/日志/进度/任务状态；Fragment 壳与 `LogPanel` 已删除
- **全部业务界面 Compose**：超话/图库网格/图库浏览/浏览博主/选图/本地与远程预览；
  RecyclerView 适配器与旧布局全部删除
- **全部页面 route 化**：浏览/选图/本地预览/远程预览从 Activity 改为 NavHost route，
  对应 ViewModel 接管编排；`activity_*.xml` 只剩登录页
- **Phase 7 清理**：删除迁移后无引用的 18 个 drawable、14 个 string、2 个 color、
  23 个 dimen；Compose 页与 data 模型解耦；release APK 3.63MB
- ViewBinding：仅剩登录页 `LoginWebViewActivity` 使用

待办（按建议顺序）：

1. 端到端：用真实微博账号验证签到/自动重登/抓取/图库全链路。
2. Phase 7 清理：清理迁移后无引用的 drawable/style；`grep` 复核 UI→data 直接依赖。
3. 可选：Room（签到历史/统计）、kotlinx.serialization（新 DTO）、
   `:feature` 模块（需先把 `R.*` 资源迁移到 feature）。
4. 端到端：用真实微博账号验证签到/自动重登/抓取/图库全链路。

## 7. 不变量（改动时必须保留）

- 抓取单闸门：任何新增入口（定时、通知、快捷方式）都必须经 `CrawlService`。
- 三层去重与 `CrawlIndex` 的终态语义：不要绕过 `CrawlSaver` 直接写文件。
- 账号表并发：`AccountStore` 的读改写仍在 `LOCK` 内；设置/账号分离后，
  抓取设置由 `CrawlSettings.LOCK` 保护。
- 安全口径：出站 URL 只允许 https + 微博域名白名单；登录 WebView 白名单
  与出站白名单分开。
- 数据兼容：SharedPreferences 文件仍叫 `wcm`，索引仍是
  `filesDir/crawl/index/<treeTag>.ndjson`，keystore 与签名不变。
