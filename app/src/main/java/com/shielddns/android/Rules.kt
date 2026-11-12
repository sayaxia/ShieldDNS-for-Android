package com.shielddns.android

/**
 * 规则引擎：只吃 AdGuard/常见 hosts 列表里真正有用的那部分语法。
 *
 * 支持：
 *   ||example.com^        域名及所有子域拦截
 *   @@||good.example.com^ 例外（放行）
 *   example.com           裸域名
 *   0.0.0.0 example.com   hosts 格式
 *   |http://a.com/b|      前后锚定（取其中的域名部分）
 * 另支持 * 通配（如 ||*-reading-ad.example.com^、||ads*.adcdn.com^）：
 *   含 * 的规则编译成正则匹配，与上面按标签截断的 HashSet 匹配并存。
 * 忽略：注释（! 和 #）、## 外观规则、$options、正则、其它修饰符。
 *
 * 分层：规则按来源分成若干层，从高优先级到低优先级判定。
 * 同一层内例外永远先于拦截；层与层之间，高优先级层先给结论 ——
 * 也就是「自定义规则 > 关键域名放行 > 内置策略 > 订阅（列表越靠上越高）」。
 * 内置策略在「过滤订阅」页是置顶且不可拖动的，所以它在引擎里也真的排在订阅之前，
 * 任何订阅都压不过它；订阅之间仍然按拖动顺序决定优先级。
 *
 * 匹配方式：把 QNAME 按标签逐级截断去查 HashSet，一次查询最多几次哈希，十万级规则也很轻。
 */
class RuleEngine {

    private class Layer(
        val blocked: Set<String>,
        val allowed: Set<String>,
        val wildBlocked: List<Regex>,
        val wildAllowed: List<Regex>
    )

    /** 从高优先级到低优先级排列。 */
    @Volatile
    private var layers: List<Layer> = emptyList()

    /** [CriticalHosts] 解析出来的放行层，只解析一次。 */
    @Volatile
    private var criticalLayer: Layer? = null

    private val maxWild = 2000

    @Volatile
    var ruleCount: Int = 0
        private set

    @Volatile
    var blockedCount: Int = 0
        private set

    @Volatile
    var allowCount: Int = 0
        private set

    /**
     * @param sources 订阅层，顺序即优先级（第一个最高）
     * @param custom  自定义规则，优先级最高
     * @param fallback 内置策略，页面上置顶且不可拖动，因此引擎里排在所有订阅之前
     *
     * 层序：自定义 → 关键域名放行（见 [CriticalHosts]）→ 内置 → 订阅。
     * 关键域名那层只有例外，作用是把所有来源的误伤挡下来；用户自定义仍能覆盖它。
     * 它属于内置兜底、不是用户可管理的规则，所以不计入 [ruleCount]。
     */
    fun rebuild(
        sources: List<String>,
        custom: String = "",
        fallback: List<String> = emptyList()
    ) {
        val texts = ArrayList<String>(sources.size + fallback.size + 1)
        if (custom.isNotBlank()) texts.add(custom)
        texts.addAll(fallback)
        texts.addAll(sources)
        val built = texts.filter { it.isNotBlank() }.map { parseLayer(it) }

        val critical = criticalLayer ?: parseLayer(CriticalHosts.TEXT).also { criticalLayer = it }
        val ordered = ArrayList<Layer>(built.size + 1)
        if (custom.isNotBlank() && built.isNotEmpty()) {
            ordered.add(built[0])
            ordered.add(critical)
            ordered.addAll(built.subList(1, built.size))
        } else {
            ordered.add(critical)
            ordered.addAll(built)
        }

        // 整体替换引用，读线程永远看到一份完整的规则表
        layers = ordered
        blockedCount = built.sumOf { it.blocked.size + it.wildBlocked.size }
        allowCount = built.sumOf { it.allowed.size + it.wildAllowed.size }
        ruleCount = blockedCount + allowCount
    }

    fun isBlocked(host: String): Boolean {
        if (host.isEmpty()) return false
        val ls = layers
        if (ls.isEmpty()) return false
        for (l in ls) {
            if (l.allowed.isNotEmpty() && matchSuffix(l.allowed, host)) return false
            if (l.wildAllowed.isNotEmpty() && l.wildAllowed.any { it.matches(host) }) return false
            if (l.blocked.isNotEmpty() && matchSuffix(l.blocked, host)) return true
            if (l.wildBlocked.isNotEmpty() && l.wildBlocked.any { it.matches(host) }) return true
        }
        return false
    }

