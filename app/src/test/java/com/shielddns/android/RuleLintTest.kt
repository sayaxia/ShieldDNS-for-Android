package com.shielddns.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleLintTest {

    @Test
    fun `裸域名和通配都算有效`() {
        val r = RuleLint.check("example.com\n||ads.example.com^\n||*-reading-ad.qznovelvod.com^")
        assertEquals(3, r.valid)
        assertEquals(0, r.fatalCount)
    }

    @Test
    fun `例外与 hosts 格式有效`() {
        val r = RuleLint.check("@@||ok.example.com^\n0.0.0.0 block.example.com\n127.0.0.1 b.example.org")
        assertEquals(3, r.valid)
        assertEquals(0, r.issues.size)
    }

    @Test
    fun `注释和空行只计入忽略`() {
        val r = RuleLint.check("# 说明\n! 说明\n\nexample.com")
        assertEquals(1, r.valid)
        assertEquals(2, r.ignored)
    }

    @Test
    fun `外观规则与正则被判无效`() {
        val r = RuleLint.check("example.com##.ad-banner\n/ads[0-9]/\n[dns]")
        assertEquals(0, r.valid)
        assertEquals(3, r.fatalCount)
    }

    @Test
    fun `没有点号的行判无效并给出原因`() {
        val r = RuleLint.check("localhost")
        assertEquals(0, r.valid)
        assertEquals(1, r.fatalCount)
        assertTrue(r.issues[0].reason.contains("缺少域名"))
    }

    @Test
    fun `行号与编辑器一致`() {
        val r = RuleLint.check("# c\nexample.com\nbad line")
        assertEquals(3, r.issues.single().line)
    }

    @Test
    fun `dollar 选项只警告不算无效`() {
        val r = RuleLint.check("||ads.example.com^\$third-party")
        assertEquals(1, r.valid)
        assertEquals(1, r.warnCount)
        assertEquals(0, r.fatalCount)
    }
}
