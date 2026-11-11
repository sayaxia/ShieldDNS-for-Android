package com.shielddns.android

/**
 * 从 TLS ClientHello 里取出 SNI（server_name）。
 *
 * 只读明文握手头，不做解密：ClientHello 的第一个记录里，扩展区 type 0x0000
 * 那一项就是明文域名。启用 ECH 的目标会多一个 0xFE0D 扩展，此时外层 SNI
 * 通常只是占位值，需要调用方判断 [Result.ech]。
 *
 * 仅用于 TCP 上的 TLS；QUIC 的 ClientHello 封在 QUIC Initial 包里，结构不同。
 */
object SniParser {

    private const val REC_HANDSHAKE = 0x16
    private const val HS_CLIENT_HELLO = 0x01
    private const val EXT_SERVER_NAME = 0x0000
    private const val EXT_ECH = 0xFE0D
    private const val MAX_SNI = 255

    data class Result(val sni: String, val ech: Boolean)

    /** 尝试解析；不是 ClientHello、数据不完整或没有 SNI 时返回 null。 */
    fun parse(buf: ByteArray, offset: Int = 0, length: Int = buf.size): Result? {
        if (length < 9) return null
        val end = offset + length
        if ((buf[offset].toInt() and 0xFF) != REC_HANDSHAKE) return null
        val recLen = u16(buf, offset + 3, end) ?: return null
        val recEnd = minOf(end, offset + 5 + recLen)
        if ((buf[offset + 5].toInt() and 0xFF) != HS_CLIENT_HELLO) return null
        val hsLen = u24(buf, offset + 6, recEnd) ?: return null
        val hsEnd = minOf(recEnd, offset + 9 + hsLen)

        var p = offset + 9
        p += 34 // client_version(2) + random(32)
        if (p + 1 > hsEnd) return null
        p += 1 + (buf[p].toInt() and 0xFF) // session id
        if (p + 2 > hsEnd) return null
        p += 2 + (u16(buf, p, hsEnd) ?: return null) // cipher suites
        if (p + 1 > hsEnd) return null
        p += 1 + (buf[p].toInt() and 0xFF) // compression methods
        if (p + 2 > hsEnd) return null
        val extLen = u16(buf, p, hsEnd) ?: return null
        p += 2
        val extEnd = minOf(hsEnd, p + extLen)

        var sni: String? = null
        var ech = false
        while (p + 4 <= extEnd) {
            val type = u16(buf, p, extEnd) ?: return null
            val len = u16(buf, p + 2, extEnd) ?: return null
            val dataStart = p + 4
            val dataEnd = dataStart + len
            if (dataEnd > extEnd) return null
            if (type == EXT_SERVER_NAME) {
                sni = readServerName(buf, dataStart, dataEnd) ?: sni
            } else if (type == EXT_ECH) {
                ech = true
            }
            p = dataEnd
        }
        return sni?.let { Result(it, ech) }
    }

    /** 只需要判断这条连接是否值得缓存握手头，不必等整个 ClientHello 到齐。 */
    fun looksLikeTls(buf: ByteArray, offset: Int = 0, length: Int = buf.size): Boolean {
        if (length < 6) return false
        return (buf[offset].toInt() and 0xFF) == REC_HANDSHAKE &&
            (buf[offset + 1].toInt() and 0xFF) == 3
    }

    private fun readServerName(buf: ByteArray, start: Int, end: Int): String? {
        if (end - start < 2) return null
        val listLen = u16(buf, start, end) ?: return null
        val listEnd = minOf(end, start + 2 + listLen)
        var p = start + 2
        var found: String? = null
        while (p + 3 <= listEnd) {
            val nameType = buf[p].toInt() and 0xFF
            val nameLen = u16(buf, p + 1, listEnd) ?: return null
            val nameEnd = p + 3 + nameLen
            if (nameEnd > listEnd) return null
            if (nameType == 0 && nameLen in 1..MAX_SNI) {
                found = String(buf, p + 3, nameLen, Charsets.US_ASCII).lowercase()
            }
            p = nameEnd
        }
        return found
    }

    private fun u16(d: ByteArray, p: Int, end: Int): Int? {
        if (p + 2 > end) return null
        return ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
    }

    private fun u24(d: ByteArray, p: Int, end: Int): Int? {
        if (p + 3 > end) return null
        return ((d[p].toInt() and 0xFF) shl 16) or
            ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p + 2].toInt() and 0xFF)
    }
}
