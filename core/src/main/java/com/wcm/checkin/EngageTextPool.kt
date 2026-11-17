package com.wcm.checkin

import java.security.MessageDigest

/**
 * 文案池：规范化、去重、批量拆分、占位符渲染、最终正文组装、轮换取用。
 *
 * 全部是纯函数——这是本功能里唯一「用户内容会直接变成公开帖子」的环节，
 * 所以它必须能被单测钉死，尤其是 [compose]：**预览与实发必须是同一份代码产出**，
 * 否则用户看到的和真正发出去的会悄悄漂移。
 */
object EngageTextPool {

    /** 批量导入的分隔策略。 */
    enum class ImportMode { BLANK_LINE, SINGLE_LINE, CUSTOM }

    /** 批量导入结果。[risky] 只提示不拦截。 */
    data class BulkResult(
        val accepted: List<String> = emptyList(),
        val duplicateCount: Int = 0,
        val tooShortCount: Int = 0,
        val tooLongCount: Int = 0,
        val risky: List<String> = emptyList(),
    ) {
        val summary: String
            get() = "接受 ${accepted.size} · 重复 $duplicateCount · 过短 $tooShortCount · 过长 $tooLongCount"
    }

    /** 一条文案最后的可读性判断。 */
    data class Verdict(val ok: Boolean, val reason: String = "")

    private val NUMBERING = Regex("^\\s*(?:\\d{1,3}\\s*[.、)）]|[-•·*]|[①-⑳])\\s*")
    private val PLACEHOLDER = Regex("\\{([a-z_]{1,16})\\}")
    private val BLANK_LINE = Regex("\\n\\s*\\n")
    private val URL_RE = Regex("(?:https?://|t\\.cn/)\\S+", RegexOption.IGNORE_CASE)
    private val MENTION_RE = Regex("@[\\w\\u4e00-\\u9fa5-]{1,20}")

    /** 导入时只提示不拦截的高风险词（本地表，不做联网审核）。 */
    private val AD_WORDS = listOf("加微信", "代购", "兼职", "返利", "优惠券", "点击链接", "私信我")

