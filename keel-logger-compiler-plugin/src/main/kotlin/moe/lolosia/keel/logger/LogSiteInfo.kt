package moe.lolosia.keel.logger

/** 一次调用点解析结果，对应 `Log.of(...)` 的七个实参。 */
internal class LogSiteInfo(
    val displayName: String,
    val file: String,
    val line: Int,
    val packageName: String?,
    val className: String?,
    val functionName: String?,
    val kind: LoggerKind,
)
