@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrThrowImpl
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*

/**
 * 给异常的构造与抛出点插桩：
 *
 * ```
 * val t = IllegalStateException(...)   ->  ThrowTrace.created(t, "包.类.函数:行")
 * throw t                              ->  ThrowTrace.thrown(t, "包.类.函数:行")
 * ```
 *
 * 站点字符串在编译期算好；运行期只做一次侧表写入，不分配额外对象。
 */
internal class ExceptionSiteTransformer(
    private val pluginContext: IrPluginContext,
    private val symbols: ExceptionSymbols,
) : IrElementTransformerVoidWithContext() {

    override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
        val call = super.visitConstructorCall(expression) as? IrConstructorCall ?: return expression
        if (!call.isThrowable()) return call
        val site = site(call.startOffset)
        val builder = builder(call)
        return builder.irBlock(resultType = call.type) {
            val temp = irTemporary(call)
            +trace(symbols.created, temp, site)
            +irGet(temp)
        }
    }

    override fun visitThrow(expression: IrThrow): IrExpression {
        val call = super.visitThrow(expression) as? IrThrow ?: return expression
        val site = site(call.startOffset)
        val builder = builder(call)
        return builder.irBlock(resultType = pluginContext.irBuiltIns.nothingType) {
            val temp = irTemporary(call.value)
            +trace(symbols.thrown, temp, site)
            +IrThrowImpl(call.startOffset, call.endOffset, pluginContext.irBuiltIns.nothingType, irGet(temp))
        }
    }

    private fun IrBuilderWithScope.trace(
        function: IrSimpleFunctionSymbol,
        error: IrVariable,
        site: String,
    ): IrCall {
        val call = irCall(function)
        call.initializeTargetShapeFromSymbol()
        call.dispatchReceiver = irGetObjectValue(symbols.traceType, symbols.traceClass)
        val offset = function.owner.parameters.count { it.kind != IrParameterKind.Regular }
        call.arguments[offset] = irGet(error)
        call.arguments[offset + 1] = irString(site)
        return call
    }

    private fun builder(expression: IrExpression): DeclarationIrBuilder {
        val owner = currentScope?.scope?.scopeOwnerSymbol ?: currentFile.symbol
        return DeclarationIrBuilder(pluginContext, owner, expression.startOffset, expression.endOffset)
    }

    private fun site(offset: Int): String {
        val file = currentFile
        val line = file.fileEntry.getLineNumber(offset).let { if (it < 0) 1 else it + 1 }
        return LogSiteResolver.throwSite(file, allScopes, line)
    }

    private fun IrConstructorCall.isThrowable(): Boolean =
        (symbol.owner.parent as? IrClass)?.isThrowableSubclass(hashSetOf()) == true

    private fun IrClass.isThrowableSubclass(seen: MutableSet<IrClass>): Boolean {
        if (this == symbols.throwableClass) return true
        if (!seen.add(this)) return false
        return superTypes.any { it.classOrNull?.owner?.isThrowableSubclass(seen) == true }
    }
}
