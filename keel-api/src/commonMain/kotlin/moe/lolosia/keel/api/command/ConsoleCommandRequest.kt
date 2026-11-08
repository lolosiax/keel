package moe.lolosia.keel.api.command

import kotlinx.serialization.Serializable

/**
 * Host → 插件的控制台命令载荷。
 *
 * 只带拆分后的 label 与参数；终端原始行不传，避免插件重新解析引号规则。
 */
@Serializable
data class ConsoleCommandRequest(
    val label: String,
    val args: List<String> = emptyList(),
)
