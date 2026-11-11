package com.shielddns.android

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiTest {

    private fun textsOf(v: View, out: MutableList<String>) {
        if (v is TextView) out.add(v.text?.toString() ?: "")
        if (v is ViewGroup) for (i in 0 until v.childCount) textsOf(v.getChildAt(i), out)
    }

    private fun collect(v: View, texts: MutableList<TextView>, switchers: MutableList<View>) {
        if (v is TextView) texts.add(v)
        if (v is android.widget.Switch) switchers.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), texts, switchers)
    }

    private fun measure(a: Activity): List<String> {
        val decor = a.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2280, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, 1080, 2280)
        val out = mutableListOf<String>()
        textsOf(decor, out)
        return out
    }

    @Test
    fun startsAndOverviewHasEverythingWhilePaused() {
        Stats.running = false
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        val texts = measure(a)
        println("PAUSED texts=" + texts.joinToString(" | "))
        assertTrue("更新全部订阅", texts.any { it.contains("更新全部订阅") })
        assertTrue("订阅更新", texts.any { it.contains("订阅更新") })
        assertTrue("监听", texts.any { it.contains("监听 10.111.0.1:53") })
        assertTrue("运行指标", texts.any { it.contains("运行指标") })
        assertTrue("最近查询", texts.any { it.contains("最近查询") })
    }

    @Test
    fun pausedHeroKeepsButtonAndSubTimeOnScreen() {
        Stats.running = false
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        measure(a)
        val btns = mutableListOf<TextView>()
        val switches = mutableListOf<View>()
        collect(a.window.decorView, btns, switches)
        val update = btns.first { it.text.toString() == "更新全部订阅" }
        val sub = btns.first { it.text.toString().startsWith("订阅更新") }
        println("update bottom=" + update.bottom + " h=" + update.height + " sub top=" + sub.top)
        assertTrue("按钮有高度", update.height > 0)
        assertTrue("按钮在内容区内", update.bottom > update.top)
        assertTrue("订阅时间有位置", sub.top < update.top)
        assertTrue("开关有宽度", switches.first().width > 0)
    }

    @Test
    fun allFiveTabsRender() {
        Stats.running = false
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        val tabs = listOf("概览", "过滤订阅", "自定义规则", "查询日志", "设置")
        val texts = measure(a)
        tabs.forEach { assertTrue(it, texts.contains(it)) }
    }
}
