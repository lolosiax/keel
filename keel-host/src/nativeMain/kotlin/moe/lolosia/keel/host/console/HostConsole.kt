package moe.lolosia.keel.host.console

import kotlinx.cinterop.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.command.*
import moe.lolosia.keel.api.error.*
import moe.lolosia.keel.host.plugin.HostPlugins
import moe.lolosia.keel.host.plugin.HostPump

/**
 * 控制台：stdin 取行 → Host 命令表 → 插件回调。
 *
 * 分发顺序照搬 Bukkit 的 `SimpleCommandMap`：按空格拆分，label 转小写，
 * 先查 Host 自己的命令；没有命中时按启用顺序逐个询问插件，第一个
 * `handled = true` 的插件结束分发。全部不认识才打印「未知命令」。
 *
 * stdin 由 C 线程读取（`keel_console_start`），这里只在主循环里非阻塞取行，
 * 因此命令执行与事件泵仍在同一个线程上串行，插件回包不会和 HTTP 抢 pending 表。
 */
internal object HostConsole {

    private const val PLUGIN_TIMEOUT_MS = 3000u
    private const val LINE_CAPACITY = 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val commands = mutableMapOf<String, ConsoleCommand>()
    private val buffer = ByteArray(LINE_CAPACITY)
    private var stopRequested = false
    private var injectWarned = false

    /** `/stop` 置位后由 HostRuntime 检查，走正常的优雅停机。 */
    val shouldStop: Boolean get() = stopRequested

    /** 启动 stdin 读线程并注册内置命令；重复调用无副作用。 */
    fun start() {
        if (commands.isEmpty()) {
            register(HelpCommand)
            register(StopCommand)
            register(PluginsCommand)
        }
        val interactive = keel_console_start() != 0
        Log.info("控制台已就绪，输入help或?查看可用命令", tag = "Console")
        // 先打日志再画提示符，避免第一行日志把提示符顶掉
        if (interactive) ConsolePrompt.enable()
    }

    fun requestStop() {
        stopRequested = true
    }

    /** 非阻塞地把 stdin 缓冲里的行全部执行掉；每轮主循环调用一次。 */
    fun poll() {
        while (true) {
            val length = buffer.usePinned {
                keel_console_poll(it.addressOf(0), buffer.size.toUInt())
            }
            if (length <= 0) return
            // 回车已经把提示符所在行结束了；命令若没有输出，下面要把它补回来
            ConsolePrompt.beforeLine()
            dispatch(buffer.decodeToString(0, length))
            // 命令可能刚停用了插件：立刻消费它的最后几条日志，避免积压到下一轮
            HostPump.drain()
            ConsolePrompt.afterLine()
        }
    }

    /**
     * 把插件转发过来的一行注入 stdin 接收器。
     *
     * 与 C 侧读线程共用同一条环形缓冲，下一轮 [poll] 会像真实输入一样执行它；
     * Host 未进入命令循环（`--once`）或缓冲已满时只告警一次。
     */
    fun inject(line: String) {
        val text = line.trimEnd('\r', '\n')
        if (text.isEmpty()) return
        val bytes = text.encodeToByteArray()
        val pushed = bytes.usePinned {
            keel_console_push(it.addressOf(0), bytes.size.toUInt())
        } != 0
        if (!pushed && !injectWarned) {
            injectWarned = true
            Log.warn("控制台输入注入失败：Host 未运行命令循环或缓冲已满", tag = "Console")
        }
    }

    /** 执行一行；空行忽略。 */
    fun dispatch(line: String) {
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return

        val label = parts[0].lowercase()
        val args = parts.drop(1)

        val command = commands[label]
        if (command != null) {
            try {
                command.execute(args)
            } catch (e: Throwable) {
                Log.error("命令 $label 执行失败", tag = "Console", cause = e)
            }
            return
        }

        if (dispatchToPlugins(label, args)) return
        print("未知命令：$label。输入 help 查看可用命令。")
    }

    /**
     * 输出一行：走统一日志出口（TAG `Console`），由它负责写文件与提示符重绘。
     *
     * 不再直接 `println`：命令输出要和其它日志一样带时间戳，并且不能把 `> ` 提示符顶掉。
     */
    fun print(message: String) {
        Log.info(message, tag = "Console")
    }

    private fun register(command: ConsoleCommand) {
        (listOf(command.name) + command.aliases).forEach { name ->
            val key = name.lowercase()
            if (commands.put(key, command) != null) {
                Log.warn("命令名重复：$key", tag = "Console")
            }
        }
    }

    private fun dispatchToPlugins(label: String, args: List<String>): Boolean {
        val payload = json.encodeToString(ConsoleCommandRequest(label, args)).encodeToByteArray()

        for (entry in HostPlugins.enabled()) {
            val reply = HostPump.requestPluginEvent(
                pluginId = entry.pluginId,
                subject = KEEL_SUBJECT_CONSOLE.toULong(),
                payload = payload,
                timeoutMs = PLUGIN_TIMEOUT_MS,
            )
            if (reply == null) {
                Log.warn("${entry.id} 未响应控制台命令：$label", tag = "Console")
                continue
            }
            if (reply.status != KEEL_OK.toInt()) {
                printRemoteError(entry.id, reply.payload)
                continue
            }

            val result = reply.payload?.let { decodeResult(it) } ?: continue
            result.output.forEach(::print)
            if (result.handled) return true
        }
        return false
    }

    private fun decodeResult(payload: ByteArray): ConsoleCommandResult? = try {
        json.decodeFromString<ConsoleCommandResult>(payload.decodeToString())
    } catch (e: Throwable) {
        Log.warn("插件返回值无法解码：${e.message}", tag = "Console")
        null
    }

    private fun printRemoteError(plugin: String, payload: ByteArray?) {
        val envelope = payload?.let {
            runCatching { json.decodeFromString<KeelErrorPayload>(it.decodeToString()) }.getOrNull()
        }
        if (envelope == null) {
            Log.error("$plugin 执行命令失败（没有错误载荷）", tag = "Console")
            return
        }
        Log.error("$plugin 执行命令失败", tag = "Console")
        envelope.chain.forEach { entry -> print("  ${entry.type}: ${entry.message}") }
    }
}
