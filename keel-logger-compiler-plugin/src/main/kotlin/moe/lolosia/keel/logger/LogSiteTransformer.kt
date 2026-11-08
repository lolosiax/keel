@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*

/**
 * 把 `Log.<level>(...)` 原地改成 `Log.of(...).<level>(...)`。
 *
 * 只替换被调方与 dispatch receiver，原实参（含命名参数、默认参数占位）原样保留。
 */
internal class LogSiteTransformer(
    private val pluginContext: IrPluginContext,
    private val symbols: LoggerSymbols,
    private val pathIndex: SourcePathIndex,
) : IrElementTransformerVoidWithContext() {

    override fun visitCall(expression: IrCall): IrExpression {
        val call = super.visitCall(expression) as? IrCall ?: return expression
        // 占位函数 `Log.site()`：原地换成 `Log.of(实际调用点)`，用于自定义来源列的日志
        if (symbols.site != null && call.symbol == symbols.site) {
            return siteOfCall(call) ?: call
        }
        val level = symbols.originalLevels[call.symbol] ?: return call
        val siteLevel = symbols.siteLevels[level] ?: return call

        // IR 的 arguments 列表按“全部形参”排布，包含 dispatch / extension receiver
        val arity = siteLevel.owner.parameters.size
        if (call.arguments.size != arity) {
            pluginContext.messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "[keel-logger] ${currentFile.packageFqName.asString()}/${currentFile.fileEntry.name} 的 " +
                    "Log.${level.functionName} 参数数量与 LogSite 不一致（$arity vs ${call.arguments.size}），保留原调用",
            )
            return call
        }

        val ofCall = siteOfCall(call) ?: return call
        call.symbol = siteLevel
        call.dispatchReceiver = ofCall
        return call
    }

    private fun siteOfCall(call: IrCall): IrCall? {
        val file = currentFile
        val line = file.fileEntry.getLineNumber(call.startOffset).let { if (it < 0) 1 else it + 1 }
        val site = LogSiteResolver.resolve(file, allScopes, line, pathIndex)

        val builder = DeclarationIrBuilder(pluginContext, file.symbol, call.startOffset, call.endOffset)
        val ofCall = builder.irCall(symbols.of)
        ofCall.initializeTargetShapeFromSymbol()
        ofCall.dispatchReceiver = builder.irGetObjectValue(symbols.logType, symbols.logClass)
        val offset = symbols.of.owner.parameters.count { it.kind != IrParameterKind.Regular }
        ofCall.arguments[offset] = builder.irString(site.displayName)
        ofCall.arguments[offset + 1] = builder.irString(site.file)
        ofCall.arguments[offset + 2] = builder.irInt(site.line)
        ofCall.arguments[offset + 3] = nullableString(builder, site.packageName, symbols.ofParameters[3].type)
        ofCall.arguments[offset + 4] = nullableString(builder, site.className, symbols.ofParameters[4].type)
        ofCall.arguments[offset + 5] = nullableString(builder, site.functionName, symbols.ofParameters[5].type)
        ofCall.arguments[offset + 6] = builder.irInt(site.kind.ordinal)
        return ofCall
    }

    private fun nullableString(builder: IrBuilderWithScope, value: String?, type: IrType): IrExpression =
        if (value == null) builder.irNull(type) else builder.irString(value)
}
