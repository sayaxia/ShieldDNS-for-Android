package com.shielddns.android

/**
 * 关键域名：拦掉会直接让设备「看起来坏了」的那些，作为永不拦截层兜底。
 *
 * 只放登录、支付、系统网络探测这类必不可少的域；不放视频、商店、更新等可以忍受被拦的域，
 * 也不放容易连带误伤的大域（比如整个 qq.com / alipay.com）。
 *
 * 层序上排在「自定义规则」之后、订阅与内置之前：订阅或内置里的任何一条误伤都会在这里被挡下来，
 * 而用户如果想连这些也拦，可以在自定义规则里写阻断 —— 自定义仍然说了算。
 */
object CriticalHosts {

    private val LIST = listOf(
        // 微信：登录、消息、支付
        "api.weixin.qq.com",
        "login.weixin.qq.com",
        "long.weixin.qq.com",
        "szshort.weixin.qq.com",
        "payapp.weixin.qq.com",
        "api.mch.weixin.qq.com",
        "servicewechat.com",
        // 支付宝：网关与客户端接口
        "mobilegw.alipay.com",
        "mclient.alipay.com",
        "openapi.alipay.com",
        // 系统网络连通性探测（拦了会被系统判成无网络）
        "connectivitycheck.gstatic.com",
        "connect.rom.miui.com",
        "connectivitycheck.platform.hicloud.com"
    )

    val size: Int get() = LIST.size

    /** 拼成给规则引擎吃的放行文本（@@ 例外）。 */
    val TEXT: String by lazy {
        LIST.joinToString("\n") { "@@||$it^" }
    }
}
