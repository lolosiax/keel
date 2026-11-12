package moe.lolosia.keel.api.log

import moe.lolosia.keel.api.Log

/**
 * 把 [LogRecord] 渲染成控制台文本。
 *
 * Host 用它生成 Console、日志文件与插件拿到的文本，三条路径必须逐字节一致。
 */
object LogRecordFormat {

    fun render(record: LogRecord, format: LogTextFormat): String {
        // 横幅是原样输出的 ASCII 画，任何文本视图都不加前缀
        if (record.banner) return record.message

        val source = record.source ?: record.site?.display(Log.displayNameWidth) ?: "-"
        val message = if (record.error == null) {
            record.message
        } else {
            "${record.message}\n${record.error.text}"
        }
        val color = format == LogTextFormat.ANSI

        return LogLineFormatter.format(
            timestamp = record.time,
            level = record.level,
            module = record.module,
            source = source,
            message = message,
            cause = null,
            color = color,
            moduleAnsi = if (color) record.moduleAnsi else null,
            sourceAnsi = if (color) record.sourceAnsi else null,
        )
    }
}
