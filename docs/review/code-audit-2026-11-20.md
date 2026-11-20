# 全量代码审查报告

> 审查对象：`wcm-checkin` v0.15.0（versionCode 46），提交 `4b11e16`
> 审查时间：2026-11-20
> 审查方式：全量静态通读 + 独立复跑构建/单测/lint/编译告警 + 量化扫描（脚本口径见各节）
> 上一份同类报告：[`refactor-audit-2026-11-15.md`](refactor-audit-2026-11-15.md)（重构期，已过期）

---

## 修复状态（2026-11-20 当日处理）

> 本报告是**审查时**的快照，下面的清单已按它逐条落地；行号与结论不再代表当前代码。
> 落地内容见 `CHANGELOG.md` 的 `[未发布]` 小节。

| 项 | 状态 | 落地说明 |
| --- | --- | --- |
| A1 取消语义 | ✅ 已修 | `CheckinService` 三处补 `catch (CancellationException) { throw e }`；`CheckinServiceTest` 加了两个回归用例 |
| A2 文档失真 | ✅ 已修 | `architecture.md` / `verification.md` 全部按实测重写；新增 `docs/testing.md` |
| A3 生命周期感知收集 | ✅ 已修 | 22 处 `collectAsState()` → `collectAsStateWithLifecycle()`，新增 `lifecycle-runtime-compose` |
| A4 明文降级可观测 | ✅ 已修 | 新增 `SecretHealth`（持久化标记）+ 首页提示文案；迁移成功自动清除 |
| A5 索引集合公开 | ✅ 已修 | 5 个 `HashMap` 改 private，新增 `snapshotPosts()` / `picIds()` |
| A6 `target!!` | ✅ 已修 | 拆成 `executePost` / `executeOnTarget`，非空由签名保证 |
| B1 IME 逐帧重组 | ✅ 已修 | 底栏抽成 `WcmBottomBar`，`derivedStateOf` 收敛布尔值 |
| B2 重复 OkHttpClient | ✅ 已修 | `WebAjax` 复用 `WeiboHttp.client` |
| B3 私网判定遗漏 | ✅ 已修 | 补 `100.64/10`、`fc00::/7`、`fe80::/10`、IPv4-mapped；`PanelUrlTest` 加 4 个用例 |
| B4 14 个死资源 | ✅ 已修 | lint 回到 0 error / 0 warning |
| B5 3 条编译告警 | ✅ 已修 | 含删掉 `forbiddenTextDays(zone)` 这个误导参数，调用点同步 |
| B6-1 覆盖率 | ✅ 已接 | 改用 **JaCoCo**（Kover 在中文路径下会把 `-javaagent` 写成 `E://???//...`，单测起不来） |
| B6-2 detekt | ✅ 已接 | 只开 `LongMethod(100)` / `LargeClass(650)` / `LongParameterList(8)` 三条规则（`buildUponDefaultConfig` 关掉其余全部规则集）；6 条抓取链路存量违规圈进 `core/config/detekt/baseline.xml`，baseline 外新违规直接拦；CI `build-apk.yml` 加 detekt 门禁步骤。配置在 `core/config/detekt/detekt.yml`，`:app` 是 Compose 声明式 UI 暂不纳入 |
| B7 日期格式器 | ✅ 已修 | 采用低成本方案：把 `EngageSchedule` 的「唯一出口」表述收窄为「业务键唯一出口」 |
| B8 6 处空 catch | ✅ 已修 | 各补一行「为什么可以吞」 |
| §6 `runInternal` 拆分 | ✅ 已拆 | 先补护栏再拆（与下方建议顺序一致）：新增 `EngageServiceRunTest` 10 个特征测试锁住现有行为（含 NO_TEXT 不进熔断计数这类此前未文档化的真实行为），然后 605 行拆成 ~46 行编排器 + `EngageRunExecution.kt`（`prepareAccess`/`preparePlan`/…/`finishRun` 五段 + `RunExecution` 单条执行）。拆分后 603/603 全绿，`EngageService.kt` 最大函数 73 行、`EngageRunExecution.kt` 最大函数 60 行 |
| §5 补编排层测试 | 🟡 部分 | `CheckinService`（7 例）+ `AutoRelogin`（9 例）+ `EngageService` 全链路特征测试（10 例）已补；`CrawlSaver` / `CrawlService` / 两个 Crawler 仍为 0，优先级写在 `docs/testing.md` §5 |

**两处暂缓项的处理（均已按上述顺序完成）**

