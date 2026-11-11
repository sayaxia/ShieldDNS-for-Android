package com.shielddns.android.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.shielddns.android.LogEntry
import com.shielddns.android.MainActivity
import com.shielddns.android.Page
import com.shielddns.android.Stats
import com.shielddns.android.Ui

/**
 * 查询日志：
 * - 内存里最多留 2000 条，页面按 200 条一页往上加载，不再只显示最近 100 条；
 * - 可按「全部 / 拦截 / 放行」筛选，默认全部；
 * - 域名最多两行、不截断关键部分，点任意一条能看完整域名并一键复制。
 */
class LogPage(private val a: MainActivity) : Page {

    private enum class Filter(val label: String) { ALL("全部"), BLOCKED("拦截"), PASSED("放行") }

    private companion object {
        const val PAGE_STEP = 200
    }

    override val view: View
    private val box: LinearLayout
    private val countText: TextView
    private val chips = mutableListOf<TextView>()
    private var filter = Filter.ALL
    private var pageSize = PAGE_STEP
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

        val filterRow = Ui.row(a).apply { setPadding(0, Ui.dp(a, 10), 0, 0) }
        Filter.values().forEach { f ->
            val chip = Ui.text(a, f.label, 12f, Ui.MUTED, true).apply {
                val ph = Ui.dp(a, 12)
                val pv = Ui.dp(a, 6)
                setPadding(ph, pv, ph, pv)
                setOnClickListener {
                    filter = f
                    pageSize = PAGE_STEP
                    lastKey = ""
                    refresh()
                }
            }
            chips.add(chip)
            filterRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = Ui.dp(a, 8) }
            )
        }
        column.addView(filterRow)

        countText = Ui.text(a, "", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10)) }
        column.addView(countText)

        box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        column.addView(box)

        view = scroll
    }

    override fun refresh() {
        syncChips()
        val all = Stats.snapshot()
        val filtered = when (filter) {
            Filter.ALL -> all
            Filter.BLOCKED -> all.filter { it.blocked }
            Filter.PASSED -> all.filter { !it.blocked }
        }
        val shown = filtered.take(pageSize)

        countText.text = when {
            all.isEmpty() -> ""
            filtered.isEmpty() -> "共 ${all.size} 条，没有${filter.label}记录"
            else -> buildString {
                append("${filter.label} ${filtered.size} 条")
                if (filtered.size > shown.size) append(" · 已显示 ${shown.size}，可继续加载")
            }
        }

        val key = "${filter}:${pageSize}:${shown.size}:${shown.firstOrNull()?.time}:${Stats.running}"
        if (key == lastKey) return
        lastKey = key
        render(shown, filtered.size)
    }

    private fun syncChips() {
        Filter.values().forEachIndexed { i, f ->
            val on = f == filter
            chips[i].setTextColor(if (on) Ui.ACCENT else Ui.MUTED)
            chips[i].background = if (on) {
                Ui.rounded(Ui.adjustAlpha(Ui.ACCENT, 0.12f), 14, Ui.adjustAlpha(Ui.ACCENT, 0.5f), 1, a)
            } else {
                Ui.rounded(0x00000000, 14, 0xFF2B3D34.toInt(), 1, a)
            }
        }
    }

    private fun render(items: List<LogEntry>, totalFiltered: Int) {
        box.removeAllViews()

        if (items.isEmpty()) {
            val card = Ui.card(a)
            card.addView(
                Ui.text(
                    a,
                    "暂无记录",
                    12f, Ui.MUTED
                )
            )
            box.addView(card, Ui.lpMatch())
            return
        }

        val card = Ui.card(a)
        items.forEachIndexed { i, e ->
            val row = Ui.row(a).apply {
                setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8))
                setOnClickListener { Ui.showQueryDetail(a, e) }
            }
            row.addView(Ui.text(a, Stats.formatTime(e.time), 11f, Ui.MUTED).apply {
                setPadding(0, 0, Ui.dp(a, 10), 0)
            })
            row.addView(Ui.text(a, e.host, 12.5f, Ui.TEXT).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, Ui.lpWeight(1f))
            row.addView(
                Ui.text(a, if (e.blocked) "拦截" else "放行", 11.5f, if (e.blocked) Ui.DANGER else Ui.ACCENT, true),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            card.addView(row, Ui.lpMatch())
            if (i < items.size - 1) card.addView(Ui.divider(a), Ui.lpMatch())
        }
        box.addView(card, Ui.lpMatch())

        if (totalFiltered > items.size) {
            val more = Ui.button(a, "加载更多（还有 ${totalFiltered - items.size} 条）", primary = false)
            more.setOnClickListener {
                pageSize += PAGE_STEP
                lastKey = ""
                refresh()
            }
            box.addView(more, Ui.lpMatch().apply { topMargin = Ui.dp(a, 12) })
        }
    }

}
