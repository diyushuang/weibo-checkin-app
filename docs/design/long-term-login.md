# 长期登录：能不能让账号一直不失效

结论先行：**能，但靠的不是「刷新 Cookie」，而是「攒一份可再生的凭证，到期前用它重登一次」。**
而且本项目原有的这套机制此前**从未真正跑起来过**（见 §5），已随 0.12.4 修复。

---

## 1. 微博的会话模型（决定了一切）

| Cookie | 作用 | 寿命 |
| --- | --- | --- |
| `SUB` | 主站登录态（`weibo.com`） | 约 7 天；本项目按 7 天保守估算 |
| `SUBP` | 移动端登录态（`weibo.cn`） | 同上，扫码产出的 `SUBP` 缺 `et` 字段 |
| `ALF` | 名义到期时间戳（account login expire time） | 实测本账号为 30 天 |
| `SSOLoginState` | 真实登录时刻 | 不参与有效期，只作寿命起算基准 |
| `ALC` | 登录中心下发的**中长期票据** | 由登录中心决定，可用于免密码重签 |

`CookieLife` 的判定口径（`core/.../CookieLife.kt`）：

- 寿命基准 = `max(loginAt, rotatedAt, issuedAt)`
- 推算到期 = 基准 + 7 天；服务端到期 = `ALF`
- **两者取更早**作为最终判定；剩余 ≤ 2 天进入「临期」

## 2. 三条「续命」路径，实测结论

### 路径 A：滚动续期（定期发请求，让服务端换发 SUB）—— **不成立**

`AccountStore.patchAccount` 里有个严格口径：只有 `SUB / SUBP / SCF / SSOLoginState / ALF`
这些**关键字段真的变了**，才写 `rotatedAt`（= 视为续期）；`_T_WM` 这类普通字段变化不算。

本账号实测（2026-11-17）：

| 时刻 | 动作 | `rotateKeys` | `rotatedAt` |
| --- | --- | --- | --- |
| 12:59 | 扫码登录 | `login` | 12:59 |
| 13:10 | 超话发帖（`weibo.com/ajax/...`） | `login` | 12:59 |
| 13:15–13:17 | 评论 ×2、点赞 ×2（`weibo.com`） | `login` | 12:59 |
| 13:44 | 转发（`weibo.com`） | `login` | 12:59 |
| 13:45 | 签到 4 个超话（`m.weibo.cn`） | `login` | 12:59 |
| 13:49 | 探活（`m.weibo.cn/api/config`） | `login` | 12:59 |

跑了一整轮互动 + 签到 + 探活，**SUB 一次都没换发**。所以「只要保持活跃就能一直续下去」
这个假设在微博上不成立（至少这些请求形态下不成立）。

> 也不要去找「刷新接口」：`sso/updatetgt.php` + `sso/crossdomain.php` 那条旧更新链
> 会把现有凭证直接作废（社区实测：调用后 5 秒同一接口返回 401，恢复原 cookie 也救不回），
> 本项目明确永不调用（见 `WeiboAlcLogin` 类注释）。

### 路径 B：账号密码重登 —— **成立**

`WeiboPasswordLogin`：用保存的账号密码走 SSO，拿回**全新 7 天**的 `SUB/SUBP`。
这是最可靠的续命方式，代价是要在本机存一次密码（Android Keystore 加密，不上传面板）。

### 路径 C：ALC 票据重签 —— **成立，且不用交密码**

`WeiboAlcLogin`：用 `ALC` 走 `sso/login.php` 票据换会话，两臂（`miniblog` 写 `.weibo.com`、
`sinawap` 写 `.weibo.cn`）各换一次。不碰密码。

ALC 的获取方式（App 弹窗里也写了）：电脑浏览器登录 `weibo.com` →
DevTools → Application → Cookies → 找到 `ALC` 整条粘贴。**只需要一次**。

> **0.12.5 起不需要手动粘贴了**：实测扫码登录**会**下发 `ALC`，此前拿不到纯粹是
> App 没有提取逻辑。现在登录/探活时用 `WeiboAlcLogin.alcFromCookie()` 从 cookie 串里
> 自动取出并加密存进票据库（只在尚无 ALC 时写，不覆盖手动填的那份）。
> 实测：装上 0.12.5 后触发一次探活，`alcAt` 立即由 0 变为当前时刻、
> `wcm_credentials` 里出现 `alc_<uid>` 键。

## 3. 本项目已经实现了什么

```
CheckInWorker（每 6 小时）
   └─ 探活 verify → 临期(≤2天) 或 失效?
        └─ AutoRelogin.tryAutoRelogin()
             ├─ 有密码 → WeiboPasswordLogin（账密 SSO）
             └─ 只有 ALC → WeiboAlcLogin（票据重签）
                  └─ ReloginPolicy：24h 最多 3 次，失败指数退避，验证码/二次验证直接暂停
```

