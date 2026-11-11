package com.shielddns.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * 端到端（进程内）：
 * - UDP：拦掉的包变成 NXDOMAIN、放行的包经「上游」转发回来、缓存命中时 ID 正确；
 * - TCP：三次握手 → 长度前缀查询 → 应答帧，验证最小 TCP 栈与校验和。
 */
class PipelineTest {

    private fun dnsQuery(host: String, id: Int): ByteArray {
        val labels = host.split('.')
        val qLen = 1 + labels.sumOf { it.length + 1 } + 4
        val dns = ByteArray(12 + qLen)
        dns[0] = ((id shr 8) and 0xFF).toByte()
        dns[1] = (id and 0xFF).toByte()
        dns[2] = 0x01 // RD
        dns[5] = 1
        var p = 12
        labels.forEach {
            dns[p++] = it.length.toByte()
            it.toByteArray(Charsets.US_ASCII).forEach { c -> dns[p++] = c }
        }
        dns[p++] = 0
        dns[p++] = 0; dns[p++] = 1; dns[p++] = 0; dns[p++] = 1
        return dns
    }

    private fun queryPacket(host: String, id: Int, clientPort: Int = 52345): ByteArray =
        DnsPackets.wrapUdp(
            byteArrayOf(10, 111, 0, 2),
            byteArrayOf(10, 111, 0, 1),
            clientPort, 53, dnsQuery(host, id)
        )

    private fun answerOf(respPacket: ByteArray): ByteArray {
        val ihl = (respPacket[0].toInt() and 0x0F) * 4
        val thl = if ((respPacket[9].toInt() and 0xFF) == 6) ((respPacket[ihl + 12].toInt() and 0xFF) shr 4) * 4 else 8
        return respPacket.copyOfRange(ihl + thl, respPacket.size)
    }

    private fun buildPipeline(
        rules: String,
        upstream: ((ByteArray) -> ByteArray?)?,
        out: MutableList<ByteArray>,
        cached: () -> Unit = {}
    ): DnsPipeline {
        val engine = RuleEngine()
        engine.rebuild(listOf(rules), "")
        return DnsPipeline(
            engine = engine,
            forward = { raw, _ -> upstream?.invoke(raw) },
            blockMode = { "nxdomain" },
            cacheEnabled = { true },
            onQuery = { _, _, _, _ -> },
            onCached = cached,
            onForwardFailed = {},
            deliver = { pkt -> out.add(pkt) }
        )
    }

    // ---------- UDP ----------

    @Test
    fun blockedQueryGetsNxdomainAnswer() {
        val out = Collections.synchronizedList(mutableListOf<ByteArray>())
        val ppl = buildPipeline("||ads.example.com^", null, out)
        val pkt = queryPacket("ads.example.com", 0x2222)
        ppl.onPacket(pkt, pkt.size)

        assertEquals(1, out.size)
        val resp = out[0]
        assertEquals(10, resp[12].toInt())
        assertEquals(111, resp[13].toInt())
        assertEquals(0, resp[14].toInt())
        assertEquals(1, resp[15].toInt())
        assertEquals(2, resp[19].toInt())
        assertEquals(53, ((resp[20].toInt() and 0xFF) shl 8) or (resp[21].toInt() and 0xFF))
        val dns = answerOf(resp)
        assertTrue((dns[2].toInt() and 0x80) != 0)      // QR=1
        assertEquals(3, dns[3].toInt() and 0x0F)        // NXDOMAIN
        assertEquals(0x22, dns[0].toInt() and 0xFF)
        ppl.shutdown()
    }

