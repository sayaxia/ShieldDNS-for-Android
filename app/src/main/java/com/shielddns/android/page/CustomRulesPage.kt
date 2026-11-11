package com.shielddns.android.page

import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shielddns.android.MainActivity
import com.shielddns.android.Page
import com.shielddns.android.Prefs
import com.shielddns.android.Ui

/** 自定义规则：AdGuard / hosts 语法，保存后立刻重建规则引擎。 */
class CustomRulesPage(private val a: MainActivity) : Page {

    override val view: View
    private val editor: EditText
    private val hintText: TextView

    init {
        val scroll = ScrollView(a)
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(a, 14)
            setPadding(p, p, p, Ui.dp(a, 24))
        }
        scroll.addView(column)

        column.addView(Ui.sectionTitle(a, "自定义规则"))
        column.addView(Ui.text(
            a,
            "每行一条，支持 ||example.com^（拦截域名及子域）、@@||ok.example.com^（例外放行）、example.com（裸域名）、0.0.0.0 example.com（hosts 格式）。注释用 ! 或 #。",
            12f, Ui.MUTED
        ).apply { setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 12)) })

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
        card.addView(editor)
        column.addView(card, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 12) })

        val actions = Ui.row(a)
        val save = Ui.button(a, "保存并应用")
        save.setOnClickListener {
            Prefs.customRules = editor.text.toString()
            a.reloadService()
            save.text = "已保存"
            save.postDelayed({ save.text = "保存并应用" }, 1500)
            updateHint()
        }
        val clear = Ui.button(a, "清空", primary = false)
        clear.setOnClickListener {
            editor.setText("")
            Prefs.customRules = ""
            a.reloadService()
            updateHint()
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

    private fun updateHint() {
        val lines = Prefs.customRules.lineSequence().count { it.isNotBlank() }
        hintText.text = if (lines == 0) "当前没有自定义规则。" else "当前 $lines 行非空规则，已生效。"
    }

    override fun refresh() {}
}
