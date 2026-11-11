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
        listOf("1-overview", "2-subs", "3-rules", "4-log", "5-settings").forEachIndexed { i, name ->
            sel.invoke(a, i)
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2280, View.MeasureSpec.EXACTLY)
            )
            decor.layout(0, 0, 1080, 2280)
            val bmp = Bitmap.createBitmap(1080, 2280, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bmp))
            FileOutputStream(File(out, "$name.png")).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
