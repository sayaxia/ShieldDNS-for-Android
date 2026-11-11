package com.shielddns.android

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 纯逻辑的 DNS 处理管线（不碰 Android API，方便单测）：
 * 核心是消息级的 onDnsMessage，UDP 与 TCP 共用同一套规则、缓存和统计。
 * UDP 入口负责解 IPv4/UDP 包并把应答封回去；TCP 由 TcpDnsProxy 负责封装。
 */
class DnsPipeline(
    private val engine: RuleEngine,
    private val forward: (ByteArray, Boolean) -> ByteArray?,
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

    /** 处理一个进入 tun 的 UDP 报文；不是 UDP:53 的话直接忽略。 */
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

        val query = buf.copyOfRange(off, off + payloadLen)
        onDnsMessage(query) { resp ->
            deliver(DnsPackets.wrapUdp(dstIp, srcIp, 53, srcPort, resp))
        }
    }

    /**
     * 消息级处理：一条完整的 DNS 查询消息进，应答通过 respond 交出去。
     * @param viaTcp 查询是从 TCP:53 进来的，上游也必须用 TCP 转发。
     * respond 可能在后台线程被调用，调用方要保证自己的写出是线程安全的。
     */
    fun onDnsMessage(query: ByteArray, viaTcp: Boolean = false, respond: (ByteArray) -> Unit) {
        val q = DnsPackets.parseQuery(query, 0, query.size) ?: return
        val qtype = ((query[q.questionEnd - 4].toInt() and 0xFF) shl 8) or
            (query[q.questionEnd - 3].toInt() and 0xFF)
        val key = "${q.name}|$qtype"

        if (engine.isBlocked(q.name)) {
            val zero = blockMode() == "zero"
            respond(DnsPackets.buildResponse(query, 0, q.questionEnd, if (zero) 0 else 3, zero))
            onQuery(q.name, true)
            return
        }

        if (cacheEnabled()) {
            val hit = cache[key]
            if (hit != null && hit.expires > System.currentTimeMillis()) {
                // 缓存里是某一次的应答，ID 必须换成这次的，否则客户端丢弃
                respond(DnsPackets.adoptId(hit.payload, query, 0))
                onCached()
                onQuery(q.name, false)
                return
            }
        }

        onQuery(q.name, false)
        pool.execute {
            val resp = forward(query, viaTcp)
            if (resp == null) {
                onForwardFailed()
                respond(DnsPackets.buildResponse(query, 0, q.questionEnd, 2, false))
            } else {
                if (cacheEnabled()) {
                    val ttl = DnsPackets.minTtl(resp).coerceIn(30, 3600)
                    if (cache.size > 6000) cache.clear()
                    cache[key] = Entry(resp, System.currentTimeMillis() + ttl * 1000L)
                }
                respond(resp)
            }
        }
    }
}
