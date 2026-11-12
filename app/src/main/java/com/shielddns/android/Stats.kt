package com.shielddns.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

data class LogEntry(
    val time: Long,
    val host: String,
    val blocked: Boolean,
    val tcp: Boolean = false,
    val cached: Boolean = false,
)

/** 增强模式下从 TLS 握手取到的域名。 */
data class SniEntry(
    val time: Long,
    val host: String,
    val ech: Boolean = false,
)

/** 运行期状态：进程内共享，UI 每秒拉一次即可。 */
object Stats {

    /** 日志在内存里最多留这么多条；页面按需分页展示，不再一刀切只看 100 条。 */
    const val MAX_LOG = 2000

    val total = AtomicLong(0)
    val blocked = AtomicLong(0)
    val cached = AtomicLong(0)
    val failed = AtomicLong(0)

    /** 增强模式下的 TCP 建连结果，用来区分「连不上」和「连上但数据不对」。 */
    val tcpOk = AtomicLong(0)
    val tcpFail = AtomicLong(0)

    /** 转发失败的细分原因，诊断包里逐条输出。 */
    val relayErrors = ArrayDeque<String>()

    /** SNI 层命中的拦截记录：域名 → 次数。同一域名被反复重试只累加计数，不会刷爆队列。 */
    val sniBlocked = LinkedHashMap<String, Int>()

    /** 拦截总次数（含重复重试），域名去重后的条数看 sniBlocked.size。 */
    @Volatile
    var sniBlockedTotal: Int = 0
        private set

    @Volatile
    var running: Boolean = false

    /** tun 是否真的建立成功。UI 用它区分「开关开着但 VPN 没起来」。 */
    @Volatile
    var vpnUp: Boolean = false

    @Volatile
    var startedAt: Long = 0L

    @Volatile
    var listenText: String = "10.111.0.1:53"

    @Volatile
    var upstreamText: String = "未获取"

    /** 当前默认网络类型：WLAN / 蜂窝 / WLAN+蜂窝 */
    @Volatile
    var networkText: String = "—"

    @Volatile
    var lastError: String = ""

    /** 当前生效规则条数，由服务重建引擎后写入 */
    @Volatile
    var rules: Int = 0

    /** SNI 层生效规则条数（内置 sni 段比 DNS 层多一批 SDK 整域） */
    @Volatile
    var sniRules: Int = 0

    val recent = ConcurrentLinkedDeque<LogEntry>()

    /** 增强模式采集到的 SNI，最多 500 条，诊断导出时单独成段。 */
    val sniHits = ConcurrentLinkedDeque<SniEntry>()

    private const val MAX_SNI = 1500
    private const val MAX_BLOCK_HOSTS = 60

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun reset() {
        total.set(0)
        blocked.set(0)
        cached.set(0)
        failed.set(0)
        tcpOk.set(0)
        tcpFail.set(0)
        synchronized(relayErrors) { relayErrors.clear() }
        synchronized(sniBlocked) { sniBlocked.clear() }
        sniBlockedTotal = 0
        startedAt = 0L
        recent.clear()
        sniHits.clear()
        lastError = ""
    }

    fun onTcpConnect(ok: Boolean) {
        if (ok) tcpOk.incrementAndGet() else tcpFail.incrementAndGet()
    }

    fun onRelayError(target: String, reason: String) {
        synchronized(relayErrors) {
            if (relayErrors.size >= 40) relayErrors.removeFirst()
            relayErrors.addLast("$target $reason")
        }
    }

    fun onSniBlock(host: String) {
        synchronized(sniBlocked) {
            sniBlockedTotal++
            if (!sniBlocked.containsKey(host) && sniBlocked.size >= MAX_BLOCK_HOSTS) {
                val itr = sniBlocked.keys.iterator()
                if (itr.hasNext()) {
                    itr.next()
                    itr.remove()
                }
            }
            sniBlocked[host] = (sniBlocked[host] ?: 0) + 1
        }
    }

    fun sniBlockedSnapshot(): List<Pair<String, Int>> =
        synchronized(sniBlocked) { sniBlocked.entries.map { it.key to it.value } }

    /** SNI 命中域名按次数聚合，避免单个域名刷满队列后看不到别的。 */
    fun sniTop(limit: Int = 40): List<Pair<String, Int>> {
        val m = HashMap<String, Int>()
        for (e in sniHits) m[e.host] = (m[e.host] ?: 0) + 1
        return m.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    fun onSni(host: String, ech: Boolean = false) {
        sniHits.addFirst(SniEntry(System.currentTimeMillis(), host, ech))
        while (sniHits.size > MAX_SNI) sniHits.pollLast()
    }

    fun sniSnapshot(): List<SniEntry> = sniHits.toList()

    fun onQuery(host: String, isBlocked: Boolean, tcp: Boolean = false, fromCache: Boolean = false) {
        total.incrementAndGet()
        if (isBlocked) blocked.incrementAndGet()
        recent.addFirst(LogEntry(System.currentTimeMillis(), host, isBlocked, tcp, fromCache))
        while (recent.size > MAX_LOG) recent.pollLast()
    }

    fun snapshot(): List<LogEntry> = recent.toList()

    fun formatTime(t: Long): String = timeFmt.format(Date(t))

    fun uptimeText(): String {
        if (!running || startedAt == 0L) return "—"
        val sec = (System.currentTimeMillis() - startedAt) / 1000
        return when {
            sec < 60 -> "${sec} 秒"
            sec < 3600 -> "${sec / 60} 分钟"
            else -> "${sec / 3600} 小时 ${sec % 3600 / 60} 分"
        }
    }

    fun blockRateText(): String {
        val t = total.get()
        if (t <= 0) return "—"
        return String.format(Locale.US, "%.1f%%", blocked.get() * 100.0 / t)
    }
}
