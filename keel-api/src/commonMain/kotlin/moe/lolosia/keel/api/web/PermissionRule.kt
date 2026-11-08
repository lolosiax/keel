package moe.lolosia.keel.api.web

/**
 * 一条已解析的权限规则。
 *
 * @property pattern 小写、已去掉 `+` / `-` 的规则串
 * @property allow false 表示 `-` 规则
 */
data class PermissionRule(
    val pattern: String,
    val allow: Boolean,
) {
    /** 规范化原文（带符号），用于展示与回写。 */
    val text: String get() = if (allow) "+$pattern" else "-$pattern"
}
