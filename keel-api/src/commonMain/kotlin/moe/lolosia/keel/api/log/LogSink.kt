package moe.lolosia.keel.api.log

/**
 * 日志出口。
 *
 * Host 用带本地时间戳的实现，插件运行时用 [moe.lolosia.keel.api.PluginLogger] 的
 * 事件实现；后续 Lua 日志改写也在这里包一层。
 */
interface LogSink {

    /**
     * @param site 调用点；未经过编译器插件改写时为 null
     * @param tag  显式标签，优先于 [LogSite.displayName]
     */
    fun log(level: LogLevel, site: LogSite?, tag: String?, message: String, cause: Throwable?)

    /**
     * 带自定义来源列的日志（HTTP 中间件用）。
     *
     * [source] 是纯文本（用于对齐），[sourceAnsi] 是着色后的同一段文本；
     * [moduleAnsi] 用于强制模块列颜色（如 HTTP IN / HTTP OUT）。
     *
     * 默认实现忽略自定义格式，退回 [log]。
     */
    fun logWithSource(
        level: LogLevel,
        tag: String?,
        source: String,
        sourceAnsi: String?,
        message: String,
        cause: Throwable? = null,
        moduleAnsi: String? = null,
        site: LogSite? = null,
    ) = log(level, site, tag, message, cause)

    /**
     * 输出一条不加时间 / 级别 / 模块前缀的原始行（横幅、第三方输出）。
     *
     * 默认实现退化成一条 INFO 日志；Host 侧覆盖后原样写入归档与文件。
     */
    fun logRaw(text: String) {
        log(LogLevel.INFO, null, null, text, null)
    }
}
