package com.shielddns.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

/**
 * 本地 VPN 形式的 DNS 过滤：
 * 把虚拟接口地址（10.111.0.1/32）注册成系统 DNS，只给这一个地址加路由，
 * 因此只有发往它的 DNS 流量（UDP:53 与 TCP:53）会进 tun，其它流量完全不经过 VPN。
 *
 * 进 tun 的查询交给 DnsPipeline：命中规则就本地造 NXDOMAIN / 0.0.0.0 应答，
 * 没命中就经 protect() 的 socket 转发上游并把结果写回；TCP 由 TcpDnsProxy 负责封装。
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

        /** 用户最后一次点「关闭」的时间，界面用它避免刚关又被告知「还在运行」。 */
        @Volatile
        var lastStopAt: Long = 0L

        /** 同进程单实例引用：关闭时直接调 teardownAll，不依赖 stopService 的时序。 */
        @Volatile
        private var instance: DnsVpnService? = null

        /** 服务与界面同进程，这个标志比查 VPN 传输类型更准（不会把别的 VPN 应用当成自己）。 */
        fun isRunning(): Boolean = alive

        fun startIntent(ctx: Context): Intent = Intent(ctx, DnsVpnService::class.java)

        fun stop(ctx: Context) {
            // 先把状态置为「已停止」，界面立刻有反馈。
            Stats.running = false
            Stats.vpnUp = false
            lastStopAt = System.currentTimeMillis()

            // 同进程直接调用实例收尾：绕开 stopService 的生命周期时序，
            // 关 tun、撤前台、取消通知一次做完，不会出现「开关关了但图标还在」。
            val inst = instance
            if (inst != null) {
                inst.main.post {
                    inst.teardownAll()
                    inst.stopSelf()
                }
                // 自检：1.2 秒后还没退（tun 仍活着），再补一刀直接停服务
                inst.main.postDelayed({
                    if (alive) {
                        runCatching {
                            ctx.stopService(Intent(ctx, DnsVpnService::class.java))
                        }
                    }
                }, 1200)
                return
            }
            // 实例还没建起来（或已销毁）时退回系统接口
            val stopped = runCatching {
                ctx.stopService(Intent(ctx, DnsVpnService::class.java))
            }.getOrDefault(false)
            if (!stopped) {
                runCatching {
                    ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
                }
            }
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
    private lateinit var tcpProxy: TcpDnsProxy
    private var tun: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null
    private var reader: Thread? = null
    private val writeLock = Any()

    @Volatile
    private var upstreams: List<InetAddress> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private var notifTick = 0
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        createChannel()
        resolver = Resolver(
            protectUdp = { socket: DatagramSocket -> protect(socket) },
            protectTcp = { socket: Socket -> protect(socket) }
        )
        tcpProxy = TcpDnsProxy(deliver = { packet -> writeOut(packet) })
        pipeline = DnsPipeline(
            engine = engine,
            forward = { raw, viaTcp ->
                if (viaTcp) resolver.queryTcp(raw, upstreams, 6000)
                else resolver.query(raw, upstreams, 4000, 53)
            },
            blockMode = { Prefs.blockMode },
            cacheEnabled = { Prefs.cacheEnabled },
            onQuery = { host, blocked -> Stats.onQuery(host, blocked) },
            onCached = { Stats.cached.incrementAndGet() },
            onForwardFailed = { Stats.failed.incrementAndGet() },
            deliver = { packet -> writeOut(packet) }
        )
        tcpProxy.onMessage = { msg, respond -> pipeline.onDnsMessage(msg, true, respond) }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardownAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (tun != null) reloadRules()
                return START_NOT_STICKY
            }
        }

        // intent == null：系统按 START_STICKY 重新拉起一个占位实例。
        // 不能借这个时机替用户把 VPN 重新建起来，否则「关掉后图标又回来」。
        if (intent == null) {
            if (tun == null) {
                Stats.running = false
                Stats.vpnUp = false
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (tun != null) {
            // 已经有一套连接：真正在跑就只重载规则；处在「刚 stop 还没销毁」的中间态则先清掉重建
            if (Stats.running && alive) {
                reloadRules()
                return START_NOT_STICKY
            }
            closeTun()
        }

        beginForeground()
        startProxy()
        return START_NOT_STICKY
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
        val text = buildString {
            append("${Stats.networkText} · 拦截 ${Stats.blocked.get()} / 查询 ${Stats.total.get()}")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_fg)
            .setContentTitle("ShieldDNS 防护中")
            .setContentText(text)
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

    /** 撤前台状态 + 取消通知：关开关、被系统撤销权限时都要走这里，否则图标会挂在通知栏。 */
    private fun removeNotification() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }

    private fun tickNotification() {
        notifTick++
        if (notifTick % 600 == 0) maybeAutoUpdate()
        if (notifTick % 5 == 0) {
            // 兜底：即使系统没给网络回调，也每 5 秒重算一次上游与网络类型（内部还有 500ms 节流）
            refreshUpstreams()
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification())
            }
        }
    }

    private fun startProxy() {
        Stats.reset()
        // 先置为「运行中」让界面立刻进入「正在建立 VPN…」中间态，tun 建成后再置 vpnUp
        Stats.running = true
        Stats.vpnUp = false
        reloadRules()

        upstreams = collectUpstreams()
        Stats.upstreamText = upstreamText()
        Stats.networkText = currentNetworkText()

        val pfd = establishTun()
        if (pfd == null) {
            Stats.lastError = "无法建立本地 VPN：系统没给授权，或上一次连接还在撤销。请等一两秒再开，仍失败就重启手机。"
            Stats.running = false
            Stats.vpnUp = false
            removeNotification()
            stopSelf()
            return
        }
        tun = pfd
        output = FileOutputStream(pfd.fileDescriptor)
        alive = true
        Stats.vpnUp = true
        Stats.listenText = "$DNS_ADDR:53（UDP + TCP，本地 VPN）"
        Stats.running = true
        Stats.lastError = ""
        Stats.startedAt = System.currentTimeMillis()

        watchNetworks()

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

    /** establish 在上一次连接刚撤销时偶尔会返回 null，短重试两次再判定失败。 */
    private fun establishTun(): ParcelFileDescriptor? {
        repeat(3) { i ->
            val builder = Builder()
                .setSession("ShieldDNS")
                .setMtu(1500)
                .addAddress(VPN_ADDR, 32)
                .addDnsServer(DNS_ADDR)
                .addRoute(DNS_ADDR, 32)
                .setBlocking(true)
            val pfd = runCatching { builder.establish() }.getOrNull()
            if (pfd != null) return pfd
            if (i < 2) runCatching { Thread.sleep(400) }
        }
        return null
    }

    /** 只关连接，不动通知；重复开启时先清掉上一次残留的 tun。 */
    private fun closeTun() {
        alive = false
        Stats.vpnUp = false
        runCatching { reader?.interrupt() }
        reader = null
        runCatching { tun?.close() }
        tun = null
        runCatching { output?.close() }
        output = null
        stopWatchingNetworks()
        if (::pipeline.isInitialized) pipeline.clearCache()
    }

    /** 完整收尾：置状态、关 tun、停 ticker、撤前台与通知。停止与撤销权限都走这里。 */
    private fun teardownAll() {
        Stats.running = false
        closeTun()
        if (::tcpProxy.isInitialized) tcpProxy.clear()
        Stats.startedAt = 0L
        runCatching { main.removeCallbacksAndMessages(null) }
        removeNotification()
    }

    // ---------- 网络变化 ----------

    /**
     * 监听 WLAN / 蜂窝的变化。
     *
     * 注意别用 registerDefaultNetworkCallback：VPN 一旦建立起，默认网络就是本应用的 VPN，
     * 那个回调只会跟自己的 VPN 走，底层切网根本收不到（v1/v2 只显示开服务那一刻网络的根因）。
     * 这里注册「所有具备 INTERNET 能力」的网络，回调里再统一过滤掉 VPN。
     */
    private fun watchNetworks() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshUpstreams()
            }

            override fun onLost(network: Network) {
                refreshUpstreams()
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                refreshUpstreams()
            }
        }
        runCatching { cm.registerNetworkCallback(req, cb) }.onSuccess { netCallback = cb }
    }

    private fun stopWatchingNetworks() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
    }

    @Volatile
    private var lastRefreshAt = 0L

    private fun refreshUpstreams() {
        if (!Stats.running) return
        // 回调可能被各类网络事件连着触发，500ms 内只算一次
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < 500) return
        lastRefreshAt = now

        val fresh = collectUpstreams()
        Stats.networkText = currentNetworkText()
        if (fresh == upstreams) {
            Stats.upstreamText = upstreamText()
            return
        }
        upstreams = fresh
        Stats.upstreamText = upstreamText()
        if (::pipeline.isInitialized) pipeline.clearCache()
    }

    /**
     * 收集上游：只看「底层网络」（排除我们自己的 VPN 网络），有线上网优先，WLAN 次之，蜂窝兜底。
     *
     * 关键点：VPN 建起来后 activeNetwork 往往就是我们的 VPN，它的 dnsServers 是 10.111.0.1 自己，
     * 拿它当上游会绕回自己形成回环；所以必须按 transport 过滤。
     */
    private fun collectUpstreams(): List<InetAddress> {
        if (Prefs.upstreamMode == "custom") {
            val list = Prefs.customUpstream.split(',', ' ', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                .filter { it is Inet4Address && !it.isLoopbackAddress && !isOwnDns(it) }
            if (list.isNotEmpty()) return list
        }

        val cm = getSystemService(ConnectivityManager::class.java)
        val out = LinkedHashSet<InetAddress>()
        underlayNetworks().forEach { n ->
            runCatching {
                cm.getLinkProperties(n)?.dnsServers?.forEach { d ->
                    if (d is Inet4Address && !d.isLoopbackAddress &&
                        !d.isAnyLocalAddress && !isOwnDns(d)
                    ) out.add(d)
                }
            }
        }
        if (out.isNotEmpty()) return out.toList()
        return listOf(
            runCatching { InetAddress.getByName("223.5.5.5") }.getOrThrow(),
            runCatching { InetAddress.getByName("8.8.8.8") }.getOrThrow()
        )
    }

    /** 10.111.0.0/30 是本应用自己的 tun 地址段，永远不能当上游。 */
    private fun isOwnDns(addr: InetAddress): Boolean {
        val b = addr.address
        return b.size == 4 && b[0] == 10.toByte() && b[1] == 111.toByte() &&
            b[2] == 0.toByte() && (b[3] == 1.toByte() || b[3] == 2.toByte())
    }

    /** 底层网络（排除 VPN），按「有线上网 > WLAN > 蜂窝 > 其它」排序。 */
    private fun underlayNetworks(): List<Network> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val nets = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        return nets.filter { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@filter false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.sortedBy { n ->
            val caps = cm.getNetworkCapabilities(n)
            when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 0
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 1
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 2
                else -> 3
            }
        }
    }

    private fun upstreamText(): String {
        if (upstreams.isEmpty()) return "未获取"
        val mode = if (Prefs.upstreamMode == "custom") "自定义" else "跟随系统"
        return "$mode · ${upstreams.size} 个（${upstreams.joinToString(", ") { it.hostAddress ?: "?" }}）"
    }

    /** 当前参与上游的底层网络类型（已排除自己的 VPN 网络）。 */
    private fun currentNetworkText(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val kinds = underlayNetworks().mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
                else -> null
            }
        }
        return when {
            kinds.isEmpty() -> "无网络"
            kinds.size == 1 -> kinds.first()
            else -> kinds.joinToString("+")
        }
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
        SubStore.refreshAll(this, subs, { _ -> }, { changed, _, _ ->
            autoBusy = false
            Prefs.saveSubs(subs)
            if (changed > 0) reloadRules()
        })
    }

    private fun reloadRules() {
        val subs = Prefs.loadSubs()
        val sources = SubStore.loadAll(this, subs)
        engine.rebuild(sources, Prefs.customRules)
        Stats.rules = engine.ruleCount
        if (::pipeline.isInitialized) pipeline.clearCache()
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
            // 6 = TCP，交给 TcpDnsProxy；其余按 UDP 处理（内部会自己过滤非 53 端口）
            if ((buf[9].toInt() and 0xFF) == 6) {
                runCatching { tcpProxy.onPacket(buf, n) }
            } else {
                runCatching { pipeline.onPacket(buf, n) }
            }
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
        teardownAll()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardownAll()
        if (instance === this) instance = null
        if (::pipeline.isInitialized) pipeline.shutdown()
        super.onDestroy()
    }
}
