package moe.lolosia.keel.host

/**
 * Host 侧日志门面。
 *
 * 统一前缀，后续可替换为文件 / 平台日志；CLI 的用法说明与结果输出不走这里。
 */
object Log {

    fun info(message: String) = println("[keel] $message")

    fun warn(message: String) = println("[keel][warn] $message")

    fun error(message: String, cause: Throwable? = null) {
        println("[keel][error] $message")
        cause?.let { println(it.stackTraceToString()) }
    }
}
