package moe.lolosia.keel.host

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.log.*

/**
 * Host 日志出口。
 *
 * 输出格式：`yyyy-MM-dd HH:mm:ss.SSS 级别 模块 : 消息`。
 * 带调用点时模块列为 `短类名:行号`，按 [Log.displayNameWidth] 缩短并补齐；
 * 没有调用点（未改写或显式 tag）时使用 tag，默认 `host`。
 */
object HostLogSink : LogSink {

    override fun log(level: LogLevel, site: LogSite?, tag: String?, message: String, cause: Throwable?) {
        val width = Log.displayNameWidth
        val module = site?.display(width) ?: (tag ?: "host")
        val text = if (cause == null) message else "$message | ${cause.stackTraceToString()}"
        println("${formatLocalTimestamp()} ${level.name.padEnd(5)} ${module.padEnd(width)} : $text")
    }
}
