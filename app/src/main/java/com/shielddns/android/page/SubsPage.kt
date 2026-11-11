package com.shielddns.android.page

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shielddns.android.MainActivity
import com.shielddns.android.Page
import com.shielddns.android.Prefs
import com.shielddns.android.SubEntry
import com.shielddns.android.SubStore
import com.shielddns.android.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 过滤订阅：加一条规则列表 URL，手动或自动更新，显示条数与更新时间。 */
class SubsPage(private val a: MainActivity) : Page {

    override val view: View
    private val listBox: LinearLayout
    private val summary: TextView
    private var lastKey = ""
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    init {
        val scroll = ScrollView(a)
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(a, 14)
            setPadding(p, p, p, Ui.dp(a, 24))
        }
        scroll.addView(column)

        val head = Ui.row(a)
        head.addView(Ui.sectionTitle(a, "订阅列表"), Ui.lpWeight(1f))
        val add = Ui.button(a, "添加订阅")
        add.setOnClickListener { showAddDialog() }
        head.addView(add)
        column.addView(head)

        summary = Ui.text(a, "", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 12))
        }
        column.addView(summary)

        listBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        column.addView(listBox)

        view = scroll
    }

    override fun refresh() {
        val subs = Prefs.loadSubs()
        val key = subs.joinToString("|") { "${it.id}:${it.count}:${it.updatedAt}:${it.name}:${it.url}" }
        if (key != lastKey) {
            lastKey = key
            render(subs)
        }
        summary.text = if (subs.isEmpty()) "" else
            "共 ${subs.size} 条订阅 · 规则 ${subs.sumOf { it.count }} 条"
    }

    private fun render(subs: List<SubEntry>) {
        listBox.removeAllViews()
        if (subs.isEmpty()) {
            val empty = Ui.card(a)
            empty.addView(Ui.text(a, "还没有订阅", 13f, Ui.TEXT, true))
            empty.addView(Ui.text(a, "点右上角「添加订阅」，粘贴一条 hosts 或 AdGuard 规则列表的 URL。规则不内置，需要自己加。", 12f, Ui.MUTED).apply {
                setPadding(0, Ui.dp(a, 6), 0, 0)
            })
            listBox.addView(empty, Ui.lpMatch())
            return
        }
        subs.forEach { s ->
            val card = Ui.card(a)
            val top = Ui.row(a)
            top.addView(Ui.text(a, s.name.ifBlank { "未命名" }, 14f, Ui.TEXT, true), Ui.lpWeight(1f))
            val del = Ui.text(a, "删除", 12f, Ui.DANGER)
            del.setPadding(Ui.dp(a, 8), Ui.dp(a, 2), 0, Ui.dp(a, 2))
            del.setOnClickListener { confirmDelete(s) }
            top.addView(del)
            card.addView(top)

            card.addView(Ui.text(a, s.url, 11.5f, Ui.MUTED).apply {
                setPadding(0, Ui.dp(a, 6), 0, 0)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })

            val info = buildString {
                append("规则 ${s.count} 条")
                append(" · ")
                append(if (s.updatedAt > 0) "更新于 ${fmt.format(Date(s.updatedAt))}" else "尚未下载")
            }
            val row = Ui.row(a).apply { setPadding(0, Ui.dp(a, 8), 0, 0) }
            row.addView(Ui.text(a, info, 11.5f, Ui.MUTED), Ui.lpWeight(1f))
            val upd = Ui.text(a, "更新", 12f, Ui.ACCENT, true)
            upd.setPadding(Ui.dp(a, 6), Ui.dp(a, 2), 0, Ui.dp(a, 2))
            upd.setOnClickListener { downloadOne(s, upd) }
            row.addView(upd)
            card.addView(row)

            listBox.addView(card, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 10) })
        }
    }

    private fun downloadOne(s: SubEntry, label: TextView) {
        label.text = "下载中…"
        SubStore.refreshAll(a, listOf(s), { _, _, _ -> }, { _, _ ->
            val all = Prefs.loadSubs().map { if (it.id == s.id) s else it }
            Prefs.saveSubs(all)
            a.runOnUiThread {
                lastKey = ""
                a.reloadService()
                refresh()
            }
        })
    }

    private fun confirmDelete(s: SubEntry) {
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("删除订阅")
            .setMessage("删除「${s.name}」？已下载的规则文件也会一起清掉。")
            .setPositiveButton("删除") { _, _ ->
                val all = Prefs.loadSubs().filter { it.id != s.id }
                Prefs.saveSubs(all)
                runCatching { SubStore.fileFor(a, s).delete() }
                lastKey = ""
                a.reloadService()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddDialog() {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(a, 18)
            setPadding(p, p, p, 0)
        }
        val nameInput = EditText(a).apply {
            hint = "名称（可留空）"
            setTextColor(Ui.TEXT)
            setHintTextColor(0xFF5F7A6D.toInt())
        }
        val urlInput = EditText(a).apply {
            hint = "https://…/rules.txt"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Ui.TEXT)
            setHintTextColor(0xFF5F7A6D.toInt())
        }
        box.addView(nameInput)
        box.addView(urlInput)
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("添加过滤订阅")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                val name = nameInput.text.toString().trim().ifBlank {
                    runCatching { java.net.URI(url).host ?: "订阅" }.getOrDefault("订阅")
                }
                val entry = SubEntry(
                    id = UUID.randomUUID().toString().take(8),
                    name = name,
                    url = url
                )
                val all = Prefs.loadSubs().apply { add(entry) }
                Prefs.saveSubs(all)
                lastKey = ""
                downloadOne(entry, Ui.text(a, "", 1f, Ui.MUTED))
                a.reloadService()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