- **`runInternal` 拆分**：按建议先补了 10 个特征测试（`app/src/test/.../EngageServiceRunTest.kt`，
  手写 fake 端口 + 可覆写 `EngageGateway` 薄层，不加 mockk），锁住 NO_TEXT 不进熔断、
  预算截断顺序、人工确认两段式等现有行为后，才动那 30+ 个共享可变局部变量——
  结果拆成 `runInternal` ~46 行编排器 + 新文件 `EngageRunExecution.kt`。
- **detekt**：等拆分落地后接入（顺序如上建议）。阈值与审查建议的差异：`LongMethod`
  用 100（拆分后最大函数 73 行，120 会放过回归）、`LargeClass` 用 650（拆完两文件
  各 649/718 行，其中 UI 无关的常量与注释占大头，600 会误伤 `EngageRunExecution.kt`）、
  `LongParameterList` 允许到 8 参（detekt 语义是「参数数 ≥ 阈值触发」，阈值 9 = 允许 8）。
  存量 6 条抓取链路违规（`AlbumCrawler`×2 / `CrawlSaver`×3 / `TimelineCrawler`×1，对应 §5
  「先补测试再重构」区域）圈进 baseline，新违规照拦。

---

## 0. 结论摘要

| 维度 | 评级 | 依据（一句话） |
| --- | --- | --- |
| 架构分层 | **A** | 4 模块依赖方向单一，端口-适配器闭环，CI 有一条真实生效的依赖门禁 |
| 代码整洁度 | **A-** | 零 TODO/FIXME、零 GlobalScope、空 catch 仅 6 处且语义成立；8 行以上实质代码块跨文件重复仅 1 处 |
| 安全与隐私 | **B+** | 权限最小化、Keystore 加密、WebView 无 JS 桥、出站域名白名单；扣分项见 A4/B3 |
| 测试 | **C+** | 纯函数 573 用例覆盖极好，但 7 个 domain 编排服务**零测试**，无覆盖率工具 |
| 可维护性 | **B** | 注释密度 16.2% 罕见地高；但 2 个函数 >500 行、10 个函数 >120 行，且无静态门禁约束 |
| 文档一致性 | **C** | `architecture.md` / `verification.md` 落后 4 个版本，关键数字（单测数、模块规模、路由数、lint 结论）全部失真 |

**整体判断**：这不是一份「需要抢救」的代码库。没有 P0/P1 级缺陷（无崩溃、无凭据泄露、无数据损坏路径），工程纪律明显高于同类个人项目。真正的问题集中在三处——**编排层无测试**、**复杂度无门禁**、**文档与实测脱节**。

**本次未发现**：内存泄漏、主线程磁盘 IO、SQL/命令注入、明文凭据落盘、导出组件越权、依赖存在已知高危 CVE 的迹象。

---

## 1. 审查范围与方法

### 1.1 覆盖

| 层 | 文件 | 行数 |
| --- | --- | --- |
| `:core` | 43 | 8,507 |
| `:data` | 11 | 2,397 |
| `:domain` | 12 | 3,955 |
| `:app`（main） | 61 | 14,877 |
| **生产代码合计** | **127** | **29,736** |
| `app/src/test` | 36 | 7,058 |
| `app/src/androidTest` | 3 | — |

注释 4,836 行 / 16.2%，空行 2,307，有效代码 22,618 行。平均单文件 234 行。

### 1.2 实测命令（本机 JDK 17，非 `--offline`）

```bash
JAVA_HOME=/c/Users/adsm/dev/jdk17 ./gradlew \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
  --console=plain -Pkotlin.compiler.execution.strategy=in-process
# BUILD SUCCESSFUL in 1m 51s（187 tasks，16 executed）

JAVA_HOME=/c/Users/adsm/dev/jdk17 ./gradlew :app:compileDebugKotlin \
  --rerun-tasks --console=plain -Pkotlin.compiler.execution.strategy=in-process
# BUILD SUCCESSFUL，3 条 Kotlin 告警
```

> 注：`env -i` 清空环境会破坏 Windows 下 Gradle 启动（静默退出码 0），本机需保留默认环境变量。

---

## 2. 实测基线（可复现）

| 项 | 数值 | 证据来源 |
| --- | --- | --- |
| 单测 | **573 用例 / 0 失败 / 0 跳过**（36 个类） | `app/build/test-results/testDebugUnitTest/*.xml` 汇总 |
| lint | **0 error / 14 warning** | `app/build/reports/lint-results-debug.xml` |
| lint 告警构成 | `UnusedResources` × 14，全部是 `strings.xml` 的 `engage_*` 文案 | 同上 |
| Kotlin 编译告警 | **3**（未使用参数/变量） | 见 B5 |
| debug APK | 15.35 MB | `app/build/outputs/apk/debug/app-debug.apk` |
| release APK | 4.11 MB（0.15.0 产物） | `dist/wcm-checkin-0.15.0-release.apk` |

