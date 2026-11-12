package com.shielddns.android.page

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shielddns.android.MainActivity
import com.shielddns.android.Page
import com.shielddns.android.Prefs
import com.shielddns.android.Stats
import com.shielddns.android.SubStore
import com.shielddns.android.Ui
import com.shielddns.android.BuiltinRules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 概览：hero（状态 + 开关 + 监听 + 生效规则 + 订阅时间 + 更新按钮）、运行指标、最近查询。
 * 所有子视图都用显式 LayoutParams，不靠 weight 撑高，避免父容器尺寸变化时把内容挤没。
 */
class OverviewPage(private val a: MainActivity) : Page {

    override val view: View

    private val statusText: TextView
    private val toggle: android.widget.Switch
    private val listenText: TextView
    private val upLine: TextView
    private val rulesText: TextView
    private val subText: TextView
    private val updateBtn: TextView
    private val warn: TextView
    private val metrics = mutableMapOf<String, TextView>()
    private val recentBox: LinearLayout
    private var syncing = false
    private var updating = false

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    init {
        val scroll = ScrollView(a)
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(a, 14)
            setPadding(p, p, p, Ui.dp(a, 24))
        }
        scroll.addView(
            column,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // ---------- hero ----------
        val hero = Ui.hero(a)
        val top = Ui.row(a)
        statusText = Ui.text(a, "保护已暂停", 17f, Ui.MUTED, true)
        top.addView(statusText, lpWrap())
        top.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        toggle = Ui.switchWidget(a, false)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            updating = true
            a.setProtection(checked)
            toggle.postDelayed({
                updating = false
                syncToggle()
            }, 2500)
        }
        top.addView(toggle, lpWrap())
        hero.addView(top, lpFull())

        warn = Ui.text(a, "私人 DNS 已开启，拦截失效", 12f, Ui.WARN, true).apply {
            visibility = View.GONE
        }
        hero.addView(warn, lpFullWith(topMargin = 8))

        listenText = Ui.text(a, "监听 10.111.0.1:53", 12f, Ui.MUTED)
        hero.addView(listenText, lpFullWith(topMargin = 10))

        upLine = Ui.text(a, "上游 DNS —", 12f, Ui.MUTED)
        hero.addView(upLine, lpFullWith(topMargin = 4))

        rulesText = Ui.text(a, "生效规则 —", 12f, Ui.MUTED)
        hero.addView(rulesText, lpFullWith(topMargin = 4))

        subText = Ui.text(a, "订阅更新 尚未更新", 12f, Ui.MUTED)
        hero.addView(subText, lpFullWith(topMargin = 4))

        updateBtn = Ui.button(a, "更新全部订阅", primary = false)
        updateBtn.setOnClickListener { updateAll() }
        hero.addView(updateBtn, lpFullWith(topMargin = 12))

        column.addView(hero, lpFullWith(bottomMargin = 14))

        // ---------- 运行指标 ----------
        column.addView(Ui.sectionTitle(a, "运行指标"), lpFullWith(bottomMargin = 8))

        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val labels = listOf(
            "查询总数" to "total",
            "已拦截" to "blocked",
            "拦截率" to "rate",
            "缓存命中" to "cached",
            "生效规则" to "rules",
            "运行时长" to "uptime"
        )
        labels.chunked(2).forEach { pair ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            pair.forEachIndexed { i, item ->
                val tile = Ui.card(a).apply {
                    val p = Ui.dp(a, 13)
                    setPadding(p, p, p, p)
                }
                tile.addView(Ui.text(a, item.first, 12f, Ui.MUTED), lpWrap())
                val value = Ui.text(a, "—", 19f, Ui.TEXT, true)
                tile.addView(value, lpWrapWith(topMargin = 6))
                metrics[item.second] = value
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.leftMargin = if (i == 0) 0 else Ui.dp(a, 5)
                lp.rightMargin = if (i == 0) Ui.dp(a, 5) else 0
                row.addView(tile, lp)
            }
            grid.addView(row, lpFullWith(bottomMargin = 10))
        }
        column.addView(grid, lpFull())

        // ---------- 最近查询 ----------
        column.addView(
            Ui.sectionTitle(a, "最近查询"),
            lpFullWith(topMargin = 4, bottomMargin = 8)
        )
        val recentCard = Ui.card(a)
        recentBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        recentCard.addView(recentBox, lpFull())
        column.addView(recentCard, lpFull())