    /**
     * 规范化：统一换行、去掉行首尾的全角空格与普通空白、合并多余空行。
     *
     * 只清行首尾而不是把 `\u3000` 全替换掉——中文正文中间的间隔空格是作者
     * 有意为之，全替换会悄悄改掉用户内容。
     */
    fun normalize(raw: String): String {
        val unified = raw.replace("\r\n", "\n").replace('\r', '\n')
        val lines = unified.split('\n').map { line ->
            line.trim().trim('\u3000').trimEnd()
        }
        val joined = lines.joinToString("\n")
        return joined.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** 规范化正文的短哈希，用于去重。取前 16 位十六进制，碰撞概率对本场景足够低。 */
    fun digest(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(normalize(text).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * 批量拆分。用户在输入法圈子复制过来的文本通常带序号、空行和零散空格，
     * 这里按 [mode] 切段后逐条清理，并统计被丢掉的原因。
     */
    fun splitBulk(
        raw: String,
        mode: ImportMode = ImportMode.BLANK_LINE,
        customSeparator: String = "",
        maxLen: Int = 1000,
        limit: Int = 200,
    ): BulkResult {
        val text = normalize(raw)
        if (text.isEmpty()) return BulkResult()

        val segments = when (mode) {
            ImportMode.BLANK_LINE -> text.split(BLANK_LINE)
            ImportMode.SINGLE_LINE -> text.split('\n')
            ImportMode.CUSTOM -> if (customSeparator.isEmpty()) listOf(text) else text.split(customSeparator)
        }

        val accepted = ArrayList<String>()
        val risky = ArrayList<String>()
        val seen = HashSet<String>()
        var dup = 0
        var short = 0
        var long = 0

        for (rawSegment in segments) {
            val body = normalizeSegment(rawSegment)
            if (body.isEmpty()) continue
            if (body.length > maxLen) { long++; continue }
            if (body.length < 2) { short++; continue }
            val key = digest(body)
            if (!seen.add(key)) { dup++; continue }
            if (accepted.size >= limit) { dup++; continue }
            accepted.add(body)
            val flags = riskFlags(body)
            if (flags.isNotEmpty()) risky.add("${body.take(20)}…（${flags.joinToString("、")}）")
        }
        return BulkResult(accepted, dup, short, long, risky)
    }

    /** 段内清理：剥掉行首序号、合并段内多余空行。 */
    private fun normalizeSegment(segment: String): String {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return ""
        val withoutNumber = NUMBERING.replace(trimmed, "")
        return normalize(withoutNumber)
    }

    /** 渲染占位符。未知占位符原样保留，避免把用户写的 `{abc}` 吃掉。 */
    fun render(body: String, ctx: RenderContext): String =
        PLACEHOLDER.replace(body) { m ->
            when (val key = m.groupValues[1]) {
                "topic" -> ctx.topicName
                "date" -> ctx.date
                "date_cn" -> ctx.dateCn
                "seq" -> ctx.seq.toString()
                "time" -> ctx.time
                "video" -> ctx.videoUrl
                else -> m.value
            }
        }

    data class RenderContext(
        val topicName: String,
        val date: String,
        val dateCn: String,
        val seq: Int,
        val time: String,
        val videoUrl: String = "",
    )

    /**
     * 最终提交正文的组装——**唯一出口**。
     *
     * 固定顺序：正文 → 空行 → `视频：<url>`（为空则整行删除）→ 空行 → `#超话名#`
     * （正文已含该话题标签则不重复追加）。预览与真正发帖都调这一个函数。
     */
    fun compose(topicName: String, renderedBody: String, videoUrl: String = ""): String {
        val sb = StringBuilder(renderedBody.trim())
        val video = videoUrl.trim()
        if (video.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("视频：").append(video)
        }
        if (topicName.isNotBlank() && !containsTopicTag(renderedBody, topicName)) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append('#').append(topicName.trim()).append('#')
        }
        return sb.toString()
    }

    /** 正文里是否已经有这个超话的话题标签。两种写法都要认。 */
    fun containsTopicTag(body: String, topicName: String): Boolean {
        val name = topicName.trim()
        if (name.isEmpty()) return false
        return body.contains("#$name#") || body.contains("#$name[超话]#")
    }

    /** 发送前的最后校验。返回 null 表示可以发。 */
    fun validate(composed: String, maxLen: Int): String? = when {
        composed.isBlank() -> "正文为空"
        composed.length > maxLen -> "正文 ${composed.length} 字，超过上限 $maxLen"
        else -> null
    }

    /**
     * 评论 / 转发的正文校验。
     *
     * 与发帖分开是因为两者上限不同（评论 140 字，发帖可到 2000）。超限**拒绝**
     * 而不是截断：悄悄砍掉用户内容比直接跳过更糟。
     */
    fun validateComment(composed: String, maxLen: Int): String? = when {
        composed.isBlank() -> "评论正文为空"
        composed.length > maxLen -> "评论 ${composed.length} 字，超过上限 $maxLen"
        else -> null
    }

    /** 本地风险提示：只提示，不拦截（是否真的有风险，本 App 判断不了）。 */
    fun riskFlags(text: String): List<String> {
        val flags = ArrayList<String>(3)
        if (URL_RE.findAll(text).count() > 2) flags.add("链接较多")
        if (MENTION_RE.findAll(text).count() > 3) flags.add("提及过多")
        val ad = AD_WORDS.firstOrNull { text.contains(it) }
        if (ad != null) flags.add("疑似广告词：$ad")
        return flags
    }

    /**
     * 轮换取用：优先最久没用过的，同序按 id 升序（保证结果确定、可测）。
     *
     * [forbiddenDays] 里出现的日期不会再被取用（通常传 `{今天}`，
     * `strictTextGapDays > 0` 时再加上前面若干天）。
     * 候选不足时从最旧的开始循环复用——**宁可用旧文案，也不空发**，
     * 但会通过返回值长度让调用方知道复用发生了。
     */
    fun pick(
        pool: List<EngageText>,
        count: Int,
        forbiddenDays: Set<String>,
    ): List<EngageText> {
        if (count <= 0) return emptyList()
        val available = pool.filter { !it.deleted }
        if (available.isEmpty()) return emptyList()

        val fresh = available
            .filter { it.lastUsedDay !in forbiddenDays }
            .sortedWith(compareBy({ it.lastUsedAt }, { it.id }))

        if (fresh.size >= count) return fresh.take(count)

        val picked = ArrayList<EngageText>(fresh)
        val rest = available
            .filter { it.lastUsedDay in forbiddenDays }
            .sortedWith(compareBy({ it.lastUsedAt }, { it.id }))
        for (t in rest) {
            if (picked.size >= count) break
            picked.add(t)
        }
        return picked
    }

    /** 池内可用条数（软删不计）。 */
    fun availableCount(pool: List<EngageText>): Int = pool.count { !it.deleted }
}