**与仓库文档的口径差异**：`docs/verification.md` 第 1 节记的是「331 单测 / 四模块 lint 0 error 0 warning / release 3.63MB」。三项全部已过期（详见 A2）。

---

## 3. 做得好的部分（先说清基线，避免把优点当默认）

这些不是客套，是本次全量扫描里**实测得到**的结论：

1. **零代码坏味道标记**：全仓 `TODO / FIXME / XXX / HACK` = 0，`GlobalScope` = 0，单行空 `catch {}` = 0（跨行写空的 6 处见第 5 条）。仅有 7 处 `!!`，每处都有注释解释其依赖的不变量。
2. **重复度极低**：以 8 行滑动窗口 + 排除 import/注解/括号行做跨文件比对，实质重复块**只有 1 处**（`BrowsingViewModel.kt` 与 `PostPickViewModel.kt` 的一个提交收尾块）。
3. **文案 100% 资源化**：`stringResource(` 调用 332 处，UI 层硬编码中文字面量 **0 处**（脚本扫描 `Text/label/title/text/contentDescription = "…"` 与位置参数形式，均 0 命中）。
4. **架构门禁是真的**：`.github/workflows/build-apk.yml` 里 `grep 'project(":data")' domain/build.gradle.kts` 命中即 fail；四个模块的 build 文件也确实没有越界依赖，`core`/`data`/`domain` 里搜不到 `androidx.compose.*` 或 `android.app.Activity`。
5. **异常吞掉的地方大多留了痕**：`CrawlIndex.append` 写盘失败会写 `lastWriteError` 供抓取日志展示；`CrawlCursor` 的脏游标兜底有 4 行注释解释「为什么不能抛」。全仓真正写空的 `catch {}` 只有 6 处——`CrawlSettings.kt:40/165/186`（坏 JSON → 返回默认空表）、`LivePhotoConverter.kt:86/96`（`release()` 清理与降级到关键帧）、`CrawlIndex.kt:666`（`tmp.delete()` 兜底）——**语义都可接受**，只是没写注释，与项目自身「吞异常必留原因」的习惯不一致（这条记在 B8）。
6. **主线程无磁盘 IO**：`app/src/main` 内 `File()/readText/listFiles` 零命中；长任务统一挂 `AppScope`（SupervisorJob + IO + 异常处理器），并显式切换 `Dispatchers.IO`。
7. **性能细节有实测依据**：索引从「按 hash 线性扫描」改成 `filesByHash` O(1)；`LivePhotoConverter` 按可用堆 1/4 做帧预算，避免 110MB 一次性驻留；`delay` 替代 `Thread.sleep` 以便取消能打断。

---

## 4. 缺陷与风险清单

> 分级：**P2 = 应修**（有明确影响，但非阻塞）；**P3 = 可择机修**（整洁度/一致性）。本次无 P0/P1。

### A1 · 协程取消语义与其他模块不一致（P2）

**位置**：`domain/.../CheckinService.kt:105`、`:152`、`:177`

**现象**：三处 `catch (e: Exception)` 会把 `CancellationException` 一起吞掉。取消只会从两个挂起点进入——`pause()`（默认 `delay`）与 `listTopics()`（suspend）——一旦命中：

- `:152` 的循环内 catch 把取消记成「✗ 某超话：xxx」，`fail++`，然后因已取消，后续每轮 `pause` 立即抛出、被立刻再次吞掉，**循环会在极短时间内把剩余超话全部计为失败**；
- `:177` 的 catch 把取消记成「超话列表失败」；
- 两种路径都会调用 `noteResult()` 把**错误小结落库**，并继续在 finally 里 `updateCookie`。

**对照**：`AlbumCrawler.kt:124`、`CrawlEngine.kt:158`、`TimelineCrawler.kt:74`、`CheckInWorker.kt:115` 四处都写了 `catch (e: CancellationException) { throw e }`，注释还专门解释了「吞掉会让 doWork 谎报成功」。**只有签到编排这一处漏了**。

**当前是否已触发**：否。`CheckinService` 唯一调用点是 `HomeViewModel:821` 的 `AppScope.launch`，全仓没有对 `taskJob` 调 `.cancel()`，所以现在取消只发生在进程死亡时。但这是**结构上的一颗定时雷**：加一个「停止签到」按钮、或把签到接进 WorkManager，就会立刻变成「用户点了停止，账号却被打上失败小结」。

