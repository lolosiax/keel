@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.*

/**
 * 异常诊断符号表。
 *
 * 只按 FqName 解析 `keel-api` 的 `ThrowTrace`，并单独取 `kotlin.Throwable`
 * 用于判断某个构造调用是不是异常。
 */
internal class ExceptionSymbols(
    val traceClass: IrClassSymbol,
    val traceType: IrType,
    val created: IrSimpleFunctionSymbol,
    val thrown: IrSimpleFunctionSymbol,
    val throwableClass: IrClass,
) {

    companion object {

        private val TRACE_ID = ClassId.topLevel(FqName("moe.lolosia.keel.api.log.ThrowTrace"))
        private val THROWABLE_ID = ClassId.topLevel(FqName("kotlin.Throwable"))

        /** 依赖里没有新版 keel-api 时返回 null，由调用方直接跳过改写。 */
        fun resolve(context: IrPluginContext): ExceptionSymbols? {
            val traceClass = context.referenceClass(TRACE_ID) ?: return null
            val throwable = context.referenceClass(THROWABLE_ID) ?: return null
            return ExceptionSymbols(
                traceClass = traceClass,
                traceType = traceClass.owner.defaultType,
                created = function(context, "created") ?: return null,
                thrown = function(context, "thrown") ?: return null,
                throwableClass = throwable.owner,
            )
        }

        private fun function(context: IrPluginContext, name: String): IrSimpleFunctionSymbol? =
            context.referenceFunctions(CallableId(TRACE_ID, Name.identifier(name)))
                .firstOrNull { it.owner.parameters.count { p -> p.kind == IrParameterKind.Regular } == 2 }
    }
}
