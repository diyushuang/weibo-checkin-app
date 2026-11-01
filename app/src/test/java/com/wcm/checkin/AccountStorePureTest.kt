package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AccountStore 纯函数单元测试（不触 Android）：序列化往返 / 容错降级 / 合并式写回语义。 */
class AccountStorePureTest {

    private fun acc(
        uid: String,
        name: String = "账号$uid",
        cookie: String = "SUB=$uid",
        lastResult: String = "",
    ) = AccountStore.Account(uid, name, cookie, lastResult)

    // ---------- 序列化往返 ----------

    @Test
    fun serialize_deserialize_roundtrip() {
        val list = listOf(
            acc("1001", name = "小号", cookie = "SUB=a; SUBP=b", lastResult = "签到 3/3 · 12:00:00"),
            acc("1002", name = "", cookie = "SUB=c"),
        )
        val parsed = AccountStore.deserializeAccounts(AccountStore.serializeAccounts(list))
        assertEquals(2, parsed.size)
        assertEquals("1001", parsed[0].uid)
        assertEquals("小号", parsed[0].name)
        assertEquals("SUB=a; SUBP=b", parsed[0].cookie)
        assertEquals("签到 3/3 · 12:00:00", parsed[0].lastResult)
        assertEquals("1002", parsed[1].uid)
        assertEquals("SUB=c", parsed[1].cookie)
    }

    @Test
    fun deserialize_emptyAndMissingFields() {
        assertEquals(emptyList<AccountStore.Account>(), AccountStore.deserializeAccounts("[]"))

        val parsed = AccountStore.deserializeAccounts("""[{"uid":"1001","cookie":"SUB=a"}]""")
        assertEquals(1, parsed.size)
        // 缺 name 落默认值（与旧行为一致），缺 lastResult 落空串
        assertEquals("未命名账号", parsed[0].name)
        assertEquals("", parsed[0].lastResult)
    }

    @Test
    fun deserialize_malformed_fallsBackToEmpty() {
        // 存储损坏时静默降级为空列表，宁可丢账号也不能让 App 起不来
        assertEquals(emptyList<AccountStore.Account>(), AccountStore.deserializeAccounts("not-json"))
        assertEquals(emptyList<AccountStore.Account>(), AccountStore.deserializeAccounts("{}"))
    }

    // ---------- 合并式写回语义（Worker 与前台共用的防丢/防复活逻辑） ----------

    @Test
    fun mergeAccounts_overlaysCookieAndResultForExistingUid() {
        // 快照比库里新（任务期间刷新过 cookie）→ 快照胜出
        val current = listOf(
            acc("1001", cookie = "SUB=old", lastResult = "旧状态").copy(cookieSeenAt = 100L),
            acc("1002", cookie = "SUB=b", lastResult = "未动"),
        )
        val snapshot = listOf(
            acc("1001", cookie = "SUB=new", lastResult = "签到 5/5").copy(cookieSeenAt = 900L)
        )

        val merged = AccountStore.mergeAccounts(current, snapshot)

        assertEquals(2, merged.size)
        assertEquals("SUB=new", merged[0].cookie)
        assertEquals("签到 5/5", merged[0].lastResult)
        assertEquals("SUB=b", merged[1].cookie)      // 快照里没有的账号原样保留
        assertEquals("未动", merged[1].lastResult)
    }

    @Test
    fun mergeAccounts_staleSnapshotNeverClobbersNewerCookie() {
        // BUG-007 回归：后台保活 Worker 拿着「任务开始前」的旧快照结束时，
        // 用户可能已经在前台重新扫码登录过。旧实现无条件用快照覆盖 cookie，
        // 用户于是立刻掉登录，界面还显示「刚刚验证」。
        val current = listOf(
            acc("1001", cookie = "SUB=fresh", lastResult = "签到 3/3").copy(cookieSeenAt = 9_000L)
        )
        val snapshot = listOf(
            acc("1001", cookie = "SUB=stale", lastResult = "签到 0/3").copy(cookieSeenAt = 1_000L)
        )

        val merged = AccountStore.mergeAccounts(current, snapshot)

        assertEquals("SUB=fresh", merged[0].cookie)
        assertEquals("签到 3/3", merged[0].lastResult)
        // 时间锚点仍取更大值（谁的「最近有效」更晚就记谁）
        assertEquals(9_000L, merged[0].cookieSeenAt)
    }

    @Test
    fun mergeAccounts_noAnchorsFallsBackToCurrent() {
        // 两份都没有时间锚点（老版本数据）时保持库里的那份：宁可不动，也不要写回旧 cookie
        val current = listOf(acc("1001", cookie = "SUB=keep", lastResult = "库里的"))
        val snapshot = listOf(acc("1001", cookie = "SUB=snap", lastResult = "快照的"))

        val merged = AccountStore.mergeAccounts(current, snapshot)

        assertEquals("SUB=keep", merged[0].cookie)
        assertEquals("库里的", merged[0].lastResult)
    }

    @Test
    fun mergeAccounts_snapshotMembershipIgnored() {
        val current = listOf(acc("1001"))
        // 快照里多出的账号（运行期间用户刚加的，或已删账号的旧快照）不进入结果
        val snapshot = listOf(
            acc("1001", cookie = "SUB=new"),
            acc("9999", name = "刚添加"),
        )

        val merged = AccountStore.mergeAccounts(current, snapshot)

        assertEquals(listOf("1001"), merged.map { it.uid })
    }

