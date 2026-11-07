package moe.lolosia.keel.logger

import org.jetbrains.kotlin.backend.common.ScopeWithIr
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.name.*

/**
 * 由 IR 作用域栈解析调用点来源。
 *
 * [scopes] 由内到外，来自 `IrElementTransformerVoidWithContext.getAllScopes()`。
 */
internal object LogSiteResolver {

    fun resolve(file: IrFile, scopes: List<ScopeWithIr>, line: Int, pathIndex: SourcePathIndex): LogSiteInfo {
        val packageName = file.packageFqName.asString().ifEmpty { null }
        val rawFile = file.fileEntry.name

        val classScope = scopes.firstOrNull { it.irElement is IrClass }?.irElement as? IrClass
        val functionScope = scopes.firstOrNull { it.irElement is IrFunction }?.irElement as? IrFunction

        val className = classScope?.let { fullClassName(it, packageName) }

        return LogSiteInfo(
            displayName = className ?: fileFacadeName(packageName, rawFile),
            file = pathIndex.relative(packageName.orEmpty(), rawFile),
            line = line,
            packageName = packageName,
            className = className,
            functionName = declarationName(scopes),
            kind = kindOf(classScope, functionScope, scopes),
        )
    }

    /** 异常诊断用的单行来源：`包.类.函数(File.kt:行)`；属性初始化器 / init 块没有函数名。 */
    fun throwSite(file: IrFile, scopes: List<ScopeWithIr>, line: Int): String {
        val packageName = file.packageFqName.asString().ifEmpty { null }
        val classScope = scopes.firstOrNull { it.irElement is IrClass }?.irElement as? IrClass
        val name = classScope?.let { fullClassName(it, packageName) }
            ?: fileFacadeName(packageName, file.fileEntry.name)
        val function = declarationName(scopes)
        val location = "(${sourceFileName(file.fileEntry.name)}:$line)"
        return if (function != null) "$name.$function$location" else "$name$location"
    }

    private fun sourceFileName(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\')

    private fun declarationName(scopes: List<ScopeWithIr>): String? =
        scopes.asSequence()
            .mapNotNull { it.irElement as? IrFunction }
            .firstOrNull { !isLambda(it) }
            ?.name
            ?.takeIf { !it.isSpecial }
            ?.asString()

    private fun kindOf(classScope: IrClass?, functionScope: IrFunction?, scopes: List<ScopeWithIr>): LoggerKind {
        if (functionScope == null) {
            return if (classScope != null) LoggerKind.CLASS_MEMBER_STATEMENT else LoggerKind.TOP_LEVEL_ANONYMOUS_FUNCTION
        }
        if (isLambda(functionScope)) {
            val hasNamedOuter = scopes.any { scope ->
                val element = scope.irElement
                element is IrFunction && !isLambda(element)
            }
            return if (hasNamedOuter || classScope != null) LoggerKind.ANONYMOUS_FUNCTION
            else LoggerKind.TOP_LEVEL_ANONYMOUS_FUNCTION
        }
        return when (functionScope.parent) {
            is IrClass -> LoggerKind.CLASS_FUNCTION
            is IrFile -> LoggerKind.TOP_LEVEL_FUNCTION
            else -> LoggerKind.NAMED_FUNCTION
        }
    }

    private fun isLambda(function: IrFunction): Boolean =
        function.origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA || function.name.isSpecial

    private fun fullClassName(classScope: IrClass, packageName: String?): String? {
        val names = ArrayList<String>()
        var current: IrDeclarationParent? = classScope
        while (current is IrClass) {
            if (current.name.isSpecial) return null
            names.add(current.name.asString())
            current = current.parent
        }
        names.reverse()
        return buildString {
            if (!packageName.isNullOrEmpty()) append(packageName).append('.')
            append(names.joinToString("."))
        }
    }

    private fun fileFacadeName(packageName: String?, rawFile: String): String {
        val base = rawFile.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        val facade = base.replaceFirstChar { it.uppercaseChar() } + "Kt"
        return if (packageName.isNullOrEmpty()) facade else "$packageName.$facade"
    }
}