    @Test
    fun allowedQueryIsForwardedAndCachedWithNewId() {
        val out = Collections.synchronizedList(mutableListOf<ByteArray>())
        val upstreamCalls = intArrayOf(0)
        val upstream: (ByteArray) -> ByteArray? = { req ->
            upstreamCalls[0]++
            val r = ByteArray(req.size + 16)
            req.copyInto(r, 0, 0, 12)
            r[2] = 0x81.toByte(); r[3] = 0x80.toByte()
            r[6] = 0; r[7] = 1
            req.copyInto(r, 12, 12, req.size)
            var p = req.size
            r[p++] = 0xC0.toByte(); r[p++] = 0x0C.toByte()
            r[p++] = 0; r[p++] = 1; r[p++] = 0; r[p++] = 1
            r[p++] = 0; r[p++] = 0; r[p++] = 0; r[p++] = 60
            r[p++] = 0; r[p++] = 4; r[p++] = 1; r[p++] = 2; r[p++] = 3; r[p] = 4
            r
        }
        var cachedHits = 0
        val ppl = buildPipeline("||ads.example.com^", upstream, out) { cachedHits++ }

        val q1 = queryPacket("good.example.com", 0x3333)
        ppl.onPacket(q1, q1.size)
        var waited = 0
        while (out.isEmpty() && waited < 3000) {
            Thread.sleep(50)
            waited += 50
        }
        assertEquals(1, out.size)
        val dns1 = answerOf(out[0])
        assertEquals(0x33, dns1[0].toInt() and 0xFF)
        assertEquals(1, dns1[7].toInt() and 0xFF)

        val q2 = queryPacket("good.example.com", 0x4444)
        ppl.onPacket(q2, q2.size)
        assertEquals(2, out.size)
        assertEquals(1, upstreamCalls[0])
        assertEquals(1, cachedHits)
        val dns2 = answerOf(out[1])
        assertEquals(0x44, dns2[0].toInt() and 0xFF)
        ppl.shutdown()
    }

    @Test
    fun nonDnsTrafficIsIgnored() {
        val out = Collections.synchronizedList(mutableListOf<ByteArray>())
        val ppl = buildPipeline("||ads.example.com^", null, out)
        val payload = ByteArray(20) { 7 }
        val pkt = DnsPackets.wrapUdp(
            byteArrayOf(10, 111, 0, 2),
            byteArrayOf(10, 111, 0, 1),
            12345, 443, payload
        )
        ppl.onPacket(pkt, pkt.size)
        assertEquals(0, out.size)
        ppl.shutdown()
    }

    @Test
    fun upstreamTimeoutProducesServfail() {
        val out = Collections.synchronizedList(mutableListOf<ByteArray>())
        val ppl = buildPipeline("||ads.example.com^", { null }, out)
        val q = queryPacket("good.example.com", 0x5555)
        ppl.onPacket(q, q.size)
        Thread.sleep(300)
        assertNotNull(out.firstOrNull())
        val dns = answerOf(out[0])
        assertEquals(2, dns[3].toInt() and 0x0F) // SERVFAIL
        ppl.shutdown()
    }

    // ---------- TCP ----------

    private fun tcpPacket(
        srcPort: Int,
        seq: Int,
        ack: Int,
        flags: Int,
        payload: ByteArray = ByteArray(0)
    ): ByteArray = DnsPackets.wrapTcp(
        DnsPackets.TcpSegment(
            srcIp = byteArrayOf(10, 111, 0, 2),
            dstIp = byteArrayOf(10, 111, 0, 1),
            srcPort = srcPort,
            dstPort = 53,
            seq = seq,
            ack = ack,
            flags = flags,
            payload = payload
        )
    )

