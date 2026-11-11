package com.shielddns.android

import android.content.Context
import java.io.File
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import kotlin.concurrent.thread

/** 上游 UDP 解析（走受保护的 socket，不经过自己的 VPN）。 */
class Resolver(private val protect: (DatagramSocket) -> Unit) {

    fun query(
        payload: ByteArray,
        servers: List<InetAddress>,
        timeoutMs: Int = 4000,
        port: Int = 53
    ): ByteArray? {
        for (server in servers) {
            val socket = DatagramSocket()
            try {
                protect(socket)
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

    /** 同步下载单条订阅；返回规则条数，失败抛异常。 */
    fun download(ctx: Context, sub: SubEntry): Int {
        val conn = (URL(sub.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ShieldDNS-Android/" + com.shielddns.android.BuildConfig.VERSION_NAME)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val f = fileFor(ctx, sub)
            f.parentFile?.mkdirs()
            f.writeText(text)
            return countRules(text)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

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

    /** 后台刷新所有订阅，回调在子线程。 */
    fun refreshAll(
        ctx: Context,
        subs: List<SubEntry>,
        onEach: (SubEntry, Int?, String?) -> Unit,
        onDone: (Int, Int) -> Unit
    ) {
        thread(name = "sub-refresh") {
            var ok = 0
            var fail = 0
            subs.forEach { s ->
                val r = runCatching { download(ctx, s) }
                if (r.isSuccess) {
                    ok++
                    s.count = r.getOrThrow()
                    s.updatedAt = System.currentTimeMillis()
                    onEach(s, s.count, null)
                } else {
                    fail++
                    onEach(s, null, r.exceptionOrNull()?.message ?: "失败")
                }
            }
            if (ok > 0) Prefs.lastUpdateAll = System.currentTimeMillis()
            onDone(ok, fail)
        }
    }
}
