package com.shielddns.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置备份与恢复：把"用户亲手配出来的东西"打包成一个 JSON，换机、重装、出问题时能还原。
 *
 * 包含：订阅列表（名称/地址/开关/顺序）、自定义规则、内置策略的开关、
 *       以及上游 DNS、拦截响应、增强模式等设置项。
 * 不包含：查询日志（只在内存里）、已下载的规则文件（恢复后会自动重新拉一次）、
 *         内置策略的显示名与规则正文（显示名是本机偏好，正文由当前版本与签名决定，
 *         这样旧备份永远不可能把新版本的规则带回旧内容）。
 */
object Backup {

    const val SCHEMA = 1

    data class RestoreResult(val ok: Boolean, val subs: Int, val message: String)

    /** 当前配置序列化成 JSON 文本。 */
    fun buildJson(): String {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("app", BuildConfig.VERSION_NAME)
        root.put("exportedAt", System.currentTimeMillis())

        root.put("builtin", JSONObject().apply {
            // 只带开关。显示名是本机偏好，规则正文由当前版本与签名决定，都不进备份
            put("enabled", Prefs.builtinEnabled)
        })

        val subs = JSONArray()
        Prefs.loadSubs().forEach {
            subs.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("url", it.url)
                put("enabled", it.enabled)
                put("count", it.count)
                put("updatedAt", it.updatedAt)
            })
        }
        root.put("subs", subs)
        root.put("customRules", Prefs.customRules)

        root.put("settings", JSONObject().apply {
            put("upstreamMode", Prefs.upstreamMode)
            put("customUpstream", Prefs.customUpstream)
            put("blockMode", Prefs.blockMode)
            put("cacheEnabled", Prefs.cacheEnabled)
            put("autoUpdate", Prefs.autoUpdate)
            put("enhanceEnabled", Prefs.enhanceEnabled)
            put("enhancePackage", Prefs.enhancePackage)
            put("sniBlockEnabled", Prefs.sniBlockEnabled)
            put("blockLiveCdn", Prefs.blockLiveCdn)
        })
        return root.toString(2)
    }

    /** 写到 cacheDir/backup/ 并弹出分享，返回文件。 */
    fun export(ctx: Context): File {
        val dir = File(ctx.cacheDir, "backup").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "shielddns-backup-$stamp.json")
        file.writeText(buildJson())

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ShieldDNS 配置备份 ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(send, "保存或分享备份").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return file
    }

    /**
     * 从用户选中的文件恢复。整体替换当前配置（不是合并），解析或校验失败时不动现有配置。
     * 订阅的规则文件不会随备份带走，恢复完成会在后台重新拉一次。
     */
    fun restore(ctx: Context, uri: Uri): RestoreResult {
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        return applyJson(ctx, text.orEmpty())
    }

    /** 解析并应用一份备份文本，便于单测直接调用；resync=false 时不触发后台重新下载。 */
    fun applyJson(ctx: Context, text: String, resync: Boolean = true): RestoreResult {
        if (text.isBlank()) return RestoreResult(false, 0, "读不到文件内容")

        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return RestoreResult(false, 0, "不是合法的备份文件（JSON 解析失败）")

        val schema = root.optInt("schema", 0)
        if (schema != SCHEMA) {
            return RestoreResult(false, 0, "备份格式版本不支持（$schema，当前 $SCHEMA）")
        }

        val subs = mutableListOf<SubEntry>()
        var skipped = 0
        val arr = root.optJSONArray("subs")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val url = o.optString("url").trim()
                // 内置策略的 id 固定，不允许出现在用户订阅里
                if (id.isEmpty() || id == BuiltinRules.ID || !url.startsWith("http")) {
                    skipped++
                    continue
                }
                subs.add(
                    SubEntry(
                        id = id,
                        name = o.optString("name").ifBlank { "订阅" },
                        url = url,
                        count = o.optInt("count", 0),
                        updatedAt = o.optLong("updatedAt", 0L),
                        enabled = o.optBoolean("enabled", true)
                    )
                )
            }
        }
        if (subs.map { it.id }.toSet().size != subs.size) {
            return RestoreResult(false, 0, "备份里有重复的订阅 id，未应用")
        }

        // 内置策略只恢复开关：名字是本机偏好（旧备份里即便带了 name 也忽略），规则正文不归备份管
        val builtin = root.optJSONObject("builtin")
        val builtinOn = builtin?.optBoolean("enabled", Prefs.builtinEnabled) ?: Prefs.builtinEnabled
        if (builtin != null) Prefs.builtinEnabled = builtinOn
        Prefs.saveSubs(subs)
        Prefs.customRules = root.optString("customRules", "")

        root.optJSONObject("settings")?.let { s ->
            s.optString("upstreamMode").takeIf { it == "system" || it == "custom" }
                ?.let { Prefs.upstreamMode = it }
            s.optString("customUpstream")?.let { Prefs.customUpstream = it }
            s.optString("blockMode").takeIf { it == "nxdomain" || it == "zero" }
                ?.let { Prefs.blockMode = it }
            Prefs.cacheEnabled = s.optBoolean("cacheEnabled", Prefs.cacheEnabled)
            Prefs.autoUpdate = s.optBoolean("autoUpdate", Prefs.autoUpdate)
            Prefs.enhanceEnabled = s.optBoolean("enhanceEnabled", Prefs.enhanceEnabled)
            s.optString("enhancePackage").takeIf { it.isNotBlank() }
                ?.let { Prefs.enhancePackage = it }
            Prefs.sniBlockEnabled = s.optBoolean("sniBlockEnabled", Prefs.sniBlockEnabled)
            Prefs.blockLiveCdn = s.optBoolean("blockLiveCdn", Prefs.blockLiveCdn)
        }

        BuiltinRules.resetCache()
        DnsVpnService.reloadIfRunning(ctx)

        // 规则文件没在备份里，后台补拉一次，避免恢复后名字回来了但规则是空的。
        // 内置策略也要补一次：正文不随备份走，别让它停在旧缓存上。
        if (resync) {
            thread(name = "restore-resync") {
                if (subs.isNotEmpty()) {
                    SubStore.refreshAll(ctx, subs, { _ -> }, { _, _, _ ->
                        Prefs.saveSubs(subs)
                        DnsVpnService.reloadIfRunning(ctx)
                    })
                }
                BuiltinRules.update(ctx) { _, _, _, _ ->
                    DnsVpnService.reloadIfRunning(ctx)
                }
            }
        }

        val msg = buildString {
            append("已恢复：${subs.size} 条订阅")
            append("、${Prefs.customRules.lineSequence().count { it.isNotBlank() }} 行自定义规则")
            if (skipped > 0) append("（跳过 $skipped 条无效订阅）")
            if (builtin != null) {
                append("。内置策略").append(if (builtinOn) "已开启" else "已关闭").append("（来自备份）")
            }
            append("。规则正在后台重新下载。")
        }
        return RestoreResult(true, subs.size, msg)
    }
}