    private fun parseLayer(text: String): Layer {
        val bl = HashSet<String>()
        val al = HashSet<String>()
        val wb = ArrayList<Regex>()
        val wa = ArrayList<Regex>()
        parseInto(text, bl, al, wb, wa)
        return Layer(bl, al, wb, wa)
    }

    private fun matchSuffix(set: Set<String>, host: String): Boolean {
        var i = 0
        while (true) {
            if (set.contains(host.substring(i))) return true
            val dot = host.indexOf('.', i)
            if (dot < 0) return false
            i = dot + 1
            if (i >= host.length) return false
        }
    }

    private fun parseInto(
        text: String,
        bl: HashSet<String>,
        al: HashSet<String>,
        wildBlock: MutableList<Regex>,
        wildAllow: MutableList<Regex>
    ) {
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("!") || line.startsWith("#")) return@forEach
            if (line.contains("##") || line.contains("#@#") || line.contains("#?#")) return@forEach

            var rule = line
            var exception = false
            if (rule.startsWith("@@")) {
                exception = true
                rule = rule.substring(2).trim()
            }
            if (rule.isEmpty()) return@forEach
            // $options 之后的部分无关
            val dollar = rule.indexOf('$')
            if (dollar > 0) rule = rule.substring(0, dollar)
            if (rule.startsWith("@@")) return@forEach
            if (rule.startsWith("/")) return@forEach
            if (rule.contains("[")) return@forEach
            if (rule.startsWith("||")) rule = rule.substring(2)
            if (rule.startsWith("|")) rule = rule.substring(1)
            if (rule.endsWith("^")) rule = rule.dropLast(1)
            if (rule.endsWith("|")) rule = rule.dropLast(1)
            if (rule.contains("*")) {
                // 通配规则：路径/端口截掉，只保留域名部分
                if (rule.contains('/')) rule = rule.substringBefore('/')
                if (rule.contains(':')) rule = rule.substringBefore(':')
                val target = if (exception) wildAllow else wildBlock
                if (target.size < maxWild) {
                    wildcardToRegex(rule)?.let { target.add(it) }
                }
                return@forEach
            }

            // hosts 格式：0.0.0.0 domain / 127.0.0.1 domain
            if (rule.startsWith("0.0.0.0 ") || rule.startsWith("127.0.0.1 ")) {
                rule = rule.substringAfter(' ').trim()
            }

            rule = rule.lowercase()
            val scheme = rule.indexOf("://")
            if (scheme > 0) rule = rule.substring(scheme + 3)
            if (rule.contains('/')) rule = rule.substringBefore('/')
            if (rule.contains(':')) rule = rule.substringBefore(':')
            if (rule.startsWith("www.")) rule = rule.removePrefix("www.")
            if (!isDomainLike(rule)) return@forEach
            if (exception) al.add(rule) else bl.add(rule)
        }
    }

    /**
     * 把 adblock 风格的域名通配转成正则：
     * * 匹配任意标签序列，其余字符按字面处理。
     * ||*-reading-ad.example.com^ -> ^[a-z0-9._-]*\-reading\-ad\.example\.com$
     */
    private fun wildcardToRegex(rule: String): Regex? {
        if (rule.length > 200) return null
        val sb = StringBuilder("^")
        for (c in rule) {
            when {
                c == '*' -> sb.append("[a-z0-9._-]*")
                c == '.' -> sb.append("\\.")
                else -> if (c.isLetterOrDigit()) sb.append(c) else sb.append(Regex.escape(c.toString()))
            }
        }
        sb.append('$')
        return runCatching { Regex(sb.toString(), RegexOption.IGNORE_CASE) }.getOrNull()
    }

    private fun isDomainLike(s: String): Boolean {
        if (s.length < 3 || s.length > 255) return false
        if (!s[0].isLetterOrDigit()) return false
        var dots = 0
        for (c in s) {
            when {
                c == '.' -> dots++
                c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == ':' || c == '/' -> {
                }
                else -> return false
            }
        }
        if (dots == 0) return false
        return true
    }
}
