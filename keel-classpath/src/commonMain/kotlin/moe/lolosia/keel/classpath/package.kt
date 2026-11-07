package moe.lolosia.keel.classpath

/** 规范化路径：去前导 `/`、`\` 统一成 `/`、折叠重复斜杠。 */
fun normalizeClasspathPath(path: String): String {
    val p = path.replace('\\', '/')
    val sb = StringBuilder(p.length)
    var lastSlash = false
    for (ch in p) {
        if (ch == '/') {
            if (!lastSlash && sb.isNotEmpty()) sb.append(ch)
            lastSlash = true
        } else {
            sb.append(ch)
            lastSlash = false
        }
    }
    return sb.toString()
}

/** 路径中是否包含 `..` 段（用于拒绝越界）。 */
internal fun hasParentSegment(path: String): Boolean =
    normalizeClasspathPath(path).split('/').any { it == ".." }
