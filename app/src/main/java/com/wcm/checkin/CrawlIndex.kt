package com.wcm.checkin

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * 抓取索引：去重的第二、三层载体 + 帖级完成状态的唯一真相。
 *
 * 存储为一个**追加式 NDJSON**（每行一条记录，同名键后者覆盖前者），
 * 放在 `filesDir/crawl/index/<treeTag>.ndjson`：
 *
 * | 类型 | 键 | 作用 |
 * | --- | --- | --- |
 * | `a` | uid | 锁定文件名里用的作者名（target 改名不再导致整库重下） |
 * | `p` | postId | 帖完成状态、期望槽位、失败槽位、重试退避 |
 * | `f` | picId + 文件名 | 已落盘文件的事实：picId / hash / size |
 * | `d` | 文件名 | 删除墓碑（图库删图后回退帖状态） |
 *
 * 为什么是追加式而不是共享首选项里的大 JSON：
 * - 崩溃安全：只追加不改写，断电最多丢最后几行，而文件已落盘 → L1 命中，不会重下；
 * - 写放大：旧 `crawl_done` 每帖全量序列化重写，O(n²)；
 * - 体积可控：6k 张 ≈1MB，30k ≈5MB，compact 后回收。
 *
 * 所有公开方法加锁：图库删图在 IO 线程调用 [onFileDeleted]，可能与抓取并发。
 */
