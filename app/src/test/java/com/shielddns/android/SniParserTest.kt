package com.shielddns.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SniParserTest {

    /** 拼一个最小可用的 TLS 1.2 ClientHello。 */
    private fun clientHello(sni: String?, ech: Boolean = false): ByteArray {
        val exts = ByteArrayOutputStream()
        if (sni != null) {
            val name = sni.toByteArray(Charsets.US_ASCII)
            val entry = ByteArrayOutputStream().apply {
                write(0x00) // name_type = host_name
                write((name.size shr 8) and 0xFF); write(name.size and 0xFF)
                write(name)
            }.toByteArray()
            val list = ByteArrayOutputStream().apply {
                write((entry.size shr 8) and 0xFF); write(entry.size and 0xFF)
                write(entry)
            }.toByteArray()
            exts.write(0x00); exts.write(0x00)
            exts.write((list.size shr 8) and 0xFF); exts.write(list.size and 0xFF)
            exts.write(list)
        }
        if (ech) {
            exts.write(0xFE); exts.write(0x0D)
            exts.write(0x00); exts.write(0x04)
            exts.write(ByteArray(4))
        }
        val ext = exts.toByteArray()

        val body = ByteArrayOutputStream().apply {
            write(0x03); write(0x03)              // client_version
            write(ByteArray(32) { 0x11 })         // random
            write(0x00)                            // session id length
            write(0x00); write(0x02); write(0x13); write(0x01) // cipher suites
            write(0x01); write(0x00)               // compression methods
            if (ext.isNotEmpty()) {
                write((ext.size shr 8) and 0xFF); write(ext.size and 0xFF)
                write(ext)
            }
        }.toByteArray()

        val hs = ByteArrayOutputStream().apply {
            write(0x01)
            write((body.size shr 16) and 0xFF)
            write((body.size shr 8) and 0xFF)
            write(body.size and 0xFF)
            write(body)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(0x16); write(0x03); write(0x01)
            write((hs.size shr 8) and 0xFF); write(hs.size and 0xFF)
            write(hs)
        }.toByteArray()
    }

    @Test
    fun extractsPlainSni() {
        val r = SniParser.parse(clientHello("ad.zijieapi.com"))
        assertEquals("ad.zijieapi.com", r?.sni)
        assertFalse(r!!.ech)
    }

    @Test
    fun lowercasesHostName() {
        assertEquals("api5-normal.fqnovel.com", SniParser.parse(clientHello("API5-Normal.FqNovel.com"))?.sni)
    }

    @Test
    fun detectsEchExtension() {
        val r = SniParser.parse(clientHello("ad.zijieapi.com", ech = true))
        assertEquals("ad.zijieapi.com", r?.sni)
        assertTrue(r!!.ech)
    }

    @Test
    fun returnsNullWithoutSniExtension() {
        assertNull(SniParser.parse(clientHello(null)))
    }

    @Test
    fun returnsNullForHttpPayload() {
        val http = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        assertNull(SniParser.parse(http))
        assertFalse(SniParser.looksLikeTls(http))
    }

    @Test
    fun toleratesTruncatedHandshake() {
        val full = clientHello("ad.zijieapi.com")
        // 只给前半段：截断在扩展区之前，必须安全返回 null 而不是抛异常
        assertNull(SniParser.parse(full.copyOfRange(0, 30)))
        // 头部完整但扩展被削掉一半
        assertNull(SniParser.parse(full.copyOfRange(0, full.size - 12)))
    }

    @Test
    fun ignoresTrailingBytesAfterRecord() {
        val hello = clientHello("v6-reading-ad.qznovelvod.com")
        // 真实 TCP 段里常把后续数据一起带过来，尾部多余字节不该影响解析
        val padded = hello + ByteArray(64) { 0x2A }
        assertEquals("v6-reading-ad.qznovelvod.com", SniParser.parse(padded)?.sni)
    }

    @Test
    fun offsetsIntoMiddleOfBuffer() {
        val hello = clientHello("dig.bdurl.net")
        val wrapped = ByteArray(16) { 0x00 } + hello
        assertEquals("dig.bdurl.net", SniParser.parse(wrapped, 16, hello.size)?.sni)
    }

    /** OpenSSL 生成的真实 ClientHello（517 字节，含 ALPN、supported_groups、padding 等扩展）。 */
    private val realClientHelloHex =
        "1603010200010001fc0303fcb6bb29c4008f188dfaf445e18ea47da6d86101a89bc6d" +
            "bd7aa8d1e1d987f3720ed9fdf4537a3ad412914172002eb469981b93324c34548063c" +
            "6ba270396046450024130213031301c02cc030c02bc02fcca9cca8c024c028c023c0" +
            "27009f009e006b006700ff0100018f00000014001200000f61642e7a696a696561706" +
            "92e636f6d000b000403000102000a00160014001d0017001e00190018010001010102" +
            "01030104002300000016000000170000000d002a00280403050306030807080808090" +
            "80a080b080408050806040105010601030303010302040205020602002b0005040304" +
            "0303002d00020101003300260024001d002062f90675e40b41623e98cf1f696fe697a" +
            "4cbfa2f213e6e8793540b75f7fd821d001500de000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000"

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }

    @Test
    fun parsesRealOpenSslClientHello() {
        val bytes = hexToBytes(realClientHelloHex)
        val r = SniParser.parse(bytes)
        assertEquals("ad.zijieapi.com", r?.sni)
        assertFalse(r!!.ech)
    }

    @Test
    fun looksLikeTlsAcceptsTls12And13() {
        assertTrue(SniParser.looksLikeTls(clientHello("a.example.com")))
        val tls13 = clientHello("b.example.com").also {
            it[1] = 0x03; it[2] = 0x03
        }
        assertTrue(SniParser.looksLikeTls(tls13))
    }
}
