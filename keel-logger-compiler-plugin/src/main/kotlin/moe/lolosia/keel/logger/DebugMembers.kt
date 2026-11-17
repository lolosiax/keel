@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType

/** 一个可供调试器读取的实例成员。 */
internal class DebugMember(
    val name: String,
    val type: IrType,
    /** 生成「读取该成员」的表达式；`receiver` 是当前实例。 */
    val read: (DeclarationIrBuilder, IrValueParameter) -> IrExpression,
)

/**
 * 列出类的实例成员：只取**有 backing field** 的属性。
 *
 * 关键点：FIR2IR 之后、后端 lowering 之前，主构造器的 `val` 参数已经生成
 * `IrProperty` + `backingField`，但字段还没有挂进 `IrClass.declarations`，
 * 只遍历 `fields` 会得到空列表（M8 第一版踩过）。所以这里走 `declarations` 里的
 * `IrProperty`，再取它的 `backingField`。
 *
 * 只读字段、不调 getter：计算属性（`val x get() = ...`）可能有副作用或抛异常，
 * 调试器不应该因为它们打不开整个对象。
 *
 * 跳过：委托属性、编译器合成名字（带 `$`）、接口 fake override。
 */
internal fun debugMembers(irClass: IrClass): List<DebugMember> {
    val members = ArrayList<DebugMember>()
    val seen = HashSet<String>()

    irClass.declarations.forEach { declaration ->
        when (declaration) {
            is IrProperty -> {
                val name = declaration.name.asString()
                if (name.isEmpty() || name.contains('$')) return@forEach
                if (declaration.origin == IrDeclarationOrigin.FAKE_OVERRIDE) return@forEach
                if (declaration.isDelegated) return@forEach
                if (!seen.add(name)) return@forEach
                val field = declaration.backingField ?: return@forEach
                if (field.isStatic) return@forEach
                members += DebugMember(name, field.type) { builder, receiver ->
                    builder.irGetField(builder.irGet(receiver), field)
                }
            }

            is IrField -> {
                // 没有对应 property 的裸字段（少见）：也展示出来
                val name = declaration.name.asString()
                if (declaration.isStatic || name.isEmpty() || name.contains('$') || !seen.add(name)) {
                    return@forEach
                }
                members += DebugMember(name, declaration.type) { builder, receiver ->
                    builder.irGetField(builder.irGet(receiver), declaration)
                }
            }

            else -> Unit
        }
    }
    return members
}
