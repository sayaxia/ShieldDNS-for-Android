package com.shielddns.android.page

import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shielddns.android.MainActivity
import com.shielddns.android.Page
import com.shielddns.android.Prefs
import com.shielddns.android.RuleLint
import com.shielddns.android.Ui

/** 自定义规则：AdGuard / hosts 语法，保存后立刻重建规则引擎。 */
class CustomRulesPage(private val a: MainActivity) : Page {

    override val view: View
    private val editor: EditText
    private lateinit var hintText: TextView
    /** 有未保存的编辑：非程序调用产生的文本变化 */
    private var dirty = false
    /** setText 由代码触发时置位，避免把程序写入当成用户编辑 */
    private var programmatic = false

    init {
        val scroll = ScrollView(a)
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(a, 14)
            setPadding(p, p, p, Ui.dp(a, 24))
        }
        scroll.addView(column)

        // 标题行右侧挂问号，完整写法收进弹窗，不占页面
        val head = Ui.row(a)
        head.addView(Ui.sectionTitle(a, "自定义规则"), Ui.lpWeight(1f))
        val helpIcon = Ui.text(a, "?", 11f, Ui.MUTED, true).apply {
            gravity = android.view.Gravity.CENTER
            background = Ui.rounded(0x00000000, 999, 0xFF33443C.toInt(), 1, a)
            setOnClickListener { showSyntaxHelp() }
        }
        val iconSize = Ui.dp(a, 20)
        head.addView(helpIcon, LinearLayout.LayoutParams(iconSize, iconSize))
        column.addView(head, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 10) })

        val card = Ui.card(a)
        editor = EditText(a).apply {
            setText(Prefs.customRules)
            hint = "在这里输入规则…"
            gravity = android.view.Gravity.TOP
            minLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(Ui.TEXT)
            setHintTextColor(0xFF5F7A6D.toInt())
            background = Ui.rounded(0xFF0C1411.toInt(), 10, 0xFF22322B.toInt(), 1, a)
            val p = Ui.dp(a, 10)
            setPadding(p, p, p, p)
        }
        // 打字过程中不打断：只提示「编辑中」，等收键盘（失焦）或点保存才真正体检
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a2: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (programmatic) return
                dirty = true
                if (editor.hasFocus()) {
                    hintText.text = "编辑中 · 收键盘后自动检查语法"
                    hintText.setTextColor(Ui.MUTED)
                }
            }
        })
        editor.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateHint()
        }
        card.addView(editor)
        column.addView(card, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 12) })

        val actions = Ui.row(a)
        val save = Ui.button(a, "保存并应用")
        save.setOnClickListener {
            val text = editor.text.toString()
            Prefs.customRules = text
            a.reloadService()
            val r = RuleLint.check(text)
            if (r.fatalCount > 0) {
                android.widget.Toast.makeText(
                    a, "已保存，其中 ${r.fatalCount} 行不会生效", android.widget.Toast.LENGTH_LONG
                ).show()
            }
            save.text = "已保存"
            save.postDelayed({ save.text = "保存并应用" }, 1500)
            dirty = false
            updateHint(force = true)
        }
        val clear = Ui.button(a, "清空", primary = false)
        clear.setOnClickListener {
            programmatic = true
            editor.setText("")
            programmatic = false
            dirty = false
            Prefs.customRules = ""
            a.reloadService()
            updateHint(force = true)
        }
        actions.addView(save)
        actions.addView(View(a), android.widget.LinearLayout.LayoutParams(Ui.dp(a, 10), 1))
        actions.addView(clear)
        column.addView(actions)

        hintText = Ui.text(a, "", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(a, 12), 0, 0)
        }
        column.addView(hintText)
        updateHint()

        view = scroll
    }

    private fun showSyntaxHelp() {
        android.app.AlertDialog.Builder(a)
            .setTitle("自定义规则写法")
            .setMessage(
                """
                每行一条，注释用 ! 或 # 开头。

                ||example.com^
                拦截该域名及其所有子域

                ||*-reading-ad.example.com^
                ||ads*.adcdn.com^
                * 通配任意字符，可放在任意位置

                @@||ok.example.com^
                例外放行，优先于拦截

                example.com
                裸域名，等同拦截

                0.0.0.0 example.com
                hosts 格式
                """.trimIndent()
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun updateHint(force: Boolean = false) {
        if (!force && editor.hasFocus()) {
            hintText.text = "编辑中 · 收键盘后自动检查语法"
            hintText.setTextColor(Ui.MUTED)
            return
        }
        val r = RuleLint.check(editor.text.toString())
        if (r.valid == 0 && r.issues.isEmpty()) {
            hintText.text = "当前没有自定义规则。"
            hintText.setTextColor(Ui.MUTED)
            return
        }
        val sb = StringBuilder("共 ${r.valid} 行有效")
        if (r.ignored > 0) sb.append(" · 忽略 ${r.ignored} 行注释")
        if (r.issues.isNotEmpty()) sb.append(" · ${r.issues.size} 行有问题")
        val fatal = r.issues.filter { it.fatal }
        if (fatal.isNotEmpty()) {
            fatal.take(3).forEach {
                sb.append("\n第 ${it.line} 行「${it.source.take(26)}」：${it.reason}")
            }
            if (fatal.size > 3) sb.append("\n…另有 ${fatal.size - 3} 行同样不会生效")
        }
        val warn = r.issues.filter { !it.fatal }
        if (warn.isNotEmpty()) {
            sb.append("\n第 ${warn.first().line} 行：${warn.first().reason}")
            if (warn.size > 1) sb.append("（另有 ${warn.size - 1} 行同类）")
        }
        hintText.text = sb.toString()
        hintText.setTextColor(if (fatal.isNotEmpty()) 0xFFE9A15C.toInt() else Ui.MUTED)
    }

    override fun refresh() {
        // 正在编辑时绝不回写：refresh 是周期性的，覆盖一次就等于把用户的输入撤销掉
        if (editor.hasFocus()) return
        val saved = Prefs.customRules
        if (dirty || editor.text.toString() != saved) {
            programmatic = true
            editor.setText(saved)
            programmatic = false
            dirty = false
        }
        updateHint(force = true)
    }
}
