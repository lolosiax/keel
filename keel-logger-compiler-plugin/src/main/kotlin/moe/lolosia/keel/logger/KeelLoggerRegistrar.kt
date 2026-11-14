@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.*
import org.jetbrains.kotlin.config.*

/** 注册日志改写与调试插桩；K2 / Native 都走 IR。 */
internal class KeelLoggerRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = KeelLoggerOption.PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (!configuration.get(KeelLoggerKeys.ENABLED, true)) return
        val config = KeelLoggerConfig(
            workspaceRoot = configuration.get(KeelLoggerKeys.WORKSPACE_ROOT),
            sourceRoots = configuration.get(KeelLoggerKeys.SOURCE_ROOTS).orEmpty(),
            traceExceptions = configuration.get(KeelLoggerKeys.EXCEPTION_TRACE, true),
            debug = configuration.get(KeelLoggerKeys.DEBUG, false),
            debugOutput = configuration.get(KeelLoggerKeys.DEBUG_OUTPUT),
            debugModule = configuration.get(KeelLoggerKeys.DEBUG_MODULE) ?: "unknown",
            debugBuildId = configuration.get(KeelLoggerKeys.DEBUG_BUILD_ID) ?: "unknown",
        )
        IrGenerationExtension.registerExtension(KeelLoggerIrExtension(config))
    }
}
