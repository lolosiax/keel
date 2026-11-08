package moe.lolosia.keel.host.console

/**
 * Host 自己的控制台命令（`help` / `stop` / `plugins`）。
 *
 * 名字与别名都按小写匹配；插件命令不在这里注册，而是由 [HostConsole] 在
 * Host 命令未命中时逐个询问插件（对应 Bukkit `PluginCommand` 的 onCommand 路径）。
 */
internal interface ConsoleCommand {

    val name: String

    val aliases: List<String> get() = emptyList()

    val usage: String get() = "/$name"

    val description: String get() = ""

    /** 参数不含 label；缺参时自己打印用法，不抛异常。 */
    fun execute(args: List<String>)
}
