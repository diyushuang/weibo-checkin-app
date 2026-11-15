package com.wcm.checkin

import android.content.Context
import org.json.JSONObject

/**
 * 相册抓取：Web 端相册墙枚举 + 游客优先取详情（18 图补全）。
 *
 * 从原 `Crawler` 拆出；时间线链路见 [TimelineCrawler]。
 */
class AlbumCrawler(
    private val settings: CrawlSettingsPort,
    private val account: AccountPort,
    private val engine: CrawlEngine,
    private val saver: CrawlSaver,
) {

    // ========================= 相册抓图模式（v0.9.8） =========================

    /** 每轮「补新」最多翻几页相册墙（每页约 20 张图）。 */
    private val ALBUM_NEW_PAGES = 2

    /** 每轮「补历史」最多翻几页相册墙。 */
    private val ALBUM_HISTORY_PAGES = 8

    /**
     * 全量（v0.9.12）每轮最多翻几页相册墙。
     *
     * 相册墙的游标本身就是「从头往下的位置」，所以相册模式没有时间线那种
     * 「覆盖区间」概念：全量与增量的唯一区别就是每轮走多深。已抓过的帖在索引里
     * 是终态，只花列表请求、不拉详情，20 页很便宜。
     */
    private val ALBUM_FULL_PAGES = 20

    /** 每轮每目标最多处理几帖（一帖可能带 18 张图，按帖计数比按图更可控）。 */
    private val ALBUM_MAX_POSTS = 40

    /** 单轮时间上限：一帖最多 18 张图，没有时间墙的话自动抓图会跑很久。 */
    private val ALBUM_MAX_MILLIS = 20 * 60 * 1000L

    /**
     * 相册墙一轮的结果。
     *
     * [lastMid] / [lastYm] 是「本轮走到的最深一条」（v0.9.17 的记忆用）：
     * 补新时把它存下来，下一轮翻墙碰到它就不再往更旧的页翻，不再把前面已抓过的帖
     * 重新枚举一遍（每轮重新扫是用户实际抱怨的点：没新帖时也照样重算一遍）。
     *
     * 本轮**没走完一整页**时 [lastMid] 留空 —— 页里还有没处理的 mid，
     * 那时把记忆推到中途某条，下一轮就会从它开始停住、把同页剩下的帖永久漏掉。
     */
    private class AlbumPass(
        val enumerated: Boolean,
        val saved: Int,
        val nextCursor: String,
        val reachedBottom: Boolean,
        val lastMid: String = "",
        val lastYm: String = "",
        /** 命中「上次抓到的那条」而提前收工（记忆本身不动）。 */
        val hitWatermark: Boolean = false,
        /** 本轮跳过的「索引里已抓过」条数（日志用，证明没白扫）。 */
        val skipped: Int = 0,
    )

    /**
     * 相册模式：用**游客身份**从相册墙枚举 `mid`，再逐帖取图。
     *
     * 为什么值得单独做一条链路：
     * 1. 相册墙只列**有图**的微博，跳过大量纯文字帖 —— 时间线要翻 10 帖才碰到几张图，
     *    相册墙一页 20 张图（约 5–8 帖）；
     * 2. 全程游客身份，不消耗账号频次与风控评分；
     * 3. 天然避开「时间线卡片对超 9 图帖只带部分图」的截断：逐帖取详情、以 `pic_ids` 为准。
     *
     * 逐帖取详情优先 `weibo.com/ajax/statuses/show`（游客），拿不到或张数不够时
     * **回退到账号 + m 站详情**（那里有帖级 `live_photo`，Live 动图不会退化）。
     *
     * @return 是否至少有一个目标成功枚举到相册墙。false 时上层回退到时间线模式。
     */
    suspend fun runAlbum(
        ctx: Context,
        log: (String) -> Unit,
        autoMode: Boolean = false,
        onEvent: (CrawlEvent) -> Unit = {},
        isStopped: () -> Boolean = { false },
        onlyUids: Set<String> = emptySet(),
        full: Boolean = false,
    ): Boolean {
        val targets = settings.selectTargets(settings.crawlTargets(ctx), onlyUids)
        val treeUriRaw = settings.crawlTreeUri(ctx)
        if (targets.isEmpty() || treeUriRaw.isEmpty()) {
            if (!autoMode) {
                log(
                    if (onlyUids.isEmpty()) "✗ 请先添加抓取目标并选择相册目录"
                    else "✗ 该博主已不在抓取目标里，刷新目标列表后再试",
                )
            }
            // 没有目标不算「相册模式失败」，不需要上层回退到时间线
            return true
        }
        val accounts = account.load(ctx)
        var anyEnumerated = false
        var downloaded = 0
        val startedAt = System.currentTimeMillis()

        onEvent(CrawlEvent.Begin(if (full) "相册全量" else "相册抓取", targets.size))
        if (onlyUids.isNotEmpty()) {
            log("范围：只抓 ${targets.joinToString("、") { it.name.ifEmpty { it.uid } }}")
        }
        engine.withCrawlContext(ctx, treeUriRaw, log) { state ->
            for ((index, target) in targets.withIndex()) {
                if (isStopped()) break
                if (System.currentTimeMillis() - startedAt > ALBUM_MAX_MILLIS) {
                    log("已达单轮时间上限（${ALBUM_MAX_MILLIS / 60000} 分钟），本轮先到这里")
                    break
                }
                val label = target.name.ifEmpty { target.uid }
                onEvent(CrawlEvent.Target(label, index + 1, targets.size))
                log("── $label（${target.uid} · 相册${if (full) " · 全量" else ""}）──")
                try {
                    val result = crawlAlbumTarget(
                        ctx, target, accounts, state, log, onEvent, startedAt, isStopped, full,
                    )
                    if (result.enumerated) anyEnumerated = true
                    downloaded += result.saved
                } catch (e: kotlinx.coroutines.CancellationException) {
                    state.index.flush()
                    throw e
                } catch (e: Exception) {
                    log("  ✗ 目标中断：${e.message}")
                }
                state.index.flush()
            }
            log("本轮共下载 $downloaded 个新文件，目录累计 ${state.store.count()}")
        }
        onEvent(CrawlEvent.Finish)
        return anyEnumerated
    }

    private class AlbumTargetResult(val enumerated: Boolean, val saved: Int)

    /**
     * 游客身份是否已被判定不可用。
     *
     * 一次运行内只要撞上一次「身份类」失败（403/432/非 JSON），后续帖子就直接走账号，
     * 不再每帖白打一次必然失败的请求，也不会把日志刷满。
     */
    private class GuestGate {
        var blocked = false

        /**
         * 本轮是否已经因「身份可疑」刷新过一次游客身份。
         *
         * 只刷一次：真被风控时反复握手除了刷日志没有任何收益，还会放大请求量。
         * 但完全不刷也不行 —— 游客 cookie 有一年有效期，换发前失效是常态，
         * 一次 403 就让整轮退化到时间线模式，等于白跑。
         */
        var refreshed = false
    }

    private suspend fun crawlAlbumTarget(
        ctx: Context,
        target: CrawlTarget,
        accounts: List<Account>,
        state: CrawlContext,
        log: (String) -> Unit,
        onEvent: (CrawlEvent) -> Unit,
        startedAt: Long,
        isStopped: () -> Boolean,
        full: Boolean = false,
    ): AlbumTargetResult {
        val uid = target.uid
        val sinceDate = settings.crawlSince(ctx, uid)
        val savedCursor = settings.albumSince(ctx, state.treeTag, uid)
        // 全量只是「每轮走得更深」，不需要新的断点字段：相册墙游标本身就是位置
        val historyPages = if (full) ALBUM_FULL_PAGES else ALBUM_HISTORY_PAGES

        // 「上次抓到哪条微博」的记忆（v0.9.17）。全量刻意不用它：全量的语义就是
        // 从头重新枚举一遍，拿记忆去提前收工会把它变成「增量」。
        val memoRaw = if (full) "" else settings.albumLastPost(ctx, state.treeTag, uid)
        val memoMid = AlbumMemo.midOf(memoRaw)
        val memoYm = AlbumMemo.ymOf(memoRaw)
        if (memoMid.isNotEmpty()) {
            val whenStr = if (memoYm.isNotEmpty()) "（$memoYm）" else ""
            log("  记得上次抓到最后一条$whenStr，本轮从它之后接着抓，前面的不再重扫")
        }

        // 账号只在需要回退时解析一次（探活有成本，不能每帖都做）
        var accountResolved = false
        var accountCookie: String? = null
        fun account(): String? {
            if (!accountResolved) {
                accountResolved = true
                accountCookie = engine.pickCrawlCookie(ctx, accounts, log)
            }
            return accountCookie
        }
        val guest = GuestGate()

        suspend fun pass(
            startCursor: String,
            maxPages: Int,
            maxPosts: Int,
            stopAtMid: String = "",
        ): AlbumPass {
            var cursor = startCursor
            var pages = 0
            var handled = 0
            var enumerated = false
            var saved = 0
            var skipped = 0
            // 本轮走到的最深一条（记忆用；中断在本页时不会被采用）
            var deepestMid = ""
            var deepestYm = ""
            // 碰到了「上次抓到的那条」：更旧的页不再翻，但本页要看完（见下方注释）
            var hitWatermark = false
            val seen = HashSet<String>()
            val floorYm = if (sinceDate.length >= 7) sinceDate.substring(0, 7) else ""

            /** 取一页相册墙；遇到「身份可疑」失败时刷新一次游客身份再重试。 */
            suspend fun wall(atCursor: String): WebAjax.WallPage? {
                repeat(2) { attempt ->
                    try {
                        return WebAjax.imageWall(uid, atCursor)
                    } catch (e: Exception) {
                        val reason = e.message ?: e.javaClass.simpleName
                        val suspect = e is WeiboHttp.WeiboException && e.identitySuspect
                        if (attempt == 0 && suspect && !guest.refreshed) {
                            guest.refreshed = true
                            log("  · 游客身份可能已失效（$reason），刷新后重试")
                            GuestSession.refresh()
                        } else {
                            log("  ✗ 相册墙第 ${pages + 1} 页失败：$reason")
                            return null
                        }
                    }
                }
                return null
            }

            while (pages < maxPages && handled < maxPosts && !isStopped() &&
                System.currentTimeMillis() - startedAt < ALBUM_MAX_MILLIS
            ) {
                val page = wall(cursor)
                    ?: return AlbumPass(enumerated, saved, cursor, false, deepestMid, deepestYm, skipped = skipped)
                enumerated = true
                // 墙上只有「年-月」，只能做粗粒度下界判定
                if (floorYm.isNotEmpty() && page.oldestYm.isNotEmpty() && page.oldestYm < floorYm) {
                    log("  已到起始日期（$sinceDate）之前，停止翻页")
                    return AlbumPass(enumerated, saved, "", true, deepestMid, deepestYm, skipped = skipped)
                }
                var pageDone = true
                for (mid in page.mids) {
                    if (isStopped() || handled >= maxPosts ||
                        System.currentTimeMillis() - startedAt > ALBUM_MAX_MILLIS
                    ) {
                        pageDone = false
                        break
                    }
                    // 回到上次抓到的那条：更旧的页不再翻 —— 这就是「增量从上次那条之后接着抓」。
                    // 但**本页要继续看完**：相册模式没有独立的 RETRY 阶段，本页里上次失败的帖
                    // 只能靠这里再走一遍（已终态的帖只花一次索引查询，不取详情、不重下）。
                    if (!hitWatermark && stopAtMid.isNotEmpty() && mid == stopAtMid) {
                        hitWatermark = true
                        log("  · 已回到上次抓到的那条，更旧的页不再翻（本页剩下的只补没抓完的）")
                    }
                    deepestMid = mid
                    deepestYm = page.ymByMid[mid].orEmpty()
                    if (state.index.isTerminal(mid) && state.index.failedSlots(mid).isEmpty()) {
                        skipped++
                        continue
                    }
                    handled++
                    saved += processAlbumPost(ctx, uid, mid, target, state, log, onEvent, guest) { account() }
                }
                pages++
                // 本页没走完就中断：游标必须停在**页首**。相册墙的 nextSinceId 是
                // 「本页最后一条」的位置，提前推进等于把本页剩下的 mid 直接跳过，
                // 而它们既不在 failed 槽位里、也不会进 RETRY —— 那些图永久缺失。
                if (!pageDone) {
                    log("  · 本轮在第 $pages 页中断（未处理完），游标留在页首，下轮从这条继续")
                    // 记忆同样不推进：本页还有没处理的 mid，记住中途某条会让下一轮
                    // 从它开始停住，同页剩下的帖就永久漏了
                    return AlbumPass(enumerated, saved, cursor, false, skipped = skipped)
                }
                if (hitWatermark) {
                    return AlbumPass(
                        enumerated, saved, cursor, false,
                        deepestMid, deepestYm, hitWatermark = true, skipped = skipped,
                    )
                }
                if (page.nextSinceId.isEmpty() || !seen.add(page.nextSinceId)) {
                    return AlbumPass(enumerated, saved, "", true, deepestMid, deepestYm, skipped = skipped)
                }
                cursor = page.nextSinceId
            }
            return AlbumPass(enumerated, saved, cursor, false, deepestMid, deepestYm, skipped = skipped)
        }

        // 1) 补新：从第一页扫，保证刚发的图能进来（不依赖历史游标走到哪）；
        //    碰到上次记下的那条就停，不再把它前面的帖重新枚举一遍
        val newPass = pass("", ALBUM_NEW_PAGES, ALBUM_MAX_POSTS, stopAtMid = memoMid)
        if (!newPass.enumerated) return AlbumTargetResult(false, 0)

        // 记忆推进：命中旧记忆（说明本轮没往下走）或本轮中断在页中（lastMid 为空）都不动
        if (!newPass.hitWatermark && newPass.lastMid.isNotEmpty() && newPass.lastMid != memoMid) {
            settings.setAlbumLastPost(
                ctx, state.treeTag, uid, AlbumMemo.encode(newPass.lastMid, newPass.lastYm),
            )
        }

        var saved = newPass.saved
        // 历史游标从「上次存下的位置」起算：补新只有真的翻到了更深处才覆盖它。
        // 命中记忆时补新停在页首（游标是空串，因为它从墙顶开始），那不是历史进度，
        // 直接拿它覆盖会把「已补到很旧的历史」整段抹掉，下一轮从墙顶重来。
        var nextCursor = if (newPass.nextCursor.isNotEmpty()) newPass.nextCursor else savedCursor
        var reachedBottom = newPass.reachedBottom

        // 2) 补历史：从存档游标继续。判据是「补新有没有真的走下去」：
        //    - 翻到了墙底 → 历史自然也没了；
        //    - 停在记忆那一页 → 墙下面还有没走过的部分，历史照跑；
        //    - 第 1 页就被预算打断（游标与 lastMid 都是空）→ 本轮到此为止，
        //      别让两个方向都从同一个计数起点再刷一遍。
        val newWentSomewhere = newPass.nextCursor.isNotEmpty() || newPass.lastMid.isNotEmpty()
        if (!newPass.reachedBottom && newWentSomewhere && savedCursor.isNotEmpty()) {
            val historyPass = pass(savedCursor, historyPages, ALBUM_MAX_POSTS)
            saved += historyPass.saved
            nextCursor = historyPass.nextCursor
            reachedBottom = reachedBottom || historyPass.reachedBottom
        }
        if (reachedBottom) nextCursor = ""

        settings.setAlbumSince(ctx, state.treeTag, uid, nextCursor)
        return AlbumTargetResult(true, saved)
    }

    /**
     * 相册模式下的单帖：取详情 → 组图清单 → 落盘。
     *
     * 两层兜底，专治「18 图只拿到 9 张」：
     * 1. 游客 Web 详情（不占账号）拿 `pic_ids`；
     * 2. 若 `pic_ids` 少于帖子自述的 `pic_num`（或游客这条路直接失败），
     *    回退账号 + m 站详情再取一次，取张数更多的那个结果；
     * 3. 仍然不足则告警（不阻塞），与参考实现 `_ensure_full_pics` 同口径。
     */
    private suspend fun processAlbumPost(
        ctx: Context,
        uid: String,
        mid: String,
        target: CrawlTarget,
        state: CrawlContext,
        log: (String) -> Unit,
        onEvent: (CrawlEvent) -> Unit,
        guest: GuestGate,
        account: () -> String?,
    ): Int {
        var detail: JSONObject? = null
        var assets: PostAssets? = null

        if (!guest.blocked) {
            // 两轮：第一轮失败且属于「身份可疑」时，刷新游客身份再试一次
            for (attempt in 0..1) {
                try {
                    val web = WebAjax.statusShow(mid)
                    detail = web
                    assets = PostAssetsParser.fromWebStatus(web)
                    break
                } catch (e: Exception) {
                    val reason = e.message ?: e.javaClass.simpleName
                    val suspect = e is WeiboHttp.WeiboException && e.identitySuspect
                    if (attempt == 0 && suspect && !guest.refreshed) {
                        guest.refreshed = true
                        log("  · 游客身份可能已失效（$reason），刷新后重试 $mid")
                        GuestSession.refresh()
                        continue
                    }
                    log("  · $mid 游客取详情失败（$reason）")
                    if (suspect) {
                        guest.blocked = true
                        log("  · 游客身份不可用，本轮后续直接走账号详情")
                    }
                    break
                }
            }
        }

        val declared = detail?.optInt("pic_num", 0) ?: 0
        val short = assets == null || assets.picIds.isEmpty() || (declared > 0 && assets.picIds.size < declared)
        if (short) {
            val cookie = account()
            if (cookie != null) {
                try {
                    val mDetail = WeiboPostApi.fetchPostDetail(cookie, mid)
                    val mAssets = PostAssetsParser.fromDetail(mDetail)
                    if (assets == null || mAssets.picIds.size > assets.picIds.size) {
                        detail = mDetail
                        assets = mAssets
                    }
                } catch (e: Exception) {
                    log("  ✗ $mid 账号详情也失败：${e.message}")
                }
            }
        }

        val d = detail
        if (d == null) {
            // 同 processPost：不落索引就等于「这帖从未来过」，相册游标会把整页翻过去
            state.index.onPostUnknown(mid)
            log("  ✗ $mid 取不到详情（已记入待重试）")
            onEvent(CrawlEvent.Post(saved = 0, failed = 0))
            return 0
        }
        val at = engine.epochOf(d.optString("created_at", ""))
        val a = assets
        if (a == null || a.picIds.isEmpty()) {
            state.index.onVoid(mid, at)
            onEvent(CrawlEvent.Post(saved = 0, failed = 0))
            return 0
        }
        if (declared > 0 && a.picIds.size < declared) {
            log("  ⚠ $mid 只识别到 ${a.picIds.size}/$declared 张（接口截断），已按现有图片继续")
        }

        val authorUid = d.optJSONObject("user")?.optString("id", "").orEmpty().ifEmpty { uid }
        val authorName = d.optJSONObject("user")?.optString("screen_name", "").orEmpty()
        val label = engine.resolveLabel(state.index, authorUid, authorName.ifEmpty { target.name.ifEmpty { target.uid } })
        val prefix = engine.prefixOf(at, label)
        val outcome = saver.savePost(ctx, state.store, state.index, prefix, mid, at, a, null, log)
        if (outcome.saved > 0) {
            log("  ✓ $prefix…（+${outcome.saved}${if (outcome.failed.isEmpty()) "" else "，缺 ${outcome.failed.size}"}）")
        }
        onEvent(CrawlEvent.Post(saved = outcome.saved, failed = outcome.failed.size))
        return outcome.saved
    }
}
