package moe.lolosia.keel.host

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.log.*

/**
 * Host 日志出口。
 *
 * 输出格式：`HH:mm:ss 级别 模块 来源 : 消息`。
 *
 * 模块列为 Host / 插件名 / 自定义 tag，按 [Log.moduleWidth] 补齐；
 * 来源列为 `短类名:行号`，按 [Log.displayNameWidth] 缩短并补齐；
 * 没有调用点（未改写）时来源列显示 `-`。
 *
 * 终端支持 ANSI 时按 Spring Boot 风格着色：时间 faint、级别按级别取色、
 * 模块名按哈希取色、来源列 cyan；重定向到文件或设置 `NO_COLOR` 时自动关闭。
 */
object HostLogSink : LogSink {

    private val useColor = ansiSupported()

    override fun log(level: LogLevel, site: LogSite?, tag: String?, message: String, cause: Throwable?) {
        val module = tag ?: "Host"
        val source = site?.display(Log.displayNameWidth) ?: "-"
        val console = LogLineFormatter.format(
            timestamp = formatLocalTimestamp(),
            level = level,
            module = module,
            source = source,
            message = message,
            cause = cause,
            color = useColor,
            printedAt = site?.traceLocation(),
        )
        println(console)
        appendLogFile(
            if (useColor) {
                LogLineFormatter.format(
                    timestamp = formatLocalTimestamp(),
                    level = level,
                    module = module,
                    source = source,
                    message = message,
                    cause = cause,
                    color = false,
                    printedAt = site?.traceLocation(),
                )
            } else {
                console
            } + "\n",
        )
    }

    override fun logWithSource(
        level: LogLevel,
        tag: String?,
        source: String,
        sourceAnsi: String?,
        message: String,
        cause: Throwable?,
        moduleAnsi: String?,
    ) {
        val module = tag ?: "Host"
        val console = LogLineFormatter.format(
            timestamp = formatLocalTimestamp(),
            level = level,
            module = module,
            source = source,
            message = message,
            cause = cause,
            color = useColor,
            moduleAnsi = moduleAnsi,
            sourceAnsi = if (useColor) sourceAnsi else null,
        )
        println(console)
        appendLogFile(
            if (useColor) {
                LogLineFormatter.format(
                    timestamp = formatLocalTimestamp(),
                    level = level,
                    module = module,
                    source = source,
                    message = message,
                    cause = cause,
                    color = false,
                )
            } else {
                console
            } + "\n",
        )
    }
}
