package moe.lolosia.keel.api

import moe.lolosia.keel.api.log.*

/**
 * 统一日志门面：Host、插件与库代码都通过它输出日志。
 *
 * 调用方通常只写 `Log.info(...)`；构建期的 keel-logger 编译器插件会把它改写成
 * `Log.of(displayName, file, line, packageName, className, functionName, kind).info(...)`，
 * 把调用点来源一并带到 [LogSink]。没有应用编译器插件时，六个级别方法仍然可用，
 * 只是 [LogSink] 收到的是 `site = null`。
 */
object Log {

    /** 模块列宽度（Host / 插件名 / 自定义 tag）；默认 15。 */
    var moduleWidth: Int = 15
        private set

    /** 来源列宽度；`displayName:line` 按此宽度缩短并补齐。 */
    var displayNameWidth: Int = 40
        private set

    /** 当前出口；默认写 stdout，Host 与插件 Worker 启动时替换成自己的实现。 */
    var sink: LogSink = StdoutLogSink
        private set

    /** 安装日志出口。只应在进程 / 插件运行时启动阶段调用一次。 */
    fun install(sink: LogSink) {
        this.sink = sink
    }

    /** 调整模块列与来源列宽度。 */
    fun configure(moduleWidth: Int = this.moduleWidth, displayNameWidth: Int = this.displayNameWidth) {
        require(moduleWidth > 3) { "moduleWidth 至少为 4" }
        require(displayNameWidth > 8) { "displayNameWidth 至少为 9" }
        this.moduleWidth = moduleWidth
        this.displayNameWidth = displayNameWidth
    }

    /**
     * 绑定一个调用点。编译器插件生成的调用会填满全部字段；手写时也可用于
     * 需要结构化来源的场景。
     */
    fun of(
        displayName: String,
        file: String,
        line: Int,
        packageName: String?,
        className: String?,
        functionName: String?,
        kind: LogSiteKind,
    ): LogSite = LogSite(displayName, file, line, packageName, className, functionName, kind)

    /**
     * 编译器插件用的重载：枚举在 IR 里构造需要编译器内部 API，所以传 ordinal。
     * 手写代码请用 [LogSiteKind] 重载。
     */
    fun of(
        displayName: String,
        file: String,
        line: Int,
        packageName: String?,
        className: String?,
        functionName: String?,
        kind: Int,
    ): LogSite = LogSite(
        displayName,
        file,
        line,
        packageName,
        className,
        functionName,
        LogSiteKind.entries.getOrElse(kind) { LogSiteKind.TOP_LEVEL_FUNCTION },
    )

    fun trace(message: String, tag: String? = null) = emit(LogLevel.TRACE, null, tag, message, null)

    fun debug(message: String, tag: String? = null) = emit(LogLevel.DEBUG, null, tag, message, null)

    fun info(message: String, tag: String? = null) = emit(LogLevel.INFO, null, tag, message, null)

    fun warn(message: String, tag: String? = null) = emit(LogLevel.WARN, null, tag, message, null)

    fun error(message: String, tag: String? = null, cause: Throwable? = null) =
        emit(LogLevel.ERROR, null, tag, message, cause)

    fun fatal(message: String, tag: String? = null, cause: Throwable? = null) =
        emit(LogLevel.FATAL, null, tag, message, cause)

    /** 低层入口：直接按级别与来源分发；Host 转发插件日志时也走这里。 */
    fun emit(
        level: LogLevel,
        site: LogSite?,
        tag: String?,
        message: String,
        cause: Throwable? = null,
    ) = sink.log(level, site, tag, message, cause)
}
