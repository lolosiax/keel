package moe.lolosia.keel.plugins.console

import kotlinx.coroutines.*
import moe.lolosia.keel.api.*
import moe.lolosia.keel.api.annotation.KeelPluginMain
import moe.lolosia.keel.api.command.*
import moe.lolosia.keel.api.config.*
import moe.lolosia.keel.api.messaging.*

/**
 * 独立控制台窗口插件。
 *
 * 窗口由 helper 子进程持有（`CREATE_NEW_CONSOLE`），插件通过双向管道回放并实时跟随
 * `Log.observe(ANSI)`；窗口里输入的行转发给 Host 的 stdin 接收器，与在 Host 终端里敲等价。
 *
 * 其他插件用 `Messenger` 发消息控制：`target = "console"`、`channel = "console"`，
 * 载荷是 `open` / `close` / `reopen` / `status` / `title <text>` / `clear`。
 */
@KeelPluginMain
class ConsolePlugin : Plugin {

    private lateinit var context: PluginContext

    private var session: ConsoleSession? = null
    private var pump: LogPump? = null
    private var eventsJob: Job? = null

    override fun onLoad(context: PluginContext) {
        this.context = context
        val settings = runCatching { context.config.read<ConsoleSettings>() }
            .getOrElse {
                Log.warn("控制台配置读取失败，使用默认值：${it.message}")
                ConsoleSettings()
            }
        ConsoleState.load(settings)
        Log.info("loaded")
    }

    override fun onEnable() {
        if (ConsoleState.settings.autoOpen) open()
        Log.info("enabled")
    }

    override fun onDisable() {
        close()
        Log.info("disabled")
    }

    override fun onMessage(message: Message): ByteArray? {
        if (message.channel != ConsoleProtocol.CHANNEL) return null
        val command = ConsoleCommand.parse(message.payload.decodeToString())
            ?: return ConsoleProtocol.failed("unknown-command").encodeToByteArray()
        return execute(command).encodeToByteArray()
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: List<String>,
    ): Boolean {
        if (label != "console") return false
        if (args.isEmpty()) {
            sender.sendMessage("用法：console open|close|reopen|status|title <text>|clear")
            return true
        }
        val parsed = ConsoleCommand.parse(args.joinToString(" "))
        if (parsed == null) {
            sender.sendMessage("未知子命令：${args[0]}")
            return true
        }
        sender.sendMessage(execute(parsed, fromCommand = true))
        return true
    }

    private fun execute(command: ConsoleCommand, fromCommand: Boolean = false): String = when (command) {
        ConsoleCommand.Open -> open()
        ConsoleCommand.Reopen -> {
            close()
            open()
        }

        ConsoleCommand.Close -> {
            // 从终端命令过来时先让 Host 把回显写进窗口，再关
            if (fromCommand) context.scheduler.schedule(150) { close() } else close()
            ConsoleProtocol.REPLY_CLOSED
        }

        ConsoleCommand.Status -> status()
        ConsoleCommand.Clear -> {
            session?.queue?.tryOffer("\u001b[2J\u001b[H")
            status()
        }

        is ConsoleCommand.Title -> setTitle(command.text)
    }

    private fun open(): String {
        if (session?.isAlive() == true) return ConsoleProtocol.REPLY_OPEN
        close()

        val helper = try {
            context.materialize(ConsoleProtocol.HELPER)
        } catch (error: Throwable) {
            Log.warn("控制台 helper 物化失败：${error.message}")
            return ConsoleProtocol.failed("helper-missing")
        }

        val created = try {
            openConsoleSession(
                helperPath = helper,
                title = ConsoleState.title,
                scrollback = ConsoleState.settings.scrollback,
            )
        } catch (error: Throwable) {
            Log.warn("控制台窗口创建失败：${error.message}")
            return ConsoleProtocol.failed(oneLine(error.message ?: "create-process"))
        }

        session = created
        pump = LogPump(
            context = context,
            queue = created.queue,
            startId = ConsoleState.settings.startId,
            maxReplayLines = ConsoleState.settings.maxReplayLines,
        ).also { it.start() }
        eventsJob = context.scope.launch {
            for (event in created.events) handle(event)
        }
        Log.info("控制台窗口已打开")
        return ConsoleProtocol.REPLY_OPEN
    }

    private fun close() {
        eventsJob?.cancel()
        eventsJob = null
        pump?.stop()
        pump = null
        session?.close()
        session = null
    }

    private fun status(): String {
        val alive = session?.isAlive() == true
        if (!alive) close()
        return if (alive) ConsoleProtocol.REPLY_OPEN else ConsoleProtocol.REPLY_CLOSED
    }

    private fun setTitle(raw: String): String {
        val text = raw.trim().ifEmpty { ConsoleState.settings.title }
        ConsoleState.setTitle(text)
        session?.setTitle(text)
        return status()
    }

    private fun handle(event: BridgeEvent) {
        when (event) {
            is BridgeEvent.Hello -> Unit
            is BridgeEvent.Resize -> Unit
            is BridgeEvent.Input -> {
                if (ConsoleState.settings.input.equals("off", ignoreCase = true)) return
                val line = ConsoleInput.normalize(event.line) ?: return
                ConsoleInput.forward(context, line)
            }

            is BridgeEvent.Closed -> {
                Log.info("控制台窗口已关闭：${event.reason}")
                close()
            }
        }
    }

    private fun oneLine(text: String): String = text.replace('\r', ' ').replace('\n', ' ').trim()
}
