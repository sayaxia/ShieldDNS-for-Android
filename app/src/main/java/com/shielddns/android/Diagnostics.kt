package com.shielddns.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断导出：把内存里的查询日志按时间窗导成 CSV，并直接拉起分享面板。
 *
 * 文件头用 # 注释写运行环境（版本、网络、上游、规则数），下面是纯数据行，
 * 用 awk / pandas 都能跳过注释直接统计。
 */
object Diagnostics {

    /** @param windowMillis 只导最近这么多毫秒；null 表示全部。返回生成的文件。 */
    fun export(ctx: Context, windowMillis: Long?): File {
        val now = System.currentTimeMillis()
        val all = Stats.snapshot()
        val entries = all.filter { windowMillis == null || now - it.time <= windowMillis }
            .sortedBy { it.time }

        val dir = File(ctx.cacheDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val file = File(dir, "shielddns-diag-$stamp.csv")
        val enhanceText = if (Prefs.enhanceEnabled) "on (${Prefs.enhancePackage})" else "off"
        file.writeText(build(now, entries, windowMillis, all.size, enhanceText))
        share(ctx, file)
        return file
    }

    internal fun build(
        now: Long,
        entries: List<LogEntry>,
        windowMillis: Long?,
        totalInMemory: Int,
        enhanceText: String = "off",
    ): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("# ShieldDNS for Android 诊断包\n")
        sb.append("# exported_at=").append(df.format(Date(now))).append('\n')
        sb.append("# version=${com.shielddns.android.BuildConfig.VERSION_NAME} " +
            "(${com.shielddns.android.BuildConfig.VERSION_CODE})\n")
        sb.append("# network=").append(Stats.networkText).append('\n')
        sb.append("# upstream=").append(Stats.upstreamText).append('\n')
        sb.append("# listen=").append(Stats.listenText).append('\n')
        sb.append("# rules=").append(Stats.rules).append('\n')
        sb.append("# sni_rules=").append(Stats.sniRules).append('\n')
        sb.append("# enhance=").append(enhanceText).append('\n')
        sb.append("# tcp: ok=").append(Stats.tcpOk.get())
            .append(" fail=").append(Stats.tcpFail.get())
            .append(" sni=").append(Stats.sniHits.size).append('\n')
        sb.append("# totals: query=${Stats.total.get()} blocked=${Stats.blocked.get()} " +
            "cached=${Stats.cached.get()} failed=${Stats.failed.get()}\n")
        sb.append("# uptime=").append(Stats.uptimeText()).append('\n')
        sb.append("# window=").append(
            when {
                windowMillis == null -> "all"
                windowMillis < 60_000 -> "last ${windowMillis / 1000}s"
                else -> "last ${windowMillis / 60_000} min"
            }
        ).append('\n')
        sb.append("# entries=").append(entries.size)
            .append(" (in memory=").append(totalInMemory).append(")\n")

        // 高频域名 Top 20，方便直接看出广告 SDK 与播放接口
        val top = entries.groupingBy { it.host }.eachCount()
            .entries.sortedByDescending { it.value }.take(20)
        if (top.isNotEmpty()) {
            sb.append("# top_domains:\n")
            top.forEach { (host, n) -> sb.append("#   ").append(n).append(' ').append(host).append('\n') }
        }

        // 增强模式采集到的 SNI：这是 DNS 层看不到的那部分，直接决定规则还能不能收紧
        val sni = Stats.sniSnapshot()
        if (sni.isNotEmpty()) {
            val blockedList = Stats.sniBlockedSnapshot()
            sb.append("# sni_blocked=").append(Stats.sniBlockedTotal)
                .append(" 次 / ").append(blockedList.size).append(" 个域名\n")
            blockedList.forEach { (h, n) ->
                sb.append("#   ").append(h).append(" ×").append(n).append('\n')
            }
        sb.append("# relay_errors=").append(Stats.relayErrors.size).append('\n')
        synchronized(Stats.relayErrors) {
            for (e in Stats.relayErrors) sb.append("#   ").append(e).append('\n')
        }
        sb.append("# sni_hits=").append(sni.size).append('\n')
            sni.take(200).forEach {
                sb.append("#   ").append(df.format(Date(it.time))).append(' ')
                    .append(it.host)
                    .append(if (it.ech) " [ech]" else "")
                    .append('\n')
            }
        }

        sb.append("time,host,result,protocol,cached\n")
        entries.forEach { e ->
            sb.append(df.format(Date(e.time))).append(',')
                .append(escape(e.host)).append(',')
                .append(if (e.blocked) "blocked" else "allowed").append(',')
                .append(if (e.tcp) "tcp" else "udp").append(',')
                .append(if (e.cached) "yes" else "no").append('\n')
        }
        return sb.toString()
    }

    private fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ShieldDNS 诊断 ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(send, "分享诊断文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
