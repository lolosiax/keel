package moe.lolosia.keel.api.log

import moe.lolosia.keel.api.Log

/**
 * 默认日志出口：直接写 stdout，不带时间戳、不启用颜色。
 *
 * Host 与插件 Worker 会在启动时换成各自的实现；这里只是保证未安装出口时
 * 日志不会静默丢失。
 */
object StdoutLogSink : LogSink {

    override fun log(level: LogLevel, site: LogSite?, tag: String?, message: String, cause: Throwable?) {
        val module = tag ?: "Host"
        val source = site?.display(Log.displayNameWidth) ?: "-"
        println(
            LogLineFormatter.format(
                null, level, module, source, message, cause,
                color = false,
                printedAt = site?.traceLocation(),
            )
        )
    }
}
