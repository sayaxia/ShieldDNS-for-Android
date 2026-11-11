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
