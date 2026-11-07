package moe.lolosia.keel.api.log

import moe.lolosia.keel.api.Log

/**
 * 控制台日志行格式：`时间 级别 模块 来源 : 消息`。
 *
 * 关闭颜色时输出与旧格式完全一致的纯文本；开启时按 keel 配色着色
 * （Spring Boot 基础 + TRACE/DEBUG/FATAL 微调）。
 */
object LogLineFormatter {

    fun format(
        timestamp: String?,
        level: LogLevel,
        module: String,
        source: String,
        message: String,
        cause: Throwable?,
        moduleWidth: Int = Log.moduleWidth,
        sourceWidth: Int = Log.displayNameWidth,
        color: Boolean,
    ): String {
        val text = if (cause == null) message else "$message | ${cause.stackTraceToString()}"
        val levelText = level.name.padEnd(5)
        val moduleText = module.padEnd(moduleWidth)
        val sourceText = source.padEnd(sourceWidth)

        return if (!color) {
            buildString {
                if (timestamp != null) append(timestamp).append(' ')
                append(levelText).append(' ')
                append(moduleText).append(' ')
                append(sourceText).append(" : ").append(text)
            }
        } else {
            buildString {
                // 整段时间保持默认色
                if (timestamp != null) append(timestamp).append(' ')
                append(Ansi.wrap(Ansi.level(level), levelText)).append(' ')
                append(Ansi.wrap(Ansi.moduleStyle(module), moduleText)).append(' ')

                // 类名 cyan；行号与冒号跟时间同色（faint）；分隔冒号保持默认色
                if (source == "-") {
                    append(Ansi.wrap(Ansi.FAINT, "-"))
                    append(" ".repeat((sourceWidth - 1).coerceAtLeast(0)))
                } else {
                    val colon = source.lastIndexOf(':')
                    val name = if (colon > 0) source.substring(0, colon) else source
                    val line = if (colon > 0) source.substring(colon) else ""
                    append(Ansi.wrap(Ansi.LOGGER, name))
                    if (line.isNotEmpty()) append(Ansi.wrap(Ansi.FAINT, line))
                    append(" ".repeat((sourceWidth - source.length).coerceAtLeast(0)))
                }

                append(" : ").append(text)
            }
        }
    }
}
