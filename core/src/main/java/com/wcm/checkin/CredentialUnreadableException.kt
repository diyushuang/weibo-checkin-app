package com.wcm.checkin

/**
 * 本机凭证存在但无法解密（Keystore 被重置 / 数据迁移 / 密文损坏）。
 *
 * 放在 `:core`：`:domain` 的重登流程要 catch 这个异常来决定「硬停」，
 * 若它留在 `:data` 的 `CredentialVault` 内部，domain 就必须依赖 data。
 */
class CredentialUnreadableException : Exception("credential unreadable")
