package com.wcm.checkin

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 面板同步客户端：POST /api/mobile/sync（X-Panel-Token 令牌鉴权）。 */
object PanelApi {

    data class SyncResult(
        val ok: Boolean,
        val updated: Boolean,
        val name: String,
        val health: String,
        val detail: String,
    )

    /**
     * 同步专用 client：**不跟随重定向**。
     *
     * 这里带的是 `X-Panel-Token`（自定义头）。OkHttp 跟随跨域 302 时只会摘掉
     * `Authorization`，自定义头是原样转发的 —— 面板地址配错 / 面板被劫持时，
     * 令牌与 cookie 会被送到跳转目标去。
     */
    private val client = WeiboApi.client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun sync(
        panelUrl: String,
        token: String,
        cookie: String,
        uid: String,
        name: String = "",
    ): SyncResult {
        // 地址策略收口在 PanelUrl：公网必须 https，只有内网才允许 http
        val base = PanelUrl.parse(panelUrl).base
        val body = JSONObject()
            .put("cookie", cookie)
            .put("uid", uid)
            // 面板只在**新建**账号时才用这个昵称（老账号保留用户在面板上的自定义名），
            // 但少了它，新账号就只能落到「手机同步账号」这种默认名。
            .put("name", name)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$base/api/mobile/sync")
            .header("X-Panel-Token", token)
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            if (resp.isRedirect) {
                // 不跟随重定向：把「面板地址指错了」直接说出来，比静默 302 更好排查
                return SyncResult(false, false, "", "bad_url", "面板返回跳转（HTTP ${resp.code}），请检查面板地址")
            }
            if (resp.code == 429) {
                // 面板限流（多账号连续同步叠加保活时会触顶）：明确提示而非笼统的 HTTP 429
                val retry = resp.header("Retry-After")?.toIntOrNull()
                val detail = "同步频率超限" +
                    (if (retry != null && retry > 0) "，$retry 秒后可重试" else "（面板限流）")
                return SyncResult(false, false, "", "rate_limited", detail)
            }
            if (resp.code == 409) {
                // 面板拒绝覆盖（探活失败/账号健康）：提示去 App 重新登录
                val detail = try {
                    JSONObject(text).optString("detail", "面板拒绝本次同步")
                } catch (e: Exception) {
                    "面板拒绝本次同步"
                }
                return SyncResult(false, false, "", "invalid", detail)
            }
            if (!resp.isSuccessful) {
                throw WeiboApi.WeiboException("面板返回 HTTP ${resp.code}")
            }
            val payload = JSONObject(text)
            return SyncResult(
                ok = payload.optBoolean("ok", false),
                updated = payload.optBoolean("updated", false),
                name = payload.optString("name", ""),
                health = payload.optString("health", ""),
                detail = "",
            )
        }
    }
}
