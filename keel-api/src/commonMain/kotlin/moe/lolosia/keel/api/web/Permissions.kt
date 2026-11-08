package moe.lolosia.keel.api.web

/**
 * 权限规则的解析与判定 —— **纯函数，无 IO**。
 *
 * 规则串语法：
 * - `*`：匹配一切；
 * - `a.b.c`：精确匹配同名节点；
 * - `a.b.*`：匹配 `a.b` 自身及其后代；通配只能出现在末尾整段。
 *
 * 判定口径与 Bukkit 一致：命中的规则里**负号优先**，一条都没命中即**拒绝**。
 * `a.b` 不蕴含 `a.b.c`。
 */
object Permissions {

    /** 单个节点段；节点整体为 `段(.段)*`。 */
    private const val SEGMENT = "[a-z0-9][a-z0-9_-]*"

    /** 合法模式：`*` 或 `段(.段)*(.*)?`。 */
    private val PATTERN = Regex(
        "^(?:\\*|$SEGMENT(?:\\.$SEGMENT)*(?:\\.\\*)?)$",
        RegexOption.IGNORE_CASE,
    )

    /** 解析规则原文（`+a.b.*` / `-a.b.c` / `a.b.c`）；语法非法返回 null。 */
    fun parse(raw: String): PermissionRule? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val allow = text[0] != '-'
        val body = when (text[0]) {
            '+', '-' -> text.substring(1)
            else -> text
        }.trim().lowercase()
        if (!PATTERN.matches(body)) return null
        return PermissionRule(body, allow)
    }

    /** 校验并规范化原文（统一小写、补全符号）；非法返回 null。 */
    fun canonical(raw: String): String? = parse(raw)?.text

    /** 合并「用户直授」与「组」两个来源；顺序只影响展示。 */
    fun merge(group: List<String>, direct: List<String>): List<PermissionRule> =
        (direct + group).mapNotNull { parse(it) }

    /** 单条规则是否命中节点。 */
    fun matches(rule: PermissionRule, node: String): Boolean {
        val pattern = rule.pattern
        val target = node.trim().lowercase()
        if (pattern == "*") return true
        if (pattern == target) return true
        if (!pattern.endsWith(".*")) return false
        val prefix = pattern.dropLast(2)
        return target == prefix || target.startsWith("$prefix.")
    }

    /** 一组规则（已解析）是否放行节点：未命中拒绝，命中则负号优先。 */
    fun granted(rules: List<PermissionRule>, node: String): Boolean {
        val hit = rules.filter { matches(it, node) }
        if (hit.isEmpty()) return false
        return hit.none { !it.allow }
    }

    /** 一组规则原文是否放行节点；坏数据按不命中处理。 */
    fun granted(rules: List<String>, node: String): Boolean =
        granted(rules.mapNotNull { parse(it) }, node)
}
