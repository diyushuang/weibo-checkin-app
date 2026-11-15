package com.wcm.checkin

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 图库数据源（v0.4.0）：枚举 SAF 相册目录里的图片文件。
 *
 * - 过滤口径：mime 为 image 前缀直接收；mime 缺失或是八进制流时按扩展名兜底
 *   （SAF 创建的 jpg/gif 都带正确 mime，外部文件靠扩展名兜住）；
 * - 排序口径：文件名降序 = 最新在前（抓取文件名 `yyyy-MM-dd_HHmmss_作者_序号`）。
 * 过滤与排序是纯函数，随 CI 做 JVM 单测。
 */
object GalleryFiles {

    data class GalleryItem(val uri: Uri, val name: String, val size: Long)

    // ---------------- 浏览侧筛选 / 排序 / 分组（v0.9.0） ----------------
    // 这一层刻意做成纯函数：不碰 Uri 的解析、不读资源文案，随 CI 跑 JVM 单测。
    // 文案（「更早」「全部」）留给 UI，函数只产出可判定的值。

    enum class SortOrder { NEWEST, OLDEST, NAME_ASC, SIZE_DESC, SIZE_ASC }

    enum class KindFilter { ALL, STILL, GIF, MOV }

    data class Filter(
        val query: String = "",
        val kind: KindFilter = KindFilter.ALL,
        val label: String = "",
    )

    /**
     * 预解析过的行：文件名只在 [index] 里 parse 一次，之后筛选/搜索只比对内存字符串。
     *
     * 刻意存 `uri` 字符串而不是 `Uri` 对象：这一层要能在 JVM 单测里直接构造
     * （android.net.Uri 在本地单测里是抛 Stub 的桩），UI 需要时再 `Uri.parse`。
     */
    data class Row(
        val uri: String,
        val name: String,
        val size: Long,
        val date: String,      // "yyyy-MM-dd"；外部文件为 ""
        val label: String,     // 作者；外部文件为 ""
        val ext: String,       // 小写扩展名
    )

    data class Group(val key: String, val title: String, val count: Int, val rows: List<Row>)

    data class Summary(val total: Int, val shown: Int, val groups: Int)

    /** 没有日期/作者的桶键（UI 译成「更早」/「未分类」）。 */
    const val UNKNOWN_KEY = "__unknown__"

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    fun isImage(name: String, mime: String?): Boolean {
        val m = mime?.trim()?.lowercase().orEmpty()
        if (m.startsWith("image/")) return true
        // 有明确非图片 mime 时不看扩展名；未识别（含 octet-stream / 空）时按扩展名兜底
        if (m.isNotEmpty() && m != "application/octet-stream") return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTS
    }

    fun sortNames(names: List<String>): List<String> = names.sortedByDescending { it }

    /** 一次性把文件名反解成行（IO 线程调用）。 */
    fun index(items: List<GalleryItem>): List<Row> = items.map { item ->
        val parsed = AssetName.parse(item.name)
        Row(
            uri = item.uri.toString(),
            name = item.name,
            size = item.size,
            date = parsed?.date?.takeIf { it != "unknown" } ?: "",
            label = parsed?.label ?: "",
            ext = item.name.substringAfterLast('.', "").lowercase(),
        )
    }

    /** 关键词（文件名/作者/日期）+ 类型 + 作者三重过滤；query 忽略大小写。 */
    fun applyFilter(rows: List<Row>, filter: Filter): List<Row> {
        val q = filter.query.trim().lowercase()
        return rows.filter { row ->
            val kindOk = when (filter.kind) {
                KindFilter.ALL -> true
                KindFilter.STILL -> row.ext != "gif" && row.ext != "mov"
                KindFilter.GIF -> row.ext == "gif"
                KindFilter.MOV -> row.ext == "mov"
            }
            if (!kindOk) return@filter false
            if (filter.label.isNotEmpty() && row.label != filter.label) return@filter false
            if (q.isEmpty()) return@filter true
            row.name.lowercase().contains(q) ||
                row.label.lowercase().contains(q) ||
                row.date.contains(q)
        }
    }

