package moe.lolosia.keel.host.console

/** `/stop`：请求优雅停机（停 HTTP → 逐个投 SHUTDOWN → drain）。 */
internal object StopCommand : ConsoleCommand {

    override val name: String = "stop"
    override val aliases: List<String> = listOf("shutdown", "exit", "quit")
    override val usage: String = "/stop"
    override val description: String = "停止 Host"

    override fun execute(args: List<String>) {
        HostConsole.print("正在停止…")
        HostConsole.requestStop()
    }
}