- 触发点：`CheckInWorker`（保活）、首页「立即重登」（`force=true` 越过退避）
- `AutoRelogin.cookieUsable` 要求状态 **OK** 才算可用 —— 也就是说
  **剩余 ≤2 天就触发重登**，不是等失效，属于主动提前
- 凭证存储：`CredentialVault`（密码）、`AlcVault`（ALC），都是 Keystore 加密

## 4. 当前账号的实际状态（2026-11-17 22:00）

| 字段 | 值 | 含义 |
| --- | --- | --- |
| `issuedAt` / `rotatedAt` / `loginAt` | 11-17 12:59 | 登录后没换发过 |
| `rotateKeys` | `login` | 同上 |
| `serverExpiresAt`（ALF） | 12-17 12:59 | 服务端名义 30 天 |
| 本地推算到期 | 11-24 12:59 | 登录 + 7 天，**取更早 → 实际按 11/24 判** |
| `credentialAt` | 0 | **没有保存密码** |
| `alcAt` | 0 | **没有 ALC** |
| `autoRelogin` | true | 开关开着，但无凭证 → 形同虚设 |

**所以：当前状态最多撑到 11/24，届时只能重新扫码。**

## 5. 曾经「有机制但不生效」的原因（已修）

`CheckInWorker.schedule()` 给周期任务加了 `NetworkType.CONNECTED`。WorkManager 判它时看
`NetworkState.isValidated`，而该标志来自系统对 `connectivitycheck.gstatic.com/generate_204`
的探测 —— 国内访问不到，恒为 `false`，约束永不满足。

实证（拉 `/data/data/.../no_backup/androidx.work.workdb`）：

```
CheckInWorker | state=ENQUEUED | run_attempt_count=0 | interval=6h | period_count=0
```

装了三天的 App，6 小时一次的保活 **一个周期都没跑过**。这条链路一断，探活换发、
临期重登、失效自愈全部失效 —— 用户看到的就是「到期了也不会自己续」。

0.12.4 已去掉该约束（保留 `RequiresBatteryNotLow`）。

**还有一个隐藏的二次坑**：`enqueueUniquePeriodicWork` 原本用的是
`ExistingPeriodicWorkPolicy.KEEP`。KEEP 会**保留数据库里已有的 work spec**，
所以只改约束不改策略的话，老用户升级后依然带着旧约束永远不跑 ——
修复等于没生效，而且从日志到界面都看不出来。已改为 `UPDATE`
（WorkManager 2.8+ 保留周期进度、只更新约束与参数）。

修完实测：

```
WM-GreedyScheduler: Constraints met: Scheduling work ... (generation=1)
WM-WorkerWrapper: Starting work for com.wcm.checkin.CheckInWorker
WM-WorkerWrapper: Worker result SUCCESS
```

账号 `seenAt` / `lastCheckAt` 随即更新到执行时刻（14:22），说明探活确实发出并落库了。

## 6. 让长期登录真正生效，需要三件事

1. **装 0.12.4 或更新版本**（保活恢复执行）
2. **给账号配一份可再生凭证**：账号行 `⋮` → 「自动重登 / 账号密码」→
   填密码 或 只填 ALC（二选一即可）
3. 确认设置里「自动重登（账号密码 / ALC）」是开着的（默认开）

之后的行为：每 6 小时探活一次；Cookie 剩余 ≤2 天时自动重登拿全新 7 天；
失败按 30s 起指数退避、24h 最多 3 次；遇到验证码/二次验证直接暂停并等人处理。

**理想的续命节奏**：每 5 天左右自动换一次新 cookie，用户永远不用扫码。

## 7. 已知限制与风险

- **不是永久**：重登本身也可能被要求验证码/二次验证/风控，那时只能人工扫码。
- **频繁重登有风控风险**：所以限制 24h 内 3 次，且有退避；不要手动连点「立即重登」。
- **ALC 也会过期**：失效后重新登录一次即可（0.12.5 起会自动收，不必手动复制）。
- **有效期口径（0.12.5 已调整）**：判定改为**服务端声明优先**（`ALF` / `Set-Cookie` 的
  Expires），没有服务端声明时才退回「登录 + 7 天」的经验值。
  调整前取两者更早者，导致实测账号在登录第 7 天就判临期（ALF 实际给的是 30 天），
  白触发一次重登、没凭证时直接判死，比真实失效早 23 天。
  提前失效的风险由 6 小时保活兜住：探活失败即 `markDead` → 立刻触发重登。
- **扫码登录会下发 ALC（已验证）**：见 §2 路径 C 的补充说明。0.12.5 实测 `alcAt`
  由 0 变为当前时刻、`wcm_credentials` 出现 `alc_<uid>` 键。
