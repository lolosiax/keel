package moe.lolosia.keel.api

/**
 * 插件日志出口（兼容入口）。实现由宿主提供，格式与级别跟宿主日志统一。
 *
 * 新代码请直接用 [Log]：只有经过 `moe.lolosia.keel.logger` 改写的调用才会带上
 * 文件 / 行号 / 类 / 函数等来源信息；`ctx.logger` 没有这些字段。
 */
interface PluginLogger {

    fun trace(message: String)

    fun debug(message: String)

    fun info(message: String)

    fun warn(message: String)

    fun error(message: String, cause: Throwable? = null)

    fun fatal(message: String, cause: Throwable? = null)
}