    @Test
    fun mergeAccounts_nameAndUidNotOverwritten() {
        val current = listOf(acc("1001", name = "面板自定义名"))
        val snapshot = listOf(acc("1001", name = "另一个名字", cookie = "SUB=new"))

        val merged = AccountStore.mergeAccounts(current, snapshot)

        assertTrue(merged[0].name == "面板自定义名")
        assertEquals("1001", merged[0].uid)
    }

    @Test
    fun mergeAccounts_blankSnapshotUidNeverMatches() {
        val current = listOf(acc("1001", cookie = "SUB=keep"))
        val snapshot = listOf(acc("", cookie = "SUB=blank"))

        val merged = AccountStore.mergeAccounts(current, snapshot)

        assertEquals("SUB=keep", merged[0].cookie)
    }

    // ---------- cookie 时间锚点（CookieLife 的输入，落盘不能丢） ----------

    @Test
    fun serialize_deserialize_keepsCookieAnchors() {
        val acc = AccountStore.Account(
            uid = "1001",
            name = "小号",
            cookie = "SUB=a",
            cookieIssuedAt = 1000L,
            cookieRotatedAt = 2000L,
            cookieSeenAt = 3000L,
            cookieSyncedAt = 4000L,
        )
        val parsed = AccountStore.deserializeAccounts(AccountStore.serializeAccounts(listOf(acc)))
        assertEquals(1000L, parsed[0].cookieIssuedAt)
        assertEquals(2000L, parsed[0].cookieRotatedAt)
        assertEquals(3000L, parsed[0].cookieSeenAt)
        assertEquals(4000L, parsed[0].cookieSyncedAt)
    }

    @Test
    fun deserialize_legacyJsonWithoutAnchors_isZero() {
        // 0 = 没有锚点（旧版本账号），界面显示「未同步」而不是瞎猜一个到期时间
        val parsed = AccountStore.deserializeAccounts(
            """[{"uid":"1001","name":"老账号","cookie":"SUB=a","lastResult":""}]"""
        )
        assertEquals(0L, parsed[0].cookieIssuedAt)
        assertEquals(0L, parsed[0].cookieRotatedAt)
        assertEquals(0L, parsed[0].cookieSeenAt)
        assertEquals(0L, parsed[0].cookieSyncedAt)
    }

    @Test
    fun mergeAccounts_takesLatestAnchors() {
        val current = listOf(
            acc("1001").copy(cookieSeenAt = 500L, cookieSyncedAt = 900L)
        )
        val snapshot = listOf(
            acc("1001").copy(cookieSeenAt = 700L, cookieSyncedAt = 100L)
        )

        val merged = AccountStore.mergeAccounts(current, snapshot)

        // 取更大值：后台保活推进的「最近有效」不能被前台旧快照退回去
        assertEquals(700L, merged[0].cookieSeenAt)
        assertEquals(900L, merged[0].cookieSyncedAt)
    }

    @Test
    fun mergeAccounts_fillsPlaceholderNameFromSnapshot() {
        // 当前是占位名、快照已补到真实昵称 → 采用快照（后台保活补拉的昵称要能落地）
        val current = listOf(acc("1001", name = AccountStore.placeholderName("1001")))
        val snapshot = listOf(acc("1001", name = "真实昵称", cookie = "SUB=new"))

        val merged = AccountStore.mergeAccounts(current, snapshot)

        assertEquals("真实昵称", merged[0].name)
    }

    @Test
    fun cookieDead_persistsAndIsNotTakenFromSnapshot() {
        val dead = acc("1001").copy(cookieDead = true)
        val parsed = AccountStore.deserializeAccounts(AccountStore.serializeAccounts(listOf(dead)))
        assertTrue(parsed[0].cookieDead)

        // 快照里的「活着」不能把库里刚记下的失效抹掉（快照是任务开始前的状态）
        val merged = AccountStore.mergeAccounts(listOf(dead), listOf(acc("1001")))
        assertEquals(true, merged[0].cookieDead)
    }

    // ---------- 单博主抓取范围（v0.9.12） ----------

    private fun targets() = listOf(
        AccountStore.CrawlTarget("1001", "小号"),
        AccountStore.CrawlTarget("1002", "大号"),
        AccountStore.CrawlTarget("1003", ""),
    )

    @Test
    fun selectTargets_emptyMeansAll() {
        // 空集合 = 旧行为「全部抓取」，不能因为「filter 空集合」变成什么都不抓
        assertEquals(targets(), AccountStore.selectTargets(targets(), emptySet()))
    }

    @Test
    fun selectTargets_keepsListOrderAndOnlyRequestedUids() {
        val picked = AccountStore.selectTargets(targets(), setOf("1003", "1001"))
        assertEquals(listOf("1001", "1003"), picked.map { it.uid })
    }

    @Test
    fun selectTargets_unknownUidIgnored() {
        // 点完菜单到真正开跑之间目标可能被删：静默忽略，不能抛也不能把已删的当成范围
        val picked = AccountStore.selectTargets(targets(), setOf("9999"))
        assertTrue(picked.isEmpty())
    }
}
