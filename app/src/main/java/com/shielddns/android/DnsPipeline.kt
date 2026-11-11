package com.shielddns.android

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 纯逻辑的 DNS 处理管线（不碰 Android API，方便单测）：
 * 输入一个 IPv4/UDP 报文 → 判规则 / 查缓存 / 转发上游 → 输出一个 IPv4/UDP 应答包。
 */
class DnsPipeline(
    private val engine: RuleEngine,
    private val forward: (ByteArray) -> ByteArray?,
    private val blockMode: () -> String,
    private val cacheEnabled: () -> Boolean,
    private val onQuery: (String, Boolean) -> Unit,
    private val onCached: () -> Unit,
    private val onForwardFailed: () -> Unit,
    private val deliver: (ByteArray) -> Unit
) {

    private data class Entry(val payload: ByteArray, val expires: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val pool: ExecutorService = Executors.newFixedThreadPool(4)

    fun clearCache() = cache.clear()

    fun shutdown() = pool.shutdownNow()

    /** 处理一个进入 tun 的报文；不是 UDP:53 的话直接忽略。 */
    fun onPacket(buf: ByteArray, len: Int) {
        if (len < 28) return
        if ((buf[0].toInt() shr 4) and 0x0F != 4) return
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return
        if ((buf[9].toInt() and 0xFF) != 17) return

        val ipTotal = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        if (ipTotal < ihl + 8 || ipTotal > len) return

        val srcIp = buf.copyOfRange(12, 16)
        val dstIp = buf.copyOfRange(16, 20)
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val off = ihl + 8
        val payloadLen = ipTotal - off
        if (payloadLen < 12) return

        val q = DnsPackets.parseQuery(buf, off, payloadLen) ?: return
        val qtype = ((buf[q.questionEnd - 4].toInt() and 0xFF) shl 8) or
            (buf[q.questionEnd - 3].toInt() and 0xFF)
        val key = "${q.name}|$qtype"

        if (engine.isBlocked(q.name)) {
            val zero = blockMode() == "zero"
            val resp = DnsPackets.buildResponse(buf, off, q.questionEnd, if (zero) 0 else 3, zero)
            deliver(DnsPackets.wrapUdp(dstIp, srcIp, 53, srcPort, resp))
            onQuery(q.name, true)
            return
        }

        if (cacheEnabled()) {
            val hit = cache[key]
            if (hit != null && hit.expires > System.currentTimeMillis()) {
                // 缓存里是某一次的应答，ID 必须换成这次的，否则客户端丢弃
                val payload = DnsPackets.adoptId(hit.payload, buf, off)
                deliver(DnsPackets.wrapUdp(dstIp, srcIp, 53, srcPort, payload))
                onCached()
                onQuery(q.name, false)
                return
            }
        }

        onQuery(q.name, false)
        val raw = buf.copyOfRange(off, off + payloadLen)
        pool.execute {
            val resp = forward(raw)
            if (resp == null) {
                onForwardFailed()
                val failResp = DnsPackets.buildResponse(buf, off, q.questionEnd, 2, false)
                deliver(DnsPackets.wrapUdp(dstIp, srcIp, 53, srcPort, failResp))
            } else {
                if (cacheEnabled()) {
                    val ttl = DnsPackets.minTtl(resp).coerceIn(30, 3600)
                    if (cache.size > 6000) cache.clear()
                    cache[key] = Entry(resp, System.currentTimeMillis() + ttl * 1000L)
                }
                deliver(DnsPackets.wrapUdp(dstIp, srcIp, 53, srcPort, resp))
            }
        }
    }
}
