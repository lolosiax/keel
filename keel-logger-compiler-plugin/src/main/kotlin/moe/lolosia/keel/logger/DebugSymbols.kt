@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.*

/**
 * 调试插桩的符号表。
 *
 * 只按 FqName 解析 `keel-api` 的 `Debug`；旧版依赖里没有它时整段插桩跳过。
 * 每个方法都按「名字 + 常规参数个数」挑，避免误命中同名重载。
 */
internal class DebugSymbols(
    val debugClass: IrClassSymbol,
    val debugType: IrType,
    val checkpoint: IrSimpleFunctionSymbol,
    val shouldStop: IrSimpleFunctionSymbol,
    val publish: IrSimpleFunctionSymbol,
    val enter: IrSimpleFunctionSymbol,
    val exit: IrSimpleFunctionSymbol,
) {

    companion object {

        private val DEBUG_ID = ClassId.topLevel(FqName("moe.lolosia.keel.api.debug.Debug"))

        fun resolve(context: IrPluginContext): DebugSymbols? {
            val debugClass = context.referenceClass(DEBUG_ID) ?: return null

            fun method(name: String, regular: Int): IrSimpleFunctionSymbol? =
                context.referenceFunctions(CallableId(DEBUG_ID, Name.identifier(name)))
                    .firstOrNull { function ->
                        function.owner.parameters.count { it.kind == IrParameterKind.Regular } == regular
                    }

            val checkpoint = method("checkpoint", 1) ?: return null
            val shouldStop = method("shouldStop", 1) ?: return null
            val publish = method("publish", 2) ?: return null
            val enter = method("enter", 1) ?: return null
            val exit = method("exit", 1) ?: return null

            return DebugSymbols(
                debugClass = debugClass,
                debugType = debugClass.owner.defaultType,
                checkpoint = checkpoint,
                shouldStop = shouldStop,
                publish = publish,
                enter = enter,
                exit = exit,
            )
        }
    }
}
