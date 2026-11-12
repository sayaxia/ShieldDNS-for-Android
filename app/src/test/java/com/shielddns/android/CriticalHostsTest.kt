package com.shielddns.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 关键域名兜底：订阅或内置误伤不能把微信/支付/系统探测拦掉，但自定义规则可以覆盖。 */
class CriticalHostsTest {

    @Test
    fun 订阅误伤微信登录会被挡下() {
        val e = RuleEngine()
        e.rebuild(listOf("||api.weixin.qq.com^"), "", emptyList())
        assertFalse(e.isBlocked("api.weixin.qq.com"))
        assertFalse(e.isBlocked("login.weixin.qq.com"))
    }

    @Test
    fun 通配全拦也不会伤到关键域名() {
        val e = RuleEngine()
        e.rebuild(listOf("||*^"), "", emptyList())
        assertFalse(e.isBlocked("mobilegw.alipay.com"))
        assertFalse(e.isBlocked("api.mch.weixin.qq.com"))
        assertTrue(e.isBlocked("ads3-normal-hl.zijieapi.com"))
    }

    @Test
    fun 内置层误伤同样被挡下() {
        val e = RuleEngine()
        e.rebuild(emptyList(), "", listOf("||connectivitycheck.platform.hicloud.com^"))
        assertFalse(e.isBlocked("connectivitycheck.platform.hicloud.com"))
    }

    @Test
    fun 自定义规则仍然可以主动拦关键域名() {
        val e = RuleEngine()
        e.rebuild(emptyList(), "||api.weixin.qq.com^", emptyList())
        assertTrue(e.isBlocked("api.weixin.qq.com"))
    }
}
