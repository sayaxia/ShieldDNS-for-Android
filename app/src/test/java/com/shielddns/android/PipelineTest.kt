package com.shielddns.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 端到端（进程内）：造一个完整的 IPv4/UDP 查询包送进 DnsPipeline，
 * 校验拦掉的包会变成 NXDOMAIN 应答、放行的包会经「上游」转发回来、缓存命中的包 ID 正确。
 */
class PipelineTest {

    private fun queryPacket(host: String, id: Int, clientPort: Int = 52345): ByteArray {
        val labels = host.split('.')
        val qLen = 1 + labels.sumOf { it.length + 1 } + 4
        val dns = ByteArray(12 + qLen)
        dns[0] = ((id shr 8) and 0xFF).toByte()
        dns[1] = (id and 0xFF).toByte()
        dns[2] = 0x01 // RD
        dns[4] = 0
        dns[5] = 1
        var p = 12
        labels.forEach {
            dns[p++] = it.length.toByte()
            it.toByteArray(Charsets.US_ASCII).forEach { c -> dns[p++] = c }
        }
        dns[p++] = 0
        dns[p++] = 0; dns[p++] = 1; dns[p++] = 0; dns[p++] = 1
        return DnsPackets.wrapUdp(
            byteArrayOf(10, 111, 0, 2),
            byteArrayOf(10, 111, 0, 1),
            clientPort, 53, dns
        )
    }

    private fun answerOf(respPacket: ByteArray): ByteArray {
        val ihl = (respPacket[0].toInt() and 0x0F) * 4
        return respPacket.copyOfRange(ihl + 8, respPacket.size)
    }

    private fun buildPipeline(
        rules: String,
        upstream: ((ByteArray) -> ByteArray?)?,
        out: MutableList<ByteArray>,
        cached: () -> Unit = {}
    ): DnsPipeline {
        val engine = RuleEngine()
        engine.rebuild(listOf(rules), "")
        val pipeline = DnsPipeline(
            engine = engine,
            forward = upstream ?: { null },
            blockMode = { "nxdomain" },
            cacheEnabled = { true },
            onQuery = { _, _ -> },
            onCached = cached,
            onForwardFailed = {},
            deliver = { pkt -> out.add(pkt) }
        )
        return pipeline
    }

    @Test
    fun blockedQueryGetsNxdomainAnswer() {
        val out = Collections.synchronizedList(mutableListOf<ByteArray>())
        val ppl = buildPipeline("||ads.example.com^", null, out)
        val pkt = queryPacket("ads.example.com", 0x2222)
        ppl.onPacket(pkt, pkt.size)

        assertEquals(1, out.size)
        val resp = out[0]
        // 源/目的对调：源是我们的 VPN 地址，目的回到客户端
        assertEquals(10, resp[12].toInt())
        assertEquals(111, resp[13].toInt())
        assertEquals(0, resp[14].toInt())
        assertEquals(1, resp[15].toInt())
        assertEquals(2, resp[19].toInt())
        assertEquals(53, ((resp[20].toInt() and 0xFF) shl 8) or (resp[21].toInt() and 0xFF))
        val dns = answerOf(resp)
        assertTrue((dns[2].toInt() and 0x80) != 0)      // QR=1
        assertEquals(3, dns[3].toInt() and 0x0F)        // NXDOMAIN
        assertEquals(0x22, dns[0].toInt() and 0xFF)     // ID 原样返回
        ppl.shutdown()
    }

    @Test
    fun allowedQueryIsForwardedAndCachedWithNewId() {
        val out = Collections.synchronizedList(mutableListOf<ByteArray>())
        val latch = CountDownLatch(1)
        val upstreamCalls = intArrayOf(0)
        // 上游：直接把查询原样改一个 A 记录返回
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
            latch.countDown()
            r
        }
        var cachedHits = 0
        val ppl = buildPipeline("||ads.example.com^", upstream, out) { cachedHits++ }

        val q1 = queryPacket("good.example.com", 0x3333)
        ppl.onPacket(q1, q1.size)
        // 转发是异步的：轮询等第一条应答写回
        var waited = 0
        while (out.isEmpty() && waited < 3000) {
            Thread.sleep(50)
            waited += 50
        }
        assertEquals(1, out.size)
        val dns1 = answerOf(out[0])
        assertEquals(0x33, dns1[0].toInt() and 0xFF)
        assertEquals(1, dns1[7].toInt() and 0xFF) // 带一条应答

        // 第二次相同查询：应命中缓存，不再打上游
        val q2 = queryPacket("good.example.com", 0x4444)
        ppl.onPacket(q2, q2.size)
        assertEquals(2, out.size)
        assertEquals(1, upstreamCalls[0])
        assertEquals(1, cachedHits)
        val dns2 = answerOf(out[1])
        assertEquals(0x44, dns2[0].toInt() and 0xFF)
        assertEquals(0x44, dns2[1].toInt() and 0xFF)
        ppl.shutdown()
    }

    @Test
    fun nonDnsTrafficIsIgnored() {
        val out = Collections.synchronizedList(mutableListOf<ByteArray>())
        val ppl = buildPipeline("||ads.example.com^", null, out)
        // 造一个非 53 端口的 UDP 包（比如 443 的 QUIC）
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
        val latch = CountDownLatch(1)
        val ppl = buildPipeline("||ads.example.com^", { null }, out)
        val q = queryPacket("good.example.com", 0x5555)
        ppl.onPacket(q, q.size)
        // 转发是异步的，等一会儿
        Thread.sleep(300)
        assertNotNull(out.firstOrNull())
        val dns = answerOf(out[0])
        assertEquals(2, dns[3].toInt() and 0x0F) // SERVFAIL
        latch.countDown()
        ppl.shutdown()
    }
}
