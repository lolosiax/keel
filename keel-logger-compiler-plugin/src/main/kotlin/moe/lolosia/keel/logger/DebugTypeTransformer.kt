@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * 给类补一份 `DebugView` 实现，让调试器能在 K/N 上展开对象成员。
 *
 * `DebugView` 的五个方法都按「一次 `when (index)` 覆盖所有成员」生成，而不是
 * 一个成员一个方法：类里只多 5 个方法，成员数是编译期常量。生成的函数
 * `startOffset = UNDEFINED_OFFSET`，所以 [DebugSiteTransformer] 不会给它们插检查点。
 *
 * 只处理本模块里源码定义的普通类与 `object`：接口 / 枚举 / 注解 / value class /
 * 伴生对象 / lambda 与匿名对象全部跳过；已经实现过 `DebugView` 的类也不重复加
 * 父类型，但子类仍会生成自己的实现（否则父类字段看不到）。
 */
internal class DebugTypeTransformer(
    private val pluginContext: IrPluginContext,
    private val symbols: DebugViewSymbols,
) : IrVisitorVoid() {

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitClass(declaration: IrClass) {
        if (eligible(declaration)) generate(declaration)
        declaration.acceptChildrenVoid(this)
    }

    private fun generate(irClass: IrClass) {
        val members = debugMembers(irClass)
        irClass.superTypes = irClass.superTypes + symbols.debugViewType

        val modality = if (irClass.modality == Modality.FINAL) Modality.FINAL else Modality.OPEN
        val builtIns = pluginContext.irBuiltIns

        addMethod(irClass, "keelDebugTypeName", builtIns.stringType, modality, symbols.typeName, withIndex = false) { builder, _, _ ->
            builder.irString(irClass.name.asString())
        }
        addMethod(irClass, "keelDebugMemberCount", builtIns.intType, modality, symbols.memberCount, withIndex = false) { builder, _, _ ->
            builder.irInt(members.size)
        }
        addMethod(irClass, "keelDebugMemberName", builtIns.stringType, modality, symbols.memberName, withIndex = true) { builder, _, index ->
            whenIndex(
                builder,
                index!!,
                members.map { builder.irString(it.name) },
                builder.irString(""),
            )
        }
        addMethod(irClass, "keelDebugMemberType", builtIns.stringType, modality, symbols.memberType, withIndex = true) { builder, _, index ->
            whenIndex(
                builder,
                index!!,
                members.map { builder.irString(renderDebugType(it.type)) },
                builder.irString(""),
            )
        }
        addMethod(irClass, "keelDebugMember", builtIns.anyNType, modality, symbols.member, withIndex = true) { builder, receiver, index ->
            if (members.isEmpty()) return@addMethod builder.irNull()
            val branches = members.mapIndexed { position, member ->
                builder.irBranch(
                    builder.irEquals(builder.irGet(index!!), builder.irInt(position)),
                    builder.irImplicitCast(member.read(builder, receiver), builtIns.anyNType),
                )
            } + builder.irElseBranch(builder.irNull())
            builder.irWhen(builtIns.anyNType, branches)
        }
    }

    private fun addMethod(
        irClass: IrClass,
        name: String,
        returnType: org.jetbrains.kotlin.ir.types.IrType,
        modality: Modality,
        overridden: IrSimpleFunctionSymbol,
        withIndex: Boolean,
        body: (DeclarationIrBuilder, IrValueParameter, IrValueParameter?) -> IrExpression,
    ) {
        val function = irClass.addFunction(
            name = name,
            returnType = returnType,
            modality = modality,
            origin = GENERATED,
        )
        val index = if (withIndex) function.addValueParameter("index", pluginContext.irBuiltIns.intType, GENERATED) else null
        function.overriddenSymbols = listOf(overridden)

        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        val receiver = function.dispatchReceiverParameter
            ?: error("生成的调试方法缺少实例接收者：${irClass.fqNameWhenAvailable}.$name")
        function.body = builder.irBlockBody {
            +irReturn(body(builder, receiver, index))
        }
    }

    /** `when (index) { 0 -> values[0]; ...; else -> fallback }`。 */
    private fun whenIndex(
        builder: DeclarationIrBuilder,
        index: IrValueParameter,
        values: List<IrExpression>,
        fallback: IrExpression,
    ): IrExpression {
        if (values.isEmpty()) return fallback
        val branches = values.mapIndexed { position, value ->
            builder.irBranch(
                builder.irEquals(builder.irGet(index), builder.irInt(position)),
                value,
            )
        } + builder.irElseBranch(fallback)
        return builder.irWhen(values.first().type, branches)
    }

    private fun eligible(irClass: IrClass): Boolean {
        // 只处理本模块源码里的类；lambda / 匿名对象 / 编译器合成类都有别的 origin
        if (irClass.origin != IrDeclarationOrigin.DEFINED) return false
        if (irClass.isExpect || irClass.isExternal || irClass.isValue) return false
        if (irClass.isCompanion || irClass.isAnonymousObject) return false
        if (irClass.kind != ClassKind.CLASS && irClass.kind != ClassKind.OBJECT) return false
        if (irClass.parent !is IrFile && irClass.parent !is IrClass) return false
        if (packageName(irClass) == DEBUG_PACKAGE) return false
        if (irClass.implementsDebugView(symbols)) return false
        // 用户恰好声明了同名方法时让路，避免重复声明
        return irClass.declarations.filterIsInstance<IrSimpleFunction>()
            .none { it.name.asString().startsWith("keelDebug") }
    }

    private fun packageName(irClass: IrClass): String =
        irClass.fqNameWhenAvailable?.parent()?.asString().orEmpty()

    private companion object {
        /** `keel-api` 的调试实现本身不参与生成，避免自递归。 */
        const val DEBUG_PACKAGE = "moe.lolosia.keel.api.debug"

        object DebugViewKey : GeneratedDeclarationKey()

        val GENERATED: IrDeclarationOrigin = IrDeclarationOrigin.GeneratedByPlugin(DebugViewKey)
    }
}
