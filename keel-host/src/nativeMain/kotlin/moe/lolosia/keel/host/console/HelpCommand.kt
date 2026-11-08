package moe.lolosia.keel.host.console

import moe.lolosia.keel.host.plugin.HostPlugins

/**
 * `/help`：列出 Host 命令，并提示插件命令的发现方式。
 *
 * 插件命令目前没有声明表（`plugin.json` 的 `commands` 尚未解析），
 * 只能靠插件自己的 `onCommand` 应答，所以这里不枚举具体插件命令。
 */
internal object HelpCommand : ConsoleCommand {

    override val name: String = "help"
    override val aliases: List<String> = listOf("?")
    override val usage: String = "/help"
    override val description: String = "列出控制台命令"

    override fun execute(args: List<String>) {
        HostConsole.print("Host 命令：")
        HostConsole.print("  /help                                  列出命令")
        HostConsole.print("  /stop                                  停止 Host，执行优雅停机")
        HostConsole.print("  /plugins [list|info|install|remove|enable|disable]")
        HostConsole.print("                                         插件管理")

        val plugins = HostPlugins.list()
        if (plugins.isEmpty()) {
            HostConsole.print("插件：没有已安装的插件")
        } else {
            HostConsole.print("插件：${plugins.joinToString { it.id }}，详情见 /plugins")
        }
        HostConsole.print("其它输入按启用顺序交给插件，由插件 onCommand 决定是否接管。")
    }
}
