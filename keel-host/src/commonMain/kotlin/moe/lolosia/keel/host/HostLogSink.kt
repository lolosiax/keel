package moe.lolosia.keel.host

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.error.toKeelError
import moe.lolosia.keel.api.log.*

/**
 * Host 日志出口。
 *
 * 每条日志先归档成 [LogRecord]，再从同一条记录渲染控制台行与文件行：
 * 于是 Console、`work/log` 下的日志文件与插件通过 [Log.history] 拿到的文本逐字节一致。
 *
 * 模块列为 Host / 插件日志名 / 自定义 tag，按 [Log.moduleWidth] 补齐；
 * 来源列为 `短类名:行号`，按 [Log.displayNameWidth] 缩短并补齐；
 * 没有调用点（未改写）时来源列显示 `-`。
 *
 * 终端支持 ANSI 时按 Spring Boot 风格着色：时间 faint、级别按级别取色、
 * 模块名按哈希取色、来源列 cyan；重定向到文件或设置 `NO_COLOR` 时自动关闭。
 */
object HostLogSink : LogSink {

    /** 桌面应用（GUI 子系统、无控制台）下仍然归档与写文件，只是不往 stdout 打。 */
    private val useConsole = consoleAvailable()

    private val useColor = useConsole && ansiSupported()

    override fun log(level: LogLevel, site: LogSite?, tag: String?, message: String, cause: Throwable?) {
        emit(
            level = level,
            module = tag ?: "Host",
            site = site,
            source = null,
            moduleAnsi = null,
            sourceAnsi = null,
            message = message,
            error = cause?.let { logErrorOf(it, site?.traceLocation()) },
            banner = false,
        )
    }

    override fun logWithSource(
        level: LogLevel,
        tag: String?,
        source: String,
        sourceAnsi: String?,
        message: String,
        cause: Throwable?,
        moduleAnsi: String?,
        site: LogSite?,
    ) {
        emit(
            level = level,
            module = tag ?: "Host",
            site = site,
            source = source,
            moduleAnsi = moduleAnsi,
            sourceAnsi = sourceAnsi,
            message = message,
            error = cause?.let { logErrorOf(it, site?.traceLocation()) },
            banner = false,
        )
    }

    override fun logRaw(text: String) {
        emit(
            level = LogLevel.INFO,
            module = "",
            site = null,
            source = null,
            moduleAnsi = null,
            sourceAnsi = null,
            message = text,
            error = null,
            banner = true,
        )
    }

    private fun emit(
        level: LogLevel,
        module: String,
        site: LogSite?,
        source: String?,
        moduleAnsi: String?,
        sourceAnsi: String?,
        message: String,
        error: LogError?,
        banner: Boolean,
    ) {
        val record = LogArchive.append(
            level = level,
            module = module,
            site = site,
            source = source,
            moduleAnsi = moduleAnsi,
            sourceAnsi = sourceAnsi,
            message = message,
            error = error,
            banner = banner,
        )

        if (useConsole) {
            consoleOutBegin()
            println(
                LogRecordFormat.render(
                    record,
                    if (useColor) LogTextFormat.ANSI else LogTextFormat.PLAIN,
                )
            )
            consoleOutEnd()
        }

        // 文件里始终是纯文本；着色版只用于控制台
        appendLogFile(LogRecordFormat.render(record, LogTextFormat.PLAIN) + "\n")

        // 合并唤醒：只置标记，正文由订阅方按游标拉取
        LogWake.mark()
    }
}

/**
 * 把异常转成 [LogError]。
 *
 * 先渲染完整文本（要读 `ThrowTrace` 侧表），再拆结构化链——后者会释放侧表条目。
 */
private fun logErrorOf(error: Throwable, printedAt: String?): LogError {
    val text = StackTraceFormat.throwable(error, printedAt)
    val payload = error.toKeelError()
    return LogError(
        chain = payload.chain,
        text = text,
        printedAt = printedAt,
        truncated = payload.truncated,
    )
}
