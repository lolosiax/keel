@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.compiler.plugin.*
import org.jetbrains.kotlin.config.*

/** 解析 `-P plugin:moe.lolosia.keel.logger:...`。 */
internal class KeelLoggerCliProcessor : CommandLineProcessor {

    override val pluginId: String = KeelLoggerOption.PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(KeelLoggerOption.ENABLED, "<true|false>", "是否改写日志调用", required = false),
        CliOption(KeelLoggerOption.WORKSPACE_ROOT, "<path>", "相对路径基准", required = false),
        CliOption(
            KeelLoggerOption.SOURCE_ROOT,
            "<path>",
            "源码根；可重复",
            required = false,
            allowMultipleOccurrences = true,
        ),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            KeelLoggerOption.ENABLED -> configuration.put(KeelLoggerKeys.ENABLED, value.toBooleanStrictOrNull() ?: true)
            KeelLoggerOption.WORKSPACE_ROOT -> configuration.put(KeelLoggerKeys.WORKSPACE_ROOT, value)
            KeelLoggerOption.SOURCE_ROOT -> configuration.appendList(KeelLoggerKeys.SOURCE_ROOTS, value)
        }
    }
}
