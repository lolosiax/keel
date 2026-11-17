package moe.lolosia.keel.logger

import java.io.File
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.acceptVoid

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
        // 先把 DebugView 生成出来：插桩只看检查点，两趟互不依赖，但生成的方法要先存在
        if (config.debugValues) {
            DebugViewSymbols.resolve(pluginContext)?.let { symbols ->
                moduleFragment.acceptVoid(DebugTypeTransformer(pluginContext, symbols))
            }
        }
        // 调试插桩放在最后：它只加「调用点」，不该被日志改写再处理一遍
        val output = config.debugOutput
        if (config.debug && output != null) {
            DebugSymbols.resolve(pluginContext)?.let { symbols ->
                val table = DebugLocationTable(config.debugModule, config.debugBuildId)
                moduleFragment.transform(DebugSiteTransformer(pluginContext, symbols, pathIndex, table), null)
                table.writeTo(File(output))
            }
        }
    }
}
