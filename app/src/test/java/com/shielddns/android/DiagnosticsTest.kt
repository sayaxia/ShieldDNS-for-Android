package com.shielddns.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断 CSV 的格式约束：头注释、表头、行内容都必须稳定，否则导出的文件没法批量分析。 */
class DiagnosticsTest {

    private val base = 1_700_000_000_000L

    @Test
    fun `csv 头部写环境与统计`() {
        val csv = Diagnostics.build(base, emptyList(), 600_000L, 0)
        assertTrue(csv.startsWith("# ShieldDNS for Android 诊断包"))
        assertTrue(csv.contains("# exported_at="))
        assertTrue(csv.contains("# version="))
        assertTrue(csv.contains("# network="))
        assertTrue(csv.contains("# upstream="))
        assertTrue(csv.contains("# rules="))
        assertTrue(csv.contains("# window=last 10 min"))
        assertTrue(csv.contains("# entries=0"))
    }

    @Test
    fun `数据行字段与顺序正确`() {
        val entries = listOf(
            LogEntry(base, "ad.zijieapi.com", true, tcp = false, cached = false),
            LogEntry(base + 1000, "www.baidu.com", false, tcp = true, cached = false),
            LogEntry(base + 2000, "cdn.example.com", false, tcp = false, cached = true),
        )
        val csv = Diagnostics.build(base + 3000, entries, null, entries.size)
        val rows = csv.lines().filter { !it.startsWith("#") }.filter { it.isNotBlank() }
        assertEquals("time,host,result,protocol,cached", rows.first())
        assertEquals(4, rows.size) // 表头 + 3 行
        assertTrue(rows[1].endsWith("ad.zijieapi.com,blocked,udp,no"))
        assertTrue(rows[2].endsWith("www.baidu.com,allowed,tcp,no"))
        assertTrue(rows[3].endsWith("cdn.example.com,allowed,udp,yes"))
    }

    @Test
    fun `高频域名按次数倒序写在注释里`() {
        val entries = listOf(
            LogEntry(base, "a.example.com", false),
            LogEntry(base + 1, "a.example.com", false),
            LogEntry(base + 2, "b.example.com", true),
        )
        val csv = Diagnostics.build(base, entries, null, entries.size)
        val topLines = csv.lines().filter { it.startsWith("#   ") }
        assertEquals("#   2 a.example.com", topLines[0])
        assertEquals("#   1 b.example.com", topLines[1])
    }

    @Test
    fun `不足一分钟的时间窗按秒展示`() {
        val csv = Diagnostics.build(base, emptyList(), 30_000L, 0)
        assertTrue(csv.contains("# window=last 30s"))
    }
}
