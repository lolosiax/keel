@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.*

/**
 * 日志门面的符号表。
 *
 * 全部按 FqName 解析，不依赖插件编译期可见的 `keel-api` 类：
 * 编译器插件只认 Kotlin 编译器 API。
 */
internal class LoggerSymbols(
    val logClass: IrClassSymbol,
    val logType: IrType,
    val of: IrSimpleFunctionSymbol,
    val ofParameters: List<IrValueParameter>,
    val siteLevels: Map<LoggerLevel, IrSimpleFunctionSymbol>,
    val originalLevels: Map<IrSimpleFunctionSymbol, LoggerLevel>,
) {

    companion object {

        private val LOG_ID = ClassId.topLevel(FqName("moe.lolosia.keel.api.Log"))
        private val SITE_ID = ClassId.topLevel(FqName("moe.lolosia.keel.api.log.LogSite"))

        /** 依赖里没有新版 keel-api 时返回 null，由调用方直接跳过改写。 */
        fun resolve(context: IrPluginContext): LoggerSymbols? {
            val logClass = context.referenceClass(LOG_ID) ?: return null
            context.referenceClass(SITE_ID) ?: return null

            // 选 ordinal 重载：它的最后一个参数是 Int
            val of = context.referenceFunctions(CallableId(LOG_ID, Name.identifier("of")))
                .firstOrNull { function ->
                    val parameters = valueParameters(function.owner)
                    parameters.size == 7 && parameters[6].type.isInt()
                } ?: return null

            val originalLevels = LoggerLevel.entries.mapNotNull { level ->
                context.referenceFunctions(CallableId(LOG_ID, Name.identifier(level.functionName)))
                    .firstOrNull()
                    ?.let { it to level }
            }.toMap()
            if (originalLevels.size != LoggerLevel.entries.size) return null

            val siteLevels = LoggerLevel.entries.mapNotNull { level ->
                context.referenceFunctions(CallableId(SITE_ID, Name.identifier(level.functionName)))
                    .firstOrNull()
                    ?.let { level to it }
            }.toMap()
            if (siteLevels.size != LoggerLevel.entries.size) return null

            return LoggerSymbols(
                logClass = logClass,
                logType = logClass.owner.defaultType,
                of = of,
                ofParameters = valueParameters(of.owner),
                siteLevels = siteLevels,
                originalLevels = originalLevels,
            )
        }

        private fun valueParameters(function: IrFunction): List<IrValueParameter> =
            function.parameters.filter { it.kind == IrParameterKind.Regular }
    }
}
