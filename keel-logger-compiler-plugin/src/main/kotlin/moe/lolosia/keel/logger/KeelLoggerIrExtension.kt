package moe.lolosia.keel.logger

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/** 每个模块跑一次；符号缺失（旧版 keel-api）时静默跳过。 */
internal class KeelLoggerIrExtension(private val config: KeelLoggerConfig) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val pathIndex = SourcePathIndex(config.workspaceRoot, config.sourceRoots)
        LoggerSymbols.resolve(pluginContext)?.let { symbols ->
            moduleFragment.transform(LogSiteTransformer(pluginContext, symbols, pathIndex), null)
        }
        if (config.traceExceptions) {
            ExceptionSymbols.resolve(pluginContext)?.let { symbols ->
                moduleFragment.transform(ExceptionSiteTransformer(pluginContext, symbols), null)
            }
        }
    }
}
