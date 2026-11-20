# 验证记录

> 本文只记**跑过什么、结果如何、还差什么**。结构与设计见
> [`docs/architecture.md`](architecture.md)，功能对照见
> [`docs/review/refactor-audit-2026-11-15.md`](review/refactor-audit-2026-11-15.md)。

## 1. 当前基线（0.15.0 / versionCode 46，2026-11-20 实测）

| 项 | 数值 |
| --- | --- |
| JVM 单测 | **603 个（39 个测试类），0 失败 / 0 跳过** |
| 覆盖率（JaCoCo，行） | **4,150 / 16,302 ≈ 25.5%**（明细与补测优先级见 [`testing.md`](testing.md)） |
| 仪器测试 | 7 个（需要设备/模拟器，CI 未接入 emulator） |
| lint | 四个模块 `lintDebug` 均 0 errors / 0 warnings（`NewApi` 为硬错误，`GradleDependency` 等版本提示已显式关闭，理由见各模块 `build.gradle.kts`） |
| detekt | 三模块 0 违规（`:core`/`:data`/`:domain`；仅开 `LongMethod`/`LargeClass`/`LongParameterList` 三条规则，6 条抓取链路存量违规圈进 baseline，见 `core/config/detekt/`） |
| Kotlin 编译告警 | 0（`-Pkotlin.compiler.execution.strategy=in-process`） |
| debug APK | **14.66 MB** |
| release APK | **3.92 MB**（R8 + 资源收缩；0.15.0 发版产物是 4.11 MB，差异来自死资源清理与 R8 回归） |
| release 冷启动 | 864 ms（热安装）/ 3195 ms（全新安装）——**0.11.0 的数据，未复测** |

> 上一版基线（0.11.0：331 单测 / release 3.63 MB）已作废，只在 git 历史里可查。
> 数字口径：模块规模与路由数由 `find` / `grep` 统计，覆盖率由
> `./gradlew :app:jacocoTestReport` 生成，**不要手抄旧值**。

## 2. 最近一次全量门禁（2026-11-20）

```
JAVA_HOME=$JDK17_HOME ./gradlew clean :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleRelease :app:lintDebug :app:jacocoTestReport detekt
BUILD SUCCESSFUL
```

| 任务 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | 603 用例 / 0 失败 / 0 跳过 |
| `lintDebug`（app / core / data / domain） | 四模块均 0 errors / 0 warnings |
| `detekt`（core / data / domain） | 三模块均 0 违规（6 条存量在 baseline 里） |
| `:app:assembleDebug` | `app-debug.apk` 14.66 MB |
| `:app:assembleRelease` | `app-release.apk` 3.92 MB |
| `:app:jacocoTestReport` | 行覆盖 25.5% |

产物核验（`app/build/outputs/mapping/release/mapping.txt`）：

- `com.wcm.checkin.CheckInWorker -> com.wcm.checkin.CheckInWorker`：类名保留，
  升级后 WorkManager 库里那条旧 `WorkSpec` 仍能按类名反射实例化。
- `com.wcm.checkin.HomeViewModel -> o6.md`：正常混淆，说明 R8 生效。
- `WcmApp` 保留、`debuggable` 未开启。

## 3. 运行时验证（2026-11-14，Android 14 模拟器）

> 注意：以下记录对应 0.11.0–0.13.0 期间的行为，0.15.0 未复测。

以下为重构落地后实际在模拟器上走过并确认的项，不是静态推导。

**壳与导航**

- 冷启动、四个 Tab 渲染正常，无 `FATAL EXCEPTION`。
- Navigation Compose 壳：四 Tab 切换、底部 Badge、主题色与旧底栏一致；
  超话筛选切 Tab 往返后仍保持（Activity 作用域 ViewModel）；设置页输入时底栏隐藏且内容被 IME 顶起。
- 叶子 route：图库抓取页 → 图库浏览 → 本地预览整条链路跑通（含底栏隐藏与翻页器复用）。

**页面**