    fun sortRows(rows: List<Row>, order: SortOrder): List<Row> = when (order) {
        // 时间排序以「日期」为主键、文件名为次序：名字里的日期是零填充的字典序，
        // 但外部文件（IMG_xxxx）没有日期，直接按名字排会插到最前面，把时间序打乱。
        SortOrder.NEWEST -> rows.sortedWith(
            compareByDescending<Row> { it.date }.thenByDescending { it.name }
        )
        SortOrder.OLDEST -> rows.sortedWith(
            compareBy<Row> { it.date.isEmpty() }.thenBy { it.date }.thenBy { it.name }
        )
        SortOrder.NAME_ASC -> rows.sortedBy { it.name }
        SortOrder.SIZE_DESC -> rows.sortedByDescending { it.size }
        SortOrder.SIZE_ASC -> rows.sortedBy { it.size }
    }

    /** 按日期分组，保持传入顺序（因此先排序再分组）；未知日期统一进 UNKNOWN_KEY 桶并排最后。 */
    fun groupByDate(rows: List<Row>): List<Group> {
        val buckets = LinkedHashMap<String, MutableList<Row>>()
        rows.forEach { row ->
            val key = row.date.ifEmpty { UNKNOWN_KEY }
            buckets.getOrPut(key) { ArrayList() }.add(row)
        }
        val known = buckets.filterKeys { it != UNKNOWN_KEY }
            .map { (key, list) -> Group(key, key, list.size, list) }
        val other = buckets[UNKNOWN_KEY]?.let { Group(UNKNOWN_KEY, "", it.size, it) }
        return if (other == null) known else known + other
    }

    /** 按博主分组，组头按张数降序（拍得多的账号在前），未知作者垫底。 */
    fun groupByLabel(rows: List<Row>): List<Group> {
        val buckets = LinkedHashMap<String, MutableList<Row>>()
        rows.forEach { row ->
            val key = row.label.ifEmpty { UNKNOWN_KEY }
            buckets.getOrPut(key) { ArrayList() }.add(row)
        }
        return buckets.map { (key, list) -> Group(key, key, list.size, list) }
            .sortedWith(
                compareBy<Group> { it.key == UNKNOWN_KEY }.thenByDescending { it.count }
            )
    }

    /** 作者 pill 用的候选：[作者, 张数]，按张数降序，未知作者不列。 */
    fun labelTabs(rows: List<Row>): List<Pair<String, Int>> =
        rows.filter { it.label.isNotEmpty() }
            .groupingBy { it.label }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }

    fun summarize(total: Int, shown: Int, groups: Int): Summary = Summary(total, shown, groups)

    /** 列出目录内全部图片（最新在前）。目录不可读时抛出的异常由调用方兜底。 */
    fun listImages(resolver: ContentResolver, treeUri: Uri): List<GalleryItem> =
        query(resolver, treeUri, imagesOnly = true)

    /**
     * 列出目录内**全部文件**（含 `.mov`，即 Live 未转 GIF 时的原始视频）。
     * 接管去重必须用它：`listImages` 会按 mime 把 mov 排除，导致这些文件永远认领不到。
     */
    fun listAll(resolver: ContentResolver, treeUri: Uri): List<GalleryItem> =
        query(resolver, treeUri, imagesOnly = false)

    private fun query(resolver: ContentResolver, treeUri: Uri, imagesOnly: Boolean): List<GalleryItem> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        val names = ArrayList<GalleryItem>()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                if (name.isEmpty()) continue
                val mime = cursor.getString(mimeCol)
                if (imagesOnly && !isImage(name, mime)) continue
                val docId = cursor.getString(idCol) ?: continue
                names.add(
                    GalleryItem(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        name = name,
                        size = cursor.getLong(sizeCol),
                    )
                )
            }
        }
        return names.sortedByDescending { it.name }
    }
}