**建议**：

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ...
}
```

三处都补，与其余 4 个模块保持同一范式。

---

### A2 · 架构与验证文档落后 4 个版本（P2）

**位置**：`docs/architecture.md`、`docs/verification.md`

| 文档写的 | 实测 | 位置 |
| --- | --- | --- |
| 适用版本 `0.11.0`（versionCode 35） | `0.15.0`（versionCode 46） | architecture.md:3 |
| core 32 文件 / 5.4k 行 | **43 文件 / 8,507 行** | architecture.md:14 |
| data 10 文件 / 2.0k 行 | 11 文件 / 2,397 行 | architecture.md:15 |
| domain 11 文件 / 2.9k 行 | 12 文件 / 3,955 行 | architecture.md:16 |
| app 43 文件 / 10.4k 行 | **61 文件 / 14,877 行** | architecture.md:17 |
| 12 条 route | **14 条**（新增 `engage_texts`、`engage_posts` 等） | architecture.md:110 |
| 331 个 JVM 单测 | **573** | architecture.md:133、verification.md:11 |
| 四模块 lint 0 error **0 warning** | app 模块 **14 warning** | verification.md:13 |
| release APK 3.63 MB | **4.11 MB** | verification.md:14 |

**影响**：这两份文档是「新会话/AI 接手时的事实来源」，架构说明里甚至给出「加新功能往哪放」的决策表。数字失真本身不致命，但**「lint 零告警」这条已经不成立**会让后来者失去警觉。

**建议**：把「数字」从文档里抽掉，或在 CI 构建后自动回填；本次审查后至少更新一次版本号、模块规模、单测数、lint 结论四行。

---

### A3 · 22 处 `collectAsState()`，0 处生命周期感知收集（P2）

**位置**：全 `app/src/main`

```
collectAsState()              : 22
collectAsStateWithLifecycle(): 0
```

**影响**：页面进入后台（切到登录 WebView、按 Home 键）后，Flow 仍在收集并驱动重组。本项目恰恰有后台高频更新源——`AppScope` 里的签到/抓取进度、`HomeViewModel` 每秒的计时器、日志流——后台仍在跑重组属于纯浪费（弱机上有可测的电量与卡顿差异）。

**建议**：接 `androidx.lifecycle:lifecycle-runtime-compose`，把 Tab 级页面（`HomeTab`/`GalleryTab`/`EngageTab`/`TopicTab`/`SettingsTab`）的收集换成 `collectAsStateWithLifecycle()`。子页可保留（生命周期短）。

---

### A4 · 明文降级策略在两个凭证模块里方向相反（P2）

**位置**：`data/.../SecretBox.kt:110`（cookie/ALC）vs `data/.../CredentialVault.kt:22`（账号密码）

- `CredentialVault` 的原则写得很硬：解密失败抛 `CredentialUnreadableException`，注释明写「**绝不降级成明文**」；
- `SecretBox.sealOrPlain` 在 Keystore 异常时**静默写明文**，只在 logcat 留一条 `Log.w`，没有在 prefs 留标记，UI 完全不知道。

**风险**：cookie（SUB）是微博登录态的**全权凭据**——拿到它就能发帖、删帖、看私信。一旦某 ROM 的 Keystore 出问题，账号凭据会以明文躺在 `shared_prefs/wcm.xml` 里，而用户与开发者都无从察觉（日志只有 `加密失败，降级明文（长度 N）`）。

**建议**（不改可用性，只加可观测性）：降级时在 prefs 写一个 `wcm_sealed_degraded=true`，首页 Cookie 卡显示一行「本机加密不可用，凭据以明文保存」；或在 `migrateToSealed` 的重试路径里纳入判定。若判断该降级已无必要（minSdk 23 的 Keystore 稳定性在 2026 年足够），也可以直接改成与密码一致的硬失败。

---

### A5 · 索引的内部可变集合对外公开（P3）

**位置**：`core/.../CrawlIndex.kt:98`–`:109`

```kotlin
val posts = HashMap<String, PostRec>()          // public
val filesByPic = HashMap<String, FileRec>()     // public
val filesByName = HashMap<String, FileRec>()
val filesByHash = HashMap<String, FileRec>()
val labels = HashMap<String, String>()
```

类注释写的是「所有公开方法加锁」，但这 5 个 `HashMap` 绕过了锁。目前唯一的外部读者是 `data/.../CrawlMarks.kt:120-123`（用 `open()` 拿到**独立实例**后读取，不共享抓取实例，所以现在安全）。

**风险**：`CrawlPlanner` 之类的新代码一旦改成直接遍历共享实例的 `posts`，就是「迭代中扩容」→ `ConcurrentModificationException`，而且只在抓取高峰期偶发。

**建议**：改成 `private val`，对外暴露 `fun snapshotPosts(): Map<String, PostRec>`（内部 `synchronized(LOCK) { HashMap(posts) }`）与 `fun picIds(): Set<String>`；`CrawlMarks` 改用这两个方法。

---

### A6 · `execute()` 里三处 `target!!`（P3）

**位置**：`domain/.../EngageService.kt:869-871`

判空确实在 `:547` 做过（`planned.action != POST && target == null` → `continue`），所以**当前不会 NPE**。但这是「靠 60 行外的一次判断维持的类型不变式」，`execute()` 又是 private 且可被后续重构单独调用。

**建议**：把签名拆成 `executePost(...)` 与 `executeOnTarget(target: FeedPost, ...)`，让编译器而不是注释来保证非空。

---

### B1 · `AppShell` 顶层读取 IME 状态 → 键盘动画每帧重组整个壳（P3）

**位置**：`app/.../MainActivity.kt:154-155`，消费点 `:578`

```kotlin
val density = LocalDensity.current
val imeVisible = WindowInsets.ime.getBottom(density) > 0   // 在 AppShell 作用域读快照状态
```

`WindowInsets.ime` 在 IME 动画期间逐帧变化，这个读取发生在 `AppShell` 的函数体里，意味着**每一帧都会重组 `AppShell`**——而 `NavHost`（14 条 route）就在它的下游。配合项目已有的 `imePadding()`，属于重复触发。

**建议**：把底栏抽成 `private fun BottomBar(route, badge, onSelect)`，在**它内部**读 `imeVisible`；或 `remember { derivedStateOf { … } }` 把布尔值变化收敛成一次重组。

---

### B2 · `WebAjax` 自建 OkHttpClient，与全局口径重复（P3）

`core/.../WebAjax.kt:26` 新建了一个 `OkHttpClient`，超时配置与 `WeiboHttp.client` 完全一致（15s/25s），无特殊需求；`GuestSession.kt:82` 建独立 client 是**有理由的**（必须 `followRedirects(false)` 才能收全 Set-Cookie）。

**影响**：多一个 Dispatcher + ConnectionPool（各 5 条空闲连接、5 分钟保活），属于可省的资源。

**建议**：`WebAjax` 直接复用 `WeiboHttp.client`。

---

### B3 · 面板「私有网段」判定遗漏三类常见地址（P3）

**位置**：`core/.../PanelUrl.kt:55-73`

当前覆盖：`10/8`、`127/8`、`192.168/16`、`172.16/12`、`169.254/16`、`localhost`、`::1`、`.local`、`.lan`、`.home`。

遗漏：

| 遗漏 | 后果 |
| --- | --- |
| `100.64.0.0/10`（CGNAT，Tailscale/ZeroTier 的默认网段） | 会被判为公网 → 强制 https → 用户 `http://100.x.y.z:8080` 的面板直接不可用 |
| IPv6 ULA `fc00::/7`、链路本地 `fe80::/10` | 同上，纯 IPv6 内网部署被拒 |
| `.local` 允许 http | mDNS 可被局域网投毒解析到外部地址，明文策略被绕过（风险低，可接受但应写进注释） |

