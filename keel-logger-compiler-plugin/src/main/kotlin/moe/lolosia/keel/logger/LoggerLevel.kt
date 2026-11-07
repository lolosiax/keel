package moe.lolosia.keel.logger

/** 需要改写的日志级别；名称与 `moe.lolosia.keel.api.Log` / `LogSite` 的方法名一致。 */
internal enum class LoggerLevel(val functionName: String) {
    TRACE("trace"),
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error"),
    FATAL("fatal"),
}
