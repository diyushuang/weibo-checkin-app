package com.wcm.checkin

import androidx.core.net.toUri
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF 相册目录的可用性自检（v0.9.0）。
 *
 * 背景：目录授权会被系统回收（清理存储、卸载重装、用户在设置里撤销），
 * 旧实现只在下一次抓取时以一句「写入失败」暴露，用户不知道是授权没了。
 * 这里在进入页面/开抓前主动探一次，把三种失效原因分开说清楚。
 */
object SafAccess {

    sealed interface Status {
        /** 可读可写。 */
        data object Ok : Status

        /** 还没选目录。 */
        data object Missing : Status

        /** 目录 URI 还在，但持久授权已被撤销 —— 需要重新选一次（不是重新登录）。 */
        data object NoPermission : Status

        /** 授权还在，但 provider 打不开（U 盘拔出、云盘掉线等）。 */
        data class Unreadable(val message: String) : Status
    }

    fun check(ctx: Context, treeUriRaw: String): Status {
        if (treeUriRaw.isEmpty()) return Status.Missing
        val uri = try {
            treeUriRaw.toUri()
        } catch (e: Exception) {
            return Status.NoPermission
        }
        if (!hasPersisted(ctx, uri)) return Status.NoPermission
        return try {
            val rootId = DocumentsContract.getTreeDocumentId(uri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, rootId)
            // 只取一列、只读第一行：确认 provider 真的能打开，而不是遍历整个目录
            val cursor = ctx.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            )
            // query 返回 null = provider 没应答（U 盘拔出 / 云盘掉线），
            // 旧实现把它和「查询成功」混成同一个 Ok 分支 —— 自检形同虚设，
            // 抓取照常开始然后每一张图都失败，用户完全看不出根因。
            if (cursor == null) return Status.Unreadable("目录 provider 无响应")
            cursor.use { it.moveToFirst() }
            Status.Ok
        } catch (e: SecurityException) {
            // 授权没了抛的是 SecurityException：这是「重新选目录」而不是「设备/网络问题」
            Status.NoPermission
        } catch (e: Exception) {
            Status.Unreadable(e.message ?: "目录不可读")
        }
    }

    /** 是否持有该目录的持久化授权（读写各一项，任一满足即认为"授权还在"）。 */
    fun hasPersisted(ctx: Context, uri: Uri): Boolean {
        val target = uri.toString()
        return ctx.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == target && (it.isReadPermission || it.isWritePermission)
        }
    }

    /** 是否只有读权限（部分第三方 provider 不给写，抓取会全部失败）。 */
    fun readOnly(ctx: Context, uri: Uri): Boolean {
        val target = uri.toString()
        val perms = ctx.contentResolver.persistedUriPermissions.filter { it.uri.toString() == target }
        if (perms.isEmpty()) return false
        return perms.none { it.isWritePermission }
    }
}