**建议**：补 `100.64/10` 与 IPv6 私网判定；这三类都是自建面板的真实部署形态。

---

### B4 · 14 个未使用字符串资源（P3）

`app/src/main/res/values/strings.xml` 第 492–609 行：`engage_counts_title`、`engage_count_post/repost/comment/like`、`engage_actions_title`、`engage_actions_note`、`engage_pace_post/comment/repost/like/note`、`engage_texts_open`、`engage_back`。

这是 0.14.0「界面文案迁入资源」那一版的残留。删掉可让 lint 回到 0 warning（也能让 A2 里那个「lint 零告警」的结论重新成立）。

---

### B5 · 3 条编译告警，其中 1 条是误导性 API（P3）

| 位置 | 告警 |
| --- | --- |
| `EngageProgress.kt:235` | `Parameter 'now' is never used` |
| `EngageSchedule.kt:64` | **`Parameter 'zone' is never used`** |
| `EngageTextPool.kt:132` | `Variable 'key' is never used` |

`EngageSchedule.forbiddenTextDays(today, strictTextGapDays, zone = ...)` 收了 `zone` 却完全没用（实现是 `LocalDate.parse(today, DAY)`，`today` 已是字符串）。这是唯一有实质误导的一条：调用方会以为传 `zone` 能改变「禁用哪些天」的时区口径，实际不能。

**建议**：删掉 `zone` 参数（调用点少），或真正用上；另外两条直接清理。

---

### B6 · 无静态分析门禁、无覆盖率工具（流程建议）

- 无 `detekt` / `ktlint` / `.editorconfig` / `spotless`；
- 无 `jacoco` / `kover`，覆盖率**不可测量**（这也是本报告第 5 节只能按「被测类是否出现」做定性判断的原因）；
- CI 只有三道闸：单测、编译、一条架构 grep。

