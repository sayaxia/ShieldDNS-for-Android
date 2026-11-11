package com.shielddns.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 本地 VPN 形式的 DNS 过滤：
 * 只把一个虚拟接口地址（10.111.0.1/32）作为系统 DNS 拉进 tun，其它流量完全不走 VPN。
 * 进 tun 的 UDP:53 查询交给 DnsPipeline 判规则——命中就自己造 NXDOMAIN / 0.0.0.0 应答，
 * 没命中就经受保护的 socket 转发到上游并把结果写回 tun。
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.shielddns.android.STOP"
        const val ACTION_RELOAD = "com.shielddns.android.RELOAD"

        private const val CHANNEL_ID = "shielddns"
        private const val NOTIF_ID = 1
        private const val VPN_ADDR = "10.111.0.2"
        private const val DNS_ADDR = "10.111.0.1"

        @Volatile
        private var alive = false

        /** 服务与界面同进程，这个标志比查 VPN 传输类型更准（不会把别的 VPN 应用当成自己）。 */
        fun isRunning(): Boolean = alive

        fun startIntent(ctx: Context): Intent = Intent(ctx, DnsVpnService::class.java)

        fun stop(ctx: Context) {
            Stats.running = false
            runCatching {
                ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
            }
            // 服务要是没在跑（或 startService 被拦），直接把它停掉，保证开关真的能关
            runCatching { ctx.stopService(Intent(ctx, DnsVpnService::class.java)) }
        }

        fun reload(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_RELOAD))
            }
        }
    }

    private val engine = RuleEngine()
    private lateinit var pipeline: DnsPipeline
    private lateinit var resolver: Resolver
    private var tun: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null
    private var reader: Thread? = null
    private val writeLock = Any()
    private var upstreams: List<InetAddress> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private var notifTick = 0

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        createChannel()
        resolver = Resolver { socket: DatagramSocket -> protect(socket) }
        pipeline = DnsPipeline(
            engine = engine,
            forward = { raw -> resolver.query(raw, upstreams, 4000, 53) },
            blockMode = { Prefs.blockMode },
            cacheEnabled = { Prefs.cacheEnabled },
            onQuery = { host, blocked -> Stats.onQuery(host, blocked) },
            onCached = { Stats.cached.incrementAndGet() },
            onForwardFailed = { Stats.failed.incrementAndGet() },
            deliver = { packet -> writeOut(packet) }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Stats.running = false
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                reloadRules()
                return START_STICKY
            }
        }
        if (tun == null) {
            beginForeground()
            startProxy()
        } else {
            reloadRules()
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "DNS 防护", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.enableVibration(false)
            ch.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_fg)
            .setContentTitle("ShieldDNS 防护中")
            .setContentText("已拦截 ${Stats.blocked.get()} / 查询 ${Stats.total.get()}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "暂停防护", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun beginForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun tickNotification() {
        notifTick++
        if (notifTick % 600 == 0) maybeAutoUpdate()
        if (notifTick % 5 == 0) {
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification())
            }
        }
    }

    private fun startProxy() {
        Stats.reset()
        reloadRules()

        upstreams = resolveUpstreams()
        Stats.upstreamText = upstreamText()

        val builder = Builder()
            .setSession("ShieldDNS")
            .setMtu(1500)
            .addAddress(VPN_ADDR, 32)
            .addDnsServer(DNS_ADDR)
            .addRoute(DNS_ADDR, 32)
            .setBlocking(true)

        val pfd = runCatching { builder.establish() }.getOrNull()
        if (pfd == null) {
            Stats.lastError = "无法建立本地 VPN（权限被拒绝）"
            Stats.running = false
            stopSelf()
            return
        }
        tun = pfd
        output = FileOutputStream(pfd.fileDescriptor)
        alive = true
        Stats.listenText = "$DNS_ADDR:53（本地 VPN）"
        Stats.running = true
        Stats.startedAt = System.currentTimeMillis()

        reader = Thread({ readLoop(pfd) }, "dns-reader").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }

        main.post(object : Runnable {
            override fun run() {
                if (Stats.running) {
                    tickNotification()
                     main.postDelayed(this, 1000)
                }
            }
        })

        maybeAutoUpdate()
    }

    private var autoBusy = false

    /** 到期规则：从没更新过、或距上次更新超过 24 小时。运行期间每 10 分钟查一次。 */
    private fun maybeAutoUpdate() {
        if (autoBusy || !Prefs.autoUpdate) return
        val subs = Prefs.loadSubs()
        if (subs.isEmpty()) return
        val last = Prefs.lastUpdateAll
        val due = last == 0L || System.currentTimeMillis() - last > 24L * 3600 * 1000
        if (!due) return
        autoBusy = true
        autoRefreshSubs()
    }

    private fun autoRefreshSubs() {
        val subs = Prefs.loadSubs()
        if (subs.isEmpty()) {
            autoBusy = false
            return
        }
        SubStore.refreshAll(this, subs, { _, _, _ -> }, { ok, _ ->
            autoBusy = false
            if (ok > 0) {
                Prefs.lastUpdateAll = System.currentTimeMillis()
                Prefs.saveSubs(subs)
                reloadRules()
            }
        })
    }

    private fun reloadRules() {
        val subs = Prefs.loadSubs()
        val sources = SubStore.loadAll(this, subs)
        engine.rebuild(sources, Prefs.customRules)
        Stats.rules = engine.ruleCount
        if (::pipeline.isInitialized) pipeline.clearCache()
    }

    private fun resolveUpstreams(): List<InetAddress> {
        if (Prefs.upstreamMode == "custom") {
            val list = Prefs.customUpstream.split(',', ' ', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                .filter { it is java.net.Inet4Address }
            if (list.isNotEmpty()) return list
        }
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork
        val props = net?.let { cm.getLinkProperties(it) }
        val dns = props?.dnsServers?.filter { it is java.net.Inet4Address } ?: emptyList()
        if (dns.isNotEmpty()) return dns
        return listOf(
            runCatching { InetAddress.getByName("223.5.5.5") }.getOrThrow(),
            runCatching { InetAddress.getByName("8.8.8.8") }.getOrThrow()
        )
    }

    private fun upstreamText(): String {
        if (upstreams.isEmpty()) return "未获取"
        val mode = if (Prefs.upstreamMode == "custom") "自定义" else "跟随系统"
        return "$mode · ${upstreams.size} 个（${upstreams.joinToString(", ") { it.hostAddress ?: "?" }}）"
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        while (Stats.running) {
            val n = try {
                input.read(buf)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) continue
            runCatching { pipeline.onPacket(buf, n) }
        }
        runCatching { input.close() }
    }

    private fun writeOut(packet: ByteArray) {
        val out = output ?: return
        synchronized(writeLock) {
            runCatching { out.write(packet) }
        }
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        alive = false
        Stats.running = false
        reader?.interrupt()
        runCatching { tun?.close() }
        if (::pipeline.isInitialized) pipeline.shutdown()
        tun = null
        output = null
        Stats.startedAt = 0L
        runCatching { main.removeCallbacksAndMessages(null) }
        super.onDestroy()
    }
}
