package com.shielddns.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SniRulesTest {

    @Test
    fun 广告投放域名被拦() {
        assertTrue(SniRules.shouldBlock("ads3-normal-hl.zijieapi.com"))
        assertTrue(SniRules.shouldBlock("ads5-normal-hl.zijieapi.com"))
        assertTrue(SniRules.shouldBlock("ADS9-NORMAL-HL.ZIJIEAPI.COM"))
    }

    @Test
    fun 激励窗口与广告签名被拦() {
        assertTrue(SniRules.shouldBlock("praisewindow.ugsdk.cn"))
        assertTrue(SniRules.shouldBlock("p3-ad-sign.byteimg.com"))
    }

    @Test
    fun 广告素材域名被拦() {
        assertTrue(SniRules.shouldBlock("v6-reading-ad.qznovelvod.com"))
        assertTrue(SniRules.shouldBlock("v9-reading-ad.qznovelvod.com"))
        assertTrue(SniRules.shouldBlock("v5-se-sjy-daily-reading-ad.qznovelvod.com"))
    }

    @Test
    fun 正片域名全部放行() {
        assertFalse(SniRules.shouldBlock("v5-bd-daily-reading-video.qznovelvod.com"))
        assertFalse(SniRules.shouldBlock("v13-reading-videocdn.qznovelvod.com"))
        assertFalse(SniRules.shouldBlock("v26-reading-videocdn302.qznovelvod.com"))
        assertFalse(SniRules.shouldBlock("v95-hzyy-thr-daily-reading-videocdn.qznovelvod.com"))
    }

    @Test
    fun 正片优先于广告特征() {
        // 同时带 reading-ad 和 reading-video 的异常组合，正片优先放行
        assertFalse(SniRules.shouldBlock("v5-reading-ad-reading-video.qznovelvod.com"))
    }

    @Test
    fun 接口与日志域名放行() {
        assertFalse(SniRules.shouldBlock("api3-normal-sinfonlinea.fqnovel.com"))
        assertFalse(SniRules.shouldBlock("imapi-oth.zijieapi.com"))
        assertFalse(SniRules.shouldBlock("mon11-misc-hl.fqnovel.com"))
        assertFalse(SniRules.shouldBlock("log5-applog-hl.fqnovel.com"))
        assertFalse(SniRules.shouldBlock("tnc0-aliec2.zijieapi.com"))
        assertFalse(SniRules.shouldBlock("webcast5-open-hl.douyin.com"))
    }

    @Test
    fun 穿山甲广告签名被拦() {
        assertTrue(SniRules.shouldBlock("lf26-mgsdk-sign.dailygn.com"))
    }

    @Test
    fun 主流广告SDK域名被拦() {
        assertTrue(SniRules.shouldBlock("v2.gdt.qq.com"))
        assertTrue(SniRules.shouldBlock("qzs.gdtimg.com"))
        assertTrue(SniRules.shouldBlock("adservice.sigmob.cn"))
        assertTrue(SniRules.shouldBlock("cdn.mintegral.com"))
        assertTrue(SniRules.shouldBlock("ads.doubleclick.net"))
        assertTrue(SniRules.shouldBlock("api.applovin.com"))
        assertTrue(SniRules.shouldBlock("sdk.ironsrc.com"))
        assertTrue(SniRules.shouldBlock("mobads.baidu.com"))
        assertTrue(SniRules.shouldBlock("acdn.tanx.com"))
        assertTrue(SniRules.shouldBlock("open-api.pangleglobal.com"))
    }

    @Test
    fun 广告接口机房变体全部拦() {
        assertTrue(SniRules.shouldBlock("ads.zijieapi.com"))
        assertTrue(SniRules.shouldBlock("ads3-normal-hl.zijieapi.com"))
        assertTrue(SniRules.shouldBlock("ads3-normal-lf.zijieapi.com"))
        assertTrue(SniRules.shouldBlock("ads3-normal-lq.zijieapi.com"))
        assertTrue(SniRules.shouldBlock("ads3-normal.zijieapi.com"))
        assertTrue(SniRules.shouldBlock("ads5-normal-lf.zijieapi.com"))
    }

    @Test
    fun 巨量引擎域名被拦() {
        assertTrue(SniRules.shouldBlock("ad.oceanengine.com"))
        assertTrue(SniRules.shouldBlock("leads.oceanengine.com"))
        assertTrue(SniRules.shouldBlock("clue.oceanengine.com"))
    }

    @Test
    fun 阅读图片与私有视频域名放行() {
        assertFalse(SniRules.shouldBlock("p3-reading.byteimg.com"))
        assertFalse(SniRules.shouldBlock("p9-reading.byteimg.com"))
        assertFalse(SniRules.shouldBlock("p6-novel.byteimg.com"))
        assertFalse(SniRules.shouldBlock("v0-video-cn-private.qznovelvod.com"))
        assertFalse(SniRules.shouldBlock("lf3-reading.fqnovelstatic.com"))
    }

    @Test
    fun 混用大域不被误伤() {
        // 这些域名同时承载图片/接口/正常业务，不能拦
        assertFalse(SniRules.shouldBlock("img.gtimg.cn"))
        assertFalse(SniRules.shouldBlock("qq.com"))
        assertFalse(SniRules.shouldBlock("www.baidu.com"))
        assertFalse(SniRules.shouldBlock("p3-novel.byteimg.com"))
        assertFalse(SniRules.shouldBlock("lf3-reading.fqnovelpic.com"))
        assertFalse(SniRules.shouldBlock("aweme.snssdk.com"))
    }

    @Test
    fun 直播CDN默认不拦() {
        assertFalse(SniRules.shouldBlock("pull-flv-t11.douyincdn.com"))
        assertFalse(SniRules.shouldBlock("pull-x2-h5-hls.douyincdn.com"))
        assertFalse(SniRules.shouldBlock("pull-tsl-f26.douyinliving.com"))
        assertFalse(SniRules.shouldBlock("pull-hls-hs-f5-enc.flive.douyincdn.com"))
    }

    @Test
    fun 打开开关后直播CDN被拦且不影响正片() {
        assertTrue(SniRules.shouldBlock("pull-flv-t11.douyincdn.com", true))
        assertTrue(SniRules.shouldBlock("pull-x2-h5-hls.douyincdn.com", true))
        assertTrue(SniRules.shouldBlock("pull-tsl-f26.douyinliving.com", true))
        assertTrue(SniRules.shouldBlock("pull-hls-hs-f5-enc.flive.douyincdn.com", true))
        assertTrue(SniRules.shouldBlock("pull-rtmp-l1-spe.ixigua.com", true))
        assertTrue(SniRules.shouldBlock("pull-flv-l1.ixigua.com", true))
        assertTrue(SniRules.shouldBlock("pull-hs-f5-xg.flive.ixigua.com", true))
        assertTrue(SniRules.shouldBlock("v5-se2-bjtc-reading-ad.qznovelvod.com", true))
        assertFalse(SniRules.shouldBlock("v26-reading-videocdn302.qznovelvod.com", true))
        assertFalse(SniRules.shouldBlock("www.ixigua.com", true))
        assertFalse(SniRules.shouldBlock("api.ixigua.com", true))
        assertFalse(SniRules.shouldBlock("v5-reading-video-c.qznovelvod.com", true))
    }

    @Test
    fun 空域名不拦() {
        assertFalse(SniRules.shouldBlock(""))
        assertFalse(SniRules.shouldBlock("   "))
    }
}
