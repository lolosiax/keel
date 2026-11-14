@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.*
import org.jetbrains.kotlin.ir.util.*

/**
 * 调试插桩：断点检查点 + 影子栈 + 命中时的参数快照。
 *
 * - 检查点：在「行号变化处」插入 `if (Debug.shouldStop(id)) { publish(...); checkpoint(id) }`；
 *   不命中时只有一次位图查询，`publish` 的装箱与 `toString()` 都不执行。
 * - 影子栈：给含检查点的**同步**函数包一层 `Debug.enter(id)` / `try { ... } finally { Debug.exit(id) }`，
 *   运行期按线程维护调用链。`suspend` 函数与构造器不参与（协程会让帧跨线程漂移）。
 * - 位置表：`locationId` 与 `functionId` 同趟分配，`kind = line | enter`。
 */
internal class DebugSiteTransformer(
    private val pluginContext: IrPluginContext,
    private val symbols: DebugSymbols,
    private val pathIndex: SourcePathIndex,
    private val table: DebugLocationTable,
) : IrElementTransformerVoidWithContext() {

    /** 当前函数：位置表 id + 本函数内插了多少检查点（嵌套块也算）。 */
    private class Frame(val functionId: Int) {
        var checkpoints: Int = 0
    }

    private val frames = ArrayList<Frame>(8)

    override fun visitFunctionNew(declaration: IrFunction): IrStatement {
        val body = declaration.body as? IrBlockBody
        if (body == null || !isInstrumentable(declaration)) return super.visitFunctionNew(declaration)

        val file = runCatching { currentFile }.getOrNull() ?: return super.visitFunctionNew(declaration)
        if (isSyntheticFile(file)) return super.visitFunctionNew(declaration)

        val frame = Frame(table.nextFunctionId())
        frames.add(frame)
        try {
            val function = super.visitFunctionNew(declaration) as? IrFunction ?: return declaration
            val transformed = function.body as? IrBlockBody ?: return function
            instrument(transformed.statements, file, frame, skipHead = function is IrConstructor)
            if (frame.checkpoints > 0 && canTrackStack(function)) {
                wrapShadowStack(function, transformed, file, frame)
            }
            return function
        } finally {
            frames.removeAt(frames.size - 1)
        }
    }

    /** `if` / 循环 / `when` 分支这些嵌套块。 */
    override fun visitBlock(expression: IrBlock): IrExpression {
        val block = super.visitBlock(expression) as? IrBlock ?: return expression
        val frame = frames.lastOrNull() ?: return block
        if (isCompilerGenerated(block)) return block
        val file = runCatching { currentFile }.getOrNull() ?: return block
        instrument(block.statements, file, frame, skipHead = false)
        return block
    }

    /**
     * 后端的 lowering 会按固定形状消费这些块（`for` 循环要求恰好两条语句），
     * 往里加语句会触发编译器内部断言。
     */
    private fun isCompilerGenerated(block: IrBlock): Boolean = when (block.origin) {
        IrStatementOrigin.FOR_LOOP,
        IrStatementOrigin.ARGUMENTS_REORDERING_FOR_CALL,
        -> true

        else -> false
    }

    /**
     * 按行号变化插入检查点。
     *
     * @param skipHead 构造器的 super(...) / 属性初始化必须留在最前面
     */
    private fun instrument(
        statements: MutableList<IrStatement>,
        file: IrFile,
        frame: Frame,
        skipHead: Boolean,
    ) {
        if (statements.isEmpty()) return
        val original = statements.toList()
        val rebuilt = ArrayList<IrStatement>(original.size + 8)

        var index = 0
        if (skipHead) {
            while (index < original.size) {
                val statement = original[index]
                if (statement is IrDelegatingConstructorCall || statement is IrInstanceInitializerCall) {
                    rebuilt += statement
                    index++
                } else {
                    break
                }
            }
        }

        var line = -1
        while (index < original.size) {
            val statement = original[index]
            val currentLine = lineOf(file, statement)
            if (currentLine > 0 && currentLine != line) {
                line = currentLine
                val locationId = table.nextLocationId()
                table.add(
                    locationId,
                    frame.functionId,
                    LogSiteResolver.resolve(file, allScopes, currentLine, pathIndex),
                    KIND_LINE,
                )
                frame.checkpoints++
                rebuilt += checkpoint(statement, locationId)
            }
            rebuilt += statement
            index++
        }

        if (rebuilt.size == original.size) return
        statements.clear()
        statements.addAll(rebuilt)
    }

    /**
     * 断点语句：`if (Debug.shouldStop(id)) { Debug.publish(id, 名字, 值, ...); Debug.checkpoint(id) }`。
     *
     * 函数没有 `this` 也没有参数时退化成单独的 `Debug.checkpoint(id)`。
     */
    private fun checkpoint(statement: IrStatement, locationId: Int): IrStatement {
        val start = statement.startOffset
        val end = statement.endOffset
        val builder = builderAt(start, end)
        val checkpointCall = call(builder, symbols.checkpoint, locationId)
        val pairs = snapshotValues(builder)
        if (pairs.isEmpty()) return checkpointCall

        val shouldStop = builder.irCall(symbols.shouldStop).apply {
            initializeTargetShapeFromSymbol()
            dispatchReceiver = builder.irGetObjectValue(symbols.debugType, symbols.debugClass)
            arguments[receiverOffset(symbols.shouldStop)] = builder.irInt(locationId)
        }
        val publish = builder.irCall(symbols.publish).apply {
            initializeTargetShapeFromSymbol()
            dispatchReceiver = builder.irGetObjectValue(symbols.debugType, symbols.debugClass)
            val offset = receiverOffset(symbols.publish)
            arguments[offset] = builder.irInt(locationId)
            arguments[offset + 1] = IrVarargImpl(
                start,
                end,
                anyArrayType(),
                pluginContext.irBuiltIns.anyNType,
                pairs,
            )
        }
        val unit = pluginContext.irBuiltIns.unitType
        val thenBranch = IrBlockImpl(start, end, unit).apply {
            statements.add(publish)
            statements.add(checkpointCall)
        }
        return builder.irIfThen(unit, shouldStop, thenBranch)
    }

    /**
     * 命中时要上报的值：`this` + 全部参数（扩展接收者也算参数）。
     *
     * 只取值、不递归展开；`toString()` 由运行期做，不在热路径上。
     */
    private fun snapshotValues(builder: DeclarationIrBuilder): List<IrVarargElement> {
        val function = currentScope?.scope?.scopeOwnerSymbol?.owner as? IrFunction ?: return emptyList()
        val values = ArrayList<IrVarargElement>(8)
        function.parameters.forEach { parameter ->
            when (parameter.kind) {
                IrParameterKind.DispatchReceiver -> {
                    values += builder.irString("this")
                    values += builder.irGet(parameter)
                }

                IrParameterKind.ExtensionReceiver -> {
                    values += builder.irString("this")
                    values += builder.irGet(parameter)
                }

                IrParameterKind.Regular -> {
                    if (parameter.isVararg || parameter.name.isSpecial) return@forEach
                    values += builder.irString(parameter.name.asString())
                    values += builder.irGet(parameter)
                }

                else -> Unit
            }
        }
        return values
    }

    /** 影子栈：入口压栈、`finally` 出栈。 */
    private fun wrapShadowStack(function: IrFunction, body: IrBlockBody, file: IrFile, frame: Frame) {
        val line = declarationLine(file, function)
        val enterId = table.nextLocationId()
        table.add(enterId, frame.functionId, LogSiteResolver.resolve(file, allScopes, line, pathIndex), KIND_ENTER)

        val builder = builderAt(function.startOffset, function.endOffset)
        val enterCall = call(builder, symbols.enter, enterId)
        val exitCall = call(builder, symbols.exit, enterId)

        val unit = pluginContext.irBuiltIns.unitType
        val tryBody = IrBlockImpl(function.startOffset, function.endOffset, unit).apply {
            statements.addAll(body.statements)
        }
        val finallyBody = IrBlockImpl(function.startOffset, function.endOffset, unit).apply {
            statements.add(exitCall)
        }
        val tryExpression = IrTryImpl(function.startOffset, function.endOffset, unit).apply {
            tryResult = tryBody
            finallyExpression = finallyBody
        }

        body.statements.clear()
        body.statements.add(enterCall)
        body.statements.add(tryExpression)
    }

    /** `Array<Any?>`：变长参数 `publish(locationId, vararg values)` 的形参类型。 */
    private fun anyArrayType(): IrType =
        pluginContext.irBuiltIns.arrayClass.typeWith(pluginContext.irBuiltIns.anyNType)

    private fun call(
        builder: DeclarationIrBuilder,
        symbol: IrSimpleFunctionSymbol,
        locationId: Int,
    ): IrCall = builder.irCall(symbol).apply {
        initializeTargetShapeFromSymbol()
        dispatchReceiver = builder.irGetObjectValue(symbols.debugType, symbols.debugClass)
        arguments[receiverOffset(symbol)] = builder.irInt(locationId)
    }

    /** `Debug` 是 object，第 0 个参数是 dispatch receiver。 */
    private fun receiverOffset(symbol: IrSimpleFunctionSymbol): Int =
        symbol.owner.parameters.count { it.kind != IrParameterKind.Regular }

    private fun builderAt(start: Int, end: Int): DeclarationIrBuilder {
        val owner = currentScope?.scope?.scopeOwnerSymbol ?: currentFile.symbol
        return DeclarationIrBuilder(pluginContext, owner, start, end)
    }

    private fun declarationLine(file: IrFile, function: IrFunction): Int {
        if (function.startOffset >= 0) {
            val line = file.fileEntry.getLineNumber(function.startOffset)
            if (line >= 0) return line + 1
        }
        return 1
    }

    private fun lineOf(file: IrFile, statement: IrStatement): Int {
        if (statement.startOffset < 0) return -1
        val line = file.fileEntry.getLineNumber(statement.startOffset)
        return if (line < 0) -1 else line + 1
    }

    /** 跳过编译器合成体与没有真实源码的函数。 */
    private fun isInstrumentable(function: IrFunction): Boolean {
        if (function.isFakeOverride) return false
        if (function.startOffset < 0 || function.endOffset < 0) return false
        return true
    }

    /**
     * 影子栈覆盖同步与 `suspend` 函数：栈跟着「当前协程」走（`DebugFrameElement` 切的），
     * 挂起时 `finally` 不执行，帧留在那个协程的帧表里；恢复在哪个线程都行。
     * 构造器除外（`this` 初始化期间插 try/finally 风险大）。
     */
    private fun canTrackStack(function: IrFunction): Boolean = function !is IrConstructor

    private fun isSyntheticFile(file: IrFile): Boolean {
        val name = file.fileEntry.name
        return name.isBlank() || name.startsWith("<")
    }

    private companion object {
        /** 位置表 `kind` 列的取值。 */
        const val KIND_LINE = "line"
        const val KIND_ENTER = "enter"
    }
}
