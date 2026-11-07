package moe.lolosia.keel.host

/**
 * Host 侧日志门面。
 *
 * 输出格式：`yyyy-MM-dd HH:mm:ss.SSS 级别 模块/包名 : 消息`。
 * 模块/包名字段固定 40 列；包名当前预留为空。CLI 的用法说明与结果输出仍可直接写 stdout。
 */
object Log {

    /** 启动横幅；只在进程入口打印一次。 */
    fun banner(version: String, target: String) {
        println(
            """
            >
            >  _  __        _
            > | |/ /___ ___| |
            > | ' </ -_) -_) |
            > |_|\_\___\___|_|
            >   keel $version · $target
            >
            """.trimMargin(">"),
        )
    }

    fun trace(message: String, tag: String = "host") = write("TRACE", tag, message)

    fun debug(message: String, tag: String = "host") = write("DEBUG", tag, message)

    fun info(message: String, tag: String = "host") = write("INFO", tag, message)

    fun warn(message: String, tag: String = "host") = write("WARN", tag, message)

    fun error(message: String, tag: String = "host", cause: Throwable? = null) =
        write("ERROR", tag, withCause(message, cause))

    fun fatal(message: String, tag: String = "host", cause: Throwable? = null) =
        write("FATAL", tag, withCause(message, cause))

    private fun write(level: String, tag: String, message: String) {
        val module = "${tag.trimEnd('/')}/"
        println("${formatLocalTimestamp()} ${level.padEnd(5)} ${module.padEnd(40)} : $message")
    }

    private fun withCause(message: String, cause: Throwable?): String =
        if (cause == null) message else "$message | ${cause.stackTraceToString()}"
}
