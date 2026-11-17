package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写接口的表单构造与响应分类测试。
 *
 * 这些断言对应实测结论（`docs/research/weibo-write-endpoints.md`），
 * 一旦有人把参数改回 `text`、把 `1022:` 前缀去掉，测试会立刻红。
 */
class EngageWriteTest {

    private val tid = "1008088cf9880c8ec5f07f84c2f30e74d43574"

    // ---------- 表单构造 ----------

    @Test
    fun `发帖表单使用 content 而不是 text`() {
        val form = WeiboWriteApi.buildPostForm(tid, "你好", syncToTimeline = true)
        assertTrue(form.startsWith("content="))
        assertFalse(form.contains("&text="))
        assertTrue(form.contains("content=%E4%BD%A0%E5%A5%BD"))
    }

    @Test
    fun `发帖表单的超话归属必须带 1022 前缀`() {
        val form = WeiboWriteApi.buildPostForm(tid, "正文", syncToTimeline = false)
        assertTrue(form.contains("topic_id=1022%3A$tid"))
    }

    @Test
    fun `同步到主页开关映射为 sync_mblog`() {
        assertTrue(WeiboWriteApi.buildPostForm(tid, "x", true).contains("sync_mblog=1"))
        assertTrue(WeiboWriteApi.buildPostForm(tid, "x", false).contains("sync_mblog=0"))
    }

    @Test
    fun `只给 hash 时会自动补上 100808 前缀`() {
        // 超话列表返回的是去掉 100808 的 hash，直接拼 1022: 会发到错误对象
        val hashOnly = "8cf9880c8ec5f07f84c2f30e74d43574"
        val form = WeiboWriteApi.buildPostForm(EngageSchedule.topicContainerId(hashOnly), "正文", false)
        assertTrue(form.contains("topic_id=1022%3A1008088cf9880c8ec5f07f84c2f30e74d43574"))
    }

    @Test
    fun `帖子流 flowId 使用完整 containerid`() {
        val hashOnly = "8cf9880c8ec5f07f84c2f30e74d43574"
        assertEquals(
            "https://weibo.com/ajax_proxy/chaohua/page?flowId=1008088cf9880c8ec5f07f84c2f30e74d43574",
            WeiboFeedApi.CHAOHUA_PAGE + "?flowId=" + EngageSchedule.topicContainerId(hashOnly),
        )
    }

    @Test
    fun `评论表单字段与实测一致`() {
        val form = WeiboWriteApi.buildCommentForm("5000000000000002", "围观一下")
        assertTrue(form.contains("id=5000000000000002"))
        assertTrue(form.contains("comment=%E5%9B%B4%E8%A7%82%E4%B8%80%E4%B8%8B"))
        assertTrue(form.contains("is_repost=0"))
        assertTrue(form.contains("comment_ori=0"))
    }

    @Test
    fun `转发表单额外携带 visible 与 share_id`() {
        val form = WeiboWriteApi.buildRepostForm("5000000000000002", "转发测试")
        assertTrue(form.contains("visible=0"))
        assertTrue(form.contains("share_id="))
    }

    @Test
    fun `发帖 Referer 指向超话页`() {
        assertEquals("https://weibo.com/page/$tid?containerid=$tid", WeiboWriteApi.topicReferer(tid))
    }

    // ---------- mid 校验 ----------

    @Test
    fun `数字 mid 合法`() {
        assertTrue(WeiboWriteApi.isValidMid("5000000000000002"))
    }

    @Test
    fun `base62 短 id 不可用于写接口`() {
        assertFalse(WeiboWriteApi.isValidMid("RrGEf7U5j"))
    }

    @Test
    fun `空 mid 非法`() {
        assertFalse(WeiboWriteApi.isValidMid(""))
    }

    // ---------- 成功判定 ----------

    @Test
    fun `发帖成功响应能取到 mid`() {
        val body = """{"data":{"visible":{"type":0},"id":5000000000000001,"mid":"5000000000000001","mblogid":"AbCdEf12"}}"""
        val r = WeiboWriteApi.parseWriteResult(200, body)
        assertEquals(EngageErrorKind.NONE, r.kind)
        assertEquals("5000000000000001", r.remoteId)
        assertTrue(r.ok)
    }

    @Test
    fun `ok 为 1 视为成功`() {
        val r = WeiboWriteApi.parseWriteResult(200, """{"ok":1}""")
        assertEquals(EngageErrorKind.NONE, r.kind)
        assertTrue(r.ok)
    }

    @Test
    fun `code 为 100000 视为成功`() {
        val r = WeiboWriteApi.parseWriteResult(200, """{"code":"100000","msg":"评论成功"}""")
        assertEquals(EngageErrorKind.NONE, r.kind)
    }

    // ---------- 失败分类 ----------

    @Test
    fun `登录墙识别为未登录`() {
        assertEquals(
            EngageErrorKind.NOT_LOGGED_IN,
            WeiboWriteApi.classify(200, """{"ok":-100,"url":"https://weibo.com/newlogin"}"""),
        )
    }

    @Test
    fun `errno 404 判为接口改版而非参数错误`() {
        assertEquals(
            EngageErrorKind.ENDPOINT_INVALID,
            WeiboWriteApi.classify(200, """{"ok":0,"errno":404,"msg":"  出错了: 链接http://x 无效"}"""),
        )
    }

