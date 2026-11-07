package moe.lolosia.keel.api.worker

import kotlinx.cinterop.*
import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.PluginLogger
import moe.lolosia.keel.api.log.LogLevel
import moe.lolosia.keel.api.log.LogSite
import moe.lolosia.keel.api.log.LogSiteCodec
import moe.lolosia.keel.api.log.LogSink

/**
 * 插件侧日志出口：把日志作为 `KEEL_EVENT_LOG` 投递到 Host 收件箱。
 *
 * 带调用点时先把 [LogSite] 编码进载荷，Host 解析后按类名与行号展示；
 * 纯文本载荷保持旧格式。载荷由本二进制的 CRT 分配，Host 复制后调用 release 归还，
 * 日志字符串不会以 Kotlin 对象的形式跨运行时。
 */
internal class NativePluginLogger(
    private val host: CPointer<KeelHostApi>?,
    private val pluginId: ULong,
) : PluginLogger, LogSink {

    override fun trace(message: String) = send(KEEL_LOG_TRACE.toInt(), message)

    override fun debug(message: String) = send(KEEL_LOG_DEBUG.toInt(), message)

    override fun info(message: String) = send(KEEL_LOG_INFO.toInt(), message)

    override fun warn(message: String) = send(KEEL_LOG_WARN.toInt(), message)

    override fun error(message: String, cause: Throwable?) =
        send(KEEL_LOG_ERROR.toInt(), withCause(message, cause))

    override fun fatal(message: String, cause: Throwable?) =
        send(KEEL_LOG_FATAL.toInt(), withCause(message, cause))

    override fun log(level: LogLevel, site: LogSite?, tag: String?, message: String, cause: Throwable?) {
        val text = withCause(message, cause)
        val payload = if (site == null) text else LogSiteCodec.encode(site, tag, text)
        send(levelOf(level), payload)
    }

    private fun levelOf(level: LogLevel): Int = when (level) {
        LogLevel.TRACE -> KEEL_LOG_TRACE.toInt()
        LogLevel.DEBUG -> KEEL_LOG_DEBUG.toInt()
        LogLevel.INFO -> KEEL_LOG_INFO.toInt()
        LogLevel.WARN -> KEEL_LOG_WARN.toInt()
        LogLevel.ERROR -> KEEL_LOG_ERROR.toInt()
        LogLevel.FATAL -> KEEL_LOG_FATAL.toInt()
    }

    private fun withCause(message: String, cause: Throwable?): String =
        if (cause == null) message else "$message\n${cause.stackTraceToString()}"

    private fun send(level: Int, message: String) {
        val hostInbox = host?.pointed?.host_inbox ?: return
        val bytes = message.encodeToByteArray()
        val payload = keel_payload_alloc(bytes.size.toUInt()) ?: return
        if (bytes.isNotEmpty()) {
            bytes.usePinned { keel_payload_copy(payload, it.addressOf(0), bytes.size.toUInt()) }
        }

        memScoped {
            val event = alloc<KeelEvent>()
            event.size = sizeOf<KeelEvent>().toUInt()
            event.kind = KEEL_EVENT_LOG.toUShort()
            event.source = pluginId
            event.target = 0u
            event.payload_size = bytes.size.toUInt()
            event.status = level
            event.payload = payload
            keel_event_set_default_release(event.ptr)
            if (keel_queue_push(hostInbox, event.ptr, 0u) != KEEL_OK) {
                keel_event_release_default(null, event.ptr)
            }
        }
    }
}