**后果**：文件与函数规模没有任何约束地增长（见第 6 节的两个 500+ 行函数），只能靠人肉评审发现。

**建议**（按性价比排序）：

1. 先接 **kover**（AGP 原生支持，一条命令出报告），让「编排层覆盖率 0%」变成可见数字；
2. 再上 **detekt**，只开 `LongMethod(120)` / `LargeClass(600)` / `LongParameterList` 三条规则，先记为 warning 不阻断，把存量问题显性化；
3. 不建议现在开 ktlint 全量格式化——会与现有注释风格（中文长注释 + 特定换行）打架，收益低于噪音。

---

### B7 · 日期格式器散落 8 处，与「唯一出口」并存（P3）

`core/.../EngageSchedule.kt` 的类注释明确写着「时间与日期口径的**唯一出口**……此前散落着多处 SimpleDateFormat」。但实际上仍有 8 处在用 `SimpleDateFormat`：

```
GalleryViewModel.kt:77    HomeViewModel.kt:140,142,394    TopicViewModel.kt:68,70
CheckinService.kt:37,41   ReloginPolicy.kt:187
```

它们都是**纯展示**用途（`HH:mm:ss` / `M月d日 HH:mm`），没有参与「当日计数」这类键值计算，所以**不会**造成 EngageSchedule 注释里担心的那类不一致。但「唯一出口」的表述与现状不符。

**建议**：要么把展示用的格式化也收进 `EngageSchedule`（或另立 `WcmTime`），要么把 `EngageSchedule` 的注释改成「业务键唯一出口，展示格式化不在此限」。后者成本更低。

---

### B8 · 6 处空 `catch` 未写原因（P3）

| 位置 | 语义 |
| --- | --- |
| `CrawlSettings.kt:40` | 抓取目标列表 JSON 坏了 → 返回空表 |
| `CrawlSettings.kt:165` | 游标 JSON 坏了 → 返回空表 |
| `CrawlSettings.kt:186` | 旧版 `crawl_done` JSON 坏了 → 返回空表 |
| `LivePhotoConverter.kt:86` | `MediaMetadataRetriever.release()` 清理 |
| `LivePhotoConverter.kt:96` | `getFrameAtTime` 失败 → 降级到关键帧 |
| `CrawlIndex.kt:666` | 临时文件删除兜底 |

六处语义都成立（解析失败退回默认值 / 资源清理不能抛），**不会造成功能问题**。记在这里只因为项目自身在别处（如 `CrawlCursor.kt:163`、`CrawlIndex.kt:523`）都写了「为什么可以吞」，这六处破了同一条习惯——后来者无法一眼判断是「刻意」还是「忘了」。

**建议**：各补一行注释即可，不必改逻辑。

---

## 5. 测试覆盖：数量优秀，结构失衡

### 5.1 现状

| 分类 | 类 | 测试 |
| --- | --- | --- |
| 纯函数/算法（强） | `CrawlPlanner` `CrawlCursor` `CrawlProgress` `ReloginPolicy` `CookieLife` `AssetName` `PanelUrl` `GifEncoder` `EngagePlanner` `EngageGuard` `EngageCodec` `EngageTextPool` `EngageSchedule` `EngagePacing` `AccountStatusHead` `GalleryFiles` `Takeover` `Hashing` | 36 个测试类 / 573 用例 |
| **编排服务（零）** | `CheckinService` `AutoRelogin` `AccountMaintenanceService` `CrawlService` `CrawlEngine` `AlbumCrawler` `TimelineCrawler` | **0** |
| **本地存储（零）** | `CrawlSettings` `PanelSettings` `CrawlMarks` `SafAccess` `CredentialVault` `EngageStore`（`AccountStore` 仅纯函数部分被覆盖） | **0** |
| **UI 层** | 10 个 ViewModel 中仅 `HomeViewModel` 有 1 个测试；10 个 Screen 全部无测试 | 仅 `HomeNoAccountSmokeTest` 等 3 个仪器冒烟 |

`CrawlSaver` 只在 1 个测试里被引用（测的是 `slotsForPics` 纯函数），`WeiboHttp` 出现在 5 个测试文件里（测的是 URL 白名单与 Cookie 解析，不含请求重试）。

### 5.2 判断

这个分布恰好**把风险最高的部分留空了**：会写盘（`CrawlSaver`/`CrawlIndex`）、会发写请求（`EngageService`/`CheckinService`）、会改账号表（`AutoRelogin`）的逻辑全在 domain，而 domain 的测试数为 0。

不过要公平地说：这些类大量依赖 `Context` + 真实网络 + 文件系统，没有注入时钟以外的时间/网络端口，**当前形态确实不好测**。`EngageServiceTest` 能测，是因为它有 `EngagePorts` 两个窄端口 + `turnstile` 注入。

