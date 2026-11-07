package moe.lolosia.keel.api

/**
 * 插件日志出口。实现由宿主提供，格式与级别跟宿主日志统一。
 */
interface PluginLogger {

    fun info(message: String)

    fun warn(message: String)

    fun error(message: String, cause: Throwable? = null)
}
