package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文案池纯函数测试。
 *
 * 重点钉住两件事：**批量导入的切分/去重规则**，以及**预览与实发必须是同一份字符串**
 * （[EngageTextPool.compose] 是唯一出口，真实发帖也调它）。
 */
class EngageTextPoolTest {

    // ---------- normalize ----------

    @Test
    fun `normalize 统一换行并压缩多余空行`() {
        val raw = "  a\r\n\r\n\r\nb\u3000 "
        assertEquals("a\n\nb", EngageTextPool.normalize(raw))
    }

    @Test
    fun `normalize 不动行内的全角空格`() {
        // 中文正文中间的间隔空格是作者有意为之，不能顺手吃掉
        assertEquals("好\u3000的", EngageTextPool.normalize("好\u3000的"))
    }

    // ---------- digest ----------

    @Test
    fun `digest 对首尾空白不敏感`() {
        assertEquals(EngageTextPool.digest("  你好  "), EngageTextPool.digest("你好"))
    }

    @Test
    fun `digest 对内容差异敏感`() {
        assertFalse(EngageTextPool.digest("你好") == EngageTextPool.digest("你好啊"))
    }

    // ---------- splitBulk ----------

    @Test
    fun `空行分割并剥掉行首序号`() {
        val raw = "1. 第一条文案\n\n2、第二条文案\n\n③第三条文案"
        val r = EngageTextPool.splitBulk(raw)
        assertEquals(listOf("第一条文案", "第二条文案", "第三条文案"), r.accepted)
    }

    @Test
    fun `单行模式一行一条`() {
        val r = EngageTextPool.splitBulk("第一条\n第二条\n第三条", EngageTextPool.ImportMode.SINGLE_LINE)
        assertEquals(3, r.accepted.size)
    }

    @Test
    fun `自定义分隔符`() {
        val r = EngageTextPool.splitBulk(
            "甲文案###乙文案###丙文案",
            EngageTextPool.ImportMode.CUSTOM,
            customSeparator = "###",
        )
        assertEquals(listOf("甲文案", "乙文案", "丙文案"), r.accepted)
    }

    @Test
    fun `批内重复被丢弃并计数`() {
        val r = EngageTextPool.splitBulk("同一条文案\n\n同一条文案\n\n另一条文案")
        assertEquals(2, r.accepted.size)
        assertEquals(1, r.duplicateCount)
    }

    @Test
    fun `过短与过长分别计数且不进结果`() {
        val r = EngageTextPool.splitBulk("a\n\n正常长度文案\n\n" + "长".repeat(20), maxLen = 10)
        assertEquals(1, r.accepted.size)
        assertEquals(1, r.tooShortCount)
        assertEquals(1, r.tooLongCount)
    }

    @Test
    fun `超过单次上限后多余条目记入重复计数`() {
        val raw = (1..5).joinToString("\n\n") { "第${it}条文案" }
        val r = EngageTextPool.splitBulk(raw, limit = 3)
        assertEquals(3, r.accepted.size)
        assertEquals(2, r.duplicateCount)
    }

    @Test
    fun `风险项被标出但仍然接收`() {
        val raw = "看这个 https://a.com https://b.com https://c.com 很有意思"
        val r = EngageTextPool.splitBulk(raw)
        assertEquals(1, r.accepted.size)
        assertEquals(1, r.risky.size)
        assertTrue(r.risky[0].contains("链接较多"))
    }

    @Test
    fun `空输入返回空结果`() {
        val r = EngageTextPool.splitBulk("   \n\n  ")
        assertTrue(r.accepted.isEmpty())
    }

    // ---------- render ----------

    @Test
    fun `占位符全部替换且未知占位符原样保留`() {
        val ctx = EngageTextPool.RenderContext(
            topicName = "棋手战鹰", date = "2026-11-16", dateCn = "11月16日",
            seq = 3, time = "09:30", videoUrl = "https://v.example/1",
        )
        val out = EngageTextPool.render("{topic} 第{seq}条 {date} {date_cn} {time} {video} {unknown}", ctx)
        assertEquals("棋手战鹰 第3条 2026-11-16 11月16日 09:30 https://v.example/1 {unknown}", out)
    }

