package com.wcm.checkin

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

/**
 * SAF 相册目录的轻量快照：单次查询缓存全部文件名与大小。
 *
 * 从 `Crawler.TreeStore` 拆出。抓取主流程与勾选/单条抓取共用同一份目录语义；
 * 独立成文件后可以单独测试/替换，不再和 1600 行的抓取编排绑在一起。
 */
class SafTreeStore(private val resolver: ContentResolver, treeUri: Uri) {
    private val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
    private val childrenUri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)

    /** 精确文件名 → 字节数。 */
    private val names = HashMap<String, Long>()

    /** 归一后的文件名（去掉 ` (1)` 撞名后缀）→ 字节数。 */
    private val canonicals = HashMap<String, Long>()

    fun loadExisting() {
        names.clear()
        canonicals.clear()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val n = cursor.getString(nameIdx) ?: continue
                remember(n, cursor.getLong(sizeIdx))
            }
        }
    }

    private fun remember(name: String, size: Long) {
        names[name] = size
        val c = AssetName.canonical(name)
        if (!canonicals.containsKey(c)) canonicals[c] = size
    }

    fun count(): Int = names.size

    /** 归一匹配：`x (1).jpg` 也算 `x.jpg` 已存在 —— SAF 撞名副本不会再被当成「没有」。 */
    fun hasCanonical(name: String): Boolean = canonicals.containsKey(AssetName.canonical(name))

    fun sizeOf(name: String): Long =
        names[name] ?: canonicals[AssetName.canonical(name)] ?: 0L

    /** 幂等写入：同名文件已存在视为成功（断点续跑不产生 "(1)" 副本）。 */
    fun save(name: String, mime: String, bytes: ByteArray): Boolean {
        if (hasCanonical(name)) return true
        if (bytes.isEmpty()) return false
        val docUri = try {
            DocumentsContract.createDocument(resolver, rootUri, mime, name)
        } catch (e: Exception) {
            null
        } ?: return false
        // createDocument 已经把文档建出来了；写入失败若就这样返回 false，
        // 相册里会留下一个 0 字节 / 截断的坏图，而下次 loadExisting() 会把它收进
        // canonicals → 判 EXISTS → 永远不再重下。所以失败必须回滚（删掉空文档）。
        val wrote = try {
            resolver.openOutputStream(docUri)?.use { out ->
                out.write(bytes)
                out.flush()
                true
            } ?: false
        } catch (e: IOException) {
            false
        } catch (e: Exception) {
            false
        }
        if (!wrote) {
            runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
            return false
        }
        remember(name, bytes.size.toLong())
        return true
    }
}
