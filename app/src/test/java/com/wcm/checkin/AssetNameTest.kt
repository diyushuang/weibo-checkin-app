package com.wcm.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 命名口径单测：往返一致、撞名归一、外部文件不误判。 */
class AssetNameTest {

    // ---------- 生成 ----------

    @Test
    fun prefix_usesRealFormat() {
        // 真实格式是 yyyy-MM-dd_HHmmss（带连字符、时分秒 6 位），
        // 旧的注释与测试数据写成 yyyyMMdd_HHmmss 是错的，这里锁死正确口径。
        assertEquals(
            "2026-10-22_152712_某某",
            AssetName.prefix("2026-10-22", "15:27:12", "某某"),
        )
    }

    @Test
    fun stillName_zeroPadsSlot() {
        val p = "2026-10-22_152712_某某"
        assertEquals("${p}_01.jpg", AssetName.stillName(p, 1))
        assertEquals("${p}_10.jpg", AssetName.stillName(p, 10))
    }

    @Test
    fun liveNames_bothKinds() {
        val p = "2026-10-22_152712_某某"
        assertEquals("${p}_03_live.gif", AssetName.pairedLiveName(p, 3, gif = true))
        assertEquals("${p}_03_live.mov", AssetName.pairedLiveName(p, 3, gif = false))
        // 帖级 Live 的序号从 0 进、从 1 出
        assertEquals("${p}_live01.gif", AssetName.extraLiveName(p, 0, gif = true))
        assertEquals("${p}_live02.mov", AssetName.extraLiveName(p, 1, gif = false))
    }

    @Test
    fun safeName_stripsIllegalAndSpace() {
        assertEquals("a1", AssetName.safeName(" a/1 "))
        assertEquals("某某", AssetName.safeName("某某"))
        assertEquals("ab", AssetName.safeName("""a\:*?"<>|b"""))
    }

    @Test
    fun safeName_fallsBackWhenEverythingIllegal() {
        assertEquals("微博", AssetName.safeName(""))
        assertEquals("微博", AssetName.safeName(" / "))
    }

    @Test
    fun unknownPrefix_isStable() {
        assertEquals("unknown_000000_微博", AssetName.unknownPrefix(""))
    }

    // ---------- 撞名归一 ----------

    @Test
    fun canonical_stripsSafDuplicateSuffix() {
        assertEquals("a.jpg", AssetName.canonical("a (1).jpg"))
        assertEquals("a.jpg", AssetName.canonical("a (12).jpg"))
        assertEquals("a.jpg", AssetName.canonical("a(1).jpg"))
        // 只去撞名后缀，扩展名原样保留
        assertEquals("a.mov", AssetName.canonical("a(1).mov"))
    }

    @Test
    fun canonical_keepsLegitParentheses() {
        // 4 位年份不是 SAF 的撞名后缀，不能删
        assertEquals("旅行 (2024).jpg", AssetName.canonical("旅行 (2024).jpg"))
        assertEquals("a (1a).jpg", AssetName.canonical("a (1a).jpg"))
    }

    @Test
    fun canonical_noSuffixUntouched() {
        assertEquals("a.jpg", AssetName.canonical("a.jpg"))
        assertEquals("noext", AssetName.canonical("noext"))
    }

    // ---------- 反解 ----------

    @Test
    fun parse_stillRoundTrip() {
        val p = AssetName.prefix("2026-10-22", "15:27:12", "某某")
        val name = AssetName.stillName(p, 2)
        val parsed = AssetName.parse(name)!!
        assertEquals("2026-10-22", parsed.date)
        assertEquals("152712", parsed.time)
        assertEquals("某某", parsed.label)
        assertEquals(2, parsed.slot)
        assertEquals(AssetName.Kind.STILL, parsed.kind)
        assertEquals("jpg", parsed.ext)
    }

    @Test
    fun parse_pairedLiveRoundTrip() {
        val p = AssetName.prefix("2026-10-22", "15:27:12", "某某")
        val parsed = AssetName.parse(AssetName.pairedLiveName(p, 5, gif = true))!!
        assertEquals(AssetName.Kind.LIVE_PAIRED, parsed.kind)
        assertEquals(5, parsed.slot)
        assertEquals("gif", parsed.ext)
        assertEquals("某某", parsed.label)
    }

    @Test
    fun parse_extraLiveRoundTrip() {
        val p = AssetName.prefix("2026-10-22", "15:27:12", "某某")
        val parsed = AssetName.parse(AssetName.extraLiveName(p, 1, gif = false))!!
        assertEquals(AssetName.Kind.LIVE_EXTRA, parsed.kind)
        assertEquals(2, parsed.slot)
        assertEquals("mov", parsed.ext)
        assertEquals("某某", parsed.label)
    }

    @Test
    fun parse_labelMayContainUnderscore() {
        val p = AssetName.prefix("2026-10-22", "15:27:12", "a_b_c")
        val parsed = AssetName.parse(AssetName.stillName(p, 1))!!
        assertEquals("a_b_c", parsed.label)
        assertEquals(1, parsed.slot)
    }

    @Test
    fun parse_rejectsForeignFiles() {
        assertNull(AssetName.parse("IMG_0001.jpg"))
        assertNull(AssetName.parse("20260101_120000_作者_01.jpg"))  // 旧错格式
        assertNull(AssetName.parse("2026-10-22_152712_某某_01.txt"))
        assertNull(AssetName.parse(""))
    }

    @Test
    fun parse_unknownPrefixIsRecognisedButNoDate() {
        // unknown 前缀认得出结构（可被接管认领），日期字段为 "unknown"
        val parsed = AssetName.parse("unknown_000000_某某_01.jpg")!!
        assertEquals("unknown", parsed.date)
    }

    @Test
    fun canonicalThenParse_stillWorks() {
        val p = AssetName.prefix("2026-10-22", "15:27:12", "某某")
        val dup = AssetName.stillName(p, 1).replace(".jpg", " (1).jpg")
        assertTrue(AssetName.parse(AssetName.canonical(dup))?.label == "某某")
    }
}
