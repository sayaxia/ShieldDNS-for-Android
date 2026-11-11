package com.shielddns.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {

    private fun qnameBytes(host: String): ByteArray {
        val labels = host.split('.')
        val out = ByteArray(1 + labels.sumOf { it.length + 1 } + 4)
        var p = 0
        labels.forEach {
            out[p++] = it.length.toByte()
            it.toByteArray(Charsets.US_ASCII).forEach { c -> out[p++] = c }
        }
        out[p++] = 0
        // QTYPE A, QCLASS IN
        out[p++] = 0; out[p++] = 1; out[p++] = 0; out[p++] = 1
        return out
    }

    private fun buildQuery(host: String, id: Int): ByteArray {
        val q = qnameBytes(host)
        val out = ByteArray(12 + q.size)
        out[0] = ((id shr 8) and 0xFF).toByte()
        out[1] = (id and 0xFF).toByte()
        out[2] = 0x01 // RD
        out[4] = 0; out[5] = 1
        q.copyInto(out, 12)
        return out
    }

    // ---------- 规则 ----------

    @Test
    fun hostsAndAdguardSyntax() {
        val e = RuleEngine()
        e.rebuild(
            listOf(
                """
                ! 注释
                # 注释
                ||ads.example.com^
                0.0.0.0 tracker.example.org
                127.0.0.1 beacon.example.net
                @@||ok.example.com^
                example.com##.banner
                """.trimIndent()
            ),
            "||custom.example.cn^\nplain.example.io"
        )
        assertTrue(e.isBlocked("ads.example.com"))
        assertTrue(e.isBlocked("sub.ads.example.com"))
        assertTrue(e.isBlocked("tracker.example.org"))
        assertTrue(e.isBlocked("beacon.example.net"))
        assertTrue(e.isBlocked("custom.example.cn"))
        assertTrue(e.isBlocked("a.custom.example.cn"))
        assertTrue(e.isBlocked("plain.example.io"))
        // 例外优先
        assertFalse(e.isBlocked("ok.example.com"))
        assertFalse(e.isBlocked("deep.ok.example.com"))
        assertFalse(e.isBlocked("example.com"))
        assertFalse(e.isBlocked("unrelated.site"))
    }

    @Test
    fun wildcardRulesMatchDynamicAdHosts() {
        val e = RuleEngine()
        e.rebuild(
            emptyList(),
            """
            ||*.reading-ad.*^
            ||ads*.zijieapi.com^
            ||*-ad-sign.byteimg.com^
            ||*-daily-reading-ad.qznovelvod.com^
            ||*-reading-ad.qznovelvod.com^
            """.trimIndent()
        )

        // 之前这些域名一条规则都匹配不上，App 因此陷入重连风暴
        assertTrue(e.isBlocked("v95-aw-reading-ad.qznovelvod.com"))
        assertTrue(e.isBlocked("v13-reading-ad.qznovelvod.com"))
        assertTrue(e.isBlocked("v5-se-sjy-daily-reading-ad.qznovelvod.com"))
        assertTrue(e.isBlocked("ads3-normal-hl.zijieapi.com"))
        assertTrue(e.isBlocked("ads.zijieapi.com"))
        assertTrue(e.isBlocked("p3-ad-sign.byteimg.com"))
        assertTrue(e.isBlocked("p9-ad-sign.byteimg.com"))

        // 正片、接口不受通配影响
        assertFalse(e.isBlocked("v5-reading-video-c.qznovelvod.com"))
        assertFalse(e.isBlocked("v26-reading-videocdn302.qznovelvod.com"))
        assertFalse(e.isBlocked("api5-normal-hl.fqnovel.com"))
    }

    @Test
    fun wildcardExceptionWins() {
        val e = RuleEngine()
        e.rebuild(
            emptyList(),
            "||*-ad-sign.byteimg.com^\n@@||p9-ad-sign.byteimg.com^"
        )
        assertTrue(e.isBlocked("p3-ad-sign.byteimg.com"))
        assertFalse(e.isBlocked("p9-ad-sign.byteimg.com"))
    }

    @Test
    fun emptyRulesNeverBlock() {
        val e = RuleEngine()
        e.rebuild(emptyList(), "")
        assertEquals(0, e.ruleCount)
        assertFalse(e.isBlocked("anything.example"))
    }

    // ---------- 报文 ----------

    @Test
    fun parseQueryReadsNameAndEnd() {
        val q = buildQuery("www.example.com", 0x1234)
        val parsed = DnsPackets.parseQuery(q, 0, q.size)
        assertNotNull(parsed)
        assertEquals("www.example.com", parsed!!.name)
        assertEquals(0x1234, parsed.id)
        assertEquals(q.size, parsed.questionEnd)
    }

    @Test
    fun nxdomainResponseKeepsIdAndQuestion() {
        val q = buildQuery("blocked.example.com", 0x4321)
        val parsed = DnsPackets.parseQuery(q, 0, q.size)!!
        val resp = DnsPackets.buildResponse(q, 0, parsed.questionEnd, 3, false)
        assertEquals(0x43, resp[0].toInt() and 0xFF)
        assertEquals(0x21, resp[1].toInt() and 0xFF)
        // QR=1, RA=1, RCODE=3
        assertEquals(0x81, resp[2].toInt() and 0xFF and 0x81)
        assertEquals(0x83, resp[3].toInt() and 0xFF)
        assertEquals(0, resp[7].toInt() and 0xFF) // ANCOUNT=0
        assertEquals(q.size, resp.size)
    }

    @Test
    fun zeroAddressResponseCarriesOneAnswer() {
        val q = buildQuery("blocked.example.com", 0x1111)
        val parsed = DnsPackets.parseQuery(q, 0, q.size)!!
        val resp = DnsPackets.buildResponse(q, 0, parsed.questionEnd, 0, true)
        assertEquals(1, resp[7].toInt() and 0xFF)
        // 末尾 4 字节是 0.0.0.0
        val n = resp.size
        assertEquals(0, resp[n - 1].toInt())
        assertEquals(0, resp[n - 2].toInt())
    }

    @Test
    fun cachedPayloadTakesNewId() {
        val a = buildQuery("x.example.com", 0xAAAA)
        val b = buildQuery("x.example.com", 0xBBBB)
        val parsedA = DnsPackets.parseQuery(a, 0, a.size)!!
        val cached = DnsPackets.buildResponse(a, 0, parsedA.questionEnd, 3, false)
        val reused = DnsPackets.adoptId(cached, b, 0)
        assertEquals(0xBB, reused[0].toInt() and 0xFF)
        assertEquals(0xBB, reused[1].toInt() and 0xFF)
        // 其余内容不变
        assertEquals(cached[3], reused[3])
    }

    @Test
    fun udpPacketLayout() {
        val payload = ByteArray(20) { it.toByte() }
        val pkt = DnsPackets.wrapUdp(
            byteArrayOf(10, 111, 0, 1),
            byteArrayOf(10, 111, 0, 2),
            53, 9999,
            payload
        )
        assertEquals(48, pkt.size)
        assertEquals(17, pkt[9].toInt())
        assertEquals(9999, ((pkt[22].toInt() and 0xFF) shl 8) or (pkt[23].toInt() and 0xFF))
        assertEquals(53, ((pkt[20].toInt() and 0xFF) shl 8) or (pkt[21].toInt() and 0xFF))
        // IPv4 头校验和应为 0
        var sum = 0
        var i = 0
        while (i < 20) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFF, sum)
        payload.copyOf().forEachIndexed { idx, b -> assertEquals(b, pkt[28 + idx]) }
    }

    @Test
    fun subStoreCountsRealRules() {
        val text = """
            ! comment
            ||a.com^
            ||b.com^
            c.com##.x
            # another comment
            0.0.0.0 d.com
        """.trimIndent()
        assertEquals(3, SubStore.countRules(text))
    }
}
