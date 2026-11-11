package com.shielddns.android

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 增强模式下的 TCP 转发（简化版 tun2socks）。
 *
 * 只做三件事：
 *  1. 客户端 SYN → 立刻回 SYN-ACK，异步用 protect() 的 socket 连目标，连不上再补 RST；
 *  2. 客户端数据按序写进 socket，目标数据原样 PSH 回客户端；
 *  3. 目的端口 443 的连接，在首个含 TLS 记录的包里取 SNI，交给 [onSni]。
 *
 * 刻意不做的：重传、滑动窗口、乱序重组、分片。tun 里的 TCP 由本机内核发起，
 * 按序且不会分片，这套最小状态机够用；真正的可靠性由两端内核的 socket 负责。
 */
class TcpRelay(
    private val protect: (Socket) -> Boolean,
    private val deliver: (ByteArray) -> Unit,
) {

    companion object {
        private const val STALE_MS = 120_000L
        private const val MAX_CONN = 300
        private const val MAX_PENDING = 8
        private const val READ_CHUNK = 1400
    }

    private class Conn(
        val clientIp: ByteArray,
        val clientPort: Int,
        val targetIp: ByteArray,
        val targetPort: Int,
        var clientNext: Int,
        var serverSeq: Int,
    ) {
        var socket: Socket? = null
        val pending = ArrayDeque<ByteArray>()
        var sniDone = false

        /** connect 成功且待发数据已经按序冲出去之后才置位，避免新旧数据插队。 */
        var ready = false

        @Volatile
        var closed = false

        @Volatile
        var lastActive = System.currentTimeMillis()
    }

    private val conns = ConcurrentHashMap<String, Conn>()
    private var packets = 0

    /** 取到 SNI 时回调：host 为明文域名，ech 表示客户端声明了加密握手。 */
    var onSni: ((host: String, ech: Boolean) -> Unit)? = null

    /** 每次向目标建连的结果：true 成功、false 失败，用于诊断统计。 */
    var onConnect: ((Boolean) -> Unit)? = null

    /** 建连失败的具体原因（目标 + 异常信息），用于诊断。 */
    var onRelayError: ((target: String, reason: String) -> Unit)? = null

    /** 取到 SNI 后判定是否拦截；返回 true 则立刻断开这条连接。 */
    var blockPolicy: ((host: String) -> Boolean)? = null

    /** 命中拦截名单时回调，用于统计。 */
    var onBlock: ((host: String) -> Unit)? = null

    var log: (String) -> Unit = {}

    fun onPacket(buf: ByteArray, len: Int) {
        val seg = DnsPackets.parseTcp(buf, len) ?: return
        if (++packets % 64 == 0) cleanup()

        val key = keyOf(seg.srcIp, seg.srcPort)
        val isSyn = seg.flags and DnsPackets.TCP_SYN != 0
        val isAck = seg.flags and DnsPackets.TCP_ACK != 0
        val isFin = seg.flags and DnsPackets.TCP_FIN != 0
        val isRst = seg.flags and DnsPackets.TCP_RST != 0

        if (isSyn && !isAck) {
            if (conns.size >= MAX_CONN) cleanup()
            val isn = (System.nanoTime() and 0x7FFFFFFF).toInt()
            val conn = Conn(seg.srcIp, seg.srcPort, seg.dstIp, seg.dstPort, seg.seq + 1, isn + 1)
            conns[key] = conn
            // 先乐观回 SYN-ACK，连接目标放到线程里做，避免握手卡住客户端
            reply(conn, DnsPackets.TCP_SYN or DnsPackets.TCP_ACK, isn, conn.clientNext)
            openSocket(conn, key)
            return
        }

        val conn = conns[key]
        if (conn == null) {
            // 不认识的连接直接 RST，客户端立刻失败而不是干等超时
            val rst = DnsPackets.TcpSegment(
                srcIp = seg.dstIp, dstIp = seg.srcIp,
                srcPort = seg.dstPort, dstPort = seg.srcPort,
                seq = seg.ack, ack = seg.seq + seg.payload.size + if (isSyn) 1 else 0,
                flags = DnsPackets.TCP_RST or DnsPackets.TCP_ACK,
                payload = ByteArray(0)
            )
            deliver(DnsPackets.wrapTcp(rst))
            return
        }
        conn.lastActive = System.currentTimeMillis()

        if (isRst) {
            close(conn, key)
            return
        }
        if (isFin) {
            reply(conn, DnsPackets.TCP_FIN or DnsPackets.TCP_ACK, conn.serverSeq, conn.clientNext)
            conn.serverSeq += 1
            close(conn, key)
            return
        }
        if (seg.payload.isEmpty()) return

        // 只吃按序段，乱序或重传直接丢（本地 tun 场景基本不出现）
        if (seg.seq != conn.clientNext) return
        conn.clientNext = seg.seq + seg.payload.size

        if (conn.targetPort == 443 && !conn.sniDone) {
            val r = SniParser.parse(seg.payload)
            if (r != null) {
                conn.sniDone = true
                onSni?.invoke(r.sni, r.ech)
                // 判定放在写入 socket 之前：命中就直接丢 ClientHello，目标侧看不到这次握手
                if (blockPolicy?.invoke(r.sni) == true) {
                    onBlock?.invoke(r.sni)
                    conn.closed = true
                    conns.remove(key)
                    reply(conn, DnsPackets.TCP_RST or DnsPackets.TCP_ACK,
                        conn.serverSeq, conn.clientNext)
                    runCatching { conn.socket?.close() }
                    return
                }
            } else if (!SniParser.looksLikeTls(seg.payload)) {
                conn.sniDone = true // 不是 TLS，别反复试
            }
        }

        synchronized(conn) {
            val s = conn.socket
            if (conn.ready && s != null) {
                writeToSocket(conn, s, seg.payload)
            } else if (conn.pending.size < MAX_PENDING) {
                // 目标还没连上：先攒着，别把首包丢了（TLS 的 ClientHello 往往就在这）
                conn.pending.addLast(seg.payload)
            }
        }
        reply(conn, DnsPackets.TCP_ACK, conn.serverSeq, conn.clientNext)
    }

    private fun openSocket(conn: Conn, key: String) {
        Thread({
            val s = Socket()
            val target = "${ip4(conn.targetIp)}:${conn.targetPort}"
            // protect 只是让 socket 绕开 VPN 走底层网络。本进程不在白名单时流量本就不进 tun，
            // 所以返回值 false 不该当成致命错误，否则会出现「一条都连不上」。
            val protected = try {
                protect(s)
            } catch (e: Exception) {
                false
            }
            if (!protected) onRelayError?.invoke(target, "protect=false")
            try {
                s.tcpNoDelay = true
                s.connect(
                    InetSocketAddress(InetAddress.getByAddress(conn.targetIp), conn.targetPort),
                    5000
                )
                onConnect?.invoke(true)
                conn.socket = s
                synchronized(conn) {
                    val out = s.getOutputStream()
                    while (conn.pending.isNotEmpty()) out.write(conn.pending.removeFirst())
                    out.flush()
                    conn.ready = true
                }
                pump(conn, s, key)
            } catch (e: Exception) {
                onConnect?.invoke(false)
                onRelayError?.invoke(target, "${e.javaClass.simpleName}: ${e.message}"
                    .take(72))
                log("relay $target 失败: ${e.message}")
                conn.closed = true
                conns.remove(key)
                reply(conn, DnsPackets.TCP_RST or DnsPackets.TCP_ACK, conn.serverSeq, conn.clientNext)
                runCatching { s.close() }
            }
        }, "relay-${conn.targetPort}").apply {
            isDaemon = true
            start()
        }
    }

    private fun ip4(b: ByteArray): String =
        (b[0].toInt() and 0xFF).toString() + "." + (b[1].toInt() and 0xFF) +
            "." + (b[2].toInt() and 0xFF) + "." + (b[3].toInt() and 0xFF)

    /** 目标 → 客户端，直到目标关闭或连接被收掉。 */
    private fun pump(conn: Conn, s: Socket, key: String) {
        val input = s.getInputStream()
        val buf = ByteArray(READ_CHUNK)
        while (!conn.closed) {
            val n = try {
                input.read(buf)
            } catch (_: Exception) {
                break
            }
            if (n < 0) break
            if (n == 0) continue
            conn.lastActive = System.currentTimeMillis()
            val payload = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
            synchronized(conn) {
                if (conn.closed) return
                reply(
                    conn,
                    DnsPackets.TCP_PSH or DnsPackets.TCP_ACK,
                    conn.serverSeq,
                    conn.clientNext,
                    payload
                )
                conn.serverSeq += n
            }
        }
        synchronized(conn) {
            if (!conn.closed) {
                reply(conn, DnsPackets.TCP_FIN or DnsPackets.TCP_ACK, conn.serverSeq, conn.clientNext)
                conn.serverSeq += 1
            }
        }
        close(conn, key)
    }

    private fun writeToSocket(conn: Conn, s: Socket, payload: ByteArray) {
        try {
            synchronized(conn) {
                val out = s.getOutputStream()
                out.write(payload)
                out.flush()
            }
        } catch (_: Exception) {
            conn.closed = true
            runCatching { s.close() }
        }
    }

    private fun reply(conn: Conn, flags: Int, seq: Int, ack: Int, payload: ByteArray = ByteArray(0)) {
        val seg = DnsPackets.TcpSegment(
            srcIp = conn.targetIp, dstIp = conn.clientIp,
            srcPort = conn.targetPort, dstPort = conn.clientPort,
            seq = seq, ack = ack, flags = flags, payload = payload
        )
        deliver(DnsPackets.wrapTcp(seg))
    }

    private fun close(conn: Conn, key: String) {
        conn.closed = true
        conns.remove(key)
        runCatching { conn.socket?.close() }
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        val dead = conns.entries.filter { now - it.value.lastActive > STALE_MS }
        dead.forEach {
            it.value.closed = true
            runCatching { it.value.socket?.close() }
            conns.remove(it.key)
        }
    }

    fun clear() {
        conns.values.forEach {
            it.closed = true
            runCatching { it.socket?.close() }
        }
        conns.clear()
    }

    fun activeCount(): Int = conns.size

    private fun keyOf(ip: ByteArray, port: Int): String = "${ip.contentToString()}:$port"
}