**建议（按 ROI 排序）**：

1. **`AutoRelogin` + `ReloginPolicy`**：`ReloginPolicy` 已纯函数化且已测，补 `AutoRelogin` 需要的是 `AccountPort` / `CredentialPort` 的 fake（两接口各 5 个方法），成本最低、收益最高（这是「24 小时 3 次」这类**风控安全边界**）。
2. **`CrawlSaver`**：把「三层去重」的判定从 `File` 依赖里抽成纯函数（`shouldSave(index, picId, hash, size)`），即可全覆盖。
3. **`CheckinService`**：把「逐条签到的失败分类」抽成纯函数（输入 `List<SignResult>`，输出 `AccountOutcome`），A1 的取消语义顺带也能测。
4. UI/VieModel 层不建议强上 Compose 测试，投入产出比明显低于上面三条。

---

## 6. 代码规模热点

### 6.1 超长函数（脚本按花括号匹配，已人工复核 Top 2）

| 行数 | 位置 | 说明 |
| --- | --- | --- |
| **587** | `EngageService.kt:154` `runInternal` | 一轮互动的全流程：配置 → 登录 → 关注校验 → 计划 → 逐条执行 → 收尾。内部用局部函数（`emit/log/stepStart/abort`）共享上下文，注释解释了「为什么不改成字段」 |
| **515** | `MainActivity.kt:141` `AppShell` | 壳 + 14 条 route 的装配，全部内联 lambda |
| 255 | `GalleryTab.kt:39` | 页面组装 |
| 221 | `EngageTextScreen.kt:46` | 文案库页 |
| 175 | `AlbumCrawler.kt:159` | 相册模式枚举 |
| 160 | `HomeTab.kt:52` | 首页组装 |
| 153 | `EngagePostLogScreen.kt:44` | 发帖记录页 |
| 142 | `WeiboPasswordLogin.kt:121` | SSO 登录 |
| 129 | `CrawlSaver.kt:84` | 单帖落盘 |
| 127 | `TopicScreen.kt:297` | 超话行卡片 |

> `>=120` 行共 11 个函数（另有 3 个是 Composable，属正常形态）。

### 6.2 判断

Compose 的 `XxxScreen`/`XxxTab` 长是**形态使然**（声明式 UI 天然竖向堆叠），不建议强拆。

真正值得处理的是 `runInternal`：它不是 UI，是一段**有 30+ 个可变局部变量**（`ok/fail/idx/breakFrom/stopReason/skipAction/targetQueue/...`）的业务流程，且**零测试覆盖**。0.12.x–0.14.0 连续三个版本都在这个函数里修 bug（评论 403 全灭、发帖被预算砍掉、后台永不执行），说明它的认知负荷已经在产生返工。

**建议**（不做大重构，只做可测性拆分）：把「单条动作执行 + 结果归类」抽成 `private suspend fun runOneAction(...)`，把「收尾汇总」抽成 `private fun finishRun(...)`。局部函数机制保留，`runInternal` 降到 200 行以内即可；同时这个拆分让第 5 节的第 3 条建议变得可行。

---

## 7. 安全与隐私复核

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| 权限 | **最小**：仅 `INTERNET` + `ACCESS_LOCAL_NETWORK`（API 37 局域网） | `AndroidManifest.xml:7,11` |
| 备份 | `allowBackup=false` + `dataExtractionRules` 全域排除（云备份与设备迁移都排） | `AndroidManifest.xml:19-21`、`xml/data_extraction_rules.xml` |
| 凭据加密 | AES-256-GCM，Android Keystore，密钥不可导出；密码与 cookie 用**不同 alias**（互不连累） | `SecretBox.kt:37`、`CredentialVault.kt:32` |
| 日志泄露 | 全仓日志中**无** cookie/密码/ALC 内容，只有长度与 uid | grep `Log.*(cookie|password|alc|token)` 仅 3 条命中，均为长度 |
| WebView | 无 `addJavascriptInterface`；`allowFileAccess/allowContentAccess=false`；`MIXED_CONTENT_NEVER_ALLOW`；导航白名单（微博域 + 新浪护照域），http 自动升 https | `LoginWebViewActivity.kt:106-137,166-180` |
| 出站策略 | 业务请求强制 https + 4 个域名白名单；面板公网强制 https，仅私有网段允许 http | `WeiboHttp.kt:153-165`、`PanelUrl.kt:39-46` |
| 组件导出 | 仅 `MainActivity`（launcher）导出；登录页 `exported=false` | `AndroidManifest.xml:43,54` |
| 注入面 | 拼进 URL 的 id 全过 `URLEncoder`；topicId 有正则白名单（`^[0-9A-Za-z]{8,64}$`） | `WeiboHttp.kt:74`、`EngageSchedule.kt:27` |
| 混淆 | release R8 + 资源收缩，Worker 类名显式 keep（跨版本 WorkSpec 依赖） | `proguard-rules.pro` |
| 待改进 | ① A4 明文降级；② `network_security_config` 全局放行明文（依赖应用层收敛，已在该文件注释里说明取舍）；③ 无证书固定（自建面板场景合理，不要求补） | — |

