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

/** 运行期状态：进程内共享，UI 每秒拉一次即可。 */
object Stats {

    /** 日志在内存里最多留这么多条；页面按需分页展示，不再一刀切只看 100 条。 */
    const val MAX_LOG = 2000

    val total = AtomicLong(0)
    val blocked = AtomicLong(0)
    val cached = AtomicLong(0)
    val failed = AtomicLong(0)

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

    val recent = ConcurrentLinkedDeque<LogEntry>()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun reset() {
        total.set(0)
        blocked.set(0)
        cached.set(0)
        failed.set(0)
        startedAt = 0L
        recent.clear()
        lastError = ""
    }

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
