package com.wcm.checkin

import android.view.View
import android.widget.ScrollView
import android.widget.TextView

/**
 * 运行日志面板：首页（签到）与图片页（抓取）共用的同一套交互。
 *
 * 行为口径（两页原先各写一遍，容易改漏）：
 * - 按时间顺序追加，**只有用户本来就贴着底部时才自动滚到底**，往上翻旧日志时不打断；
 * - 收起状态下卡头保留一行「最近发生了什么」的摘要；
 * - 展开 / 收起的状态文案挂在**可点的卡头容器**上（箭头设了
 *   `importantForAccessibility="no"`，写在它身上的 contentDescription 读屏不播报）。
 *
 * 日志行缓冲由调用方持有（进程级），所以旋转重建后历史日志不会丢。
 */
internal class LogPanel(
    private val host: View,
    private val lines: ArrayDeque<String>,
    private val scrollId: Int,
    private val textId: Int,
    private val summaryId: Int,
    private val toggleId: Int,
    private val headerId: Int,
    private val emptyTextRes: Int,
    private val cdExpandedRes: Int,
    private val cdCollapsedRes: Int,
    private val maxLines: Int = 500,
) {

    var expanded: Boolean = false
        private set

    private val scroll: ScrollView? get() = host.findViewById(scrollId) as? ScrollView

    private fun text(id: Int): TextView? = host.findViewById(id) as? TextView

    private fun str(id: Int): String = host.context.getString(id)

    /**
     * 上一次渲染进 TextView 的完整文本。
     *
     * 旧实现每次 append 都「joinToString(换行) + setText」全量重建：500 行缓冲时
     * 每来一行日志就拼接并重建一次 Spannable —— O(n²)，抓取高峰期日志自己就是卡顿源。
     * 现在只有「新文本以旧文本为前缀」（最常见的纯追加）时走增量 append，
     * 一旦触到 maxLines 截断或内容被替换，就退回全量重建。
     */
    private var rendered = ""

    /** 追加一行（线程安全：IO 线程也会调，统一切到宿主线程再改）。 */
    fun append(line: String) {
        host.post {
            synchronized(lines) {
                lines.addLast(line)
                while (lines.size > maxLines) lines.removeFirst()
            }
            render()
        }
    }

    fun render() {
        val tv = text(textId) ?: return
        val view = scroll ?: return
        // 先取「是否贴底」，再赋新文本 —— 赋值会 requestLayout，反过来读就依赖
        // "此刻还没重新测量" 这一时序巧合。
        val wasAtBottom = !view.canScrollVertically(1)
        val joined = synchronized(lines) { lines.joinToString("\n") }.ifEmpty { str(emptyTextRes) }
        if (rendered.isNotEmpty() && joined.length > rendered.length && joined.startsWith(rendered)) {
            tv.append("\n" + joined.substring(rendered.length))
        } else {
            tv.text = joined
        }
        rendered = joined
        text(summaryId)?.text = synchronized(lines) {
            lines.lastOrNull { it.isNotBlank() } ?: str(emptyTextRes)
        }
        // 收起时窗口高度为 0，canScrollVertically 恒为 false，此时不要滚（展开时再滚）
        if (view.visibility != View.VISIBLE) return
        if (wasAtBottom) view.post { view.fullScroll(View.FOCUS_DOWN) }
    }

    fun setExpanded(value: Boolean) {
        expanded = value
        val view = scroll ?: return
        view.visibility = if (value) View.VISIBLE else View.GONE
        text(summaryId)?.visibility = if (value) View.GONE else View.VISIBLE
        host.findViewById<View>(toggleId)?.rotation = if (value) 180f else 0f
        host.findViewById<View>(headerId)?.contentDescription =
            str(if (value) cdExpandedRes else cdCollapsedRes)
        if (value) view.post { view.fullScroll(View.FOCUS_DOWN) }
    }
}
