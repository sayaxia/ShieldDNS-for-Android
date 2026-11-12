package com.shielddns.android

/**
 * SNI 层判定。
 *
 * 规则本身已经搬到 assets/default-rules.txt，这里只保留两件不能数据化的事：
 *  1. 直播拉流 CDN 的识别 —— 靠域名形态（pull- 前缀 / flive 子域）判断，与具体域名无关；
 *  2. 例外优先的判定顺序 —— 由 RuleEngine.isBlocked 保证（allow 永远先于 block 生效）。
 *
 * 想改规则去改规则文件，不用动代码；只有要加一种全新的「域名形态」特征时才需要改这里。
 */
object SniRules {

    /**
     * 是否属于直播拉流域名。红果信息流里的「直播间广告」就是直接拉一条直播流，
     * 域名和正常直播功能完全共用，只能整类一起拦 —— 所以由开关控制，默认关。
     */
    fun isLiveCdn(host: String): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if (h.endsWith(".douyinliving.com")) return true
        // 抖音系：pull-flv/pull-hls/pull-tsl/... 与 flive 子域都是直播拉流
        if (h.contains("douyincdn.com")) return h.startsWith("pull-") || h.contains(".flive.")
        // 西瓜系：红果的直播流也会走 ixigua，只认 pull-/flive，不碰普通点播域名
        if (h.contains("ixigua.com")) return h.startsWith("pull-") || h.contains(".flive.")
        return false
    }

    /**
     * @param engine SNI 层规则引擎（内置 dns 段 + 内置 sni 段 + 订阅 + 自定义）
     * @param blockLiveCdn 是否连带拦直播 CDN
     */
    fun shouldBlock(engine: RuleEngine, host: String, blockLiveCdn: Boolean = false): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if (blockLiveCdn && isLiveCdn(h)) return true
        return engine.isBlocked(h)
    }
}
