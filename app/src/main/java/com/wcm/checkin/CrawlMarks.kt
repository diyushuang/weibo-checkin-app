package com.wcm.checkin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「哪些图已经抓过」的本地快照（v0.9.18）。
 *
 * 预 览页要回答的问题很简单：这张图我是不是已经抓过了？答案在 [CrawlIndex] 里 ——
 * `f` 记录（picId → 文件名）就是「这张已经落盘」的事实，`p` 记录的终态就是
 * 「这帖已经完整抓过」。问题只在于索引是追加式 NDJSON，要读就得先全量解析。
 *
 * 所以这里的取舍是：
 * - **一次性快照，不长期持有索引**：解析完只留两份 `HashSet`（picId 与已完成帖 id），
 *   索引对象立刻 `closeReadOnly` 放掉 —— 三十万条记录的索引在内存里是几十 MB，
 *   而浏览页只需要「有 / 没有」两个布尔。
 * - **按索引文件 mtime 判断要不要重读**：抓完一轮文件变了才重解析，页面来回切不重复读。
 * - **宁缺勿错**：没加载完 / 索引缺失 / 文件超大时一律当作「没有标记」，
 *   不会给一张没抓过的图打勾。
 * - 只读收尾用 [CrawlIndex.closeReadOnly]：拿加载时的旧快照回写「待重试」提示，
 *   会把并发抓取刚算出的数字盖旧。
 *
 * 线程约定：加载完的回调在主线程执行（页面直接刷新视图），[ensure] 也只应在主线程调。
 */
object CrawlMarks {

    private const val TAG = "wcm"

    /** 索引超过这个体积就跳过解析：几十万条时要好几秒，不值得为了标记拖住预览页。 */
    private const val MAX_INDEX_BYTES = 32L * 1024 * 1024

    /** 已抓取的图片 picId 与已完成（DONE / VOID）的帖 id。 */
    class Snapshot(val pics: Set<String>, val posts: Set<String>) {
        fun hasPic(picId: String): Boolean = picId.isNotEmpty() && pics.contains(picId)
        fun isPostDone(postId: String): Boolean = postId.isNotEmpty() && posts.contains(postId)
    }

    private val EMPTY = Snapshot(emptySet(), emptySet())

    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var loadedTag: String = ""
    @Volatile private var loadedMtime: Long = 0L

    /** 正在加载哪个 tag（主线程读写）。 */
    private var loadingTag: String = ""

    /** 等这次加载结果的回调（主线程读写）。 */
    private val waiters = ArrayList<() -> Unit>()

    /** 当前快照；从未加载 / 已失效时为 null。 */
    fun current(): Snapshot? = snapshot

    /** 抓取完成、图库删图之后调一次：下次 [ensure] 会重读索引。 */
    fun invalidate() {
        snapshot = null
        loadedTag = ""
        loadedMtime = 0L
    }

    /** 便捷判定：这张图抓过没有（快照没就绪时恒为 false）。 */
    fun hasPic(picId: String): Boolean = snapshot?.hasPic(picId) == true

    /**
     * 确保快照就绪；就绪（或索引没变）时立即回调，否则后台加载完再回调。
     * 回调**一定**会在主线程被调用一次。
     */
    fun ensure(ctx: Context, onReady: () -> Unit) {
        val treeUri = AccountStore.crawlTreeUri(ctx)
        if (treeUri.isEmpty()) {
            onReady()
            return
        }
        val tag = CrawlIndex.treeTagOf(treeUri)
        val file = indexFile(ctx, tag)
        val mtime = file.lastModified()
        if (snapshot != null && loadedTag == tag && loadedMtime == mtime) {
            onReady()
            return
        }
        waiters.add(onReady)
        // 已在加载：等它结束时统一回调（那是同一次加载，读的是同一份文件）
        if (loadingTag.isNotEmpty()) return
        loadingTag = tag
        val app = ctx.applicationContext
        AppScope.launch {
            val snap = withContext(Dispatchers.IO) { load(app, tag, file) }
            withContext(Dispatchers.Main) {
                // 加载期间换了相册目录：这一份作废，别把旧目录的标记刷到新目录上
                val nowTag = CrawlIndex.treeTagOf(AccountStore.crawlTreeUri(app))
                if (tag == nowTag) {
                    snapshot = snap
                    loadedTag = tag
                    loadedMtime = file.lastModified()
                }
                loadingTag = ""
                val pending = ArrayList(waiters)
                waiters.clear()
                pending.forEach { it() }
            }
        }
    }

    private fun indexFile(ctx: Context, tag: String): File =
        File(File(ctx.filesDir, "crawl/index"), "$tag.ndjson")

    /** 解析索引 → 两份集合。失败一律返回空快照（不抛）。 */
    private fun load(ctx: Context, tag: String, file: File): Snapshot {
        if (!file.isFile) return EMPTY
        if (file.length() > MAX_INDEX_BYTES) {
            Log.i(TAG, "抓取索引 ${file.length()}B 过大，本次不做「已抓取」标记")
            return EMPTY
        }
        val idx = CrawlIndex.open(ctx.filesDir, tag)
        return try {
            idx.load()
            val pics = HashSet<String>(idx.filesByPic.size)
            pics.addAll(idx.filesByPic.keys)
            val posts = HashSet<String>()
            idx.posts.forEach { (id, rec) -> if (rec.terminal) posts.add(id) }
            Snapshot(pics, posts)
        } catch (e: Exception) {
            Log.w(TAG, "读取抓取索引失败（已抓取标记不可用）：${e.message}")
            EMPTY
        } finally {
            runCatching { idx.closeReadOnly() }
        }
    }
}
