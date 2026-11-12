package com.shielddns.android

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/** 一次订阅下载的结果：条数、更新前的条数、内容是否真的变了。 */
data class SubDownload(val count: Int, val before: Int, val changed: Boolean)

/** 单条订阅的刷新结果，给 UI 用来区分「有变更 / 无变化 / 失败」。 */
data class SubUpdate(
    val sub: SubEntry,
    val ok: Boolean,
    val before: Int,
    val after: Int,
    val changed: Boolean,
    val error: String? = null
)

/** 上游解析（UDP / TCP 都走受保护的 socket，不经过自己的 VPN）。 */
class Resolver(
    private val protectUdp: (DatagramSocket) -> Unit,
    private val protectTcp: (Socket) -> Unit = {}
) {

    fun query(
        payload: ByteArray,
        servers: List<InetAddress>,
        timeoutMs: Int = 4000,
        port: Int = 53
    ): ByteArray? {
        for (server in servers) {
            val socket = DatagramSocket()
            try {
                protectUdp(socket)
                socket.soTimeout = timeoutMs
                socket.send(
                    java.net.DatagramPacket(payload, payload.size, InetSocketAddress(server, port))
                )
                val buf = ByteArray(1500)
                val resp = java.net.DatagramPacket(buf, buf.size)
                socket.receive(resp)
                if (resp.length >= 12) return buf.copyOf(resp.length)
            } catch (_: Exception) {
            } finally {
                runCatching { socket.close() }
            }
        }
        return null
    }

    /** DNS over TCP（RFC 1035 的 2 字节长度前缀）。客户端回退到 TCP 时用。 */
    fun queryTcp(
        payload: ByteArray,
        servers: List<InetAddress>,
        timeoutMs: Int = 6000,
        port: Int = 53
    ): ByteArray? {
        for (server in servers) {
            val socket = Socket()
            try {
                protectTcp(socket)
                socket.connect(InetSocketAddress(server, port), timeoutMs)
                socket.soTimeout = timeoutMs
                val out = socket.getOutputStream()
                val framed = ByteArray(2 + payload.size)
                framed[0] = (payload.size shr 8).toByte()
                framed[1] = payload.size.toByte()
                System.arraycopy(payload, 0, framed, 2, payload.size)
                out.write(framed)
                out.flush()

                val input = socket.getInputStream()
                val hdr = ByteArray(2)
                if (!readFully(input, hdr)) continue
                val len = ((hdr[0].toInt() and 0xFF) shl 8) or (hdr[1].toInt() and 0xFF)
                if (len < 12) continue
                val resp = ByteArray(len)
                if (!readFully(input, resp)) continue
                return resp
            } catch (_: Exception) {
            } finally {
                runCatching { socket.close() }
            }
        }
        return null
    }

    private fun readFully(input: InputStream, target: ByteArray): Boolean {
        var off = 0
        while (off < target.size) {
            val n = input.read(target, off, target.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }
}

/** 订阅：下载、落盘、统计条数。文件放在 filesDir/filters/<id>.txt。 */
object SubStore {

    fun fileFor(ctx: Context, sub: SubEntry): File =
        File(File(ctx.filesDir, "filters"), "${sub.id}.txt")

    fun loadAll(ctx: Context, subs: List<SubEntry>): List<String> {
        val out = mutableListOf<String>()
        subs.forEach { s ->
            val f = fileFor(ctx, s)
            if (f.exists()) runCatching { out.add(f.readText()) }
        }
        return out
    }

    /** 同步下载单条订阅；返回条数、更新前条数、内容是否变化，失败抛异常。 */
    fun download(ctx: Context, sub: SubEntry): SubDownload {
        val conn = (URL(sub.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ShieldDNS-Android/" + BuildConfig.VERSION_NAME)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val f = fileFor(ctx, sub)
            val old = if (f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
            val before = if (old.isEmpty()) 0 else countRules(old)
            val newCount = countRules(text)
            // 内容是否要落盘：看规则条目指纹（注释、空行、顺序差异不算）
            val contentChanged = ruleSignature(old) != ruleSignature(text)
            if (contentChanged) {
                f.parentFile?.mkdirs()
                f.writeText(text)
            }
            // 给用户看的「有没有变化」：只看规则条数增减。
            // 云盘/CDN 直链每次返回的字节可能带随机 token，按字节判会次次误报。
            return SubDownload(newCount, before, newCount != before)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 规则指纹：去掉注释、空行，按行去空白后排序拼起来。 */
    fun ruleSignature(text: String): String =
        text.lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.isNotEmpty() && !it.startsWith("!") && !it.startsWith("#") }
            .sorted()
            .joinToString("\n")

    fun countRules(text: String): Int {
        var n = 0
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("#")) return@forEach
            if (line.contains("##")) return@forEach
            n++
        }
        return n
    }

    /**
     * 后台刷新所有订阅，回调在子线程。
     * onDone(有变更的条数, 无变化的条数, 失败的条数)。
     */
    fun refreshAll(
        ctx: Context,
        subs: List<SubEntry>,
        onEach: (SubUpdate) -> Unit,
        onDone: (Int, Int, Int) -> Unit
    ) {
        thread(name = "sub-refresh") {
            var changed = 0
            var unchanged = 0
            var fail = 0
            subs.forEach { s ->
                val r = runCatching { download(ctx, s) }
                if (r.isSuccess) {
                    val d = r.getOrThrow()
                    s.count = d.count
                    if (d.changed) {
                        s.updatedAt = System.currentTimeMillis()
                        changed++
                    } else {
                        unchanged++
                    }
                    onEach(SubUpdate(s, true, d.before, d.count, d.changed, null))
                } else {
                    fail++
                    onEach(
                        SubUpdate(
                            s, false, s.count, s.count, false,
                            r.exceptionOrNull()?.message ?: "失败"
                        )
                    )
                }
            }
            // 只要成功拉到过就算「检查过一次」，否则自动更新会每 10 分钟重复拉
            if (changed + unchanged > 0) Prefs.lastUpdateAll = System.currentTimeMillis()
            onDone(changed, unchanged, fail)
        }
    }
}
