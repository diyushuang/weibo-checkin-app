package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 首页账号行第一行（「今天签了吗」）的口径测试。
 *
 * 回归的现场：chip 上是「已签 7 / 待签 0」（打开 App 自动刷新超话得到的今日结论），
 * 同一行下面却写「尚未签到」（读的是本机 `lastResult`，而这个号本机没签过）。
 */
class AccountStatusHeadTest {

    @Test
    fun todaySnapshotAllDoneWins() {
        val head = AccountStatusHead.of(
            loaded = true, done = 7, total = 7,
            lastResult = "", lastResultToday = false,
        )
        assertEquals(AccountStatusHead.Head.TodayAll(7, 7), head)
    }

    @Test
    fun todaySnapshotWithPendingLeft() {
        val head = AccountStatusHead.of(
            loaded = true, done = 4, total = 7,
            lastResult = "", lastResultToday = false,
        )
        assertEquals(AccountStatusHead.Head.TodayLeft(4, 7), head)
    }

    @Test
    fun staleSnapshotFallsBackToTodaysSummary() {
        // 快照跨天 / 刷新失败（loaded=false）时，今天写的小结仍可当结论
        val head = AccountStatusHead.of(
            loaded = false, done = 0, total = 0,
            lastResult = "签到 1/7 · 09:10", lastResultToday = true,
        )
        assertEquals(AccountStatusHead.Head.LastToday("签到 1/7 · 09:10"), head)
    }

    @Test
    fun earlierSummaryMarkedAsLastNotToday() {
        val head = AccountStatusHead.of(
            loaded = false, done = 0, total = 0,
            lastResult = "签到 7/7 · 22:04", lastResultToday = false, lastResultAt = 1_700_000_000_000L,
        )
        assertEquals(
            AccountStatusHead.Head.LastEarlier("签到 7/7 · 22:04", 1_700_000_000_000L),
            head,
        )
    }

    @Test
    fun nothingKnownIsIdle() {
        val head = AccountStatusHead.of(
            loaded = false, done = 0, total = 0,
            lastResult = "", lastResultToday = false,
        )
        assertEquals(AccountStatusHead.Head.Idle, head)
    }

    @Test
    fun loadedButEmptyListFallsBack() {
        // 没关注超话（total=0）时 loaded 为假，别把 0/0 说成「今日已全部签到」
        val head = AccountStatusHead.of(
            loaded = false, done = 0, total = 0,
            lastResult = "", lastResultToday = false,
        )
        assertEquals(AccountStatusHead.Head.Idle, head)
    }

    @Test
    fun doneGreaterThanTotalIsClamped() {
        // 接口偶尔回 done > total（关注数变了），不能显示「已签 9/7」
        val head = AccountStatusHead.of(
            loaded = true, done = 9, total = 7,
            lastResult = "", lastResultToday = false,
        )
        assertEquals(AccountStatusHead.Head.TodayAll(7, 7), head)
    }

    @Test
    fun negativeDoneIsClamped() {
        val head = AccountStatusHead.of(
            loaded = true, done = -1, total = 7,
            lastResult = "", lastResultToday = false,
        )
        assertEquals(AccountStatusHead.Head.TodayLeft(0, 7), head)
    }
}
