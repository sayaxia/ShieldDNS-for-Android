package com.shielddns.android

/**
 * 规则引擎：只吃 AdGuard/常见 hosts 列表里真正有用的那部分语法。
 *
 * 支持：
 *   ||example.com^        域名及所有子域拦截
 *   @@||good.example.com^ 例外（放行），优先于拦截
 *   example.com           裸域名
 *   0.0.0.0 example.com   hosts 格式
 *   |http://a.com/b|      前后锚定（取其中的域名部分）
 * 另支持 * 通配（如 ||*-reading-ad.example.com^、||ads*.adcdn.com^）：
 *   含 * 的规则编译成正则匹配，与上面按标签截断的 HashSet 匹配并存。
 * 忽略：注释（! 和 #）、## 外观规则、$options、正则、其它修饰符。
 *
 * 匹配方式：把 QNAME 按标签逐级截断去查 HashSet，一次查询最多几次哈希，十万级规则也很轻。
 */
class RuleEngine {

    @Volatile
    private var blocked: Set<String> = emptySet()

    @Volatile
    private var allowed: Set<String> = emptySet()

    /** 含 * 的规则，编译成正则在精确匹配未命中时兜底。 */
    @Volatile
    private var wildBlocked: List<Regex> = emptyList()

    @Volatile
    private var wildAllowed: List<Regex> = emptyList()

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

    fun rebuild(sources: List<String>, custom: String) {
        val bl = HashSet<String>(1 shl 17)
        val al = HashSet<String>(1 shl 15)
        val wb = ArrayList<Regex>()
        val wa = ArrayList<Regex>()
        sources.forEach { parseInto(it, bl, al, wb, wa) }
        parseInto(custom, bl, al, wb, wa)
        // 整体替换引用，读线程永远看到一份完整的规则表
        blocked = bl
        allowed = al
        wildBlocked = wb.toList()
        wildAllowed = wa.toList()
        blockedCount = bl.size + wb.size
        allowCount = al.size + wa.size
        ruleCount = blockedCount + allowCount
    }

    fun isBlocked(host: String): Boolean {
        if (host.isEmpty()) return false
        val allow = allowed
        val block = blocked
        val wAllow = wildAllowed
        val wBlock = wildBlocked
        if (allow.isEmpty() && block.isEmpty() && wAllow.isEmpty() && wBlock.isEmpty()) return false
        if (matchSuffix(allow, host)) return false
        if (wAllow.isNotEmpty() && wAllow.any { it.matches(host) }) return false
        if (matchSuffix(block, host)) return true
        return wBlock.isNotEmpty() && wBlock.any { it.matches(host) }
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
        // 去掉协议/路径尾巴
        return true
    }
}
