package com.shielddns.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 规则已从代码搬到 assets/default-rules.txt，这里直接读那份真实文件来跑断言：
 * 既验证匹配行为，也保证规则文件的语法真的能被 RuleEngine 吃下去。
 */
class SniRulesTest {

    private fun locate(path: String): File {
        val candidates = listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path"))
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到 $path，工作目录=${File(".").absolutePath}")
    }

    private val items: List<BuiltinRules.Item> = BuiltinRules.parse(locate("default-rules.txt").readText())

    /** SNI 层引擎：dns 段 + sni 段 */
    private val engine: RuleEngine = RuleEngine().apply {
        rebuild(
            listOf(
                BuiltinRules.select(items, BuiltinRules.SCOPE_DNS, emptySet()),
                BuiltinRules.select(items, BuiltinRules.SCOPE_SNI, emptySet())
            ),
            ""
        )
    }

    /** DNS 层引擎：只有 dns 段 */
    private val dnsEngine: RuleEngine = RuleEngine().apply {
        rebuild(listOf(BuiltinRules.select(items, BuiltinRules.SCOPE_DNS, emptySet())), "")
    }

    private fun block(host: String) = SniRules.shouldBlock(engine, host)

    @Test
    fun 广告投放域名被拦() {
        assertTrue(block("ads3-normal-hl.zijieapi.com"))
        assertTrue(block("ads5-normal-hl.zijieapi.com"))
        assertTrue(block("ADS9-NORMAL-HL.ZIJIEAPI.COM"))
    }

    @Test
    fun 激励窗口与广告签名被拦() {
        assertTrue(block("praisewindow.ugsdk.cn"))
        assertTrue(block("p3-ad-sign.byteimg.com"))
        assertTrue(block("p9-ad-sign.byteimg.com"))
    }

    @Test
    fun 广告素材域名被拦() {
        assertTrue(block("v6-reading-ad.qznovelvod.com"))
        assertTrue(block("v9-reading-ad.qznovelvod.com"))
        assertTrue(block("v5-se-sjy-daily-reading-ad.qznovelvod.com"))
        assertTrue(block("v95-aw-reading-ad.qznovelvod.com"))
        assertTrue(block("v11-reading-ad.qznovelvod.com"))
        assertTrue(block("v26-reading-ad.qznovelvod.com"))
    }

    @Test
    fun 正片域名全部放行() {
        assertFalse(block("v5-bd-daily-reading-video.qznovelvod.com"))
        assertFalse(block("v13-reading-videocdn.qznovelvod.com"))
        assertFalse(block("v26-reading-videocdn302.qznovelvod.com"))
        assertFalse(block("v95-hzyy-thr-daily-reading-videocdn.qznovelvod.com"))
        assertFalse(block("v0-video-cn-private.qznovelvod.com"))
    }

    @Test
    fun 正片优先于广告特征() {
        // 同时带 reading-ad 和 reading-video 的异常组合，例外优先放行
        assertFalse(block("v5-reading-ad-reading-video.qznovelvod.com"))
    }

    @Test
    fun 接口与日志域名放行() {
        assertFalse(block("api3-normal-sinfonlinea.fqnovel.com"))
        assertFalse(block("imapi-oth.zijieapi.com"))
        assertFalse(block("mon11-misc-hl.fqnovel.com"))
        assertFalse(block("log5-applog-hl.fqnovel.com"))
        assertFalse(block("tnc0-aliec2.zijieapi.com"))
        assertFalse(block("webcast5-open-hl.douyin.com"))
        assertFalse(block("polaris5-normal-hl.zijieapi.com"))
    }

    @Test
    fun 穿山甲广告签名被拦() {
        assertTrue(block("lf26-mgsdk-sign.dailygn.com"))
        assertTrue(block("p6-mgsdk-sign.byteimg.com"))
    }

    @Test
    fun 主流广告SDK域名被拦() {
        assertTrue(block("v2.gdt.qq.com"))
        assertTrue(block("qzs.gdtimg.com"))
        assertTrue(block("adservice.sigmob.cn"))
        assertTrue(block("cdn.mintegral.com"))
        assertTrue(block("ads.doubleclick.net"))
        assertTrue(block("api.applovin.com"))
        assertTrue(block("sdk.ironsrc.com"))
        assertTrue(block("mobads.baidu.com"))
        assertTrue(block("acdn.tanx.com"))
        assertTrue(block("open-api.pangleglobal.com"))
    }

    @Test
    fun 广告接口机房变体全部拦() {
        assertTrue(block("ads.zijieapi.com"))
        assertTrue(block("ads3-normal-hl.zijieapi.com"))
        assertTrue(block("ads3-normal-lf.zijieapi.com"))
        assertTrue(block("ads3-normal-lq.zijieapi.com"))
        assertTrue(block("ads3-normal.zijieapi.com"))
        assertTrue(block("ads5-normal-lf.zijieapi.com"))
        assertTrue(block("ad.zijieapi.com"))
    }

