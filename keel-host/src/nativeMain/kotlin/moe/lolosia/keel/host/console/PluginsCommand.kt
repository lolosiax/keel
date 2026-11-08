package moe.lolosia.keel.host.console

import moe.lolosia.keel.host.plugin.HostPlugins

/**
 * `/plugins`：插件管理，支持 list / info / install / remove / enable / disable。
 *
 * 卸载只做「停用 + 注销引用 + 删除包文件」；Kotlin/Native 运行时不能 `dlclose`，
 * 动态库会一直留在进程地址空间里，这条限制在输出里明确告诉操作者。
 */
internal object PluginsCommand : ConsoleCommand {

    override val name: String = "plugins"
    override val aliases: List<String> = listOf("pl", "plugin")
    override val usage: String =
        "/plugins [list|info <id>|install <.keel 路径>|remove <id>|enable <id>|disable <id>]"
    override val description: String = "插件管理"

    override fun execute(args: List<String>) {
        when (val sub = args.firstOrNull()?.lowercase() ?: "list") {
            "list", "ls" -> list()
            "info" -> info(args.getOrNull(1))
            "install", "add" -> install(args.getOrNull(1))
            "remove", "uninstall", "rm" -> remove(args.getOrNull(1))
            "enable" -> enable(args.getOrNull(1))
            "disable" -> disable(args.getOrNull(1))
            "help", "?" -> HostConsole.print(usage)
            else -> {
                HostConsole.print("未知子命令：$sub")
                HostConsole.print(usage)
            }
        }
    }

    private fun list() {
        val entries = HostPlugins.list()
        if (entries.isEmpty()) {
            HostConsole.print("没有已安装的插件。")
            return
        }
        HostConsole.print("已安装 ${entries.size} 个插件。目录：${HostPlugins.pluginsDirectory}")
        entries.forEach { entry ->
            val state = if (entry.enabled) "enabled " else "disabled"
            HostConsole.print(
                "  ${entry.id.padEnd(12)} ${entry.loaded.manifest.version.padEnd(8)} " +
                    "$state  plugin_id=${entry.pluginId}  ${entry.displayName}"
            )
        }
    }

    private fun info(id: String?) {
        if (id == null) {
            HostConsole.print("用法：/plugins info <id>")
            return
        }
        val entry = HostPlugins.find(id)
        if (entry == null) {
            HostConsole.print("插件不存在：$id")
            return
        }

        val manifest = entry.loaded.manifest
        val state = if (entry.enabled) "enabled, plugin_id=${entry.pluginId}" else "disabled"
        HostConsole.print("id: ${manifest.id}")
        HostConsole.print("name: ${entry.displayName}")
        HostConsole.print("version: ${manifest.version}")
        HostConsole.print("abi: ${manifest.abiVersion}")
        HostConsole.print("state: $state")
        HostConsole.print("package: ${entry.loaded.keelPath}")
        HostConsole.print("library: ${entry.loaded.libraryPath}")
        HostConsole.print("resources: ${entry.loaded.classpathRef.entries}")
        HostConsole.print("authors: ${manifest.authors.joinToString().ifBlank { "unknown" }}")
        manifest.buildInfo?.let { HostConsole.print("build: ${it.gitSha} (${it.commitTime})") }
    }

    private fun install(path: String?) {
        if (path == null) {
            HostConsole.print("用法：/plugins install <.keel 路径>")
            return
        }
        try {
            if (HostPlugins.install(path)) {
                HostConsole.print("已安装并启用：$path")
            } else {
                HostConsole.print("安装后启动失败：$path，详见日志")
            }
        } catch (e: Throwable) {
            HostConsole.print("安装失败：${e.message}")
        }
    }

    private fun remove(id: String?) {
        if (id == null) {
            HostConsole.print("用法：/plugins remove <id>")
            return
        }
        if (HostPlugins.uninstall(id)) {
            HostConsole.print("已卸载 $id。动态库仍留在进程内，重启后彻底释放。")
        } else {
            HostConsole.print("插件不存在：$id")
        }
    }

    private fun enable(id: String?) {
        if (id == null) {
            HostConsole.print("用法：/plugins enable <id>")
            return
        }
        if (HostPlugins.enable(id)) {
            HostConsole.print("已启用 $id")
        } else {
            HostConsole.print("无法启用 $id：插件不存在或已经启用")
        }
    }

    private fun disable(id: String?) {
        if (id == null) {
            HostConsole.print("用法：/plugins disable <id>")
            return
        }
        if (HostPlugins.disable(id)) {
            HostConsole.print("已停用 $id")
        } else {
            HostConsole.print("无法停用 $id：插件不存在或已经停用")
        }
    }
}
