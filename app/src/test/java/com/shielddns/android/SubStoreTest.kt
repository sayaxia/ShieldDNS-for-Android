package com.shielddns.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 订阅「有没有变化」按规则条目判定，而不是原始文本字节。 */
class SubStoreTest {

    @Test
    fun 注释与空行差异不算变化() {
        val a = "# v1 版\n||a.example.com^\n\n||b.example.com^\n"
        val b = "# v2 版\n||b.example.com^\n||a.example.com^\n\n\n"
        assertEquals(SubStore.ruleSignature(a), SubStore.ruleSignature(b))
    }

    @Test
    fun 顺序调换不算变化() {
        val a = "||a.example.com^\n||b.example.com^\n"
        val b = "||b.example.com^\n||a.example.com^\n"
        assertEquals(SubStore.ruleSignature(a), SubStore.ruleSignature(b))
    }

    @Test
    fun 多一条规则才算变化() {
        assertNotEquals(
            SubStore.ruleSignature("||a.example.com^\n"),
            SubStore.ruleSignature("||a.example.com^\n||b.example.com^\n")
        )
    }

    @Test
    fun 例外规则也计入指纹() {
        assertNotEquals(
            SubStore.ruleSignature("||a.example.com^\n"),
            SubStore.ruleSignature("||a.example.com^\n@@||a.example.com^\n")
        )
    }
}