    @Test
    fun 巨量引擎域名被拦() {
        assertTrue(block("ad.oceanengine.com"))
        assertTrue(block("leads.oceanengine.com"))
        assertTrue(block("clue.oceanengine.com"))
    }

    @Test
    fun 阅读图片与私有视频域名放行() {
        assertFalse(block("p3-reading.byteimg.com"))
        assertFalse(block("p9-reading.byteimg.com"))
        assertFalse(block("p6-novel.byteimg.com"))
        assertFalse(block("p3-novel-sign.byteimg.com"))
        assertFalse(block("p3-sign.douyinpic.com"))
        assertFalse(block("lf3-reading.fqnovelstatic.com"))
    }

    @Test
    fun 混用大域不被误伤() {
        // 这些域名同时承载图片/接口/正常业务，不能整域拦
        assertFalse(block("img.gtimg.cn"))
        assertFalse(block("qq.com"))
        assertFalse(block("www.baidu.com"))
        assertFalse(block("p3-novel.byteimg.com"))
        assertFalse(block("lf3-reading.fqnovelpic.com"))
        assertFalse(block("aweme.snssdk.com"))
        assertFalse(block("www.ixigua.com"))
    }

    @Test
    fun 广告SDK只进SNI层不进DNS层() {
        // sni 段的第三方 SDK 整域只作用于白名单应用，DNS 层（全局）不能拦
        assertTrue(block("api.applovin.com"))
        assertFalse(SniRules.shouldBlock(dnsEngine, "api.applovin.com"))
        assertTrue(block("v2.gdt.qq.com"))
        assertFalse(SniRules.shouldBlock(dnsEngine, "v2.gdt.qq.com"))
        // dns 段的两层都生效
        assertTrue(block("ads3-normal-hl.zijieapi.com"))
        assertTrue(SniRules.shouldBlock(dnsEngine, "ads3-normal-hl.zijieapi.com"))
    }

    @Test
    fun 停用某条后不再生效() {
        val off = BuiltinRules.select(items, BuiltinRules.SCOPE_DNS, setOf("||*-reading-ad.*^"))
        val e = RuleEngine().apply { rebuild(listOf(off), "") }
        assertFalse(SniRules.shouldBlock(e, "v6-reading-ad.qznovelvod.com"))
        assertTrue(SniRules.shouldBlock(e, "ads3-normal-hl.zijieapi.com"))
    }

    @Test
    fun 直播CDN默认不拦() {
        assertFalse(block("pull-flv-t11.douyincdn.com"))
        assertFalse(block("pull-x2-h5-hls.douyincdn.com"))
        assertFalse(block("pull-tsl-f26.douyinliving.com"))
        assertFalse(block("pull-hls-hs-f5-enc.flive.douyincdn.com"))
    }

    @Test
    fun 打开开关后直播CDN被拦且不影响正片() {
        assertTrue(SniRules.shouldBlock(engine, "pull-flv-t11.douyincdn.com", true))
        assertTrue(SniRules.shouldBlock(engine, "pull-x2-h5-hls.douyincdn.com", true))
        assertTrue(SniRules.shouldBlock(engine, "pull-tsl-f26.douyinliving.com", true))
        assertTrue(SniRules.shouldBlock(engine, "pull-hls-hs-f5-enc.flive.douyincdn.com", true))
        assertTrue(SniRules.shouldBlock(engine, "pull-rtmp-l1-spe.ixigua.com", true))
        assertTrue(SniRules.shouldBlock(engine, "pull-flv-l1.ixigua.com", true))
        assertTrue(SniRules.shouldBlock(engine, "pull-hs-f5-xg.flive.ixigua.com", true))
        assertTrue(SniRules.shouldBlock(engine, "v5-se2-bjtc-reading-ad.qznovelvod.com", true))
        assertFalse(SniRules.shouldBlock(engine, "v26-reading-videocdn302.qznovelvod.com", true))
        assertFalse(SniRules.shouldBlock(engine, "www.ixigua.com", true))
        assertFalse(SniRules.shouldBlock(engine, "api.ixigua.com", true))
        assertFalse(SniRules.shouldBlock(engine, "v5-reading-video-c.qznovelvod.com", true))
    }

    @Test
    fun 空域名不拦() {
        assertFalse(block(""))
        assertFalse(block("   "))
    }

    @Test
    fun 订阅文件可直接被引擎吃下() {
        val candidates = listOf(File("../rules/hongguo-adblock.txt"), File("rules/hongguo-adblock.txt"))
        val f = candidates.firstOrNull { it.exists() } ?: return
        val e = RuleEngine().apply { rebuild(listOf(f.readText()), "") }
        assertTrue(SniRules.shouldBlock(e, "v95-aw-reading-ad.qznovelvod.com"))
        assertTrue(SniRules.shouldBlock(e, "ads3-normal-lf.zijieapi.com"))
        assertFalse(SniRules.shouldBlock(e, "v26-reading-videocdn302.qznovelvod.com"))
        assertFalse(SniRules.shouldBlock(e, "mon11-misc-hl.fqnovel.com"))
    }
}
