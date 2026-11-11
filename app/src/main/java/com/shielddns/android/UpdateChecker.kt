package com.shielddns.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 走 GitHub Release 做检查更新与自升级：
 * 查 latest release 的 tag 与资产 → 对比本地 versionName → 下载 APK（顺带用发布页附的 sha256 校验）→ 交给系统安装器。
 *
 * 所有网络请求都是阻塞的，调用方必须放在后台线程。
 */
object UpdateChecker {

    const val REPO = "sayaxia/ShieldDNS-for-Android"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val UA = "ShieldDNS-Android-Updater"

    data class Latest(
        val tag: String,
        val version: String,
        val apkUrl: String,
        val apkSize: Long,
        val shaUrl: String?,
        val pageUrl: String,
        val notes: String,
    )

    /**
     * 取版本号里的数值段：`v0.1.2`、`0.1.2v3`（测试版后缀）、`0.1.2-beta` 都归一成 [0,1,2]。
     */
    fun versionParts(raw: String): List<Int> {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        val core = cleaned.substringBefore('v').substringBefore('V')
        return core.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
    }

    /** 逐段比较，缺位按 0 处理。返回 1 表示 a 更新，-1 表示 b 更新，0 相同。 */
    fun compareVersion(a: String, b: String): Int {
        val x = versionParts(a)
        val y = versionParts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val xv = x.getOrElse(i) { 0 }
            val yv = y.getOrElse(i) { 0 }
            if (xv != yv) return if (xv > yv) 1 else -1
        }
        return 0
    }

    fun isNewer(remote: String, current: String): Boolean = compareVersion(remote, current) > 0

    /** 读 GitHub 最新 Release。失败抛异常，message 直接展示给用户。 */
    fun fetchLatest(): Latest {
        val conn = open(API_LATEST)
        try {
            if (conn.responseCode !in 200..299) {
                error(if (conn.responseCode == 403) "访问过于频繁或网络受限（HTTP 403）" else "HTTP ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            if (tag.isEmpty()) error("发布页没有版本号")
            val assets = json.optJSONArray("assets") ?: error("发布页没有资产")
            var apkUrl: String? = null
            var shaUrl: String? = null
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                when {
                    name.endsWith(".apk") -> {
                        apkUrl = a.optString("browser_download_url")
                        size = a.optLong("size")
                    }

                    name.endsWith(".sha256") -> shaUrl = a.optString("browser_download_url")
                }
            }
            val url = apkUrl ?: error("发布页没有找到 APK")
            return Latest(
                tag = tag,
                version = tag.removePrefix("v"),
                apkUrl = url,
                apkSize = size,
                shaUrl = shaUrl,
                pageUrl = json.optString("html_url"),
                notes = json.optString("body")
            )
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 下载到 cacheDir/updates，边下边回调进度（0-100）。完成后按发布页的 sha256 校验；
     * 校验不通过就删掉文件并抛异常，不会把坏包交给安装器。
     */
    fun download(ctx: Context, latest: Latest, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, "ShieldDNS-${latest.version}.apk")
        if (dest.exists()) dest.delete()

        val conn = open(latest.apkUrl)
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: latest.apkSize
            var lastPct = -1
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (dest.length() < 1024) error("下载的文件不完整")
            if (latest.apkSize > 0 && dest.length() != latest.apkSize) {
                dest.delete()
                error("文件大小不符（${dest.length()} / ${latest.apkSize}）")
            }
            latest.shaUrl?.let { url ->
                val expected = fetchText(url).trim().substringBefore(' ')
                val actual = sha256(dest)
                if (expected.isNotEmpty() && !expected.equals(actual, ignoreCase = true)) {
                    dest.delete()
                    error("sha256 校验失败，已删除下载文件")
                }
            }
            return dest
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 打开系统安装器。需要 REQUEST_INSTALL_PACKAGES，首次会由系统引导用户允许「安装未知应用」。 */
    fun install(ctx: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UA)
        }

    private fun fetchText(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
