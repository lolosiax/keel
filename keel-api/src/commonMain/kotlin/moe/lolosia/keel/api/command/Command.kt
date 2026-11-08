package moe.lolosia.keel.api.command

/**
 * 一次命令调用的元信息（Bukkit `Command` 的裁剪版）。
 *
 * 命令声明最终来自 `plugin.json` 的 `commands`；当前尚未解析，
 * 因此 [name] 就是 Host 拆出来的 label，[aliases] 为空。
 */
data class Command(
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val usage: String = "/$name",
)
