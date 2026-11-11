package com.shielddns.android

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * 用本地假上游验证「转发 → 收应答 → 取 TTL」这段链路，不依赖外网、不依赖 Android。
 */
class ResolverTest {

    private fun query(host: String, id: Int): ByteArray {
        val labels = host.split('.')
        val qnameLen = 1 + labels.sumOf { it.length + 1 } + 4
        val out = ByteArray(12 + qnameLen)
        out[0] = ((id shr 8) and 0xFF).toByte()
        out[1] = (id and 0xFF).toByte()
        out[2] = 0x01
        out[4] = 0
        out[5] = 1
        var p = 12
        labels.forEach {
            out[p++] = it.length.toByte()
            it.toByteArray(Charsets.US_ASCII).forEach { c -> out[p++] = c }
        }
        out[p++] = 0
        out[p++] = 0; out[p++] = 1; out[p++] = 0; out[p++] = 1
        return out
    }

    /** 造一个带一条 A 记录、TTL=120 的应答。 */
    private fun response(req: ByteArray, ttl: Int): ByteArray {
        val questionEnd = req.size
        val out = ByteArray(questionEnd + 16)
        req.copyInto(out, 0, 0, 12)
        out[2] = 0x81.toByte()
        out[3] = 0x80.toByte()
        out[6] = 0; out[7] = 1
        req.copyInto(out, 12, 12, questionEnd)
        var p = questionEnd
        out[p++] = 0xC0.toByte(); out[p++] = 0x0C.toByte()
        out[p++] = 0; out[p++] = 1
        out[p++] = 0; out[p++] = 1
        out[p++] = 0; out[p++] = 0
        out[p++] = ((ttl shr 8) and 0xFF).toByte(); out[p++] = (ttl and 0xFF).toByte()
        out[p++] = 0; out[p++] = 4
        out[p++] = 1; out[p++] = 2; out[p++] = 3; out[p] = 4
        return out
    }

    @Test
    fun forwardsToUpstreamAndReadsTtl() {
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val worker = thread {
            try {
                val buf = ByteArray(1500)
                val pkt = DatagramPacket(buf, buf.size)
                server.receive(pkt)
                val req = buf.copyOf(pkt.length)
                val resp = response(req, 120)
                server.send(DatagramPacket(resp, resp.size, pkt.socketAddress))
            } catch (_: Exception) {
            }
        }
        try {
            val resolver = Resolver({ })
            val req = query("probe.example.com", 0x0102)
            val resp = resolver.query(req, listOf(InetAddress.getByName("127.0.0.1")), 3000, port)
            assertNotNull(resp)
            assertTrue(resp!!.size > req.size)
            assertTrue(DnsPackets.minTtl(resp) in 100..140)
            // 应答 ID 与查询一致
            assertTrue((resp[0] == req[0]) && (resp[1] == req[1]))
        } finally {
            server.close()
            worker.join(1000)
        }
    }
}