    // ---------- compose ----------

    @Test
    fun `无视频时只追加超话标签`() {
        assertEquals("今天也要加油\n\n#棋手战鹰#", EngageTextPool.compose("棋手战鹰", "今天也要加油"))
    }

    @Test
    fun `有视频时插入视频行且保持固定顺序`() {
        val out = EngageTextPool.compose("棋手战鹰", "今天也要加油", "https://v.example/1")
        assertEquals("今天也要加油\n\n视频：https://v.example/1\n\n#棋手战鹰#", out)
    }

    @Test
    fun `正文已含超话标签时不再重复追加`() {
        val body = "今天也要加油 #棋手战鹰[超话]#"
        assertEquals(body, EngageTextPool.compose("棋手战鹰", body))
    }

    @Test
    fun `正文已含井号形式标签时不再重复追加`() {
        val body = "今天也要加油 #棋手战鹰#"
        assertEquals(body, EngageTextPool.compose("棋手战鹰", body))
    }

    @Test
    fun `视频链接为空时整行删除不留空行`() {
        val out = EngageTextPool.compose("棋手战鹰", "正文", "   ")
        assertFalse(out.contains("视频："))
        assertEquals("正文\n\n#棋手战鹰#", out)
    }

    // ---------- validate ----------

    @Test
    fun `空正文被拒`() {
        assertNotNull(EngageTextPool.validate("  ", 1000))
    }

    @Test
    fun `超长正文被拒且提示字数`() {
        val msg = EngageTextPool.validate("字".repeat(11), 10)
        assertNotNull(msg)
        assertTrue(msg!!.contains("11"))
    }

    @Test
    fun `正常长度通过`() {
        assertNull(EngageTextPool.validate("正常长度的正文", 1000))
    }

    // ---------- pick ----------

    private fun text(id: String, lastUsedAt: Long = 0L, lastUsedDay: String = "", deleted: Boolean = false) =
        EngageText(id = id, body = "文案$id", lastUsedAt = lastUsedAt, lastUsedDay = lastUsedDay, deleted = deleted)

    @Test
    fun `优先取最久没用过的`() {
        val pool = listOf(text("a", 300), text("b", 100), text("c", 200))
        val picked = EngageTextPool.pick(pool, 2, setOf("2026-11-16"))
        assertEquals(listOf("b", "c"), picked.map { it.id })
    }

    @Test
    fun `软删的文案不参与`() {
        val pool = listOf(text("a"), text("b", deleted = true))
        val picked = EngageTextPool.pick(pool, 5, emptySet())
        assertEquals(listOf("a"), picked.map { it.id })
    }

    @Test
    fun `禁用日期内的文案被排除`() {
        val pool = listOf(text("a", lastUsedDay = "2026-11-16"), text("b", lastUsedDay = "2026-11-10"))
        val picked = EngageTextPool.pick(pool, 4, setOf("2026-11-16"))
        assertEquals(listOf("b", "a"), picked.map { it.id })
    }

    @Test
    fun `候选不足时循环复用而不是空手`() {
        val pool = listOf(text("a", lastUsedDay = "2026-11-16"))
        val picked = EngageTextPool.pick(pool, 2, setOf("2026-11-16"))
        assertEquals(1, picked.size)
    }

    @Test
    fun `空池返回空`() {
        assertTrue(EngageTextPool.pick(emptyList(), 3, emptySet()).isEmpty())
    }

    @Test
    fun `可用条数排除软删`() {
        assertEquals(2, EngageTextPool.availableCount(listOf(text("a"), text("b"), text("c", deleted = true))))
    }

    // ---------- 评论正文校验 ----------

    @Test
    fun `评论为空被拒`() {
        assertNotNull(EngageTextPool.validateComment("  ", 140))
    }

    @Test
    fun `评论超长被拒且不截断`() {
        val long = "字".repeat(141)
        val reason = EngageTextPool.validateComment(long, 140)
        assertNotNull(reason)
        assertTrue(reason!!.contains("141"))
    }

    @Test
    fun `评论在限制内通过`() {
        assertNull(EngageTextPool.validateComment("好".repeat(140), 140))
    }
}
