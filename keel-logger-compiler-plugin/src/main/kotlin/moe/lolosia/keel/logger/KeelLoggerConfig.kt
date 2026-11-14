package moe.lolosia.keel.logger

/** 一次编译的插件配置。 */
internal class KeelLoggerConfig(
    val workspaceRoot: String?,
    val sourceRoots: List<String>,
    /** 是否给异常构造 / throw 插桩；关闭后只剩日志调用点改写。 */
    val traceExceptions: Boolean,
    /** 是否插入调试检查点并写出位置表。 */
    val debug: Boolean,
    /** 位置表输出路径（`<compilation>.keel-debug`）；为空表示不写。 */
    val debugOutput: String?,
    /** 位置表首行的模块 id，与运行期 `HELLO` 报的一致。 */
    val debugModule: String,
    /** 位置表首行的 buildId，供 IDE 校验版本是否对齐。 */
    val debugBuildId: String,
)
