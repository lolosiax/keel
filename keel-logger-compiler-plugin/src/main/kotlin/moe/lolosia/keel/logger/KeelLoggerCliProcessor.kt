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
        CliOption(
            KeelLoggerOption.EXCEPTION_TRACE,
            "<true|false>",
            "是否给异常构造 / throw 插桩",
            required = false,
        ),
        CliOption(KeelLoggerOption.DEBUG, "<true|false>", "是否插入调试检查点", required = false),
        CliOption(KeelLoggerOption.DEBUG_OUTPUT, "<path>", "位置表输出文件（.keel-debug）", required = false),
        CliOption(KeelLoggerOption.DEBUG_MODULE, "<id>", "位置表里的模块 id", required = false),
        CliOption(KeelLoggerOption.DEBUG_BUILD_ID, "<id>", "位置表里的 buildId", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            KeelLoggerOption.ENABLED -> configuration.put(KeelLoggerKeys.ENABLED, value.toBooleanStrictOrNull() ?: true)
            KeelLoggerOption.WORKSPACE_ROOT -> configuration.put(KeelLoggerKeys.WORKSPACE_ROOT, value)
            KeelLoggerOption.SOURCE_ROOT -> configuration.appendList(KeelLoggerKeys.SOURCE_ROOTS, value)
            KeelLoggerOption.EXCEPTION_TRACE ->
                configuration.put(KeelLoggerKeys.EXCEPTION_TRACE, value.toBooleanStrictOrNull() ?: true)

            KeelLoggerOption.DEBUG -> configuration.put(KeelLoggerKeys.DEBUG, value.toBooleanStrictOrNull() ?: true)
            KeelLoggerOption.DEBUG_OUTPUT -> configuration.put(KeelLoggerKeys.DEBUG_OUTPUT, value)
            KeelLoggerOption.DEBUG_MODULE -> configuration.put(KeelLoggerKeys.DEBUG_MODULE, value)
            KeelLoggerOption.DEBUG_BUILD_ID -> configuration.put(KeelLoggerKeys.DEBUG_BUILD_ID, value)
        }
    }
}
