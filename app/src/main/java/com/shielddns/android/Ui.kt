package com.shielddns.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** 统一的视觉语言，跟桌面版一套配色：暗底 + 绿盾。 */
object Ui {

    const val BG = 0xFF0B0F0D.toInt()
    const val CARD = 0xFF121A16.toInt()
    const val HERO_TOP = 0xFF13271F.toInt()
    const val HERO_BOTTOM = 0xFF0E1714.toInt()
    const val STROKE = 0xFF21302A.toInt()
    const val ACCENT = 0xFF3EE08A.toInt()
    const val ACCENT_DIM = 0xFF1F4A38.toInt()
    const val TEXT = 0xFFE8F2EC.toInt()
    const val MUTED = 0xFF8FA79B.toInt()
    const val WARN = 0xFFF2A65A.toInt()
    const val DANGER = 0xFFF2705E.toInt()

    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int?, strokeDp: Int, ctx: Context): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(color)
        d.cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(ctx, strokeDp), strokeColor)
        return d
    }

    fun heroBackground(ctx: Context): GradientDrawable {
        val d = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(HERO_TOP, HERO_BOTTOM))
        d.cornerRadius = dp(ctx, 20).toFloat()
        d.setStroke(dp(ctx, 1), 0xFF254235.toInt())
        return d
    }

    fun text(ctx: Context, s: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 16, STROKE, 1, ctx)
        val p = dp(ctx, 16)
        setPadding(p, p, p, p)
    }

    fun hero(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = heroBackground(ctx)
        val p = dp(ctx, 18)
        setPadding(p, p, p, p)
    }

    fun sectionTitle(ctx: Context, s: String): TextView = text(ctx, s, 13f, MUTED, true).apply {
        letterSpacing = 0.04f
    }

    fun button(ctx: Context, s: String, primary: Boolean = true): TextView = text(
        ctx, s, 13f,
        if (primary) 0xFF04150D.toInt() else TEXT,
        true
    ).apply {
        gravity = Gravity.CENTER
        val ph = dp(ctx, 18)
        val pv = dp(ctx, 11)
        setPadding(ph, pv, ph, pv)
        background = if (primary) {
            rounded(ACCENT, 12, null, 0, ctx)
        } else {
            rounded(0x00000000, 12, 0xFF2B3D34.toInt(), 1, ctx)
        }
        isClickable = true
        isFocusable = true
    }

    fun pill(ctx: Context, s: String, color: Int): TextView = text(ctx, s, 12f, color, true).apply {
        val ph = dp(ctx, 10)
        val pv = dp(ctx, 4)
        setPadding(ph, pv, ph, pv)
        background = rounded(adjustAlpha(color, 0.14f), 10, adjustAlpha(color, 0.45f), 1, ctx)
    }

    fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1))
        setBackgroundColor(0xFF1B2621.toInt())
    }

    fun spacer(ctx: Context, hDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, hDp))
    }

    fun switchWidget(ctx: Context, checked: Boolean): Switch = Switch(ctx).apply {
        isChecked = checked
        trackTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(ACCENT, 0xFF2A3A32.toInt())
        )
        thumbTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(0xFF0B1C14.toInt(), 0xFF7C8F85.toInt())
        )
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun lpMatch(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    fun lpWeight(w: Float = 1f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)

    /** 一条日志的详情弹层：完整域名（可长按选择）+ 一键复制，长域名不再被截断吃掉。 */
    fun showQueryDetail(ctx: Context, e: LogEntry) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 18)
            setPadding(p, dp(ctx, 12), p, 0)
        }
        box.addView(text(ctx, Stats.formatTime(e.time), 11.5f, MUTED))
        box.addView(
            text(ctx, e.host, 14f, TEXT).apply {
                setPadding(0, dp(ctx, 10), 0, dp(ctx, 8))
                setTextIsSelectable(true)
            }
        )
        box.addView(
            text(ctx, if (e.blocked) "已拦截" else "已放行", 12f, if (e.blocked) DANGER else ACCENT, true)
        )

        android.app.AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("查询详情")
            .setView(box)
            .setPositiveButton("复制域名") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("domain", e.host))
                android.widget.Toast.makeText(ctx, "已复制 ${e.host}", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
