package com.shielddns.android.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.shielddns.android.Ui

/**
 * 圆形勾选框：未选中是空心圆环，选中填主题绿并画白色对勾。
 * 用自绘而不是 CheckBox，是为了摆脱 Material 方框在不同 ROM 上样式不一致的问题。
 */
class CircleCheck(ctx: Context) : View(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = 0xFF41564C.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Ui.ACCENT
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.1f * density
        color = Color.WHITE
    }
    private val path = Path()
    private val box = (22 * density).toInt()

    /** 选中态；setter 不回调 onToggle，避免程序赋值时触发用户事件。 */
    var checked = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    /** 用户点击切换后的回调。 */
    var onToggle: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        setOnClickListener {
            checked = !checked
            onToggle?.invoke(checked)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(box, box)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - 1.2f * density
        if (checked) {
            canvas.drawCircle(cx, cy, r, fill)
            val h = height / 2f
            path.reset()
            path.moveTo(cx - h * 0.42f, cy + h * 0.02f)
            path.lineTo(cx - h * 0.10f, cy + h * 0.34f)
            path.lineTo(cx + h * 0.44f, cy - h * 0.34f)
            canvas.drawPath(path, tick)
        } else {
            canvas.drawCircle(cx, cy, r, ring)
        }
    }
}
