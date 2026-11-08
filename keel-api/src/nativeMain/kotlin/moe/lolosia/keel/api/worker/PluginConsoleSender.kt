package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.command.*

/**
 * 插件侧的控制台发送方。
 *
 * 输出先攒在本次请求的缓冲里，随 COMPLETE 一起回给 Host，由 Host 统一打印；
 * 请求结束后（[close]）再写就退化成插件日志，避免把内容落在已经发出的事件里。
 */
internal class PluginConsoleSender : CommandSender {

    override val name: String = "CONSOLE"

    private val lines = mutableListOf<String>()
    private var closed = false

    override fun sendMessage(message: String) {
        if (closed) {
            Log.info(message)
            return
        }
        lines += message
    }

    /** 本次命令已经输出的行；只在 [close] 之前调用。 */
    fun messages(): List<String> = lines

    /** 请求已结束；之后的消息改走插件日志。 */
    fun close() {
        closed = true
    }
}
