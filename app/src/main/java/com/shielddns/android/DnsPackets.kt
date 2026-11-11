package com.shielddns.android

import java.net.InetAddress

/**
 * DNS 报文与 IPv4/UDP 封装。
 * 只处理标准查询（QDCOUNT=1、无 EDNS 特殊处理），这覆盖了手机系统解析器与绝大多数 App。
 */
object DnsPackets {

    data class Query(val id: Int, val name: String, val questionEnd: Int)

    /** 解析 DNS 查询的 QNAME；失败返回 null。 */
    fun parseQuery(data: ByteArray, offset: Int, length: Int): Query? {
        if (length < 12) return null
        val qd = ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
        if (qd != 1) return null
        val flags = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
        if ((flags and 0x8000) != 0) return null // 不是查询
        if (((flags shr 11) and 0xF) != 0) return null // 非标准查询

        val sb = StringBuilder()
        var pos = offset + 12
        val end = offset + length
        var jumps = 0
        var cur = pos
        while (true) {
            if (cur >= end) return null
            val len = data[cur].toInt() and 0xFF
            if (len == 0) {
                cur++
                break
            }
            if (len and 0xC0 == 0xC0) return null // 查询里不该有指针
            if (len > 63 || cur + 1 + len > end) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) sb.append((data[cur + 1 + i].toInt() and 0xFF).toChar())
            cur += 1 + len
            jumps++
            if (jumps > 128) return null
        }
        // 跳过 QTYPE + QCLASS
        val questionEnd = cur + 4
        if (questionEnd > end) return null
        val id = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        return Query(id, sb.toString().lowercase(), questionEnd)
    }

    /**
     * 生成应答。questionEnd 是相对 data 起点的偏移（含 QTYPE/QCLASS 之后）。
     * rcode: 0=NOERROR 3=NXDOMAIN 2=SERVFAIL
     */
    fun buildResponse(
        query: ByteArray,
        queryOffset: Int,
        questionEnd: Int,
        rcode: Int,
        zeroAddress: Boolean
    ): ByteArray {
        val questionLen = questionEnd - queryOffset
        val answerLen = if (zeroAddress && rcode == 0) 16 else 0
        // questionLen 已经把 12 字节头算进去了
        val out = ByteArray(questionLen + answerLen)
        // ID
        out[0] = query[queryOffset]
        out[1] = query[queryOffset + 1]
        // flags: QR=1, RD 沿用, RA=1, rcode
        out[2] = (0x80 or (query[queryOffset + 2].toInt() and 0x01)).toByte()
        out[3] = (0x80 or (rcode and 0x0F)).toByte()
        // QDCOUNT=1
        out[4] = 0
        out[5] = 1
        // ANCOUNT
        out[6] = 0
        out[7] = if (answerLen > 0) 1 else 0
        // NSCOUNT / ARCOUNT = 0
        System.arraycopy(query, queryOffset + 12, out, 12, questionLen - 12)
        if (answerLen > 0) {
            var p = questionLen
            out[p++] = 0xC0.toByte()
            out[p++] = 0x0C.toByte()
            out[p++] = 0
            out[p++] = 1 // A
            out[p++] = 0
            out[p++] = 1 // IN
            out[p++] = 0
            out[p++] = 0
            out[p++] = 0
            out[p++] = 60 // TTL 60s
            out[p++] = 0
            out[p++] = 4
            // 0.0.0.0
            out[p++] = 0
            out[p++] = 0
            out[p++] = 0
            out[p] = 0
        }
        return out
    }

    /** 从应答里取最小的 TTL（秒），拿不到给 300。 */
    fun minTtl(response: ByteArray): Int {
        val an = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        if (an <= 0) return 300
        var pos = 12
        val qd = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        repeat(qd) {
            pos = skipName(response, pos)
            pos += 4
        }
        var ttl = Int.MAX_VALUE
        repeat(an) {
            if (pos + 10 > response.size) return@repeat
            pos = skipName(response, pos)
            if (pos + 10 > response.size) return@repeat
            val t = ((response[pos + 4].toInt() and 0xFF) shl 24) or
                ((response[pos + 5].toInt() and 0xFF) shl 16) or
                ((response[pos + 6].toInt() and 0xFF) shl 8) or
                (response[pos + 7].toInt() and 0xFF)
            if (t in 1 until ttl) ttl = t
            val rdlen = ((response[pos + 8].toInt() and 0xFF) shl 8) or (response[pos + 9].toInt() and 0xFF)
            pos += 10 + rdlen
        }
        return if (ttl == Int.MAX_VALUE) 300 else ttl
    }

    private fun skipName(data: ByteArray, start: Int): Int {
        var pos = start
        while (pos < data.size) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if (len and 0xC0 == 0xC0) return pos + 2
            pos += 1 + len
        }
        return pos
    }

    /** 把 DNS 负载包成 IPv4 + UDP 报文（源/目的对调由调用方给）。 */
    fun wrapUdp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val p = ByteArray(total)
        p[0] = 0x45
        p[1] = 0
        p[2] = (total shr 8).toByte()
        p[3] = total.toByte()
        p[4] = 0
        p[5] = 0
        p[6] = 0x40 // DF
        p[7] = 0
        p[8] = 64 // TTL
        p[9] = 17 // UDP
        p[10] = 0
        p[11] = 0
        System.arraycopy(srcIp, 0, p, 12, 4)
        System.arraycopy(dstIp, 0, p, 16, 4)
        // IPv4 头校验和
        var sum = 0
        var i = 0
        while (i < 20) {
            sum += ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val ck = sum.inv() and 0xFFFF
        p[10] = (ck shr 8).toByte()
        p[11] = ck.toByte()
        // UDP 头（IPv4 下校验和 0 表示不校验，合法）
        p[20] = (srcPort shr 8).toByte()
        p[21] = srcPort.toByte()
        p[22] = (dstPort shr 8).toByte()
        p[23] = dstPort.toByte()
        p[24] = (udpLen shr 8).toByte()
        p[25] = udpLen.toByte()
        p[26] = 0
        p[27] = 0
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    fun ipToBytes(ip: InetAddress): ByteArray = ip.address

    /** 复用缓存应答时把旧的事务 ID 换成这次查询的 ID，否则客户端会丢弃它。 */
    fun adoptId(payload: ByteArray, query: ByteArray, queryOffset: Int): ByteArray {
        val out = payload.copyOf()
        if (out.size >= 2 && query.size > queryOffset + 1) {
            out[0] = query[queryOffset]
            out[1] = query[queryOffset + 1]
        }
        return out
    }
}
