package moe.lolosia.keel.ksp.plugin

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

/**
 * 消费方子类胶水生成器。
 *
 * 扫描本模块里「继承 api 工件中 `@PublicApi` 类型」的普通类（例如 `class Dog : Animal()`），
 * 生成：
 *
 * - `<Type>Overrides`：该子类实际覆盖的方法线名集合，登记句柄时上报；
 * - `<Type>Dispatcher`：实例方分发器，只处理覆盖方法（声明方影子只在覆盖时转回来）。
 *
 * 与 api 生成一样采用 V1 提交制：KSP 的代码只能进目标 fragment，commonMain 看不到，
 * 所以直接写入 `keel.glue.dir`（本模块的 `src/commonMain/kotlin`），产物随仓库提交。
 */
internal class KeelSubclassGenerator(
    private val logger: KSPLogger,
    private val glueDir: String?,
) {

    private sealed interface Wire {
        object Unit : Wire
        object Text : Wire
        data class Primitive(val type: String) : Wire
        object Bytes : Wire
        data class Shared(val fqn: String, val proxy: String) : Wire
    }

    private class Param(val name: String, val fqn: String, val wire: Wire)

    private class Method(
        val name: String,
        val suspend: Boolean,
        val params: List<Param>,
        val result: Wire,
        val resultFqn: String,
    )

    fun generate(resolver: Resolver) {
        if (glueDir == null) return

        // KSP 每个 target 各跑一次，可能并发；用文件锁串行化
        val lock = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "keel-glue-${glueDir.hashCode()}.lock",
        )
        java.nio.channels.FileChannel.open(
            lock.toPath(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use { generateLocked(resolver) }
        }
    }

    private fun generateLocked(resolver: Resolver) {
        resolver.getAllFiles().toList()
            .flatMap { it.declarations.asSequence() }
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.CLASS }
            .filter { Modifier.ABSTRACT !in it.modifiers }
            .filter { !it.hasAnnotation(PUBLIC_API) && !it.hasAnnotation(KEEL_PLUGIN_MAIN) }
            .forEach { declaration ->
                val fqn = declaration.qualifiedName?.asString() ?: return@forEach
                // 只处理继承 api 工件（库）契约的子类；本模块内的实现归 impl 侧生成器管
                val contract = findContract(declaration) ?: return@forEach
                val methods = overriddenMethods(declaration, contract)
                if (methods.isEmpty()) return@forEach
                val typeId = contract.qualifiedName?.asString() ?: return@forEach
                emit(declaration, fqn, typeId, methods)
            }
    }

    private fun findContract(declaration: KSClassDeclaration): KSClassDeclaration? =
        declaration.getAllSuperTypes()
            .map { it.declaration }
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull {
                it.classKind == ClassKind.CLASS &&
                    it.containingFile == null &&
                    it.hasAnnotation(PUBLIC_API)
            }

    private fun overriddenMethods(
        declaration: KSClassDeclaration,
        contract: KSClassDeclaration,
    ): List<Method> {
        val names = contract.getDeclaredFunctions()
            .map { it.simpleName.asString() }
            .toSet()
        return declaration.getDeclaredFunctions()
            .filter { Modifier.OVERRIDE in it.modifiers }
            .filter { it.simpleName.asString() in names }
            .filter { it.simpleName.asString() !in ANY_METHODS }
            .mapNotNull { function ->
                if (function.parameters.size > 1) {
                    logger.warn("子类覆盖方法暂不支持多参数：${function.qualifiedName}", function)
                    return@mapNotNull null
                }
                val params = function.parameters.mapIndexed { index, parameter ->
                    val type = parameter.type.resolve()
                    val wire = wireOf(type)
                    if (wire == null) {
                        logger.warn("不支持的参数类型 ${type.renderName()}：${function.qualifiedName}", function)
                        return@mapNotNull null
                    }
                    Param(parameter.name?.asString() ?: "arg$index", type.renderName(), wire)
                }
                val resultType = function.returnType?.resolve() ?: return@mapNotNull null
                val result = wireOf(resultType)
                if (result == null) {
                    logger.warn("不支持的返回类型 ${resultType.renderName()}：${function.qualifiedName}", function)
                    return@mapNotNull null
                }
                Method(
                    name = function.simpleName.asString(),
                    suspend = Modifier.SUSPEND in function.modifiers,
                    params = params,
                    result = result,
                    resultFqn = resultType.renderName(),
                )
            }
            .toList()
    }

    private fun wireOf(type: KSType): Wire? {
        if (type.isMarkedNullable) return null
        if (type.arguments.isNotEmpty()) return null
        val name = type.declaration.qualifiedName?.asString() ?: return null
        return when (name) {
            "kotlin.Unit" -> Wire.Unit
            "kotlin.String" -> Wire.Text
            "kotlin.Int" -> Wire.Primitive("Int")
            "kotlin.Long" -> Wire.Primitive("Long")
            "kotlin.Short" -> Wire.Primitive("Short")
            "kotlin.Byte" -> Wire.Primitive("Byte")
            "kotlin.Boolean" -> Wire.Primitive("Boolean")
            "kotlin.Float" -> Wire.Primitive("Float")
            "kotlin.Double" -> Wire.Primitive("Double")
            "kotlin.ByteArray" -> Wire.Bytes
            else -> {
                // 共享类型既可能在 api klib 里，也可能在本模块；统一看 @PublicApi
                val declaration = type.declaration as? KSClassDeclaration
                if (declaration != null && declaration.hasAnnotation(PUBLIC_API)) {
                    Wire.Shared(
                        name,
                        "${name.substringBeforeLast('.')}.${declaration.simpleName.asString()}Proxy",
                    )
                } else {
                    null
                }
            }
        }
    }

    // ---------------------------------- 渲染 ----------------------------------

    private fun emit(
        declaration: KSClassDeclaration,
        fqn: String,
        typeId: String,
        methods: List<Method>,
    ) {
        val packageName = fqn.substringBeforeLast('.')
        val simpleName = declaration.simpleName.asString()
        val needsDirectory = methods.any { method ->
            method.params.any { it.wire is Wire.Shared } || method.result is Wire.Shared
        }
        val code = render(packageName, simpleName, typeId, methods, needsDirectory)
        val dir = java.io.File(glueDir, packageName.replace('.', '/'))
        write(dir, "${simpleName}Dispatcher.kt", code)
    }

    private fun render(
        packageName: String,
        simpleName: String,
        typeId: String,
        methods: List<Method>,
        needsDirectory: Boolean,
    ): String = buildString {
        appendLine(HEADER)
        appendLine("package $packageName")
        appendLine()
        val usesValue = methods.any { it.params.isNotEmpty() || it.result !is Wire.Unit }
        val usesShared = methods.any {
            it.params.any { param -> param.wire is Wire.Shared } || it.result is Wire.Shared
        }
        if (usesValue) appendLine("import kotlinx.serialization.builtins.serializer")
        if (methods.any { it.params.any { p -> p.wire is Wire.Bytes } || it.result is Wire.Bytes }) {
            appendLine("import kotlinx.serialization.builtins.ByteArraySerializer")
        }
        appendLine("import moe.lolosia.keel.api.service.ServiceWire")
        if (needsDirectory) appendLine("import moe.lolosia.keel.api.share.PublicApiDirectory")
        appendLine("import moe.lolosia.keel.api.share.PublicApiDispatcher")
        if (usesShared) appendLine("import moe.lolosia.keel.api.share.SharedRef")
        appendLine()
        appendLine("/** `${simpleName}Dispatcher` 覆盖的方法线名；登记句柄时上报。 */")
        appendLine(
            "val ${simpleName}Overrides: Set<String> = " +
                methods.joinToString(", ", "setOf(", ")") { "\"${it.name}\"" }
        )
        appendLine()
        appendLine("/** `$simpleName` 的实例方分发器。 */")
        val directory = if (needsDirectory) ", private val directory: PublicApiDirectory" else ""
        appendLine("class ${simpleName}Dispatcher(private val impl: $simpleName$directory) : PublicApiDispatcher {")
        appendLine()
        appendLine("    override val typeId: String = \"${escape(typeId)}\"")
        appendLine()
        appendLine("    override suspend fun dispatch(")
        appendLine("        handle: Long,")
        appendLine("        method: String,")
        appendLine("        payload: ByteArray,")
        appendLine("        callerModule: Long,")
        appendLine("        overrides: Set<String>,")
        appendLine("    ): ByteArray = when (method) {")
        methods.forEach { method ->
            appendLine("        \"${method.name}\" -> {")
            append(decodeParams(method, "            "))
            val call = "impl.${method.name}(${method.params.joinToString(", ") { it.name }})"
            when (val result = method.result) {
                Wire.Unit -> {
                    appendLine("            $call")
                    appendLine("            ByteArray(0)")
                }
                is Wire.Shared -> {
                    appendLine("            val value = $call")
                    appendLine(
                        "            ServiceWire.encode(SharedRef.serializer(), " +
                            "SharedRef(directory.handleOf(value)))"
                    )
                }
                else -> appendLine(
                    "            ServiceWire.encode(${serializer(result)}, $call)"
                )
            }
            appendLine("        }")
            appendLine()
        }
        appendLine("        else -> throw IllegalArgumentException(\"$simpleName 没有方法 \$method\")")
        appendLine("    }")
        appendLine("}")
    }

    private fun decodeParams(method: Method, indent: String): String = buildString {
        method.params.firstOrNull()?.let { param ->
            when (val wire = param.wire) {
                is Wire.Shared -> appendLine(
                    "${indent}val ${param.name} = " +
                        "${wire.proxy}(ServiceWire.decode(SharedRef.serializer(), payload).handle)"
                )
                else -> appendLine(
                    "${indent}val ${param.name} = " +
                        "ServiceWire.decode(${serializer(wire)}, payload)"
                )
            }
        }
    }

    private fun serializer(wire: Wire): String = when (wire) {
        Wire.Unit -> error("Unit 没有序列化器")
        Wire.Text -> "String.serializer()"
        is Wire.Primitive -> "${wire.type}.serializer()"
        Wire.Bytes -> "ByteArraySerializer()"
        is Wire.Shared -> "SharedRef.serializer()"
    }

    // ---------------------------------- 文件 ----------------------------------

    private fun write(dir: java.io.File, name: String, code: String) {
        val file = java.io.File(dir, name)
        if (file.isFile && !file.readText().startsWith(HEADER)) {
            logger.warn("胶水文件已存在且不是生成物，跳过：${file.path}")
            return
        }
        dir.mkdirs()
        val tmp = java.io.File.createTempFile(name, ".tmp", dir)
        tmp.writeText(code)
        try {
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Throwable) {
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun KSDeclaration.hasAnnotation(name: String): Boolean =
        annotations.any {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == name
        }

    private fun KSType.renderName(): String {
        val name = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
        val simple = when (name) {
            "kotlin.Unit" -> "Unit"
            "kotlin.String" -> "String"
            "kotlin.Int" -> "Int"
            "kotlin.Long" -> "Long"
            "kotlin.Short" -> "Short"
            "kotlin.Byte" -> "Byte"
            "kotlin.Boolean" -> "Boolean"
            "kotlin.Float" -> "Float"
            "kotlin.Double" -> "Double"
            "kotlin.ByteArray" -> "ByteArray"
            else -> name
        }
        return if (isMarkedNullable) "$simple?" else simple
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val PUBLIC_API = "moe.lolosia.keel.api.annotation.PublicApi"
        const val KEEL_PLUGIN_MAIN = "moe.lolosia.keel.api.annotation.KeelPluginMain"
        const val HEADER = "// 由 keel-plugin-ksp 生成，不要手改。"
        val ANY_METHODS = setOf("equals", "hashCode", "toString")
    }
}
