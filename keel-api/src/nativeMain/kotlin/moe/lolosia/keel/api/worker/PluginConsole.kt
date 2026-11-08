package moe.lolosia.keel.api.worker

import kotlinx.cinterop.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.command.*

/**
 * 插件侧的控制台命令分发。
 *
 * Host 把一行拆成 label + args 投过来；这里调用 `Plugin.onCommand`，
 * 把输出与「是否接管」一起回给 Host。异常不吞：交给 [PluginWorker] 的分发边界
 * 编码成错误信封，Host 打印完整错误链后继续问下一个插件。
 */
internal object PluginConsole {

    private val json = Json { ignoreUnknownKeys = true }

    fun handle(event: KeelEvent) {
        val request = decode(event)
        if (request == null) {
            reply(event, ConsoleCommandResult())
            return
        }

        val sender = PluginConsoleSender()
        val handled = try {
            val plugin = PluginWorker.plugin
            plugin?.onCommand(sender, Command(request.label), request.label, request.args) ?: false
        } finally {
            sender.close()
        }
        reply(event, ConsoleCommandResult(handled, sender.messages()))
    }

    private fun decode(event: KeelEvent): ConsoleCommandRequest? {
        if (event.payload_size == 0u) return null
        val bytes = event.payload?.reinterpret<ByteVar>()?.readBytes(event.payload_size.toInt())
            ?: return null
        return try {
            json.decodeFromString<ConsoleCommandRequest>(bytes.decodeToString())
        } catch (e: Throwable) {
            Log.warn("控制台命令载荷无法解码：${e.message}")
            null
        }
    }

    private fun reply(event: KeelEvent, result: ConsoleCommandResult) {
        val payload = Json.encodeToString(result).encodeToByteArray()
        replyCompleteWithPayload(event, payload, KEEL_OK.toInt())
    }
}
