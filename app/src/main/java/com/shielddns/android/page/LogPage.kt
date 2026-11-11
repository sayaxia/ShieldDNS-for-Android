package com.shielddns.android.page

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shielddns.android.MainActivity
import com.shielddns.android.Page
import com.shielddns.android.Stats
import com.shielddns.android.Ui

/** 查询日志：最近 200 条，域名 + 时间 + 拦截 / 放行。 */
class LogPage(private val a: MainActivity) : Page {

    override val view: View
    private val box: LinearLayout
    private val countText: TextView
    private var lastKey = ""

    init {
        val scroll = ScrollView(a)
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(a, 14)
            setPadding(p, p, p, Ui.dp(a, 24))
        }
        scroll.addView(column)

        val head = Ui.row(a)
        head.addView(Ui.sectionTitle(a, "查询日志"), Ui.lpWeight(1f))
        val clear = Ui.text(a, "清空", 12f, Ui.MUTED)
        clear.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), 0, Ui.dp(a, 4))
        clear.setOnClickListener {
            Stats.recent.clear()
            lastKey = ""
            refresh()
        }
        head.addView(clear)
        column.addView(head)

        countText = Ui.text(a, "", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 10)) }
        column.addView(countText)

        box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        column.addView(box)

        view = scroll
    }

    override fun refresh() {
        val items = Stats.snapshot().take(100)
        val key = "${items.size}:${items.firstOrNull()?.time}"
        countText.text = if (items.isEmpty()) "" else "显示最近 ${items.size} 条 / 共 ${Stats.total.get()} 次查询"
        if (key == lastKey) return
        lastKey = key
        box.removeAllViews()

        if (items.isEmpty()) {
            val card = Ui.card(a)
            card.addView(Ui.text(a, if (Stats.running) "还没有查询" else "开启防护后这里会记录每一次 DNS 查询", 12f, Ui.MUTED))
            box.addView(card, Ui.lpMatch())
            return
        }
        val card = Ui.card(a)
        items.forEachIndexed { i, e ->
            val row = Ui.row(a).apply { setPadding(0, Ui.dp(a, 7), 0, Ui.dp(a, 7)) }
            row.addView(Ui.text(a, Stats.formatTime(e.time), 11f, Ui.MUTED).apply {
                setPadding(0, 0, Ui.dp(a, 10), 0)
            })
            row.addView(Ui.text(a, e.host, 12.5f, Ui.TEXT).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }, Ui.lpWeight(1f))
            row.addView(Ui.text(a, if (e.blocked) "拦截" else "放行", 11.5f, if (e.blocked) Ui.DANGER else Ui.ACCENT, true))
            card.addView(row)
            if (i < items.size - 1) card.addView(Ui.divider(a))
        }
        box.addView(card, Ui.lpMatch())
    }
}
