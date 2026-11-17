@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * `DebugView` 的符号表。
 *
 * 五个方法按名字取，数量固定；接口由 `keel-api` 提供，依赖里没有它时整段生成跳过。
 */
internal class DebugViewSymbols(
    val debugViewClass: IrClassSymbol,
    val debugViewType: IrType,
    val typeName: IrSimpleFunctionSymbol,
    val memberCount: IrSimpleFunctionSymbol,
    val memberName: IrSimpleFunctionSymbol,
    val memberType: IrSimpleFunctionSymbol,
    val member: IrSimpleFunctionSymbol,
) {

    companion object {

        private val DEBUG_VIEW_ID = ClassId.topLevel(FqName("moe.lolosia.keel.api.debug.DebugView"))

        fun resolve(context: IrPluginContext): DebugViewSymbols? {
            val irClass = context.referenceClass(DEBUG_VIEW_ID) ?: return null
            val functions = irClass.owner.declarations.filterIsInstance<IrSimpleFunction>()

            fun method(name: String): IrSimpleFunctionSymbol? =
                functions.firstOrNull { it.name.asString() == name }?.symbol

            return DebugViewSymbols(
                debugViewClass = irClass,
                debugViewType = irClass.owner.defaultType,
                typeName = method("keelDebugTypeName") ?: return null,
                memberCount = method("keelDebugMemberCount") ?: return null,
                memberName = method("keelDebugMemberName") ?: return null,
                memberType = method("keelDebugMemberType") ?: return null,
                member = method("keelDebugMember") ?: return null,
            )
        }
    }
}

/** 类是否已经（递归地）实现了 `DebugView`。 */
internal fun IrClass.implementsDebugView(symbols: DebugViewSymbols): Boolean =
    implementsDebugView(symbols, HashSet())

private fun IrClass.implementsDebugView(symbols: DebugViewSymbols, visited: MutableSet<IrClass>): Boolean {
    if (this.symbol == symbols.debugViewClass) return true
    if (!visited.add(this)) return false
    return superTypes.any { type ->
        val superClass = type.classOrNull?.owner ?: return@any false
        superClass.implementsDebugView(symbols, visited)
    }
}
