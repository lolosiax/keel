package moe.lolosia.keel.api.command

import kotlinx.serialization.Serializable

/**
 * 插件 → Host 的控制台命令结果。
 *
 * [handled] 为 false 时 Host 继续问下一个插件；[output] 由 Host 按顺序原样打印。
 * 插件抛异常时走错误信封（status = FAILED），Host 打印错误链后同样继续问下一个插件。
 */
@Serializable
data class ConsoleCommandResult(
    val handled: Boolean = false,
    val output: List<String> = emptyList(),
)
