package moe.lolosia.keel.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
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

    /** 模块列宽度（Host / 插件日志名 / 自定义 tag）；默认 15。 */
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

    /** 历史日志来源；未安装时历史为空、没有单条记录。 */
    private var historySource: LogHistory = NoneLogHistory

    /** 安装历史日志来源。Host 装本地环形缓冲，插件装事件泵实现。 */
    fun installHistory(source: LogHistory) {
        this.historySource = source
    }

    /**
     * 批量拉取文本视图（Host 渲染，格式与控制台一致）。
     *
     * @param startId `-1` 只要订阅之后的新日志；`0` 从最老一条重放；`> 0` 只取 id 更大的
     * @param limit   最多返回多少条
     */
    fun history(
        format: LogTextFormat = LogTextFormat.PLAIN,
        startId: Long = 0,
        limit: Int = Int.MAX_VALUE,
    ): List<Pair<Long, String>> =
        historySource.page(cursorOf(startId), limit, format.toLogFormat())
            .lines.map { it.id to it.text }

    /** 批量拉取结构化记录（RAW 视图）。 */
    fun records(startId: Long = 0, limit: Int = Int.MAX_VALUE): List<Pair<Long, LogRecord>> =
        historySource.page(cursorOf(startId), limit, LogFormat.RAW)
            .records.map { it.id to it }

    /** 取单独一条记录；已被淘汰或尚未分配时返回 null。 */
    fun get(id: Long): LogRecord? = historySource.get(id)

    /** 取单独一条的渲染文本；语义同 [get]。 */
    fun getText(id: Long, format: LogTextFormat = LogTextFormat.PLAIN): String? =
        historySource.get(id)?.let { LogRecordFormat.render(it, format) }

    /**
     * 订阅文本流；先重放 `id > startId` 的历史，之后持续接收新日志。
     *
     * 只能在插件 Worker（或 Host 主线程）收集；下游消费慢时自动背压。
     */
    fun observe(
        format: LogTextFormat = LogTextFormat.PLAIN,
        startId: Long = 0,
    ): Flow<Pair<Long, String>> =
        historySource.observe(cursorOf(startId), format.toLogFormat())
            .transform { page -> page.lines.forEach { emit(it.id to it.text) } }

    /** 订阅结构化记录流（RAW 视图）。 */
    fun observeRecords(startId: Long = 0): Flow<Pair<Long, LogRecord>> =
        historySource.observe(cursorOf(startId), LogFormat.RAW)
            .transform { page -> page.records.forEach { emit(it.id to it) } }

    /** 输出一条不加格式化前缀的原始行（横幅等）。 */
    fun banner(text: String) = sink.logRaw(text)

    /** `-1` 表示「只要新日志」，用游标最大值表达。 */
    private fun cursorOf(startId: Long): Long =
        if (startId < 0) Long.MAX_VALUE else startId

    private fun LogTextFormat.toLogFormat(): LogFormat =
        if (this == LogTextFormat.ANSI) LogFormat.ANSI else LogFormat.PLAIN

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

    /**
     * 调用点占位：`moe.lolosia.keel.logger` 编译期会把调用替换成 [of]，
     * 填入真实的类 / 文件 / 行号；没有应用插件时保持 null。
     *
     * 可以单独取用，不依赖任何日志级别方法：
     *
     * ```
     * val site = Log.site() ?: return
     * println("${site.traceLocation()} ${site.functionName}")
     * ```
     *
     * 返回的是 `Log.site()` 这一行本身的位置；如果放在 `inline` 函数体里，
     * 记录的是 inline 函数里的行号，而不是最终调用方。
     */
    fun site(): LogSite? = null

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

    /**
     * 带自定义来源列的日志（HTTP 中间件用）。
     *
     * [source] 为纯文本用于对齐，[sourceAnsi] 为着色后的同一段；[moduleAnsi] 强制模块列颜色。
     */
    fun emitSource(
        level: LogLevel,
        tag: String,
        source: String,
        sourceAnsi: String?,
        message: String,
        cause: Throwable? = null,
        moduleAnsi: String? = null,
        site: LogSite? = null,
    ) = sink.logWithSource(level, tag, source, sourceAnsi, message, cause, moduleAnsi, site)
}
