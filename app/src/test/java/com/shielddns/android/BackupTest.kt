package com.shielddns.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 备份序列化与恢复校验。 */
@RunWith(RobolectricTestRunner::class)
class BackupTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setup() {
        Prefs.init(ctx)
        Prefs.saveSubs(emptyList())
        Prefs.customRules = ""
        Prefs.builtinName = "内置策略"
        Prefs.builtinEnabled = true
    }

    @Test
    fun 导出的备份能原样恢复() {
        Prefs.saveSubs(
            listOf(
                SubEntry("s1", "测试源", "https://example.com/a.txt", 12, 100L, true),
                SubEntry("s2", "红果专杀", "https://example.com/b.txt", 15, 200L, false)
            )
        )
        Prefs.customRules = "||ads.example.com^\n@@||ok.example.com^"
        Prefs.blockMode = "zero"
        Prefs.enhancePackage = "com.phoenix.read"

        val json = Backup.buildJson()

        // 先把当前配置打乱，模拟「换了台设备」
        Prefs.saveSubs(emptyList())
        Prefs.customRules = ""
        Prefs.blockMode = "nxdomain"

        val r = Backup.applyJson(ctx, json, resync = false)
        assertTrue(r.message, r.ok)
        assertEquals(2, r.subs)

        val subs = Prefs.loadSubs()
        assertEquals(listOf("s1", "s2"), subs.map { it.id })
        assertEquals("测试源", subs[0].name)
        assertEquals(12, subs[0].count)
        assertFalse(subs[1].enabled)
        assertEquals("||ads.example.com^\n@@||ok.example.com^", Prefs.customRules)
        assertEquals("zero", Prefs.blockMode)
    }

    @Test
    fun 坏文件不动现有配置() {
        Prefs.saveSubs(listOf(SubEntry("keep", "保留", "https://example.com/k.txt", 3, 1L, true)))
        val r = Backup.applyJson(ctx, "这不是 json", resync = false)
        assertFalse(r.ok)
        assertEquals(1, Prefs.loadSubs().size)
    }

    @Test
    fun 版本不符拒绝导入() {
        Prefs.saveSubs(listOf(SubEntry("keep", "保留", "https://example.com/k.txt", 3, 1L, true)))
        val r = Backup.applyJson(ctx, """{"schema":99,"subs":[]}""", resync = false)
        assertFalse(r.ok)
        assertEquals(1, Prefs.loadSubs().size)
    }

    @Test
    fun 跳过内置id与非法地址() {
        Prefs.saveSubs(emptyList())
        val json = """
            {"schema":1,"subs":[
              {"id":"builtin","name":"伪造内置","url":"https://example.com/x.txt"},
              {"id":"s3","name":"本地域","url":"example.com/a.txt"},
              {"id":"s4","name":"正常","url":"https://example.com/n.txt"}
            ]}
        """.trimIndent()
        val r = Backup.applyJson(ctx, json, resync = false)
        assertTrue(r.ok)
        assertEquals(1, r.subs)
        assertEquals("s4", Prefs.loadSubs().first().id)
    }
}
