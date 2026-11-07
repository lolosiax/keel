package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.log.LogLevel
import moe.lolosia.keel.api.log.LogSiteCodec

/** 把 `KEEL_EVENT_LOG` 的载荷还原成 Host 日志。 */
internal fun handleLogEvent(event: HostEvent) {
    val payload = event.payload?.decodeToString() ?: return
    val level = levelOf(event.status)
    val decoded = LogSiteCodec.decode(payload)
    if (decoded != null) {
        Log.emit(level, decoded.site, null, decoded.message)
    } else {
        Log.emit(level, null, PluginNames.of(event.source), payload)
    }
}

private fun levelOf(status: Int): LogLevel = when (status) {
    KEEL_LOG_TRACE.toInt() -> LogLevel.TRACE
    KEEL_LOG_DEBUG.toInt() -> LogLevel.DEBUG
    KEEL_LOG_WARN.toInt() -> LogLevel.WARN
    KEEL_LOG_ERROR.toInt() -> LogLevel.ERROR
    KEEL_LOG_FATAL.toInt() -> LogLevel.FATAL
    else -> LogLevel.INFO
}
