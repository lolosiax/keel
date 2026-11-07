package moe.lolosia.keel.api.log

import moe.lolosia.keel.api.Log

/**
 * 一个日志调用点。
 *
 * 字段由 keel-logger 编译器插件在编译期填好；运行期只做展示与分发。
 *
 * @property displayName 默认展示名：完整类名，或文件门面名（`Foo.kt` → `包.FooKt`）
 * @property file        相对 Gradle 工作区的路径，统一 `/`
 * @property line        1 基行号
 * @property packageName 所在包；默认包为 null
 * @property className   最近的外层类完整名；不在类里为 null
 * @property functionName 最近具名函数；属性初始化器 / init 块为 null
 * @property kind        直接包裹语句的声明类型
 */
class LogSite(
    val displayName: String,
    val file: String,
    val line: Int,
    val packageName: String?,
    val className: String?,
    val functionName: String?,
    val kind: LogSiteKind,
) {

    /**
     * 模块列文本：`短类名:行号`。
     *
     * 名称为空时退化为文件名；长度按 [width] 缩短，给行号与冒号留出位置。
     */
    fun display(width: Int = Log.displayNameWidth): String {
        val lineText = line.toString()
        val budget = (width - lineText.length - 1).coerceAtLeast(1)
        val name = displayName.ifBlank { file }
        return "${shorten(name, budget)}:$lineText"
    }

    fun trace(message: String, tag: String? = null) = Log.emit(LogLevel.TRACE, this, tag, message, null)

    fun debug(message: String, tag: String? = null) = Log.emit(LogLevel.DEBUG, this, tag, message, null)

    fun info(message: String, tag: String? = null) = Log.emit(LogLevel.INFO, this, tag, message, null)

    fun warn(message: String, tag: String? = null) = Log.emit(LogLevel.WARN, this, tag, message, null)

    fun error(message: String, tag: String? = null, cause: Throwable? = null) =
        Log.emit(LogLevel.ERROR, this, tag, message, cause)

    fun fatal(message: String, tag: String? = null, cause: Throwable? = null) =
        Log.emit(LogLevel.FATAL, this, tag, message, cause)
}

/**
 * 把完整类名压缩到 [max] 列。
 *
 * 保留类名本身；包段先各留 1 个字符，再从右往左逐级加长（越靠后的包段允许越长），
 * 直到用满预算。仍放不下时从左丢弃整段并加 `…`；最低每段 1 个字符。
 */
private fun shorten(name: String, max: Int): String {
    if (name.length <= max) return name
    val parts = name.split('.')
    if (parts.size == 1) return name.take(max)

    val simple = parts.last()
    if (simple.length >= max) return simple.take(max)

    val packages = parts.dropLast(1).toMutableList()

    fun joinedLength(segments: Int, ellipsis: Boolean): Int =
        (if (ellipsis) 1 else 0) + segments +
            (segments - 1).coerceAtLeast(0) + (if (segments > 0) 1 else 0) + simple.length

    var dropped = false
    while (packages.isNotEmpty() && joinedLength(packages.size, ellipsis = true) > max) {
        packages.removeAt(0)
        dropped = true
    }
    if (packages.isEmpty()) {
        return when {
            max <= 1 -> simple.take(max.coerceAtLeast(0))
            else -> "…" + simple.take(max - 1)
        }
    }

    val lengths = IntArray(packages.size) { 1 }
    fun currentLength(): Int =
        (if (dropped) 1 else 0) + lengths.sum() + packages.size + simple.length

    var remaining = max - currentLength()
    var cap = 2
    while (remaining > 0) {
        var grew = false
        for (index in packages.indices.reversed()) {
            if (remaining <= 0) break
            if (lengths[index] < packages[index].length && lengths[index] < cap) {
                lengths[index]++
                remaining--
                grew = true
            }
        }
        if (!grew && packages.indices.all { lengths[it] >= packages[it].length }) break
        cap++
    }

    return buildString {
        if (dropped) append('…')
        packages.forEachIndexed { index, segment ->
            if (index > 0) append('.')
            append(segment.take(lengths[index]))
        }
        append('.').append(simple)
    }
}
