@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeAliasSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

/**
 * 把 IR 类型渲染成「源码里大概长这样」的字符串，仅用于调试器展示。
 *
 * 用全限定名（`kotlin.String`、`moe.lolosia.keel.api.PluginContext?`），
 * 避免同名类型在不同包下混淆；泛型参数最多展开三层，再深就退化成 `…`。
 */
internal fun renderDebugType(type: IrType, depth: Int = 0): String {
    if (depth > 3) return "…"
    val simple = type as? IrSimpleType ?: return type.toString()
    val name = when (val classifier = simple.classifier) {
        is IrClassSymbol -> classifier.owner.fqNameWhenAvailable?.asString()
            ?: classifier.owner.name.asString()

        is IrTypeParameterSymbol -> classifier.owner.name.asString()
        is IrTypeAliasSymbol -> classifier.owner.name.asString()
        else -> "?"
    }
    val arguments = if (simple.arguments.isEmpty()) {
        ""
    } else {
        simple.arguments.joinToString(", ", "<", ">") { argument ->
            (argument as? IrTypeProjection)?.let { renderDebugType(it.type, depth + 1) } ?: "*"
        }
    }
    return name + arguments + if (simple.isMarkedNullable()) "?" else ""
}