**结论**：安全设计是「想清楚过」的，取舍都写在代码注释里。唯一需要补的是 A4 的可观测性。

---

## 8. 并发与性能复核

**做得对的**：

- 长任务统一 `AppScope`（SupervisorJob + IO + `CoroutineExceptionHandler`），不随 View 销毁；
- `AccountStore` 有进程级 `LOCK` + 「prefs 原文未变则复用解析缓存」，并明确记录了「四写方并发会互相吞写」这一真实故障；
- `CrawlIndex` 全方法加锁、追加式 NDJSON、写放大从 O(n²) 降到 O(1)、`flush` 节流 8 条、待重试计数旁挂文件（避免为显示一个整数解析 17MB）；
- `WeiboThrottle` 用 `Mutex + delay` 而非 `Thread.sleep`，取消能打断；`locks.computeIfAbsent` 的注释指出了 `getOrPut` 在 CHM 上的竞态——这是很少见的正确细节；
- `LivePhotoConverter` 按可用堆 1/4 限制帧缓存；
- 抓取单闸门（companion `GATE`/`STOP`）保证「全应用同一时刻只有一个抓取」。

**扣分项**：A3（生命周期感知收集）、B1（IME 逐帧重组）、`AccountStore.load` 首次在 UI 线程做 N 次 Keystore 解密（有缓存缓解，5 账号约 10ms，可接受但值得记一笔）。

---

## 9. 修复建议排序

| 序 | 项 | 优先级 | 工作量 | 收益 |
| --- | --- | --- | --- | --- |
| 1 | **A1** 补 `CancellationException` 重抛（3 处） | P2 | 10 分钟 | 消除「停止/超时即写脏小结」的隐患，与其余 4 模块对齐 |
| 2 | **A2** 更新 architecture.md / verification.md 的版本与数字 | P2 | 30 分钟 | 恢复文档的可信度（它当前会误导下一个人与下一次 AI 会话） |
| 3 | **A4** 明文降级加可观测标记 | P2 | 1 小时 | 让「凭据明文落盘」这件事不再静默 |
| 4 | **B4 + B5 + B8** 清 14 个死资源 + 3 条编译告警 + 6 处空 catch 补注释 | P3 | 30 分钟 | lint 回到 0 warning，编译 0 告警 |
| 5 | **A3** 五个 Tab 换 `collectAsStateWithLifecycle` | P2 | 1 小时 | 后台不再无谓重组 |
| 6 | **B6-1** 接 kover 出覆盖率报告 | 流程 | 2 小时 | 把「编排层 0%」变成可见数字，后续补测有靶子 |
| 7 | **A5 + A6** 索引集合收敛 + `execute` 签名拆分 | P3 | 1.5 小时 | 消除两类「靠注释维持的不变式」 |
| 8 | **B3** 补 CGNAT / IPv6 私网判定 | P3 | 30 分钟 | Tailscale 用户的面板不再被误判为公网 |
| 9 | **B1 + B2** 底栏下沉 + 复用 client | P3 | 1 小时 | 键盘弹出时的重组与一个多余连接池 |
| 10 | **第 6 节** `runInternal` 拆两个私有函数 | P3 | 半天 | 降低这个「连修三个版本」的热点函数的认知负荷 |

---

## 10. 未验证项（诚实披露）

| 项 | 原因 | 状态 |
| --- | --- | --- |
| 真机/模拟器运行 | 本机无设备、无 emulator | **未验证**（沿用 `docs/verification.md` 2026-11-14 的记录） |
| 仪器测试（7 个用例） | 同上 | **未验证** |
| `assembleRelease`（R8/资源收缩） | 本轮只跑 debug，release 沿用 17:28 的产物 | **未验证**（文件存在，未复跑） |
| 覆盖率数字 | 项目未配置 kover/jacoco | **不可测** |
| 远端 tag / GitHub Release 状态 | 网络受限（`git ls-remote` 连接被重置） | **未验证**（本地 tag 到 v0.15.0 齐全） |
| 真实账号链路（签到/重登/发帖） | 无凭据 | **未验证**（与上一份报告同一项待办） |

已运行、可作为基线引用的：单测 573/0/0、lint 0 error/14 warning、Kotlin 编译 0 error/3 warning、debug APK 打包成功。
