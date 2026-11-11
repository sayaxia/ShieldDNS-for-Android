package com.shielddns.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** SNI 拦截记录的聚合逻辑：同一域名反复重试只能累加计数，不能把队列刷满。 */
class StatsTest {

    @Test
    fun 阻断记录按域名聚合() {
        Stats.reset()
        repeat(5) { Stats.onSniBlock("ads3-normal-hl.zijieapi.com") }
        Stats.onSniBlock("v95-ynkmtc-reading-ad.qznovelvod.com")
        assertEquals(6, Stats.sniBlockedTotal)
        assertEquals(
            listOf(
                "ads3-normal-hl.zijieapi.com" to 5,
                "v95-ynkmtc-reading-ad.qznovelvod.com" to 1
            ),
            Stats.sniBlockedSnapshot()
        )
    }

    @Test
    fun 单域名狂重试不挤掉其它域名() {
        Stats.reset()
        repeat(500) { Stats.onSniBlock("v95-ynkmtc-reading-ad.qznovelvod.com") }
        Stats.onSniBlock("ads3-normal-hl.zijieapi.com")
        val snap = Stats.sniBlockedSnapshot()
        assertEquals(2, snap.size)
        assertEquals(501, Stats.sniBlockedTotal)
        assertEquals(
            500,
            snap.first { it.first == "v95-ynkmtc-reading-ad.qznovelvod.com" }.second
        )
    }

    @Test
    fun sni命中按域名聚合排序() {
        Stats.reset()
        repeat(3) { Stats.onSni("v5-reading-video-c.qznovelvod.com") }
        Stats.onSni("ads.zijieapi.com")
        val top = Stats.sniTop(5)
        assertEquals("v5-reading-video-c.qznovelvod.com", top[0].first)
        assertEquals(3, top[0].second)
        assertEquals("ads.zijieapi.com", top[1].first)
        assertEquals(1, top[1].second)
    }
}
