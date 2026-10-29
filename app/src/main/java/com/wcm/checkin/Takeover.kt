package com.wcm.checkin

/**
 * 接管相册里**已有但索引没记录**的文件（旧版本抓的、手动拷入的、改名后留下的副本）。
 *
 * 策略是「按大小预筛，只在撞车时算哈希」：
 * 1. 文件名能按本 App 格式反解 → 说明是自己抓的，没有索引记录就**直接认领，不读字节**；
 * 2. 有索引记录且 size 一致 → 直接采信；
 * 3. size 不一致才算 SHA-256 确认（同一张图被外部工具重新压缩过 / ` (1)` 副本撞名）。
 *
 * 这样 6k 张目录通常只需哈希不到 5%，耗时 10–20s；全量哈希要读完整目录字节，可能几分钟。
 *
 * 输入刻意用纯 Kotlin 的 [Candidate] 而不是 SAF 的 Uri，保证可 JVM 单测。
 */
object Takeover {

    data class Candidate(val name: String, val size: Long)

    data class Report(
        val scanned: Int = 0,
        val claimed: Int = 0,
        val hashed: Int = 0,
        val conflict: Int = 0,
        val ignored: Int = 0,
        val ms: Long = 0,
    ) {
        val summary: String
            get() = "接管 ${claimed}（哈希确认 $hashed，冲突 $conflict，非本应用文件 $ignored）"
    }

    /**
     * @param hashOf 只在 size 撞车时调用，用于计算内容哈希；返回 null 表示读取失败。
     */
    fun claimAll(
        candidates: List<Candidate>,
        index: CrawlIndex,
        hashOf: ((Candidate) -> String?)? = null,
    ): Report {
        val start = System.currentTimeMillis()
        var claimed = 0
        var hashed = 0
        var conflict = 0
        var ignored = 0

        for (c in candidates) {
            if (c.name.isEmpty()) {
                ignored++
                continue
            }
            val canonical = AssetName.canonical(c.name)
            // 认不出格式 = 不是本应用抓的，不动它（真去重靠 L3 哈希，不靠猜文件名）
            if (AssetName.parse(canonical) == null) {
                ignored++
                continue
            }
            val existing = index.byName(canonical)
            if (existing == null) {
                index.adopt(canonical, c.size)
                claimed++
                continue
            }
            if (existing.size == c.size) {
                claimed++          // 大小一致，直接采信，不花一次哈希
                continue
            }
            val h = hashOf?.invoke(c)
            hashed++
            if (h == null) {
                conflict++
                continue
            }
            if (existing.hash.isEmpty() || existing.hash == h) {
                index.adopt(canonical, c.size, h)
                claimed++
            } else {
                conflict++         // 同名不同内容：保留用户文件，不删不移
            }
        }
        return Report(
            scanned = candidates.size,
            claimed = claimed,
            hashed = hashed,
            conflict = conflict,
            ignored = ignored,
            ms = System.currentTimeMillis() - start,
        )
    }
}
