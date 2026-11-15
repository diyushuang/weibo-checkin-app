package com.wcm.checkin

import android.content.Context
import kotlinx.coroutines.delay
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 逐槽位落盘与匿名下载：L1 文件名 → L2 picId 业务身份 → L3 内容哈希。
 *
 * 从 `Crawler` 拆出。抓取编排只负责「枚举哪些帖、帖里有哪些图」，
 * 「怎么把一张图安全地写进相册目录」集中在这里，便于单独测试与复用。
 */
class CrawlSaver(
    /** 抓取设置（GIF 开关等）。 */
    private val settings: CrawlSettingsPort,
) {

    private val DOWNLOAD_ATTEMPTS = 3    // 单文件下载尝试次数（CDN 偶发 403/超时自动重试）

    /** 单文件大小上限：Live mov 正常在 64MB 以内，超过直接放弃而不是撑爆内存。 */
    private val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** 大文件下载专用 client：Live mov 可达 64MB，放宽全局 client 的 25s callTimeout。 */
    private val downloadClient = WeiboHttp.client.newBuilder()
        .callTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class SaveOutcome(val saved: Int = 0, val failed: Set<Int> = emptySet())

    // ========================= 槽位选择（纯函数，JVM 单测） =========================
    //
    // 这三个放在 companion 里：它们是纯函数，单测直接 `CrawlSaver.slotsForPics(...)`，
    // 类化之后调用点零改动。

    companion object {

    /** picId 勾选 → 槽位集合。`null` = 不筛选（抓全部）；空集 = 一张都不抓。 */
    fun slotsForPics(assets: PostAssets, picked: Set<String>?): Set<Int>? {
        if (picked == null) return null
        if (picked.isEmpty()) return emptySet()
        val slots = LinkedHashSet<Int>()
        assets.picIds.forEachIndexed { i, pid ->
            if (pid in picked) {
                slots.add(
                    if (assets.liveByIndex.containsKey(i)) AssetSlots.pairedLive(i)
                    else AssetSlots.still(i)
                )
            }
        }
        return slots
    }

    fun notPickedSlots(
        allSlots: Set<Int>,
        onlySlots: Set<Int>?,
        countNotPicked: Boolean,
    ): Set<Int> = if (onlySlots == null || !countNotPicked) emptySet() else allSlots - onlySlots

    /**
     * 剔除「这帖已经没有的槽位」：只保留 [allSlots] 里真实存在的失败记录。
     *
     * 重试路径会先把上一轮的失败槽位整体灌进 failed，可槽位对应的图可能已经被博主删掉、
     * 或详情被截断以致解析不出来 —— 那种槽位这一次根本不会被尝试，没人会把它从 failed
     * 里拿走。留着它的后果是该帖永远「有失败」：停在 PARTIAL 到不了终态，每轮 RETRY
     * 都要白拉一次详情，还占着单轮 50 帖的重试预算。
     */
    fun retainExistingSlots(failed: MutableSet<Int>, allSlots: Set<Int>) {
        failed.retainAll(allSlots)
    }

    }

    // ========================= 逐槽位落盘（三层去重） =========================

    /**
     * 按槽位下载一帖的全部资产。
     *
     * 每个槽位独立判定，**只有真正成功或确认已存在才算完成**：
     * L1 文件名（含撞名归一）→ L2 picId 业务身份 → 下载 → L3 内容哈希（撞了就不重复写盘）。
     */
    suspend fun savePost(
        ctx: Context,
        store: SafTreeStore,
        index: CrawlIndex,
        prefix: String,
        postId: String,
        at: Long,
        assets: PostAssets,
        onlySlots: Set<Int>?,
        log: (String) -> Unit,
        countNotPicked: Boolean = false,
    ): SaveOutcome {
        var saved = 0
        val failed = LinkedHashSet<Int>()

        fun attempted(slot: Int) = onlySlots == null || slot in onlySlots
        // 重试只跑部分槽位：未成功的槽位要先继承下来，否则会把上次的失败记录抹掉
        if (onlySlots != null) failed.addAll(onlySlots)

        // 本贴理论上应有的全部槽位。勾选抓取时，**没勾选**的那些槽位既没下载、
        // 也不会进 failed —— 若不开除，failed 为空就会被判 DONE，永久毒化该帖：
        // 之后增量/历史/全量都用 isTerminal 跳过它，那几张图再也补不回来。
        val allSlots = HashSet<Int>()
        for (i in assets.picIds.indices) {
            allSlots.add(
                if (assets.liveByIndex[i] == null) AssetSlots.still(i)
                else AssetSlots.pairedLive(i)
            )
        }
        for (n in assets.liveExtras.indices) allSlots.add(AssetSlots.extraLive(n))
        val notPicked = notPickedSlots(allSlots, onlySlots, countNotPicked)

        /** 落盘第 i 张配图的静图；返回是否新写了文件。 */
        suspend fun saveStill(i: Int): Boolean {
            val slot = AssetSlots.still(i)
            if (!attempted(slot)) return false
            val picId = assets.picIds[i]
            val name = AssetName.stillName(prefix, i + 1)
            val r = saveOne(
                store, index, postId, slot, CrawlIndex.Kind.STILL,
                picId, name, "image/jpeg", log,
            ) { fetchBytes(WeiboImageUrl.originalImageUrl(picId), name, log) }
            return when (r) {
                SlotResult.SAVED -> {
                    failed.remove(slot); true
                }
                SlotResult.EXISTS -> {
                    failed.remove(slot); false
                }
                SlotResult.FAILED -> {
                    failed.add(slot); false
                }
            }
        }

        for (i in assets.picIds.indices) {
            val picId = assets.picIds[i]
            val wrapper = assets.liveByIndex[i]

            // 普通静图
            if (wrapper == null) {
                if (saveStill(i)) saved++
                continue
            }

            // Live 项：动图（gif/mov）就是这张图的全部内容，**不再另存配对静图**。
            // 旧逻辑先存 _01.jpg 再存 _01_live.gif，同一张图在相册里出现两遍。
            val liveSlot = AssetSlots.pairedLive(i)
            if (!attempted(liveSlot)) continue

            val existing = existingLiveName(store, prefix, i + 1, false, ctx)
            if (existing != null) {
                // 已存在（gif 或 mov 任一）：登记映射，避免后续重复判定
                index.onFileSaved(
                    postId, liveSlot,
                    if (existing.endsWith(".gif")) CrawlIndex.Kind.LIVE_PAIRED_GIF
                    else CrawlIndex.Kind.LIVE_PAIRED_MOV,
                    picId, "", store.sizeOf(existing), existing,
                )
                failed.remove(liveSlot)
                continue
            }

            when (saveLive(ctx, store, index, postId, liveSlot, picId, prefix, i + 1, false, wrapper, log)) {
                LiveResult.GIF_SAVED -> {
                    saved++
                    failed.remove(liveSlot)
                }
                // 只存了 mov：图库不显示视频，补一张静图当封面，否则这张图等于没抓到
                LiveResult.MOV_SAVED -> {
                    saved++
                    failed.remove(liveSlot)
                    if (saveStill(i)) saved++
                }
                LiveResult.FAILED -> {
                    failed.add(liveSlot)
                    saveStill(i)          // 动图彻底失败，至少把静图留下来
                }
            }
        }
        assets.liveExtras.forEachIndexed { n, wrapper ->
            val slot = AssetSlots.extraLive(n)
            if (!attempted(slot)) return@forEachIndexed
            val existing = existingLiveName(store, prefix, n + 1, true, ctx)
            if (existing != null) {
                index.onFileSaved(
                    postId, slot,
                    if (existing.endsWith(".gif")) CrawlIndex.Kind.LIVE_EXTRA_GIF
                    else CrawlIndex.Kind.LIVE_EXTRA_MOV,
                    "", "", store.sizeOf(existing), existing,
                )
                failed.remove(slot)
            } else if (saveLive(ctx, store, index, postId, slot, "", prefix, n + 1, true, wrapper, log) != LiveResult.FAILED) {
                saved++
                failed.remove(slot)
            } else {
                failed.add(slot)
            }
        }

        // notPicked 天然是 allSlots 的子集，这里只需剪掉 failed 里已经不存在的槽位。
        retainExistingSlots(failed, allSlots)
        index.onPostSealed(
            postId, at, assets.expectedFiles,
            saved > 0 || (failed.isEmpty() && notPicked.isEmpty()),
            failed + notPicked,
        )
        return SaveOutcome(saved, failed)
    }

    /** Live 可能存成 gif（默认）或 mov（关闭转换/转换失败），两种都算「已存在」。 */
    private fun existingLiveName(
        store: SafTreeStore,
        prefix: String,
        seq: Int,
        isExtra: Boolean,
        ctx: Context,
    ): String? {
        val gifOn = settings.crawlGif(ctx)
        val a = if (isExtra) AssetName.extraLiveName(prefix, seq - 1, gifOn)
        else AssetName.pairedLiveName(prefix, seq, gifOn)
        val b = if (isExtra) AssetName.extraLiveName(prefix, seq - 1, !gifOn)
        else AssetName.pairedLiveName(prefix, seq, !gifOn)
        return when {
            store.hasCanonical(a) -> a
            store.hasCanonical(b) -> b
            else -> null
        }
    }

    private enum class SlotResult { SAVED, EXISTS, FAILED }

    /** Live 的三种结局：转成 GIF / 只存了原始 mov / 彻底失败。 */
    private enum class LiveResult { GIF_SAVED, MOV_SAVED, FAILED }

    /** 单张静图：L2 → L1 → 下载 → L3。 */
    private suspend fun saveOne(
        store: SafTreeStore,
        index: CrawlIndex,
        postId: String,
        slot: Int,
        kind: CrawlIndex.Kind,
        picId: String,
        name: String,
        mime: String,
        log: (String) -> Unit,
        fetch: suspend () -> ByteArray?,
    ): SlotResult {
        // L2：同一 picId 已经落过盘（可能是别的帖、别的日期文件名）。
        // 必须比对文件类型：Live 的 mov 和它的封面静图共用同一个 picId，
        // 若只看 picId 会把「mov 已存」误判成「静图已存」，导致回退静图永远下不来。
        index.picOwner(picId)?.let { owner ->
            if (owner.kind == kind.ordinal && store.hasCanonical(owner.name)) {
                return SlotResult.EXISTS
            }
        }
        // L1：文件名（含 SAF 撞名副本）已在目录里
        if (store.hasCanonical(name)) {
            index.onFileSaved(postId, slot, kind, picId, "", store.sizeOf(name), name)
            log("  ↷ 排除重复 $name（相册目录已存在）")
            return SlotResult.EXISTS
        }
        val bytes = fetch() ?: return SlotResult.FAILED
        val hash = Hashing.sha256Hex128(bytes)
        // L3：内容级去重 —— 同一张图换个帖/换个日期名再出现时不再重复写盘
        index.byHash(hash)?.let { other ->
            if (store.hasCanonical(other.name)) {
                index.onPicMapped(postId, slot, kind, picId, hash, bytes.size.toLong(), other.name)
                log("  ↷ 排除重复内容 $name（与 ${other.name} 相同）")
                return SlotResult.EXISTS
            }
        }
        if (!store.save(name, mime, bytes)) {
            log("  ✗ $name 写入相册目录失败")
            return SlotResult.FAILED
        }
        index.onFileSaved(postId, slot, kind, picId, hash, bytes.size.toLong(), name)
        delay(Random.nextLong(600, 900))
        return SlotResult.SAVED
    }

    /**
     * 保存一个 Live Photo：优先转 GIF（设置开启且转换成功），失败或关闭时保存原始 mov，
     * 内容不丢。转换用 cacheDir 临时文件，相册目录不留 mov 残留。
     */
    private suspend fun saveLive(
        ctx: Context,
        store: SafTreeStore,
        index: CrawlIndex,
        postId: String,
        slot: Int,
        picId: String,
        prefix: String,
        index1: Int,
        isExtra: Boolean,
        wrapperUrl: String,
        log: (String) -> Unit,
    ): LiveResult {
        val kind = when {
            isExtra && settings.crawlGif(ctx) -> CrawlIndex.Kind.LIVE_EXTRA_GIF
            isExtra -> CrawlIndex.Kind.LIVE_EXTRA_MOV
            settings.crawlGif(ctx) -> CrawlIndex.Kind.LIVE_PAIRED_GIF
            else -> CrawlIndex.Kind.LIVE_PAIRED_MOV
        }
        val movName = if (isExtra) AssetName.extraLiveName(prefix, index1 - 1, false)
        else AssetName.pairedLiveName(prefix, index1, false)
        val mov = fetchBytes(wrapperUrl, movName, log)
        if (mov == null) {
            delay(Random.nextLong(600, 900))
            return LiveResult.FAILED
        }
        val gifName = if (isExtra) AssetName.extraLiveName(prefix, index1 - 1, true)
        else AssetName.pairedLiveName(prefix, index1, true)
        if (settings.crawlGif(ctx)) {
            val gif = LivePhotoConverter.movBytesToGif(ctx, mov)
            if (gif != null && store.save(gifName, "image/gif", gif)) {
                index.onFileSaved(postId, slot, kind, picId, Hashing.sha256Hex128(gif), gif.size.toLong(), gifName)
                delay(Random.nextLong(600, 900))
                return LiveResult.GIF_SAVED
            }
            if (gif == null) log("  ↷ $gifName GIF 转换失败，回退保存原始 mov")
        }
        val ok = store.save(movName, "video/quicktime", mov)
        if (ok) {
            index.onFileSaved(postId, slot, kind, picId, Hashing.sha256Hex128(mov), mov.size.toLong(), movName)
        } else {
            log("  ✗ $movName 写入相册目录失败")
        }
        delay(Random.nextLong(600, 900))
        return if (ok) LiveResult.MOV_SAVED else LiveResult.FAILED
    }

    // ========================= 下载 =========================

    /** 匿名下载（CDN 不需要登录态）。URL 必须过微博域名白名单；上限 64MB。
     *  失败自动重试 DOWNLOAD_ATTEMPTS 次（间隔递增），重试过程与原因都写入日志。 */
    suspend fun fetchBytes(rawUrl: String, label: String, log: (String) -> Unit): ByteArray? {
        var reason = "未知错误"
        for (attempt in 1..DOWNLOAD_ATTEMPTS) {
            if (attempt > 1) {
                delay(1200L * (attempt - 1))
            }
            val (bytes, why) = tryDownload(rawUrl)
            if (bytes != null) return bytes
            reason = why
            if (attempt < DOWNLOAD_ATTEMPTS) {
                log("  ↻ $label 下载失败（第 $attempt 次：$reason），重试…")
            }
        }
        log("  ✗ $label 下载失败（$reason，已尝试 $DOWNLOAD_ATTEMPTS 次）")
        return null
    }

    /** 单次下载尝试：返回 (字节, 失败原因)。成功时原因为空串。 */
    private fun tryDownload(rawUrl: String): Pair<ByteArray?, String> {
        return try {
            val url = WeiboHttp.weiboUrl(rawUrl)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "okhttp")
                .build()
            downloadClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null to "HTTP ${resp.code}"
                val body = resp.body ?: return null to "空响应"
                // 先看响应声明的长度：旧实现是「先 body.bytes() 全读进内存，再判 64MB」——
                // 一个异常的超大响应会在上限生效之前就把进程 OOM 掉。
                val declared = body.contentLength()
                if (declared > MAX_FILE_BYTES) {
                    return null to "超过 64MB 上限（声明 ${declared / 1024 / 1024}MB）"
                }
                val bytes = body.bytes()
                when {
                    bytes.isEmpty() -> null to "空文件"
                    bytes.size > MAX_FILE_BYTES -> null to "超过 64MB 上限"
                    else -> bytes to ""
                }
            }
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
    }
}
