package com.shielddns.android

import android.content.Context
import java.io.File

/**
 * 内置策略：本质是一条固定不可删除的订阅。
 *
 * 规则内容线上维护在仓库的 rules/default-rules.txt，App 自带一份同格式的打包副本
 * （assets/default-rules.txt），点「更新」去拉线上最新版；拉不到继续用本地副本，离线也能拦。
 *
 * 文件用注释行分段，两段规则进的层不同：
 *   # == [dns] ==  同时作用于 DNS 层与 SNI 层（红果自己的广告域名）
 *   # == [sni] ==  只作用于 SNI 增强拦截（第三方广告 SDK 整域，避免波及其它 App）
 *
 * 解析结果与订阅、自定义规则共用同一个 RuleEngine，不引入第二套语法。
 */
object BuiltinRules {

    const val ASSET = "default-rules.txt"
    const val SCOPE_DNS = "dns"
    const val SCOPE_SNI = "sni"

    /** 固定 id，用来定位下载缓存文件 filters/builtin.txt。 */
    const val ID = "builtin"

    /** 线上规则地址；jsDelivr 镜像比 raw.githubusercontent 在国内更稳。 */
    const val URL =
        "https://cdn.jsdelivr.net/gh/sayaxia/ShieldDNS-for-Android@main/rules/default-rules.txt"

    data class Item(val scope: String, val line: String)

    @Volatile
    private var cached: String? = null

    /** 作为一条订阅交给下载器用的描述，id 固定，缓存文件名也就固定。 */
    fun entry(): SubEntry =
        SubEntry(ID, Prefs.builtinName, URL, Prefs.builtinCount, Prefs.builtinUpdatedAt, true)

    fun cacheFile(ctx: Context): File = SubStore.fileFor(ctx, entry())

    /**
     * 优先读下载到本地的副本，没有再回退打包在 assets 里的同格式文件。
     * 本地副本必须通过签名校验才用：没有签名文件、签名不符（比如旧版本留下的未签名缓存，
     * 或被人改过盘上的文件）一律当作没有，直接回退 assets。
     */
    fun raw(ctx: Context): String {
        cached?.let { return it }
        val disk = runCatching { SubStore.loadVerified(ctx, entry()) }.getOrNull().orEmpty()
        val text = if (disk.isBlank()) assets(ctx) else disk
        cached = text
        return text
    }

    private fun assets(ctx: Context): String = runCatching {
        ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    fun items(ctx: Context): List<Item> = parse(raw(ctx))

    /** 把某一层的内置规则拼成文本，跳过被停用的行；空行返回空串。 */
    fun text(ctx: Context, scope: String, disabled: Set<String>): String =
        select(items(ctx), scope, disabled)

    fun select(items: List<Item>, scope: String, disabled: Set<String>): String {
        val sb = StringBuilder()
        items.forEach {
            if (it.scope == scope && it.line !in disabled) sb.append(it.line).append('\n')
        }
        return sb.toString()
    }

    /** 纯解析，方便单测直接喂字符串。 */
    fun parse(raw: String): List<Item> {
        var scope = SCOPE_DNS
        val out = ArrayList<Item>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("# ==") && line.contains('[')) {
                scope = if (line.contains("[$SCOPE_SNI]")) SCOPE_SNI else SCOPE_DNS
                return@forEach
            }
            if (line.startsWith("#") || line.startsWith("!")) return@forEach
            out.add(Item(scope, line))
        }
        return out
    }

    fun resetCache() {
        cached = null
    }

    /**
     * 从线上拉一次内置策略；失败保留本地副本。
     * 回调在后台线程，调用方自己切回 UI 线程。
     */
    fun update(ctx: Context, onResult: (ok: Boolean, count: Int, changed: Boolean, error: String?) -> Unit) {
        val r = runCatching { SubStore.download(ctx, entry()) }
        resetCache()
        if (r.isSuccess) {
            val d = r.getOrThrow()
            Prefs.builtinCount = d.count
            if (d.changed || Prefs.builtinUpdatedAt == 0L) {
                Prefs.builtinUpdatedAt = System.currentTimeMillis()
            }
            onResult(true, d.count, d.changed, null)
        } else {
            onResult(false, items(ctx).size, false, r.exceptionOrNull()?.message ?: "网络不可用")
        }
    }
}