    /** 含校验和在内重新算一遍，反码和应为 0xFFFF 才说明校验和写对了。 */
    private fun tcpChecksumOk(pkt: ByteArray): Boolean {
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        val total = pkt.size
        var sum = 6 + (total - ihl)
        var i = 12
        while (i < 20) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        i = ihl
        while (i + 1 < total) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < total) sum += (pkt[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum == 0xFFFF
    }

    @Test
    fun tcpWrapRoundTripAndChecksum() {
        val payload = byteArrayOf(0, 12, 1, 2, 3, 4)
        val seg = DnsPackets.TcpSegment(
            byteArrayOf(10, 111, 0, 1), byteArrayOf(10, 111, 0, 2),
            53, 40001, 12345, 6789, DnsPackets.TCP_PSH or DnsPackets.TCP_ACK, payload
        )
        val pkt = DnsPackets.wrapTcp(seg)
        assertTrue("TCP 校验和错误", tcpChecksumOk(pkt))

        val parsed = DnsPackets.parseTcp(pkt, pkt.size)
        assertNotNull(parsed)
        assertEquals(53, parsed!!.srcPort)
        assertEquals(40001, parsed.dstPort)
        assertEquals(12345, parsed.seq)
        assertEquals(6789, parsed.ack)
        assertEquals(0x18, parsed.flags)
        assertEquals(payload.size, parsed.payload.size)
    }

    @Test
    fun tcpHandshakeAndBlockedQueryGetsAnswer() {
        val out = Collections.synchronizedList(mutableListOf<ByteArray>())
        val engine = RuleEngine().apply { rebuild(listOf("||ads.example.com^"), "") }
        val pipeline = DnsPipeline(
            engine = engine,
            forward = { _, _ -> null },
            blockMode = { "nxdomain" },
            cacheEnabled = { true },
            onQuery = { _, _, _, _ -> },
            onCached = {},
            onForwardFailed = {},
            deliver = { pkt -> out.add(pkt) }
        )
        val proxy = TcpDnsProxy(deliver = { pkt -> out.add(pkt) })
        proxy.onMessage = { msg, respond -> pipeline.onDnsMessage(msg, true, respond) }

        // 1) SYN → SYN/ACK
        val syn = tcpPacket(40001, 1000, 0, DnsPackets.TCP_SYN)
        proxy.onPacket(syn, syn.size)
        assertEquals(1, out.size)
        val synAck = DnsPackets.parseTcp(out[0], out[0].size)!!
        assertEquals(DnsPackets.TCP_SYN or DnsPackets.TCP_ACK, synAck.flags)
        assertEquals(1001, synAck.ack)
        assertTrue(tcpChecksumOk(out[0]))

        // 2) 客户端发数据：2 字节长度前缀 + 查询
        val q = dnsQuery("ads.example.com", 0x7788)
        val framed = ByteArray(2 + q.size)
        framed[0] = (q.size shr 8).toByte()
        framed[1] = q.size.toByte()
        System.arraycopy(q, 0, framed, 2, q.size)
        val data = tcpPacket(40001, 1001, synAck.seq + 1, DnsPackets.TCP_ACK or DnsPackets.TCP_PSH, framed)
        proxy.onPacket(data, data.size)

        // 应产生「纯 ACK」和「PSH 应答」两段
        assertEquals(3, out.size)
        val ackSeg = DnsPackets.parseTcp(out[1], out[1].size)!!
        assertEquals(DnsPackets.TCP_ACK, ackSeg.flags)
        assertEquals(1001 + framed.size, ackSeg.ack)

        val respSeg = DnsPackets.parseTcp(out[2], out[2].size)!!
        assertTrue(respSeg.flags and DnsPackets.TCP_ACK != 0)
        assertTrue(respSeg.payload.size > 2)
        val msgLen = ((respSeg.payload[0].toInt() and 0xFF) shl 8) or (respSeg.payload[1].toInt() and 0xFF)
        assertEquals(msgLen, respSeg.payload.size - 2)
        val dns = respSeg.payload.copyOfRange(2, respSeg.payload.size)
        assertTrue((dns[2].toInt() and 0x80) != 0)
        assertEquals(3, dns[3].toInt() and 0x0F)   // 被规则拦掉，NXDOMAIN
        assertEquals(0x77, dns[0].toInt() and 0xFF)
        assertTrue(tcpChecksumOk(out[2]))

        pipeline.shutdown()
    }

    @Test
    fun tcpUnknownConnectionGetsReset() {
        val out = Collections.synchronizedList(mutableListOf<ByteArray>())
        val proxy = TcpDnsProxy(deliver = { pkt -> out.add(pkt) })
        // 没有握手直接发数据：应回 RST
        val data = tcpPacket(40002, 5000, 0, DnsPackets.TCP_ACK, ByteArray(4))
        proxy.onPacket(data, data.size)
        assertEquals(1, out.size)
        val rst = DnsPackets.parseTcp(out[0], out[0].size)!!
        assertTrue(rst.flags and DnsPackets.TCP_RST != 0)
    }

    @Test
    fun tcpFinClosesConnection() {
        val out = Collections.synchronizedList(mutableListOf<ByteArray>())
        val proxy = TcpDnsProxy(deliver = { pkt -> out.add(pkt) })
        val syn = tcpPacket(40003, 7000, 0, DnsPackets.TCP_SYN)
        proxy.onPacket(syn, syn.size)
        val synAck = DnsPackets.parseTcp(out[0], out[0].size)!!
        val fin = tcpPacket(40003, 7001, synAck.seq + 1, DnsPackets.TCP_FIN or DnsPackets.TCP_ACK)
        proxy.onPacket(fin, fin.size)
        val finAck = DnsPackets.parseTcp(out[1], out[1].size)!!
        assertTrue(finAck.flags and DnsPackets.TCP_FIN != 0)
    }
}