class CrawlIndex private constructor(
    private val dir: File,
    val treeTag: String,
) {

    enum class Kind { STILL, LIVE_PAIRED_GIF, LIVE_PAIRED_MOV, LIVE_EXTRA_GIF, LIVE_EXTRA_MOV }

    object Status {
        const val OPEN = 0        // 见过，未完成
        const val PARTIAL = 1     // 部分成功，failed 列出缺的槽位
        const val DONE = 2        // 全部落盘
        const val VOID = 3        // 详情无图 / 纯视频帖，无需下载
    }

    data class PostRec(
        var postId: String,
        var at: Long,
        var status: Int,
        var expected: Int,
        val failed: MutableSet<Int> = LinkedHashSet(),
        var retryAt: Long = 0L,
        var attempts: Int = 0,
    ) {
        val terminal: Boolean get() = status == Status.DONE || status == Status.VOID
    }

    data class FileRec(
        var postId: String,
        var slot: Int,
        var kind: Int,
        var picId: String,
        var hash: String,
        var size: Long,
        var name: String,
    )

    data class LoadReport(
        val lines: Int = 0,
        val skipped: Int = 0,
        val posts: Int = 0,
        val files: Int = 0,
        val ms: Long = 0,
        val degraded: Boolean = false,
    )

    private val mainFile = File(dir, "$treeTag.ndjson")

    /** 待重试帖数的旁挂提示文件（几字节），供图片页在不解析索引的情况下展示。 */
    private val retryFile = File(dir, "$treeTag.retry")

    /** 延迟打开的追加句柄（compact 后需要重开，故用可变引用而非 lazy）。 */
    private var writer: BufferedWriter? = null

    private fun writerOrOpen(): BufferedWriter {
        writer?.let { return it }
        dir.mkdirs()
        val w = BufferedWriter(OutputStreamWriter(FileOutputStream(mainFile, true), Charsets.UTF_8))
        writer = w
        return w
    }

    private var dirtySinceFlush = 0
    private var lastCompactBytes = 0L
    private var loaded = false

    /** 待重试计数的节流缓存（-1 = 无缓存），见 [writeRetryHint]。 */
    private var retryHintCount = -1
    private var retryHintAtMs = 0L

    val posts = HashMap<String, PostRec>()
    val filesByPic = HashMap<String, FileRec>()
    val filesByName = HashMap<String, FileRec>()

    /**
     * 内容哈希 → 文件记录，L3 去重的索引。
     *
     * 早先 `byHash` 是在 `filesByName.values` 上做线性扫描，**每保存一张图 O(n)**：
     * 10 万文件时等于每张图都要比对十万条记录。这里维持一份哈希索引，查询退化成 O(1)。
     */
    val filesByHash = HashMap<String, FileRec>()
    val labels = HashMap<String, String>()

    /**
     * 索引最近一次写盘失败的原因（null = 正常）。
     *
     * 索引写失败不阻断抓取（文件已落盘，L1 仍能兜住），但**必须能被看见**：
     * 磁盘满 / 目录被删时用户只会观察到「进度莫名回退、图片重复下载」，
     * 没有这条记录就完全无从排查。
     */
    @Volatile var lastWriteError: String? = null
        private set

    // ========================= 读取 =========================

    /** 启动加载。坏行跳过（截断/非法 JSON），跳过率 >2% 判定为降级模式。 */
    fun load(): LoadReport {
        synchronized(LOCK) {
            if (loaded) {
                return LoadReport(
                    posts = posts.size, files = filesByName.size, degraded = degraded
                )
            }
            val start = System.currentTimeMillis()
            var lines = 0
            var skipped = 0
            // compact 中途崩溃留下的临时文件直接删掉
            val tmp = File(dir, "$treeTag.ndjson.new")
            if (tmp.exists()) tmp.delete()

            if (mainFile.exists()) {
                mainFile.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty()) return@forEachLine
                    lines++
                    if (!applyLine(line)) skipped++
                }
            }
            loaded = true
            register(this)
            lastCompactBytes = mainFile.length()
            val ratio = if (lines == 0) 0.0 else skipped.toDouble() / lines
            if (ratio > 0.02) degraded = true
            return LoadReport(
                lines = lines, skipped = skipped, posts = posts.size,
                files = filesByName.size,
                ms = System.currentTimeMillis() - start,
                degraded = degraded,
            )
        }
    }

    var degraded: Boolean = false
        private set

    private fun applyLine(line: String): Boolean {
        val o = try {
            JSONObject(line)
        } catch (e: Exception) {
            return false
        }
        return when (o.optString("t", "")) {
            "a" -> {
                val uid = o.optString("u", "")
                if (uid.isEmpty()) false else { labels[uid] = o.optString("l", ""); true }
            }
            "p" -> {
                val id = o.optString("i", "")
                if (id.isEmpty()) return false
                val rec = PostRec(
                    postId = id,
                    at = o.optLong("c", 0L),
                    status = o.optInt("s", Status.OPEN),
                    expected = o.optInt("n", 0),
                    retryAt = o.optLong("r", 0L),
                    attempts = o.optInt("y", 0),
                )
                o.optString("f", "").split(',').forEach { part ->
                    part.trim().toIntOrNull()?.let { rec.failed.add(it) }
                }
                posts[id] = rec
                true
            }
            "f" -> {
                val pic = o.optString("g", "")
                val name = o.optString("m", "")
                if (name.isEmpty()) return false
                // 与运行期 onFileSaved 同条件（!=）：旧条件把「同一个 picId」当脏数据删掉，
                // 重启后会残留另一条映射，picOwner 指错图 → 该图被误判 EXISTS 漏下
                filesByName[name]?.let { if (pic.isNotEmpty() && it.picId != pic) filesByPic.remove(it.picId) }
                val rec = FileRec(
                    postId = o.optString("i", ""),
                    slot = o.optInt("k", 0),
                    kind = o.optInt("v", 0),
                    picId = pic,
                    hash = o.optString("h", ""),
                    size = o.optLong("z", 0L),
                    name = name,
                )
                if (pic.isNotEmpty()) filesByPic[pic] = rec
                filesByName[name] = rec
                if (rec.hash.isNotEmpty()) filesByHash[rec.hash] = rec
                true
            }
            "d" -> {
                val name = o.optString("m", "")
                if (name.isEmpty()) {
                    false
                } else {
                    // 墓碑必须同时回退帖状态，否则重启后该帖仍是 DONE，
                    // 被删掉的那张图永远不会再补回来。
                    // （墓碑可能重复出现，未命中同样是合法记录。）
                    removeFileByName(name)?.let { applyRollback(it) }
                    true
                }
            }
            else -> false
        }
    }

    // ========================= L2：业务身份 =========================

    fun isTerminal(postId: String): Boolean = synchronized(LOCK) { posts[postId]?.terminal == true }

    fun expectedSlots(postId: String): Int = synchronized(LOCK) { posts[postId]?.expected ?: 0 }

    fun post(postId: String): PostRec? = synchronized(LOCK) { posts[postId] }

    fun failedSlots(postId: String): Set<Int> = synchronized(LOCK) {
        posts[postId]?.failed?.toSet() ?: emptySet()
    }

    /** 该 picId 是否已有落盘记录（跨帖同图去重，下载前即可判定）。 */
    fun picOwner(picId: String): FileRec? = synchronized(LOCK) { filesByPic[picId] }

    fun byName(name: String): FileRec? = synchronized(LOCK) {
        filesByName[name] ?: filesByName[AssetName.canonical(name)]
    }

    fun byHash(hash: String): FileRec? = synchronized(LOCK) {
        if (hash.isEmpty()) null else filesByHash[hash]
    }

    fun fileCount(): Int = synchronized(LOCK) { filesByName.size }

    fun postCount(): Int = synchronized(LOCK) { posts.size }

    fun labelOf(uid: String): String = synchronized(LOCK) { labels[uid] ?: "" }

    // ========================= 写入 =========================

    /** 锁定 uid 的文件名作者名（首次写入后不再变，target 改名不影响已抓文件）。 */
    fun lockLabel(uid: String, label: String) {
        if (uid.isEmpty() || label.isEmpty()) return
        synchronized(LOCK) {
            if (labels.containsKey(uid)) return
            labels[uid] = label
            append(JSONObject().put("t", "a").put("u", uid).put("l", label).toString())
        }
    }

    fun onFileSaved(postId: String, slot: Int, kind: Kind, picId: String, hash: String, size: Long, name: String) {
        synchronized(LOCK) {
            filesByName[name]?.let { if (picId.isNotEmpty() && it.picId != picId) filesByPic.remove(it.picId) }
            val rec = FileRec(postId, slot, kind.ordinal, picId, hash, size, name)
            if (picId.isNotEmpty()) filesByPic[picId] = rec
            filesByName[name] = rec
            if (hash.isNotEmpty()) filesByHash[hash] = rec
            append(
                JSONObject().put("t", "f").put("i", postId).put("k", slot).put("v", kind.ordinal)
                    .put("g", picId).put("h", hash).put("z", size).put("m", name).toString()
            )
            val p = posts[postId]
            if (p != null) {
                p.failed.remove(slot)
                appendPost(p)
            }
        }
    }

    /**
     * 同一张图（同 hash）已经在别的文件名下落盘：只登记 picId → 已存文件 的映射，
     * 不再写第二份字节。下次靠 L2 直接命中，不必重新下载。
     */
    fun onPicMapped(postId: String, slot: Int, kind: Kind, picId: String, hash: String, size: Long, storedName: String) {
        if (picId.isEmpty() || storedName.isEmpty()) return
        synchronized(LOCK) {
            val rec = FileRec(postId, slot, kind.ordinal, picId, hash, size, storedName)
            filesByPic[picId] = rec
            if (hash.isNotEmpty()) filesByHash[hash] = rec
            append(
                JSONObject().put("t", "f").put("i", postId).put("k", slot).put("v", kind.ordinal)
                    .put("g", picId).put("h", hash).put("z", size).put("m", storedName).toString()
            )
        }
    }

    /**
     * 接管相册里已有但索引没记录的文件（旧版本抓的、手动拷入的）。
     * 没有 picId 与 postId —— 目标只是让 L1/L3 认得它，避免下次重下。
     */
    fun adopt(name: String, size: Long, hash: String = ""): Boolean {
        synchronized(LOCK) {
            if (name.isEmpty()) return false
            val existing = filesByName[name]
            if (existing != null && existing.size == size && (hash.isEmpty() || existing.hash == hash)) return false
            val rec = existing ?: FileRec(
                postId = "", slot = 0, kind = Kind.STILL.ordinal, picId = "",
                hash = hash, size = size, name = name,
            )
            rec.size = size
            if (hash.isNotEmpty()) rec.hash = hash
            filesByName[name] = rec
            if (rec.hash.isNotEmpty()) filesByHash[rec.hash] = rec
            append(
                JSONObject().put("t", "f").put("i", rec.postId).put("k", rec.slot)
                    .put("v", rec.kind).put("g", rec.picId).put("h", rec.hash)
                    .put("z", rec.size).put("m", rec.name).toString()
            )
            return true
        }
    }

    /**
     * 帖级提交。`failed` 为空即 DONE；有失败但至少成功一个即 PARTIAL；全失败回 OPEN。
     * **只有 DONE / VOID 才算终态** —— 旧实现把「尝试过的槽位数」当完成，
     * 一次 404 就让该图永久缺席。
     */
    fun onPostSealed(postId: String, at: Long, expected: Int, savedAny: Boolean, failed: Set<Int>) {
        synchronized(LOCK) {
            val p = posts.getOrPut(postId) { PostRec(postId, at, Status.OPEN, expected) }
            p.at = if (at > 0L) at else p.at
            p.expected = expected
            p.status = when {
                failed.isEmpty() -> Status.DONE
                savedAny -> Status.PARTIAL
                else -> Status.OPEN
            }
            p.failed.clear()
            p.failed.addAll(failed)
            if (failed.isEmpty()) {
                p.retryAt = 0L
                p.attempts = 0
            } else {
                p.attempts += 1
                p.retryAt = System.currentTimeMillis() + backoffMs(p.attempts)
            }
            appendPost(p)
            dirtySinceFlush = 0
            flushLocked()
            writeRetryHint()
        }
    }

    /**
     * 详情没取到（网络 / 风控 / 接口报错）：帖的语义仍然未知。
     *
     * 三种写法都是错的：
     * - 不写 —— 索引不知道它存在，游标照样按页推进，这帖落进覆盖区间内部，永不补抓；
     * - 判 DONE —— 永久跳过；
     * - 判 VOID —— 当作无图帖，同样永久跳过。
     *
     * 这里落 OPEN + expected = -1（槽位未知）：非终态（列表扫描会再命中），
     * 同时进 RETRY 队列，按指数退避反复重试（上限 24h 一次）。
     */
    fun onPostUnknown(postId: String, at: Long = 0L) {
        synchronized(LOCK) {
            val p = posts.getOrPut(postId) { PostRec(postId, at, Status.OPEN, -1) }
            // 已有结论（DONE / VOID）的帖不要被一次失败降级
            if (p.terminal) return
            p.at = if (at > 0L) at else p.at
            p.expected = -1
            p.status = Status.OPEN
            p.attempts += 1
            p.retryAt = System.currentTimeMillis() + backoffMs(p.attempts)
            appendPost(p)
            dirtySinceFlush = 0
            flushLocked()
            writeRetryHint()
        }
    }

    /** 视频帖 / 无图帖：标记 VOID，避免反复拉详情。 */
    fun onVoid(postId: String, at: Long) {
        synchronized(LOCK) {
            val p = posts.getOrPut(postId) { PostRec(postId, at, Status.VOID, 0) }
            p.at = if (at > 0L) at else p.at
            p.status = Status.VOID
            p.failed.clear()
            p.retryAt = 0L
            appendPost(p)
            writeRetryHint()
        }
    }

    /**
     * 图库删掉一个文件 → 回退所属帖的状态，让它下一轮被补回来。
     *
     * 调用顺序铁律：**先物理删除成功，再调用本方法**。反过来（先回退再删）一旦删除
     * 失败，索引以为丢了会重下，而文件其实还在 → L1 又认领回来 → 死循环。
     *
     * 只动帖状态，**不动覆盖区间** —— 区间是「哪些日期抓过」的事实，删一张图不改变它。
     */
    fun onFileDeleted(name: String): Boolean {
        synchronized(LOCK) {
            val rec = removeFileByName(name)
            if (rec == null) {
                // 墓碑可能重复出现（例如删的是外部文件），照样记一笔，便于排查
                append(JSONObject().put("t", "d").put("m", name).toString())
                return false
            }
            append(JSONObject().put("t", "d").put("m", rec.name).toString())
            applyRollback(rec)
            posts[rec.postId]?.let { appendPost(it) }

            // 同一索引文件上可能还有别的实例活着（典型场景：抓取正在跑，
            // 用户在图库删了一张图）。不广播的话，它内存里那份 DONE
            // 会在下一次写盘时把墓碑覆盖掉，被删的图就再也补不回来。
            siblings().forEach { it.forgetInMemory(rec.name) }

            dirtySinceFlush = 0
            flushLocked()
            return true
        }
    }

    /** 只改内存、不写盘：给同文件的其它实例同步删除结果。 */
    private fun forgetInMemory(name: String) {
        removeFileByName(name)?.let { applyRollback(it) }
    }

    /** 把文件从帖里扣掉并回退完成状态（纯内存操作）。 */
    private fun applyRollback(rec: FileRec) {
        val p = posts[rec.postId] ?: return
        if (p.status != Status.DONE && p.status != Status.PARTIAL) return
        p.failed.add(rec.slot)
        // 全缺 → OPEN（本轮等于没做），其余 → PARTIAL；两种都会被 RETRY 捞起
        p.status = if (p.expected > 0 && p.failed.size >= p.expected) {
            Status.OPEN
        } else {
            Status.PARTIAL
        }
        p.retryAt = 0L      // 用户主动删的，下一轮立刻补，不要等退避
    }

    private fun removeFileByName(name: String): FileRec? {
        // 用户可能删的是 SAF 撞名副本 "x (1).jpg"，归一后再命中一次
        val rec = filesByName.remove(name)
            ?: filesByName.remove(AssetName.canonical(name))
            ?: return null
        if (filesByPic[rec.picId] === rec) filesByPic.remove(rec.picId)
        if (rec.hash.isNotEmpty() && filesByHash[rec.hash] === rec) filesByHash.remove(rec.hash)
        return rec
    }

    /**
     * 待重试：有失败槽位（或槽位未知，expected = -1）且退避时间已到。
     *
     * 「槽位未知」这批是详情请求失败的帖 —— 它们没有 failed 记录，
     * 却同样需要被捞起来重试，否则只会在列表扫描里空转。
     */
    fun pendingRetries(now: Long, limit: Int = 50): List<PostRec> = synchronized(LOCK) {
        posts.values
            .filter { retryable(it) && it.retryAt <= now }
            .sortedBy { it.at }
            .take(limit)
    }

    fun pendingRetryCount(): Int = synchronized(LOCK) {
        posts.values.count { retryable(it) }
    }

    private fun retryable(p: PostRec): Boolean =
        p.status != Status.VOID && (p.failed.isNotEmpty() || p.expected < 0)

    /** 旧版 `crawl_done` 迁移：`{uid: {postId: 尝试槽位数}}` → `p` 记录（DONE）。 */
    fun migrateLegacy(legacy: Map<String, Map<String, Int>>): Int {
        synchronized(LOCK) {
            var n = 0
            legacy.forEach { (_, inner) ->
                inner.forEach inner@{ (postId, attempted) ->
                    if (postId.isEmpty() || posts.containsKey(postId)) return@inner
                    val rec = PostRec(postId, 0L, Status.DONE, attempted)
                    posts[postId] = rec
                    appendPost(rec)
                    n++
                }
            }
            flushLocked()
            return n
        }
    }

    // ========================= 落盘 =========================

    private fun appendPost(p: PostRec) {
        val o = JSONObject()
            .put("t", "p")
            .put("i", p.postId)
            .put("c", p.at)
            .put("s", p.status)
            .put("n", p.expected)
            .put("f", p.failed.joinToString(","))
            .put("r", p.retryAt)
            .put("y", p.attempts)
        append(o.toString())
    }

    private fun append(line: String) {
        try {
            val w = writerOrOpen()
            w.write(line)
            w.write("\n")
            dirtySinceFlush++
            if (dirtySinceFlush >= 8) flushLocked()
        } catch (e: Exception) {
            // 索引写失败不能阻断抓取：文件已落盘，L1 仍能兜住。
            // 但要把原因留下来（Crawler 会写进日志），否则用户只能看到
            // 「进度莫名回退 / 图片重复下载」，无从排查。
            lastWriteError = e.message ?: e.javaClass.simpleName
        }
    }

    fun flush() {
        synchronized(LOCK) { flushLocked() }
    }

    /**
     * 把「待重试帖数」写成一个几字节的提示文件。
     *
     * 图片页每次进入都要显示「待重试 N 帖」，旧实现为此把整份 NDJSON 读进内存
     * （10 万张 ≈ 17MB 文件 / ≈25MB 内存），只为拿一个整数。这里落一份旁挂计数，
     * 页面直接读它；只有真要展开清单时才解析索引。
     */
    private fun writeRetryHint() {
        // 全表 count（O(n)）+ 写文件：每封一帖都做一次就是 O(n²)，
        // 而且全程占着进程级 LOCK，抓取主流程会被自己的进度写盘拖住。
        // 这个计数只用于界面显示「待重试 N 帖」，节流到 2 秒一次完全够新。
        val now = System.currentTimeMillis()
        if (retryHintCount >= 0 && now - retryHintAtMs < HINT_MIN_INTERVAL_MS) return
        val n = synchronized(LOCK) { posts.values.count { retryable(it) } }
        retryHintCount = n
        retryHintAtMs = now
        try {
            retryFile.writeText(n.toString())
        } catch (e: Exception) {
            // 提示文件写不出来只影响展示，不影响抓取
        }
    }

    /** 强制立即重算并落盘（close 时用，避免退出时 hint 是节流掉的旧值）。 */
    private fun writeRetryHintNow() {
        retryHintCount = -1
        writeRetryHint()
    }

    private fun flushLocked() {
        try {
            writer?.flush()
            lastWriteError = null
        } catch (e: Exception) {
            // 静默吞掉会让「进度莫名回退 / 图片重复下载」失去唯一的排查线索
            lastWriteError = e.message ?: e.javaClass.simpleName
        }
        dirtySinceFlush = 0
    }

    /** 体积超过 max(1MB, 1.5 × 上次 compact 后大小) 就重写一遍。 */
    fun maybeCompact(sinceDate: String = ""): Boolean {
        synchronized(LOCK) {
            val len = mainFile.length()
            val threshold = maxOf(1024L * 1024L, (lastCompactBytes * 3) / 2)
            if (len < threshold) return false
            return compact(sinceDate)
        }
    }

    /** 重写为 last-wins 之后的最小集合。先写 .new → 同步 → 原子 rename。 */
    fun compact(sinceDate: String = ""): Boolean {
        synchronized(LOCK) {
            val sinceSec = if (sinceDate.isEmpty()) null else CrawlDate.dayStartSec(sinceDate)
            val tmp = File(dir, "$treeTag.ndjson.new")
            try {
                dir.mkdirs()
                // 先关掉主文件写入句柄，保证后面的 rename 在所有平台都不会被占用
                flushLocked()
                reopenWriter()
                FileOutputStream(tmp).use { fos ->
                    val w = OutputStreamWriter(fos, Charsets.UTF_8)
                    labels.forEach { (uid, label) ->
                        w.write(JSONObject().put("t", "a").put("u", uid).put("l", label).toString())
                        w.write("\n")
                    }
                    posts.values
                        .filter { sinceSec == null || it.at <= 0L || it.at >= sinceSec }
                        .forEach { p ->
                            w.write(
                                JSONObject().put("t", "p").put("i", p.postId).put("c", p.at)
                                    .put("s", p.status).put("n", p.expected)
                                    .put("f", p.failed.joinToString(","))
                                    .put("r", p.retryAt).put("y", p.attempts).toString()
                            )
                            w.write("\n")
                        }
                    filesByName.values.forEach { f ->
                        w.write(
                            JSONObject().put("t", "f").put("i", f.postId).put("k", f.slot)
                                .put("v", f.kind).put("g", f.picId).put("h", f.hash)
                                .put("z", f.size).put("m", f.name).toString()
                        )
                        w.write("\n")
                    }
                    w.flush()
                    fos.fd.sync()
                }
                // 原子替换：renameTo 对已存在的目标就是原子覆盖（Linux/Android 的 rename(2)）。
                // 旧实现先 delete 主文件再 rename —— 进程在这两步之间被杀，整份索引直接归零，
                // 用户看到的是「所有图片被重新下载一遍」。
                // 不用 java.nio.file.Files —— 那是 API 26+，本项目 minSdk 23。
                if (!tmp.renameTo(mainFile)) {
                    // 少数文件系统不支持覆盖式 rename：退化为「备份 → 替换 → 删备份」，
                    // 任何一步失败都把备份还原，主文件在每一时刻都存在。
                    val bak = File(dir, "$treeTag.ndjson.bak")
                    bak.delete()
                    if (mainFile.exists() && !mainFile.renameTo(bak)) {
                        throw java.io.IOException("无法替换索引文件：$mainFile")
                    }
                    if (!tmp.renameTo(mainFile)) {
                        if (bak.exists()) bak.renameTo(mainFile)
                        throw java.io.IOException("无法替换索引文件：$mainFile")
                    }
                    bak.delete()
                }
                lastCompactBytes = mainFile.length()
                // 兄弟实例（图库删图时另开的同文件实例）的 writer 还指向刚被替换掉的旧
                // inode：不重开的话它们后续 append 全写进一个已 unlink 的文件，
                // 删图墓碑静默丢失 → 被删的图下一轮又"复活"。
                siblings().forEach { it.reopenWriter() }
                return true
            } catch (e: Exception) {
                tmp.delete()
                return false
            }
        }
    }

    /** compact 后旧句柄指向已被替换的文件，必须关掉重开。 */
    private fun reopenWriter() {
        try {
            writer?.close()
        } catch (e: Exception) {
        }
        writer = null
    }

    fun close() {
        synchronized(LOCK) {
            flushLocked()
            writeRetryHintNow()
            reopenWriter()
            unregister(this)
        }
    }

    /** 同一索引文件上的其它活实例（不含自己）。 */
    private fun siblings(): List<CrawlIndex> = synchronized(LOCK) {
        LIVE[mainFile.absolutePath].orEmpty().filter { it !== this }
    }

    private fun backoffMs(attempts: Int): Long {
        val base = 30L * 60_000L
        val shift = (attempts - 1).coerceIn(0, 6)
        return minOf(24L * 3600_000L, base shl shift)
    }

    companion object {
        /**
         * **进程级共享锁**，不是每个实例一把。
         *
         * 同一份索引文件会被两个实例同时持有：抓取流程一份，图库删图时另开一份。
         * 若各自用私有锁，删图的墓碑与抓取的状态写会互相覆盖 —— 被删的图再也补不回来。
         */
        private val LOCK = Any()

        /** 待重试计数的最小重算间隔：hint 只用于界面展示，宁旧不卡。（BUG-023） */
        private const val HINT_MIN_INTERVAL_MS = 2_000L

        /** 已加载的实例，按索引文件路径分组，用于把删除墓碑广播给同文件的其它实例。 */
        private val LIVE = HashMap<String, MutableSet<CrawlIndex>>()

        private fun register(idx: CrawlIndex) {
            LIVE.getOrPut(idx.mainFile.absolutePath) { LinkedHashSet() }.add(idx)
        }

        private fun unregister(idx: CrawlIndex) {
            val set = LIVE[idx.mainFile.absolutePath] ?: return
            set.remove(idx)
            if (set.isEmpty()) LIVE.remove(idx.mainFile.absolutePath)
        }

        fun open(filesDir: File, treeTag: String): CrawlIndex =
            CrawlIndex(File(filesDir, "crawl/index"), treeTag)

        /** treeUri → 8 位十六进制标签，用于索引文件命名与游标命名空间。 */
        fun treeTagOf(treeUri: String): String =
            Hashing.sha256Hex128(treeUri.toByteArray()).take(8)

        /**
         * 读「待重试帖数」提示文件。
         *
         * @return 计数；`-1` 表示还没有这份提示（老版本索引 / 首次运行），
         *         调用方此时才需要退化成解析索引。
         */
        fun retryHintOf(filesDir: File, treeTag: String): Int {
            val f = File(File(filesDir, "crawl/index"), "$treeTag.retry")
            if (!f.isFile) return -1
            return try {
                f.readText().trim().toIntOrNull() ?: -1
            } catch (e: Exception) {
                -1
            }
        }

        /** 只保留指定 tag 的索引文件，其余清理（换目录后避免无限增长）。 */
        fun prune(filesDir: File, keep: Set<String>) {
            val dir = File(filesDir, "crawl/index")
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { f ->
                val tag = f.name.removeSuffix(".new").removeSuffix(".ndjson").removeSuffix(".retry")
                if (tag.isNotEmpty() && tag !in keep &&
                    (f.name.endsWith(".ndjson") || f.name.endsWith(".new") || f.name.endsWith(".retry"))
                ) {
                    f.delete()
                }
            }
        }
    }
}
