package com.wcm.checkin

import java.util.Locale

/**
 * 抓取文件的命名口径（增量抓取与单条抓取共用同一套，禁止各写一份）。
 *
 * ```
 * prefix      = "<yyyy-MM-dd>_<HHmmss>_<作者>"      例：2026-10-22_152712_某某
 * 静图        prefix_01.jpg
 * 配对 Live   prefix_01_live.gif | prefix_01_live.mov
 * 帖级 Live   prefix_live01.gif | prefix_live.mov
 * ```
 *
 * 时间部分取微博 created_at 的本地墙钟（+0800），日与时分秒都零填充，
 * 因此**文件名字典序 = 时间序**，图库按名降序即「最新在前」。
 *
 * [canonical] 负责把 SAF 撞名产生的 `xxx (1).jpg` 归一成 `xxx.jpg` —— 这是
 * 「相册里明明有、却每轮重下」的根因之一（旧实现用精确字符串比对，看不见后缀副本）。
 */
object AssetName {

    enum class Kind { STILL, LIVE_PAIRED, LIVE_EXTRA }

    /** 文件名反解结果。label 是写入时锁定的作者名，可能含下划线。 */
    data class Parsed(
        val date: String,
        val time: String,
        val label: String,
        val slot: Int,
        val kind: Kind,
        val ext: String,
    )

    private val BAD_CHARS = Regex("[\\\\/:*?\"<>|\\s]+")
    private val DUP_SUFFIX = Regex("\\s*\\(\\d{1,2}\\)$")
    /** 日期位允许 `unknown`：时间解析失败的老帖也按同一套结构落盘，接管时要认得出。 */
    private const val DATE = "(unknown|\\d{4}-\\d{2}-\\d{2})"
    private const val CLOCK = "(\\d{6})"
    private const val LABEL = "(.+?)"

    private val PAIRED = Regex(
        "^${DATE}_${CLOCK}_${LABEL}_(\\d{2})_live\\.(gif|mov)$", RegexOption.IGNORE_CASE
    )
    private val EXTRA = Regex(
        "^${DATE}_${CLOCK}_${LABEL}_live(\\d{2})\\.(gif|mov)$", RegexOption.IGNORE_CASE
    )
    private val STILL = Regex(
        "^${DATE}_${CLOCK}_${LABEL}_(\\d{2})\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE
    )

    /** 昵称清洗：去掉文件系统非法字符与空白；全被去掉时回退「微博」。 */
    fun safeName(raw: String): String = raw.replace(BAD_CHARS, "").ifEmpty { "微博" }

    /** 微博时间 → 文件名前缀。date 形如 `2026-10-22`，time 形如 `15:27:12`。 */
    fun prefix(date: String, time: String, label: String): String =
        "${date}_${time.replace(":", "")}_${safeName(label)}"

    /** 时间解析失败时的占位（老帖边界/异常接口数据）。 */
    fun unknownPrefix(label: String): String = "unknown_000000_${safeName(label)}"

    fun stillName(prefix: String, slot1: Int): String =
        String.format(Locale.US, "%s_%02d.jpg", prefix, slot1)

    fun pairedLiveName(prefix: String, slot1: Int, gif: Boolean): String =
        String.format(Locale.US, "%s_%02d_live.%s", prefix, slot1, if (gif) "gif" else "mov")

    fun extraLiveName(prefix: String, index0: Int, gif: Boolean): String =
        String.format(Locale.US, "%s_live%02d.%s", prefix, index0 + 1, if (gif) "gif" else "mov")

    /**
     * SAF 撞名后缀归一：`a (1).jpg` / `a(1).jpg` → `a.jpg`。
     * 只认 1–2 位数字，避免把正文里合法的 `(2024)` 当后缀删掉。
     */
    fun canonical(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        val base = name.substring(0, dot)
        val stripped = DUP_SUFFIX.replace(base, "")
        if (stripped == base || stripped.isEmpty()) return name
        return stripped + name.substring(dot)
    }

    /** 文件名反解（认不出返回 null：说明是外部文件，不是本 App 抓的）。 */
    fun parse(name: String): Parsed? {
        PAIRED.find(name)?.let { m ->
            return Parsed(m.groupValues[1], m.groupValues[2], m.groupValues[3],
                m.groupValues[4].toInt(), Kind.LIVE_PAIRED, m.groupValues[5].lowercase())
        }
        EXTRA.find(name)?.let { m ->
            return Parsed(m.groupValues[1], m.groupValues[2], m.groupValues[3],
                m.groupValues[4].toInt(), Kind.LIVE_EXTRA, m.groupValues[5].lowercase())
        }
        STILL.find(name)?.let { m ->
            return Parsed(m.groupValues[1], m.groupValues[2], m.groupValues[3],
                m.groupValues[4].toInt(), Kind.STILL, m.groupValues[5].lowercase())
        }
        return null
    }
}
