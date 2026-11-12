package com.shielddns.android

import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置规则验签。样本用发布者私钥对固定文本签过，改一个字符就必须验不过。
 */
class RuleVerifyTest {

    private val text = "||test-ad.example.com^\nexample.org\n@@||ok.example.org^\n"
    private val sigB64 =
        "MEUCIQDG8aZENBVFt8WYyLXpxN3rHdryo1gBw8ngjqhBWT/N0QIgAijZylJfO9M7bPy6iMlSv5zn5aZTEwAb9pNMs+wqXE8="

    private fun sig() = Base64.getDecoder().decode(sigB64)

    @Test
    fun 官方签名能通过() {
        assertTrue(RuleVerify.verify(text.toByteArray(), sig()))
    }

    @Test
    fun 改一个字就验不过() {
        assertFalse(RuleVerify.verify((text + "||evil.example.com^\n").toByteArray(), sig()))
    }

    @Test
    fun 签名被截断验不过() {
        assertFalse(RuleVerify.verify(text.toByteArray(), sig().copyOf(sig().size / 2)))
    }

    @Test
    fun 空输入一律拒绝() {
        assertFalse(RuleVerify.verify(ByteArray(0), sig()))
        assertFalse(RuleVerify.verify(text.toByteArray(), ByteArray(0)))
    }
}
