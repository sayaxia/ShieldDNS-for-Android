package com.shielddns.android

/**
 * 自定义规则语法体检：告诉用户哪几行能生效、哪几行会被丢弃。
 *
 * 只做诊断，不改变 [RuleEngine] 的解析行为 —— 引擎怎么吃，这里就怎么判，
 * 避免出现「检查说没问题、实际不生效」的错位。
 */
object RuleLint {

    data class Issue(
        /** 1 起的行号，跟编辑器里看到的行号一致 */
        val line: Int,
        val source: String,
        val reason: String,
        /** true = 整行不会生效；false = 能生效但有内容被忽略 */
        val fatal: Boolean
    )

    data class Result(val valid: Int, val ignored: Int, val issues: List<Issue>) {
        val fatalCount: Int get() = issues.count { it.fatal }
        val warnCount: Int get() = issues.count { !it.fatal }
        val total: Int get() = valid
    }

    fun check(text: String): Result {
        if (text.isBlank()) return Result(0, 0, emptyList())
        var valid = 0
        var ignored = 0
        val issues = ArrayList<Issue>()
        text.lineSequence().forEachIndexed { idx, raw ->
            val line = raw.trim()
            val no = idx + 1
            when {
                line.isEmpty() -> Unit
                // 外观过滤规则（example.com##.ad），拦截层不处理
                line.contains("##") || line.contains("#@#") || line.contains("#?#") ->
                    issues.add(Issue(no, line, "外观样式规则，DNS/SNI 层不支持", true))
                line.startsWith("!") || line.startsWith("#") -> ignored++
                // 分段标记（[dns] / [sni]）只有内置规则文件支持
                line.startsWith("[") ->
                    issues.add(Issue(no, line, "分段标记 [dns]/[sni] 只用于内置规则，自定义里无效", true))
                line.startsWith("/") ->
                    issues.add(Issue(no, line, "正则规则不支持，请改写成域名或通配", true))
                else -> {
                    val domain = extractDomain(line)
                    if (domain == null) {
                        issues.add(Issue(no, line, reasonFor(line), true))
                    } else {
                        valid++
                        if (line.contains('$')) {
                            issues.add(Issue(no, line, "$ 之后的选项会被忽略，只按域名拦", false))
                        }
                    }
                }
            }
        }
        return Result(valid, ignored, issues)
    }

    /** 与 RuleEngine.parseInto 的剥离顺序保持一致。 */
    private fun extractDomain(raw: String): String? {
        var line = raw
        if (line.startsWith("@@")) line = line.substring(2).trim()
        if (line.isEmpty()) return null
        val dollar = line.indexOf('$')
        if (dollar > 0) line = line.substring(0, dollar)
        if (line.startsWith("||")) line = line.substring(2)
        if (line.startsWith("|")) line = line.substring(1)
        if (line.endsWith("^")) line = line.dropLast(1)
        if (line.endsWith("|")) line = line.dropLast(1)
        // hosts 格式
        val hosts = line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")
        if (hosts) line = line.substringAfter(' ').trim()
        if (line.isEmpty()) return null

        if (line.contains("*")) {
            // 通配：引擎会截到第一个 / 或 : 之前，只校验域名骨架
            if (line.contains('/')) line = line.substringBefore('/')
            if (line.contains(':')) line = line.substringBefore(':')
            // * 可以在开头（||*-reading-ad.example.com^），只要求整体是域名骨架
            val ok = line.length in 3..200 && line.contains('.') &&
                line.count { it.isLetterOrDigit() } >= 2 &&
                line.all { it.isLetterOrDigit() || it == '.' || it == '*' || it == '-' || it == '_' }
            return if (ok) line.lowercase() else null
        }

        if (line.contains('/')) line = line.substringBefore('/')
        if (line.contains(':')) line = line.substringBefore(':')
        val scheme = line.indexOf("://")
        if (scheme > 0) line = line.substring(scheme + 3)
        if (line.startsWith("www.")) line = line.removePrefix("www.")
        line = line.lowercase()
        return if (isDomainLike(line)) line else null
    }

    private fun isDomainLike(s: String): Boolean {
        if (s.length < 3 || s.length > 255) return false
        if (!s[0].isLetterOrDigit()) return false
        var dots = 0
        for (c in s) {
            when {
                c == '.' -> dots++
                c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' -> Unit
                else -> return false
            }
        }
        return dots > 0
    }

    private fun reasonFor(line: String): String {
        val body = line.removePrefix("@@").trim()
        return when {
            body.isEmpty() -> "只有 @@，后面没跟域名"
            body.length > 255 -> "规则太长（超过 255 字符）"
            !body.contains('.') -> "缺少域名，至少要有 a.b 形式"
            body.all { it == '*' || it == '.' } -> "只有通配符，没有域名"
            body.any { it.isWhitespace() } -> "一行里出现空格，只能写一条规则"
            else -> "无法解析出域名"
        }
    }
}