- 设置页：浅色/深色都正常；品牌玫红与 XML 页面一致；四档宽度模拟 800dp 平板后左右留白切到 24dp、
  无溢出；公网 `http://` 面板地址被拒绝并显示红色错误文案；开关切换后
  `shared_prefs/wcm.xml` 即时落盘。
- 首页：Hero 白卡、账号列表空态/假账号行/⋮ 菜单、Cookie 卡失效态、日志卡收起与展开终端、
  删除确认对话框均正常。
- 超话页：假账号 + 本地快照验证 3 条超话的指标/筛选/排序/展开详情/行菜单/原始 JSON/字段说明。
- 图库页：SAF 授权后计数正确、模式开关落盘、日志卡展开、空目标态正常；
  浏览页 `LazyVerticalGrid` 分组头/多选/五种排序/筛选 chip 正常；
  实测单图删除 106→105、批量删除 105→104 且退出多选、批量分享拉起系统 chooser。
- 登录页：微博官方登录 WebView 正常打开；浏览博主页空态正常。

**后台与网络**

- WorkManager 周期任务已注册；`cmd jobscheduler run -f -n androidx.work.systemjobscheduler
  com.wcm.checkin 1` 强制触发后，`CheckInWorker` 由 Hilt 实例化并返回 SUCCESS。
- 抓取端到端（游客态）：release APK 全新安装，添加公开目标「人民日报」，相册模式增量下载 29 张，
  图库显示 36 张；继续增量后 106 张。
- 同签名覆盖安装：`install -r` 后目录授权、抓取目标、图库 106 张全部保留。

## 4. 待执行：登录态端到端（需要本人登录，凭据不导出）

这四条链路**重构后从未跑过**，是「功能完整」结论唯一可能被推翻的地方。
以下 7 步全部通过后，才可以打 `v1.0.0`。

1. 打开 App → 首页右上「＋」→ 在微博官方页完成登录；确认首页出现账号行。
2. 打开「打开 App 即自动签到」后重启 App，观察日志卡完成「探活 → 超话 → 签到」；
   再点「立即签到」「Cookie 同步」各一次。
3. 超话页刷新、筛选/排序、展开详情、勾选补签；回首页确认状态 chip 更新。
4. 图库页添加目标 → 浏览博主 → 点卡片进选图 → 长按看大图 → 勾选下载；
   图库浏览确认新图出现，单条抓取一条公开链接。
5. 删除一张已抓图片，确认图库计数与抓取索引同步回退。
6. 触发一次自动重登（人为让 cookie 失效或用临期账号），确认失败原因分类正确、
   24 小时 3 次上限与退避生效。
7. `cmd jobscheduler run -f -n androidx.work.systemjobscheduler com.wcm.checkin 1`
   确认 `CheckInWorker` 返回 SUCCESS。

## 5. 未覆盖项

| 项 | 原因 |
| --- | --- |
| 真实账号的签到 / 自动重登 / 浏览博主 / 单条抓取 | 本机无可用授权凭据 |
| TalkBack 实听 | 无障碍描述已补（见重构审计 §3.6），未做真人实听 |
| lint 全量 | 仅跑构建期 `lintVital` 与 `lintDebug`，未跑 `lint` 全变体 |
| 升级路径的真机验证 | 签名指纹已核对一致（SHA-256 `05b5a60b…c380edd`），但未做真实覆盖安装回归 |
| 「本机加密不可用」提示的真机表现 | 需要一台 Keystore 异常的设备才能触发，未构造 |
| 签到/重登的新单测以外的编排路径 | `CrawlSaver` / `CrawlService` / `AlbumCrawler` / `TimelineCrawler` 仍为 0 覆盖，见 [`testing.md`](testing.md) §5 |

**升级前建议动作**：`adb install -r app-release.apk` 覆盖安装 → 确认账号列表、
Cookie 有效期、抓取目标与索引「已抓」标记仍在 → 跑一轮签到 → 抓一轮增量。
