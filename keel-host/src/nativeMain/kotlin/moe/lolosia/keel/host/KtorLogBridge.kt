package moe.lolosia.keel.host

import io.ktor.util.logging.LogLevel as KtorLevel
import io.ktor.util.logging.Logger
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.log.LogLevel

/**
 * 把 Ktor 自身日志接进 Keel 日志系统。
 *
 * Ktor native 默认用 `KtorSimpleLogger` 直接 `println`（如 `[INFO] (io.ktor.server.Application): ...`），
 * 格式与我们的列不一致；通过自定义 `ApplicationEnvironment.log` 替换掉。
 */
internal object KtorLogBridge : Logger {

    private const val TAG = "Ktor"

    /** Ktor 的环境 logger 名；来源列显示它，替代默认的 `-`。 */
    private const val LOGGER_NAME = "io.ktor.server.Application"

    override val level: KtorLevel = KtorLevel.INFO

    private fun emit(level: LogLevel, message: String, cause: Throwable? = null) {
        // sourceAnsi 传 null：由 LogLineFormatter 按类名着色，只是没有行号
        Log.emitSource(level, TAG, LOGGER_NAME, null, message, cause)
    }

    override fun error(message: String) = emit(LogLevel.ERROR, message)

    override fun error(message: String, cause: Throwable) = emit(LogLevel.ERROR, message, cause)

    override fun warn(message: String) = emit(LogLevel.WARN, message)

    override fun warn(message: String, cause: Throwable) = emit(LogLevel.WARN, message, cause)

    override fun info(message: String) = emit(LogLevel.INFO, message)

    override fun info(message: String, cause: Throwable) = emit(LogLevel.INFO, message, cause)

    override fun debug(message: String) = emit(LogLevel.DEBUG, message)

    override fun debug(message: String, cause: Throwable) = emit(LogLevel.DEBUG, message, cause)

    override fun trace(message: String) = emit(LogLevel.TRACE, message)

    override fun trace(message: String, cause: Throwable) = emit(LogLevel.TRACE, message, cause)
}