        view = scroll
    }

    private fun lpWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun lpWrapWith(topMargin: Int) = lpWrap().apply { this.topMargin = Ui.dp(a, topMargin) }

    private fun lpFull() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun lpFullWith(topMargin: Int = 0, bottomMargin: Int = 0) = lpFull().apply {
        this.topMargin = Ui.dp(a, topMargin)
        this.bottomMargin = Ui.dp(a, bottomMargin)
    }

    fun syncToggle() {
        syncing = true
        toggle.isChecked = Stats.running
        syncing = false
    }

    /** 用户点了开关：界面先按用户意愿显示，别被每秒一次的 ticker 又拨回去。 */
    fun userToggleState(on: Boolean) {
        syncing = true
        toggle.isChecked = on
        syncing = false
        updating = on
    }

    override fun refresh() {
        val running = Stats.running
        val up = Stats.vpnUp
        statusText.text = when {
            running && up -> "防护运行中"
            running -> "正在建立本地 VPN…"
            else -> "保护已暂停"
        }
        statusText.setTextColor(if (running && up) Ui.ACCENT else if (running) Ui.WARN else Ui.MUTED)
        if (!updating) syncToggle()

        listenText.text = if (running) {
            "监听 ${Stats.listenText} · ${Stats.networkText}"
        } else {
            "监听 10.111.0.1:53（本地 VPN，未启用）"
        }

        // 把「现在到底用的哪个上游」摆出来，切网时能一眼看出有没有跟着变
        upLine.text = if (running) {
            val mode = if (Prefs.upstreamMode == "custom") "自定义" else "跟随系统"
            "上游 $mode · ${Stats.upstreamText}"
        } else {
            "上游 —"
        }
        // 上游 DNS 只在设置页显示，概览这里省空间
        rulesText.text = if (Stats.rules > 0) "生效规则 ${Stats.rules} 条" else "生效规则 —"

        val lastUpd = Prefs.lastUpdateAll
        // 内置策略也是一条订阅，计数里必须带上，否则概览和订阅页对不上
        val subCount = Prefs.loadSubs().size + 1
        subText.text = buildString {
            append("订阅更新 ")
            append(if (lastUpd > 0) fmt.format(Date(lastUpd)) else "尚未更新")
            if (subCount > 0) append(" · $subCount 条订阅")
        }

        // 建立失败的原因优先显示，其次才是「私人 DNS」提醒
        val err = Stats.lastError
        when {
            err.isNotEmpty() && !running -> {
                warn.text = err
                warn.visibility = View.VISIBLE
            }
            privateDnsOn() -> {
                warn.text = "私人 DNS 已开启，拦截失效"
                warn.visibility = View.VISIBLE
            }
            else -> warn.visibility = View.GONE
        }

        metrics["total"]?.text = Stats.total.get().toString()
        metrics["blocked"]?.text = Stats.blocked.get().toString()
        metrics["rate"]?.text = Stats.blockRateText()
        metrics["cached"]?.text = Stats.cached.get().toString()
        metrics["rules"]?.text = if (Stats.rules > 0) Stats.rules.toString() else "—"
        metrics["uptime"]?.text = Stats.uptimeText()

        refreshRecent()
    }

    /** 系统开了私人 DNS（DoT）时，查询不经系统 DNS，本应用会看不到流量。 */
    private fun privateDnsOn(): Boolean = try {
        val mode = android.provider.Settings.Global.getString(a.contentResolver, "private_dns_mode")
        mode != null && mode != "off"
    } catch (_: Exception) {
        false
    }

    private fun refreshRecent() {
        val items = Stats.snapshot().take(8)
        recentBox.removeAllViews()
        if (items.isEmpty()) {
            recentBox.addView(
                Ui.text(
                    a,
                    "暂无记录",
                    12f, Ui.MUTED
                ),
                lpWrapWith(topMargin = 2)
            )
            return
        }
        items.forEachIndexed { i, e ->
            val row = Ui.row(a).apply {
                val v = Ui.dp(a, 6)
                setPadding(0, v, 0, v)
                setOnClickListener { Ui.showQueryDetail(a, e) }
            }
            row.addView(
                Ui.text(a, e.host, 12.5f, Ui.TEXT).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                Ui.text(a, Stats.formatTime(e.time), 11f, Ui.MUTED).apply {
                    setPadding(0, 0, Ui.dp(a, 10), 0)
                },
                lpWrap()
            )
            row.addView(
                Ui.text(a, if (e.blocked) "拦截" else "放行", 11.5f, if (e.blocked) Ui.DANGER else Ui.ACCENT, true),
                lpWrap()
            )
            recentBox.addView(row, lpFull())
            if (i < items.size - 1) recentBox.addView(Ui.divider(a), lpFull())
        }
    }

    private fun updateAll() {
        if (updating) return
        val subs = Prefs.loadSubs()
        updating = true
        updateBtn.text = "更新中…"
        updateBtn.isEnabled = false
        // 内置策略同样是一条订阅，先更新它，再更新用户订阅，最后合并计数
        BuiltinRules.update(a) { _, _, bChanged, bErr ->
            val bFail = if (bErr == null) 0 else 1
            val bUpd = if (bChanged && bErr == null) 1 else 0
            val bSame = if (!bChanged && bErr == null) 1 else 0
            if (subs.isEmpty()) {
                finishAll(bUpd, bSame, bFail)
            } else {
                SubStore.refreshAll(a, subs, { _ -> }, { changed, unchanged, fail ->
                    Prefs.saveSubs(subs)
                    finishAll(changed + bUpd, unchanged + bSame, fail + bFail)
                })
            }
        }
    }

    private fun finishAll(changed: Int, unchanged: Int, fail: Int) {
        a.runOnUiThread {
            updating = false
            updateBtn.isEnabled = true
            updateBtn.text = when {
                changed > 0 && fail == 0 -> "已更新 $changed 条订阅"
                changed > 0 -> "更新 $changed 条，失败 $fail 条"
                unchanged > 0 && fail == 0 -> "$unchanged 条订阅已是最新"
                unchanged > 0 -> "无变化，$fail 条失败"
                else -> "更新失败，检查网络或链接"
            }
            a.reloadService()
            refresh()
            updateBtn.postDelayed({ updateBtn.text = "更新全部订阅" }, 3000)
        }
    }
}
