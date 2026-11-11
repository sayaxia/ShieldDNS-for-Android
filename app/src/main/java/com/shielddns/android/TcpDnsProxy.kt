package com.shielddns.android

import java.util.concurrent.ConcurrentHashMap

/**
 * 最小 TCP:53 代理，只服务 VpnService tun 里的 DNS over TCP。
 *
 * 真实客户端的 DNS over TCP 基本是「握手 → 一发一收（2 字节长度前缀）→ 关闭」，
 * 所以这里只实现按序、单连接的最小状态机：不做重传、滑动窗口、乱序重组。
 * 目标不是做一个通用 TCP 栈，而是让回退到 TCP 的解析请求不超时、并且同样受规则约束。
 */
class TcpDnsProxy(
    private val deliver: (ByteArray) -> Unit,
    private val log: (String) -> Unit = {}
) {

    companion object {
        private const val STALE_MS = 30_000L
        private const val MAX_MSG = 4096
    }

    private class Conn(
        var clientNext: Int,
        var serverSeq: Int,
        var buf: ByteArray = ByteArray(0),
        var lastActive: Long = System.currentTimeMillis()
    )

    private val conns = ConcurrentHashMap<String, Conn>()

    /** 消息级处理器：由 DnsPipeline.onDnsMessage 注入，UDP/TCP 共用规则与缓存。 */
    var onMessage: ((ByteArray, (ByteArray) -> Unit) -> Unit)? = null

    fun onPacket(buf: ByteArray, len: Int) {
        val seg = DnsPackets.parseTcp(buf, len) ?: return
        if (seg.dstPort != 53) return
        cleanupStale()

        val key = "${seg.srcIp.contentToString()}:${seg.srcPort}"
        val isSyn = seg.flags and DnsPackets.TCP_SYN != 0
        val isAck = seg.flags and DnsPackets.TCP_ACK != 0
        val isFin = seg.flags and DnsPackets.TCP_FIN != 0

        if (isSyn && !isAck) {
            val isn = (System.nanoTime() and 0xFFFFFFFFL).toInt()
            // SYN 占一个序号，所以数据从 isn + 1 开始
            val conn = Conn(clientNext = seg.seq + 1, serverSeq = isn + 1)
            conns[key] = conn
            send(seg, DnsPackets.TCP_SYN or DnsPackets.TCP_ACK, isn, conn.clientNext, ByteArray(0))
            return
        }

        val conn = conns[key]
        if (conn == null) {
            // 不认识的连接直接 RST，让客户端立刻失败，而不是等 4 秒超时
            send(
                seg,
                DnsPackets.TCP_RST or DnsPackets.TCP_ACK,
                seg.ack,
                seg.seq + seg.payload.size,
                ByteArray(0)
            )
            return
        }
        conn.lastActive = System.currentTimeMillis()

        if (isFin) {
            send(seg, DnsPackets.TCP_FIN or DnsPackets.TCP_ACK, conn.serverSeq, conn.clientNext, ByteArray(0))
            conns.remove(key)
            return
        }

        if (seg.payload.isNotEmpty()) {
            // 只接受按序到达；乱序或重传直接丢掉（本地 tun 里几乎不会出现）
            if (seg.seq != conn.clientNext) {
                log("tcp: 丢弃乱序段 seq=${seg.seq} expect=${conn.clientNext}")
                return
            }
            conn.clientNext = seg.seq + seg.payload.size
            conn.buf = conn.buf + seg.payload

            // 立刻确认，避免转发耗时让客户端以为丢包而重传
            synchronized(conn) {
                send(seg, DnsPackets.TCP_ACK, conn.serverSeq, conn.clientNext, ByteArray(0))
            }
            drain(seg, conn)
        }
    }

    /** 把缓冲区里攒够的「长度前缀 + 消息」逐条交给处理器。 */
    private fun drain(seg: DnsPackets.TcpSegment, conn: Conn) {
        while (conn.buf.size >= 2) {
            val msgLen = ((conn.buf[0].toInt() and 0xFF) shl 8) or (conn.buf[1].toInt() and 0xFF)
            if (msgLen <= 0 || msgLen > MAX_MSG) {
                log("tcp: 长度前缀异常 $msgLen，放弃该连接")
                conn.buf = ByteArray(0)
                return
            }
            if (conn.buf.size < 2 + msgLen) return
            val msg = conn.buf.copyOfRange(2, 2 + msgLen)
            conn.buf = conn.buf.copyOfRange(2 + msgLen, conn.buf.size)
            dispatch(seg, conn, msg)
        }
    }

    private fun dispatch(seg: DnsPackets.TcpSegment, conn: Conn, msg: ByteArray) {
        val handler = onMessage ?: return
        handler(msg) { resp ->
            if (resp.isEmpty()) return@handler
            val framed = ByteArray(2 + resp.size)
            framed[0] = (resp.size shr 8).toByte()
            framed[1] = resp.size.toByte()
            System.arraycopy(resp, 0, framed, 2, resp.size)
            // 应答可能来自转发线程，锁住连接保证 seq 单调
            synchronized(conn) {
                send(seg, DnsPackets.TCP_PSH or DnsPackets.TCP_ACK, conn.serverSeq, conn.clientNext, framed)
                conn.serverSeq += framed.size
            }
        }
    }

    private fun send(
        seg: DnsPackets.TcpSegment,
        flags: Int,
        seq: Int,
        ack: Int,
        payload: ByteArray
    ) {
        val out = DnsPackets.TcpSegment(
            srcIp = seg.dstIp,   // 从本地 DNS 地址 10.111.0.1 发出
            dstIp = seg.srcIp,
            srcPort = 53,
            dstPort = seg.srcPort,
            seq = seq,
            ack = ack,
            flags = flags,
            payload = payload
        )
        deliver(DnsPackets.wrapTcp(out))
    }

    private fun cleanupStale() {
        val now = System.currentTimeMillis()
        conns.entries.removeIf { now - it.value.lastActive > STALE_MS }
    }

    fun clear() = conns.clear()
}
