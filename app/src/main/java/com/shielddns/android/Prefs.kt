package com.shielddns.android

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class SubEntry(
    val id: String,
    val name: String,
    val url: String,
    var count: Int = 0,
    var updatedAt: Long = 0L
)

/**
 * 所有配置都落在 SharedPreferences 里。启动永远「待命」：
 * 保护开关不持久化，进程起来就是暂停态，跟桌面版语义一致。
 */
object Prefs {

    private const val NAME = "shielddns"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    var upstreamMode: String
        get() = sp.getString("upstreamMode", "system") ?: "system"
        set(v) = sp.edit().putString("upstreamMode", v).apply()

    var customUpstream: String
        get() = sp.getString("customUpstream", "") ?: ""
        set(v) = sp.edit().putString("customUpstream", v).apply()

    /** nxdomain | zero */
    var blockMode: String
        get() = sp.getString("blockMode", "nxdomain") ?: "nxdomain"
        set(v) = sp.edit().putString("blockMode", v).apply()

    var cacheEnabled: Boolean
        get() = sp.getBoolean("cacheEnabled", true)
        set(v) = sp.edit().putBoolean("cacheEnabled", v).apply()

    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = sp.edit().putBoolean("autoUpdate", v).apply()

    var customRules: String
        get() = sp.getString("customRules", "") ?: ""
        set(v) = sp.edit().putString("customRules", v).apply()

    var lastUpdateAll: Long
        get() = sp.getLong("lastUpdateAll", 0L)
        set(v) = sp.edit().putLong("lastUpdateAll", v).apply()

    /** 增强模式：接管指定应用的 TCP 流量，按 SNI 拦截。默认关闭。 */
    var enhanceEnabled: Boolean
        get() = sp.getBoolean("enhanceEnabled", false)
        set(v) = sp.edit().putBoolean("enhanceEnabled", v).apply()

    /** 增强模式作用的应用包名，只挂这一个，其它应用完全不进 tun。 */
    /** 命中 SNI 广告规则时是否直接阻断连接。 */
    var sniBlockEnabled: Boolean
        get() = sp.getBoolean("sniBlockEnabled", true)
        set(v) = sp.edit().putBoolean("sniBlockEnabled", v).apply()

    /** 连带拦抖音直播 CDN（会断红果直播功能），默认关 */
    var blockLiveCdn: Boolean
        get() = sp.getBoolean("blockLiveCdn", false)
        set(v) = sp.edit().putBoolean("blockLiveCdn", v).apply()

    var enhancePackage: String
        get() = sp.getString("enhancePackage", "com.phoenix.read") ?: "com.phoenix.read"
        set(v) = sp.edit().putString("enhancePackage", v.trim()).apply()

    fun loadSubs(): MutableList<SubEntry> {
        val raw = sp.getString("subs", "") ?: ""
        val out = mutableListOf<SubEntry>()
        if (raw.isBlank()) return out
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    SubEntry(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        url = o.optString("url"),
                        count = o.optInt("count", 0),
                        updatedAt = o.optLong("updatedAt", 0L)
                    )
                )
            }
        }
        return out
    }

    fun saveSubs(list: List<SubEntry>) {
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("name", it.name)
            o.put("url", it.url)
            o.put("count", it.count)
            o.put("updatedAt", it.updatedAt)
            arr.put(o)
        }
        sp.edit().putString("subs", arr.toString()).apply()
    }
}
