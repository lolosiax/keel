package moe.lolosia.keel.logger

/** 一次编译的插件配置。 */
internal class KeelLoggerConfig(
    val workspaceRoot: String?,
    val sourceRoots: List<String>,
    /** 是否给异常构造 / throw 插桩；关闭后只剩日志调用点改写。 */
    val traceExceptions: Boolean,
)
