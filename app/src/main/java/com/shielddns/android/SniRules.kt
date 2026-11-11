package com.shielddns.android

/**
 * SNI 层拦截规则。
 *
 * 设计原则：先放行后拦。红果把正片、广告、日志、调度全塞在同一批 CDN 域名下，
 * 只按「含 ad 就拦」必然连坐正片，所以正片/CDN 特征优先放行，再匹配广告特征。
 *
 * 匹配分四层，从严到宽：
 *  1. 放行特征（正片、正片 CDN）—— 命中即放行，优先级最高；
 *  2. 精确域名；
 *  3. 字符特征（广告素材/签名的命名规律）；
 *  4. 后缀域名（整段域名专属于某家广告 SDK，可以安全地连子域一起拦）。
 *
 * 后缀只收「专属广告」的域名。gtimg.cn、byteimg.com、qq.com、baidu.com 这类混用域名
 * 一律不收，否则会把图片和正常接口一起打掉。清单依据各 SDK 官方文档与公开域名表。
 */
object SniRules {

    /** 精确拦截：单个域名，不连带子域。 */
    private val exact = setOf(
        "praisewindow.ugsdk.cn",
        "p3-ad-sign.byteimg.com",
        "ad.zijieapi.com",
        "pgdt.gtimg.cn",
    )

    /** 放行优先：正片与正片 CDN，命中这些一律不拦。 */
    private val allowMarks = listOf(
        "reading-video",
        "videocdn",
        "reading.fqnovelstatic",
        "reading.fqnovelpic",
    )

    /** 拦截特征：广告素材与签名的命名规律。 */
    private val blockMarks = listOf(
        "reading-ad",
        "-ad-sign",
        "mgsdk-sign",
    )

    /** 后缀拦截：该域名及其所有子域专属于某家广告 SDK。 */
    private val suffix = listOf(
        // ---- 字节跳动 / 穿山甲 Pangle ----
        "dailygn.com",                  // mgsdk 签名，真机实测 lf26-mgsdk-sign.dailygn.com
        "pangle.io",                    // Pangle 国际版
        "pangleglobal.com",             // Pangle 全球投放
        "csjplatform.com",              // 穿山甲平台
        "csjdeveloper.com",             // 穿山甲短剧内容 SDK
        "pangolin-sdk-toutiao.com",     // 穿山甲国内 SDK 素材
        "ug.snssdk.com",                // 字节 UG 投放
        "oceanengine.com",              // 巨量引擎（ad/brand/clue/leads 子域），APK 扫描确认

        // ---- 腾讯优量汇 / 广点通 ----
        "gdt.qq.com",                   // v2/sc/c/v.gdt.qq.com
        "gdtimg.com",                   // 素材 CDN，qzs.gdtimg.com
        "adnet.qq.com",                 // 腾讯广告联盟

        // ---- 百度联盟 ----
        "mobads.baidu.com",
        "bes.baidu.com",

        // ---- 阿里妈妈 ----
        "tanx.com",
        "alimama.com",

        // ---- Sigmob ----
        "sigmob.cn",                    // adservice.sigmob.cn
        "sigmob.com",

        // ---- Mintegral / 汇量科技 ----
        "mintegral.com",
        "mintegral.net",
        "mobvista.com",

        // ---- AppLovin / MoPub ----
        "applovin.com",
        "applvn.com",
        "mopub.com",

        // ---- Unity Ads / ironSource ----
        "unityads.unity3d.com",
        "ironsrc.com",
        "supersonicads.com",

        // ---- Vungle / Liftoff ----
        "vungle.com",
        "vungle.tv",
        "liftoff.io",

        // ---- Google AdMob ----
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "admob.com",

        // ---- 其他主流联盟 ----
        "chartboost.com",
        "inmobi.com",
        "inmobi.cn",
        "amazon-adsystem.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "smaato.net",
        "pubmatic.com",
        "openx.net",
        "tapjoy.com",
        "adcolony.com",
        "appnext.com",
        "flurry.com",
        "nexage.com",
        "inner-active.mobi",
    )

    /**
     * @param blockLiveCdn 是否连带拦抖音直播拉流 CDN（pull-*.douyincdn.com、
     *   *.douyinliving.com、*.flive.douyincdn.com）。红果信息流里的「直播间广告」
     *   就是直接拉一条直播流，域名和正常直播功能完全共用，SNI 看不到路径，
     *   只能整类一起拦——开了会断直播，所以做成可选项、默认关。
     */
    fun shouldBlock(host: String, blockLiveCdn: Boolean = false): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if (allowMarks.any { h.contains(it) }) return false
        if (h in exact) return true
        if (blockLiveCdn && isLiveCdn(h)) return true
        // ads 系列字节广告投放域名：ads.zijieapi.com 以及 ads3-normal-hl/lf/lq、
        // ads3-normal 这些机房变体，APK 扫描确认全部存在，只认 -hl 会漏掉一半
        if (h.startsWith("ads") && h.contains("zijieapi.com")) return true
        if (blockMarks.any { h.contains(it) }) return true
        return suffix.any { h == it || h.endsWith(".$it") }
    }

    private fun isLiveCdn(h: String): Boolean {
        if (h.endsWith(".douyinliving.com")) return true
        // 抖音系：pull-flv/pull-hls/pull-tsl/... 与 flive 子域都是直播拉流
        if (h.contains("douyincdn.com")) return h.startsWith("pull-") || h.contains(".flive.")
        // 西瓜系：实测红果的直播流也会走 ixigua，只认 pull-/flive 前缀，不碰普通点播域名
        if (h.contains("ixigua.com")) return h.startsWith("pull-") || h.contains(".flive.")
        return false
    }
}
