@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package moe.lolosia.keel.logger

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

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
     * 断点语句：`if (Debug.shouldStop(id)) { Debug.checkpoint(id, 名字, 类型, 值) }`。
     *
     * 函数既没有 `this` 也没有参数时退化成单独的 `Debug.checkpoint(id)`。
     * 有快照时**只发带快照的那一个调用**——它自己会停住，不能在前后再接一次 1 参调用，
     * 否则恢复后会在同一行再命中一次。
     */
    private fun checkpoint(statement: IrStatement, locationId: Int): IrStatement {
        val start = statement.startOffset
        val end = statement.endOffset
        val builder = builderAt(start, end)
        val snapshot = snapshotValues(builder)
        val checkpointValues = symbols.checkpointValues
        if (snapshot.values.isEmpty() || checkpointValues == null) {
            return call(builder, symbols.checkpoint, locationId)
        }

        val shouldStop = builder.irCall(symbols.shouldStop).apply {
            initializeTargetShapeFromSymbol()
            dispatchReceiver = builder.irGetObjectValue(symbols.debugType, symbols.debugClass)
            arguments[receiverOffset(symbols.shouldStop)] = builder.irInt(locationId)
        }
        val withValues = builder.irCall(checkpointValues).apply {
            initializeTargetShapeFromSymbol()
            dispatchReceiver = builder.irGetObjectValue(symbols.debugType, symbols.debugClass)
            val offset = receiverOffset(checkpointValues)
            arguments[offset] = builder.irInt(locationId)
            arguments[offset + 1] = stringArray(builder, snapshot.names)
            arguments[offset + 2] = stringArray(builder, snapshot.types)
            arguments[offset + 3] = IrVarargImpl(
                start,
                end,
                anyArrayType(),
                pluginContext.irBuiltIns.anyNType,
                snapshot.values,
            )
        }
        val unit = pluginContext.irBuiltIns.unitType
        return builder.irIfThen(unit, shouldStop, withValues)
    }

    /**
     * 命中时要上报的变量：`this` + 全部参数（扩展接收者也算 `this`）。
     *
     * 当前函数是 lambda 时没有有意义的 `this`，改成列出它捕获的字段（`captured.xxx`）。
     * 只覆盖**当前闭包直接持有**的捕获；再往上层的闭包要顺着 `$outer` 链找，
     * 这一版明确不做（见 `doc/debug-design.md`）。
     *
     * 只取值、不递归展开；`toString()` 由运行期做，不在热路径上。
     */
    private fun snapshotValues(builder: DeclarationIrBuilder): Snapshot {
        val function = currentScope?.scope?.scopeOwnerSymbol?.owner as? IrFunction
            ?: return Snapshot(emptyList(), emptyList(), emptyList())
        val names = ArrayList<IrExpression>(8)
        val types = ArrayList<IrExpression>(8)
        val values = ArrayList<IrExpression>(8)
        val lambda = function.origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA

        function.parameters.forEach { parameter ->
            when (parameter.kind) {
                IrParameterKind.DispatchReceiver -> {
                    // lambda 在这个阶段还是「局部函数」，没有有意义的 this；捕获在下面单独扫
                    if (!lambda) {
                        add(builder, names, types, values, "this", parameter.type, builder.irGet(parameter))
                    }
                }

                IrParameterKind.ExtensionReceiver -> {
                    val name = parameter.name
                    if (!name.isSpecial && !name.asString().contains('$')) {
                        add(builder, names, types, values, "this", parameter.type, builder.irGet(parameter))
                    }
                }

                IrParameterKind.Regular -> {
                    if (parameter.isVararg || parameter.name.isSpecial) return@forEach
                    add(
                        builder,
                        names,
                        types,
                        values,
                        parameter.name.asString(),
                        parameter.type,
                        builder.irGet(parameter),
                    )
                }

                else -> Unit
            }
        }
        if (lambda) captureValues(function, builder, names, types, values)
        return Snapshot(names, types, values)
    }

    /**
     * lambda 捕获的外层局部变量。
     *
     * 这个阶段 lambda 还是「局部函数」（`LOCAL_FUNCTION_FOR_LAMBDA`），捕获值在函数体里
     * 以 `IrGetValue` 直接引用外层声明；后端的 lambda lowering 会扫描函数体、把它们改写成
     * 闭包字段。我们插进去的取值表达式也在函数体里，所以同样会被列入捕获并改写。
     *
     * 只取当前这层直接引用的值；再往外一层的闭包要顺着 `$outer` 链找（见设计文档的边界）。
     */
    private fun captureValues(
        function: IrFunction,
        builder: DeclarationIrBuilder,
        names: MutableList<IrExpression>,
        types: MutableList<IrExpression>,
        values: MutableList<IrExpression>,
    ) {
        val seen = HashSet<String>()
        function.body?.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitGetValue(expression: IrGetValue) {
                val owner = expression.symbol.owner as? IrValueDeclaration ?: return
                if (owner.parent === function) return
                when (owner) {
                    // 只取源码里声明的局部变量；IR 临时量（tmp0、elvis 的 lhs 等）生命周期
                    // 可能已经结束，塞进闭包会让后端报「使用未初始化变量」
                    is IrVariable -> if (owner.origin != IrDeclarationOrigin.DEFINED) return
                    // 外层 lambda 的参数在 inline 之后可能已不存在（`runCatching { it -> ... }`），
                    // 引用它会生成「孤立参数」；只收普通函数的参数
                    is IrValueParameter -> {
                        if (owner.kind != IrParameterKind.Regular) return
                        val ownerFunction = owner.parent as? IrFunction
                        if (ownerFunction?.origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA) return
                    }

                    else -> return
                }
                val name = owner.name.asString()
                if (name.isEmpty() || owner.name.isSpecial || name.contains('$')) return
                if (!seen.add(name)) return
                add(
                    builder,
                    names,
                    types,
                    values,
                    "captured.$name",
                    owner.type,
                    builder.irGet(owner),
                )
            }
        })
    }

    private fun add(
        builder: DeclarationIrBuilder,
        names: MutableList<IrExpression>,
        types: MutableList<IrExpression>,
        values: MutableList<IrExpression>,
        name: String,
        type: IrType,
        value: IrExpression,
    ) {
        names += builder.irString(name)
        types += builder.irString(renderDebugType(type))
        values += value
    }

    private class Snapshot(
        val names: List<IrExpression>,
        val types: List<IrExpression>,
        val values: List<IrExpression>,
    )

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

    /** `Array<Any?>`：`checkpoint(locationId, names, types, values)` 的值数组。 */
    private fun anyArrayType(): IrType =
        pluginContext.irBuiltIns.arrayClass.typeWith(pluginContext.irBuiltIns.anyNType)

    /** `Array<String>`：`checkpoint` 的名字 / 类型数组。 */
    private fun stringArray(builder: DeclarationIrBuilder, values: List<IrExpression>): IrExpression =
        IrVarargImpl(
            builder.startOffset,
            builder.endOffset,
            pluginContext.irBuiltIns.arrayClass.typeWith(pluginContext.irBuiltIns.stringType),
            pluginContext.irBuiltIns.stringType,
            values,
        )

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