    @Test
    fun `操作频繁两种形态都识别`() {
        assertEquals(EngageErrorKind.RATE_LIMITED, WeiboWriteApi.classify(200, """{"ok":0,"errno":10022}"""))
        assertEquals(EngageErrorKind.RATE_LIMITED, WeiboWriteApi.classify(200, """{"ok":0,"errno":10023}"""))
        assertEquals(EngageErrorKind.RATE_LIMITED, WeiboWriteApi.classify(200, """{"ok":0,"msg":"操作频繁"}"""))
    }

    @Test
    fun `HTTP 层限流与错误码`() {
        assertEquals(EngageErrorKind.RATE_LIMITED, WeiboWriteApi.classify(429, ""))
        assertEquals(EngageErrorKind.RATE_LIMITED, WeiboWriteApi.classify(432, ""))
        assertEquals(EngageErrorKind.SERVER_ERROR, WeiboWriteApi.classify(503, ""))
        assertEquals(EngageErrorKind.PARAM_INVALID, WeiboWriteApi.classify(400, "bad"))
    }

    @Test
    fun `纯文本验证提示判为需要人工验证`() {
        // 实测形态：不是 JSON，也不是登录页 HTML
        assertEquals(EngageErrorKind.NEED_VERIFY, WeiboWriteApi.classify(200, "请先验证身份"))
    }

    @Test
    fun `登录页 HTML 判为登录墙`() {
        assertEquals(
            EngageErrorKind.LOGIN_WALL,
            WeiboWriteApi.classify(200, "<html><title>Sina Visitor System</title></html>"),
        )
    }

    @Test
    fun `已转发与已点赞判为重复`() {
        assertEquals(EngageErrorKind.DUPLICATE_ACTION, WeiboWriteApi.classify(200, """{"ok":0,"msg":"已转发"}"""))
        assertEquals(EngageErrorKind.DUPLICATE_ACTION, WeiboWriteApi.classify(200, """{"ok":0,"msg":"已经赞过"}"""))
    }

    @Test
    fun `目标被删判为目标丢失`() {
        assertEquals(EngageErrorKind.TARGET_GONE, WeiboWriteApi.classify(200, """{"ok":0,"msg":"该微博已被删除"}"""))
    }

    @Test
    fun `内容审核判为审核拒绝`() {
        assertEquals(EngageErrorKind.CONTENT_REJECTED, WeiboWriteApi.classify(200, """{"ok":0,"msg":"内容需要审核"}"""))
    }

    @Test
    fun `未关注超话与屏蔽分别识别`() {
        assertEquals(EngageErrorKind.TOPIC_NOT_FOLLOWED, WeiboWriteApi.classify(200, """{"ok":0,"msg":"请先关注超话"}"""))
        assertEquals(EngageErrorKind.TOPIC_BLOCKED, WeiboWriteApi.classify(200, """{"ok":0,"msg":"你已被屏蔽"}"""))
    }

    @Test
    fun `等级不足可识别`() {
        assertEquals(EngageErrorKind.LEVEL_TOO_LOW, WeiboWriteApi.classify(200, """{"ok":0,"msg":"等级不足"}"""))
    }

    @Test
    fun `无法归类的业务失败按参数错误处理`() {
        assertEquals(EngageErrorKind.PARAM_INVALID, WeiboWriteApi.classify(200, """{"ok":0,"msg":"发生未知错误"}"""))
    }

    // ---------- 结果未知 ----------

    @Test
    fun `网络与服务端错误视为结果未知`() {
        assertTrue(WeiboWriteApi.parseWriteResult(0, "timeout").outcomeUnknown)
        assertTrue(WeiboWriteApi.parseWriteResult(502, "").outcomeUnknown)
        assertFalse(WeiboWriteApi.parseWriteResult(200, """{"ok":1}""").outcomeUnknown)
    }

    // ---------- 脱敏 ----------

    @Test
    fun `响应摘要不会带出凭据`() {
        val excerpt = WeiboHttp.safeBodyExcerpt("SUB=_2A25xxxx; SUBP=0033; path=/")
        assertEquals("[响应含凭据片段，已省略]", excerpt)
    }

    @Test
    fun `普通响应摘要按长度截断`() {
        val excerpt = WeiboHttp.safeBodyExcerpt("x".repeat(200))
        assertTrue(excerpt.length <= 121)
        assertTrue(excerpt.endsWith("…"))
    }

    // ---------- XSRF ----------

    @Test
    fun `从 cookie 取 XSRF 并解码`() {
        val cookie = "_T_WM=1; XSRF-TOKEN=xW-YQrkBMcidrsA-EpLAgbxy; SUB=_2A25"
        assertEquals("xW-YQrkBMcidrsA-EpLAgbxy", WeiboHttp.xsrfOf(cookie))
    }

    @Test
    fun `URL 编码的 XSRF 会被解码`() {
        assertEquals("a=b+c", WeiboHttp.xsrfOf("XSRF-TOKEN=a%3Db%2Bc"))
    }

    @Test
    fun `没有 XSRF 的 cookie 返回空串`() {
        assertEquals("", WeiboHttp.xsrfOf("_T_WM=1; SUB=_2A25"))
    }
}
