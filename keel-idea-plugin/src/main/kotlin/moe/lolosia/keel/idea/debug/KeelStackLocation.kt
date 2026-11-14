package moe.lolosia.keel.idea.debug

/**
 * 一个栈位置的纯数据形态。
 *
 * 字段与 `LogSite` 对齐，展示文本由 IDE 侧按同一套规则拼，
 * 协议里不传拼好的字符串。
 */
class KeelStackLocation(
    val module: String,
    val file: String,
    val line: Int,
    val owner: String,
    val function: String?,
) {

    /** 与 `LogSite.traceLocation()` 同形：`包.类.函数`。 */
    val displayName: String
        get() = if (function.isNullOrBlank()) owner else "$owner.$function"

    /** 与日志帧一致的 `File.kt:行`。 */
    val fileLabel: String
        get() = file.substringAfterLast('/').substringAfterLast('\\') + ":" + line
}
