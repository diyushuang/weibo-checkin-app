package com.wcm.checkin

import java.net.URI

/**
 * 面板地址解析与安全策略（纯函数，JVM 单测覆盖）。
 *
 * 面板是用户自填地址，**无法**在 `network_security_config` 里预先枚举域名，
 * 所以 OS 层只能整体放行明文。安全边界改由这里收口：
 *
 * - **公网地址一律要求 https** —— Cookie 走公网明文等于把登录态公开发布；
 * - 只有私有网段 / 回环 / `.local`、`.lan` 这类自建局域网面板才允许 http，
 *   这正是本项目文档里记载的典型用法（`http://192.168.x.x:8080`）。
 *
 * 这样既没破坏局域网自建面板，也把"任何 http 都能发"收敛成"只有内网 http 能发"。
 */
object PanelUrl {

    data class Parsed(
        val base: String,
        val scheme: String,
        val host: String,
        val isPrivateHost: Boolean,
    )

    /** @throws WeiboApi.WeiboException 地址非法或违反明文策略时抛出，消息可直接展示给用户。 */
    fun parse(raw: String): Parsed {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw WeiboApi.WeiboException("未配置面板地址")
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw WeiboApi.WeiboException("面板地址格式不正确：$trimmed")
        }
        val scheme = uri.scheme?.lowercase()
            ?: throw WeiboApi.WeiboException("面板地址要带 http:// 或 https://")
        if (scheme != "http" && scheme != "https") {
            throw WeiboApi.WeiboException("面板地址只支持 http/https：$scheme")
        }
        val host = uri.host?.lowercase()?.trimEnd('.')
        if (host.isNullOrEmpty()) throw WeiboApi.WeiboException("面板地址缺少主机名：$trimmed")

        val priv = isPrivateHost(host)
        if (scheme == "http" && !priv) {
            throw WeiboApi.WeiboException("公网面板必须用 https：$host（明文会泄露登录态）")
        }
        return Parsed(trimmed, scheme, host, priv)
    }

    /** 私有网段 / 回环 / 局域网后缀。用于判定是否允许明文、以及是否需要本地网络权限。 */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || h == "::1" || h == "[::1]") return true
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home")) return true

        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it < 0 || it > 255 }) return false
        return when {
            octets[0] == 10 -> true                       // 10.0.0.0/8
            octets[0] == 127 -> true                      // 回环
            octets[0] == 192 && octets[1] == 168 -> true  // 192.168.0.0/16
            octets[0] == 172 && octets[1] in 16..31 -> true // 172.16.0.0/12
            octets[0] == 169 && octets[1] == 254 -> true  // 链路本地
            else -> false
        }
    }
}
