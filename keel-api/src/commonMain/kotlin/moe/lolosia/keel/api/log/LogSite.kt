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

/** 按 Spring `%logger{40}` 的方式缩短：包段取首字母，仍超长则从左丢段并加 `…`。 */
private fun shorten(name: String, max: Int): String {
    if (name.length <= max) return name
    val parts = name.split('.')
    if (parts.size == 1) return name.take(max)

    val simple = parts.last()
    if (simple.length > max) return simple.take(max)

    val abbreviated = parts.dropLast(1).map { it.take(1) }.toMutableList()
    var dropped = false
    while (abbreviated.isNotEmpty()) {
        val prefix = if (dropped) "…." else ""
        val candidate = prefix + (abbreviated + simple).joinToString(".")
        if (candidate.length <= max) return candidate
        abbreviated.removeAt(0)
        dropped = true
    }
    return if (dropped) "…$simple" else simple
}
