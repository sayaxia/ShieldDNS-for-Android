package com.shielddns.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    @Test
    fun renderAllPages() {
        org.junit.Assume.assumeTrue(
            "需要出图时设置环境变量 SHIELDDNS_SHOT=1",
            System.getenv("SHIELDDNS_SHOT") != null
        )
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sel = MainActivity::class.java.getDeclaredMethod("selectTab", Int::class.javaPrimitiveType)
        sel.isAccessible = true
        val out = File(System.getProperty("screenshot.dir") ?: "/tmp/shielddns-ui").apply { mkdirs() }
        val decor = a.window.decorView
        listOf("1-overview", "2-subs", "3-rules", "4-log", "5-settings", "6-settings-bottom").forEachIndexed { i, name ->
            if (i == 2) {
                Prefs.customRules = "# 示例\n||*-reading-ad.qznovelvod.com^\n||ads*.zijieapi.com^\nexample.com\nexample.com##.banner\n/ads[0-9]/\nlocalhost"
            }
            if (i == 1) {
                // 注入两条示例订阅，验证「手柄 + 开关 + 更新」的行布局
                Prefs.saveSubs(
                    listOf(
                        SubEntry("a1", "红果专杀", "https://cdn.jsdelivr.net/gh/sayaxia/ShieldDNS-for-Android@main/rules/hongguo-adblock.txt", 15, System.currentTimeMillis(), true),
                        SubEntry("b2", "AdGuard 基础", "https://filters.adtidy.org/android/filters/2_optimized.txt", 1234, 0L, false)
                    )
                )
            }
            // 第 6 张仍是设置页（索引 4），只是滚到底
            sel.invoke(a, if (i >= 4) 4 else i)
            if (i == 1) {
                // 切到多选态，验证圆形勾选框
                runCatching {
                    val pf = MainActivity::class.java.getDeclaredField("pages")
                    pf.isAccessible = true
                    val page = (pf.get(a) as List<*>)[1] as com.shielddns.android.page.SubsPage
                    val m = com.shielddns.android.page.SubsPage::class.java
                        .getDeclaredMethod("setSelectMode", Boolean::class.javaPrimitiveType)
                    m.isAccessible = true
                    m.invoke(page, true)
                    val m2 = com.shielddns.android.page.SubsPage::class.java
                        .getDeclaredMethod("toggleSelectAll")
                    m2.isAccessible = true
                    m2.invoke(page)
                }.onFailure { println("多选态反射失败: $it") }
            }
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2280, View.MeasureSpec.EXACTLY)
            )
            decor.layout(0, 0, 1080, 2280)
            if (i == 5) {
                // 设置页太长，再截一张滚到底的，看诊断导出与备份卡片的排布
                runCatching {
                    val pf = MainActivity::class.java.getDeclaredField("pages")
                    pf.isAccessible = true
                    val page = (pf.get(a) as List<*>)[4] as com.shielddns.android.Page
                    (page.view as android.widget.ScrollView).scrollTo(0, 10000)
                    decor.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(2280, View.MeasureSpec.EXACTLY)
                    )
                    decor.layout(0, 0, 1080, 2280)
                }.onFailure { println("滚动设置页失败: $it") }
            }
            val bmp = Bitmap.createBitmap(1080, 2280, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bmp))
            if (i == 1) Prefs.saveSubs(emptyList())
            if (i == 2) Prefs.customRules = ""
            FileOutputStream(File(out, "$name.png")).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
