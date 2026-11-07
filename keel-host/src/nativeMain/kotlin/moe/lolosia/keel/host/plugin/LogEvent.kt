package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.host.Log

/** 把 `KEEL_EVENT_LOG` 的载荷还原成 Host 日志。 */
internal fun handleLogEvent(event: HostEvent) {
    val message = event.payload?.decodeToString() ?: return
    val tag = PluginNames.of(event.source)
    when (event.status) {
        KEEL_LOG_TRACE.toInt() -> Log.trace(message, tag)
        KEEL_LOG_DEBUG.toInt() -> Log.debug(message, tag)
        KEEL_LOG_WARN.toInt() -> Log.warn(message, tag)
        KEEL_LOG_ERROR.toInt() -> Log.error(message, tag)
        KEEL_LOG_FATAL.toInt() -> Log.fatal(message, tag)
        else -> Log.info(message, tag)
    }
}
