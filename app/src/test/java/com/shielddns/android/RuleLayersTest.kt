package com.shielddns.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分层优先级：自定义 > 关键域名 > 内置（页面置顶）> 订阅（列表越靠上越高）。 */
class RuleLayersTest {

    @Test
    fun 自定义规则压过订阅() {
        val e = RuleEngine()
        e.rebuild(listOf("||x.example.com^"), "@@||x.example.com^", emptyList())
        assertFalse(e.isBlocked("x.example.com"))
    }

    @Test
    fun 内置压过订阅() {
        // 内置策略在页面上置顶、不可拖动，所以它在引擎里也排在订阅之前，例外同样说了算
        val e = RuleEngine()
        e.rebuild(listOf("||y.example.com^"), "", listOf("@@||y.example.com^"))
        assertFalse(e.isBlocked("y.example.com"))
    }

    @Test
    fun 内置拦截压过订阅例外() {
        // 反过来也成立：内置的拦截不会被订阅的 @@ 放行压掉
        val e = RuleEngine()
        e.rebuild(listOf("@@||w.example.com^"), "", listOf("||w.example.com^"))
        assertTrue(e.isBlocked("w.example.com"))
    }

    @Test
    fun 内置层的例外仍然先于内置层的拦截() {
        val e = RuleEngine()
        e.rebuild(
            emptyList(), "",
            listOf("||*-reading-video*^\n@@||*reading-video*^")
        )
        assertFalse(e.isBlocked("v5-reading-video.qznovelvod.com"))
    }

    @Test
    fun 订阅列表顺序就是优先级() {
        val high = RuleEngine()
        high.rebuild(listOf("@@||z.example.com^", "||z.example.com^"), "", emptyList())
        assertFalse(high.isBlocked("z.example.com"))

        val low = RuleEngine()
        low.rebuild(listOf("||z.example.com^", "@@||z.example.com^"), "", emptyList())
        assertTrue(low.isBlocked("z.example.com"))
    }

    @Test
    fun 停用的订阅不参与() {
        val e = RuleEngine()
        e.rebuild(emptyList(), "||q.example.com^", listOf("||ads*.zijieapi.com^"))
        assertTrue(e.isBlocked("q.example.com"))
        assertTrue(e.isBlocked("ads3-normal-hl.zijieapi.com"))
    }
}
