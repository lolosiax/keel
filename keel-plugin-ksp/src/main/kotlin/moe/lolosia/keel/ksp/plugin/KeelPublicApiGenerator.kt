package moe.lolosia.keel.ksp.plugin

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

/**
 * `@PublicApi` 代码生成器。
 *
 * 扫描本模块的 `@PublicApi` 类型，生成：
 * - `KeelPluginPublicApi`：类型声明、分发器收集、插件单例登记；
 * - 声明方侧的 `<Type>Dispatcher` / `<Type>Shadow`；
 * - 本地代理 `<Type>Proxy`。
 *
 * 标了 `@PublicApiHandler(Type::class)` 的类型视为「胶水手写」，不生成上述类，
 * 由开发者自行提供（例如通过 `constructor(...)` 注册的所有者侧分发器）。
 */
internal class KeelPublicApiGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {

    private enum class Kind { CLASS, INTERFACE, PLUGIN }

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
        val open: Boolean,
        val params: List<Param>,
        val result: Wire,
        val resultFqn: String,
    )

    private class TypeModel(
        val declaration: KSClassDeclaration,
        val fqn: String,
        val simpleName: String,
        val id: String,
        val kind: Kind,
        val manual: Boolean,
        val supported: Boolean,
        val methods: List<Method>,
    ) {
        val hasBase: Boolean get() = kind != Kind.INTERFACE
        val packageName: String get() = fqn.substringBeforeLast('.')
        val singleton: Boolean
            get() = kind == Kind.PLUGIN ||
                declaration.annotations.any {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == SINGLETON
                }
        val dispatcherNeedsDirectory: Boolean get() = methods.any { it.result is Wire.Shared }
        val proxyFqn: String get() = "$packageName.${simpleName}Proxy"
        val shadowFqn: String get() = "$packageName.${simpleName}Shadow"
        val dispatcherFqn: String get() = "$packageName.${simpleName}Dispatcher"
    }

    private class HandlerEntry(
        val target: String,
        val expression: String,
        val usesPlugin: Boolean,
    )

    private class Instantiation(val expression: String, val usesPlugin: Boolean)

    fun generate(resolver: Resolver, main: KSClassDeclaration, apiDir: String? = null) {
        val mainFqn = main.qualifiedName?.asString()
        if (mainFqn == null) {
            logger.error("@KeelPluginMain 只能标在具名类上", main)
            return
        }

        val declared = resolver.getSymbolsWithAnnotation(PUBLIC_API)
            .filterIsInstance<KSClassDeclaration>()
            .filter {
                it.classKind == ClassKind.CLASS ||
                    it.classKind == ClassKind.INTERFACE ||
                    it.classKind == ClassKind.OBJECT
            }
            .toList()

        // @KeelPluginMain 语义上等价于 @PublicApi + @Singleton。
        // KSP 不会传播元注解，这里显式把插件本体并入声明集合。
        val declarations = (declared + main)
            .filter {
                it.classKind == ClassKind.CLASS ||
                    it.classKind == ClassKind.INTERFACE ||
                    it.classKind == ClassKind.OBJECT
            }
            .distinctBy { it.qualifiedName?.asString() }
            .sortedBy { it.qualifiedName?.asString() }
            .toList()

        val handlerDeclarations = resolver.getSymbolsWithAnnotation(PUBLIC_API_HANDLER)
            .filterIsInstance<KSClassDeclaration>()
            .distinctBy { it.qualifiedName?.asString() }
            .sortedBy { it.qualifiedName?.asString() }
            .toList()

        val shared = declarations.associateBy { it.qualifiedName?.asString() ?: "" }
        val manualTargets = handlerDeclarations.mapNotNull { handlerTarget(it) }.toSet()
        val handlerEntries = handlerDeclarations.mapNotNull { handler ->
            val target = handlerTarget(handler)
            if (target == null) {
                logger.error("@PublicApiHandler 的 type 无法解析", handler)
                return@mapNotNull null
            }
            val instantiation = handlerInstantiation(handler, mainFqn, main.asStarProjectedType())
            if (instantiation == null) {
                // 所有者侧手写分发器通常通过 constructor(...) 注册，不需要进 handlers
                logger.info("@PublicApiHandler ${handler.qualifiedName} 不在 setup 里实例化", handler)
                return@mapNotNull null
            }
            HandlerEntry(target, instantiation.expression, instantiation.usesPlugin)
        }

        val models = declarations.mapNotNull { declaration ->
            val fqn = declaration.qualifiedName?.asString() ?: return@mapNotNull null
            val kind = when {
                fqn == mainFqn -> Kind.PLUGIN
                declaration.classKind == ClassKind.INTERFACE -> Kind.INTERFACE
                declaration.classKind == ClassKind.CLASS -> Kind.CLASS
                else -> {
                    logger.error("@PublicApi 暂不支持 object 类型：$fqn", declaration)
                    return@mapNotNull null
                }
            }
            val manual = fqn in manualTargets
            val methods = if (manual) emptyList() else extractMethods(declaration, kind, shared)
            TypeModel(
                declaration = declaration,
                fqn = fqn,
                simpleName = declaration.simpleName.asString(),
                id = idOf(declaration) ?: fqn,
                kind = kind,
                manual = manual,
                supported = manual || methods != null,
                methods = methods ?: emptyList(),
            )
        }

        models.filter { !it.manual && it.supported }.forEach { model ->
            writeGlue(model)
        }

        val dependencies = (declarations + handlerDeclarations)
            .mapNotNull { it.containingFile }
            .distinct()
            .toTypedArray()
        writeFile(
            fileName = "KeelPluginPublicApi",
            packageName = main.packageName.asString(),
            code = renderSetup(mainFqn, models, handlerEntries),
            dependencies = dependencies,
        )

        if (apiDir != null) {
            emitApi(models, apiDir)
        } else if (models.any { !it.manual && it.kind != Kind.PLUGIN }) {
            logger.warn(
                "存在 @PublicApi 类型但本构建没有 api 工程；如需导出，创建 api/ 并配置 keel.api.dir",
                main,
            )
        }
    }

    // ---------------------------------- 模型提取 ----------------------------------

    private fun extractMethods(
        declaration: KSClassDeclaration,
        kind: Kind,
        shared: Map<String, KSClassDeclaration>,
    ): List<Method>? {
        if (kind == Kind.CLASS && Modifier.OPEN !in declaration.modifiers) {
            logger.error("@PublicApi 类必须 open，才能生成代理与影子：${declaration.qualifiedName}", declaration)
            return null
        }

        val functions = declaration.getDeclaredFunctions()
            .filter { function ->
                function.simpleName.asString() != "<init>" &&
                    function.simpleName.asString() !in ANY_METHODS &&
                    function.extensionReceiver == null &&
                    function.typeParameters.isEmpty() &&
                    function.visibility() == Visibility.PUBLIC
            }
            .filter { function ->
                when (kind) {
                    Kind.PLUGIN -> function.annotations.any { it.isNamed(PUBLIC_API) }
                    Kind.CLASS -> isOverridable(function)
                    Kind.INTERFACE -> true
                }
            }
            .toList()

        if (functions.map { it.simpleName.asString() }.distinct().size != functions.size) {
            logger.error("暂不支持重载的 @PublicApi 方法：${declaration.qualifiedName}", declaration)
            return null
        }

        val methods = mutableListOf<Method>()
        for (function in functions) {
            if (Modifier.INLINE in function.modifiers) {
                logger.error("@PublicApi 方法不能是 inline：${function.qualifiedName}", function)
                return null
            }
            if (function.parameters.size > 1) {
                logger.error("暂不支持多参数的 @PublicApi 方法：${function.qualifiedName}", function)
                return null
            }
            val params = function.parameters.mapIndexed { index, parameter ->
                if (parameter.isVararg) {
                    logger.error("@PublicApi 方法不能有 vararg：${function.qualifiedName}", function)
                    return null
                }
                val type = parameter.type.resolve()
                val wire = wireOf(type, shared)
                if (wire == null) {
                    logger.error("不支持的参数类型 ${type.renderName()}：${function.qualifiedName}", function)
                    return null
                }
                if (kind != Kind.PLUGIN && wire is Wire.Shared) {
                    logger.error(
                        "暂不支持代理方法带共享类型参数：${function.qualifiedName}",
                        function,
                    )
                    return null
                }
                Param(parameter.name?.asString() ?: "arg$index", type.renderName(), wire)
            }

            val resultType = function.returnType?.resolve() ?: run {
                logger.error("无法解析返回类型：${function.qualifiedName}", function)
                return null
            }
            val result = wireOf(resultType, shared)
            if (result == null) {
                logger.error("不支持的返回类型 ${resultType.renderName()}：${function.qualifiedName}", function)
                return null
            }

            methods += Method(
                name = function.simpleName.asString(),
                suspend = Modifier.SUSPEND in function.modifiers,
                open = kind != Kind.PLUGIN && isOverridable(function),
                params = params,
                result = result,
                resultFqn = resultType.renderName(),
            )
        }
        return methods
    }

    private fun wireOf(type: KSType, shared: Map<String, KSClassDeclaration>): Wire? {
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
                val declaration = shared[name]
                if (declaration == null) null else {
                    val simple = declaration.simpleName.asString()
                    Wire.Shared(name, "${name.substringBeforeLast('.')}.${simple}Proxy")
                }
            }
        }
    }

    private fun isOverridable(function: KSFunctionDeclaration): Boolean {
        if (Modifier.FINAL in function.modifiers) return false
        return Modifier.OPEN in function.modifiers ||
            Modifier.ABSTRACT in function.modifiers ||
            Modifier.OVERRIDE in function.modifiers
    }

    private fun KSDeclaration.visibility(): Visibility = when {
        Modifier.PRIVATE in modifiers -> Visibility.PRIVATE
        Modifier.PROTECTED in modifiers -> Visibility.PROTECTED
        Modifier.INTERNAL in modifiers -> Visibility.INTERNAL
        else -> Visibility.PUBLIC
    }

    // ---------------------------------- 渲染 ----------------------------------

    // ---------------------------------- api 工件（V1 提交制） ----------------------------------

    /**
     * 把 api 侧需要的代理 / Key / 访问器直接写进 `api/src/commonMain/kotlin`。
     *
     * KSP 的 `CodeGenerator` 只能写当前编译输出（在 KMP 里是目标 fragment，common 代码看不到），
     * 所以这里用普通文件写入；落点由 `keel.api.dir` 指定，生成物随仓库提交。
     * 只写 `XXXProxy.kt` / `XXXKey.kt` / `XXXAccessor.kt`，不碰镜像；
     * 已存在且不是生成物的文件绝不覆盖。
     */
    private fun emitApi(models: List<TypeModel>, dir: String) {
        val targets = models.filter { !it.manual && it.supported }
        if (targets.isEmpty()) return

        val root = java.io.File(dir)
        root.mkdirs()
        val directoryNeeds = apiDirectoryNeeds(targets)
        // 三个 target 各跑一次 KSP，可能并发；用文件锁串行化
        val lock = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "keel-api-${dir.hashCode()}.lock",
        )
        java.nio.channels.FileChannel.open(
            lock.toPath(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                targets.forEach { model ->
                    val packageDir = java.io.File(root, model.packageName.replace('.', '/'))
                    val keyWritten = writeApiFile(
                        packageDir,
                        "${model.simpleName}Key.kt",
                        renderApiKey(model),
                    )
                    val proxyWritten = writeApiFile(
                        packageDir,
                        "${model.simpleName}Proxy.kt",
                        renderApiProxy(model, directoryNeeds),
                    )
                    if (!model.singleton) return@forEach
                    if (!keyWritten || !proxyWritten) {
                        logger.warn(
                            "api 的 Key / Proxy 被手写占用，跳过访问器：${model.fqn}",
                            model.declaration,
                        )
                        return@forEach
                    }
                    writeApiFile(
                        packageDir,
                        "${model.simpleName}Accessor.kt",
                        renderApiAccessor(model, directoryNeeds),
                    )
                }
            }
        }
    }

    private fun apiDirectoryNeeds(models: List<TypeModel>): Map<String, Boolean> {
        val needs = models.associate { it.fqn to false }.toMutableMap()
        var changed = true
        while (changed) {
            changed = false
            models.forEach { model ->
                if (needs[model.fqn] == true) return@forEach
                val value = model.methods.any { method ->
                    method.params.any { it.wire is Wire.Shared } ||
                        (method.result as? Wire.Shared)?.let { needs[it.fqn] == true } == true
                }
                if (value) {
                    needs[model.fqn] = true
                    changed = true
                }
            }
        }
        return needs
    }

    private fun renderApiKey(model: TypeModel): String = buildString {
        appendLine(API_HEADER)
        appendLine("package ${model.packageName}")
        appendLine()
        appendLine("import moe.lolosia.keel.api.share.PublicApiKey")
        appendLine()
        appendLine("/** `${model.fqn}` 的跨插件类型 id；声明方与消费方必须一致。 */")
        appendLine("const val ${apiConstName(model)}: String = \"${escape(model.id)}\"")
        appendLine()
        appendLine("/** `${model.fqn}` 的查找键。 */")
        appendLine(
            "val ${apiKeyName(model)} = PublicApiKey<${model.simpleName}>(${apiConstName(model)})"
        )
    }

    private fun renderApiProxy(model: TypeModel, directoryNeeds: Map<String, Boolean>): String =
        buildString {
            appendLine(API_HEADER)
            appendLine("package ${model.packageName}")
            appendLine()

            val needsDirectory = directoryNeeds[model.fqn] == true
            val usesValue = model.methods.any { it.params.isNotEmpty() || it.result !is Wire.Unit }
            val usesShared = model.methods.any { method ->
                method.params.any { it.wire is Wire.Shared } || method.result is Wire.Shared
            }
            val usesBytes = model.methods.any { method ->
                method.params.any { it.wire is Wire.Bytes } || method.result is Wire.Bytes
            }
            if (usesValue) appendLine("import kotlinx.serialization.builtins.serializer")
            if (usesBytes) appendLine("import kotlinx.serialization.builtins.ByteArraySerializer")
            appendLine("import moe.lolosia.keel.api.service.ServiceWire")
            appendLine("import moe.lolosia.keel.api.share.PublicApiBridge")
            if (needsDirectory) appendLine("import moe.lolosia.keel.api.share.PublicApiDirectory")
            if (usesShared) appendLine("import moe.lolosia.keel.api.share.SharedRef")
            appendLine()

            appendLine("/** `${model.fqn}` 在消费方运行时里的代理。 */")
            val directory = if (needsDirectory) ", private val directory: PublicApiDirectory" else ""
            when (model.kind) {
                Kind.CLASS -> {
                    appendLine("class ${model.simpleName}Proxy(")
                    appendLine("    proxyHandle: Long$directory,")
                    appendLine(") : ${model.fqn}() {")
                    appendLine()
                    appendLine("    init {")
                    appendLine("        sharedHandle = proxyHandle")
                    appendLine("    }")
                }
                else -> {
                    appendLine("class ${model.simpleName}Proxy(")
                    appendLine("    private val proxyHandle: Long$directory,")
                    appendLine(") : ${model.fqn} {")
                }
            }

            val handle = if (model.kind == Kind.CLASS) "sharedHandle" else "proxyHandle"
            model.methods.forEach { method ->
                appendLine()
                appendLine(
                    "    override ${if (method.suspend) "suspend " else ""}fun " +
                        "${method.name}(${signature(method.params)}): ${method.resultFqn} {"
                )
                val call = apiBridgeCall(model, method, handle)
                when (val result = method.result) {
                    Wire.Unit -> appendLine("        $call")
                    is Wire.Shared -> {
                        appendLine(
                            "        val ref = ServiceWire.decode(SharedRef.serializer(), $call)"
                        )
                        val extra = if (directoryNeeds[result.fqn] == true) ", directory" else ""
                        appendLine("        return ${result.proxy}(ref.handle$extra)")
                    }
                    else -> appendLine(
                        "        return ServiceWire.decode(${serializer(result)}, $call)"
                    )
                }
                appendLine("    }")
            }
            appendLine("}")
        }

    private fun apiBridgeCall(model: TypeModel, method: Method, handle: String): String {
        val args = mutableListOf(handle, "\"${escape(model.id)}\"", "\"${method.name}\"")
        method.params.firstOrNull()?.let { param ->
            args += when (val wire = param.wire) {
                is Wire.Shared ->
                    "ServiceWire.encode(SharedRef.serializer(), " +
                        "SharedRef(directory.handleOf(${param.name})))"
                else -> "ServiceWire.encode(${serializer(wire)}, ${param.name})"
            }
        }
        return "PublicApiBridge.call(${args.joinToString(", ")})"
    }

    private fun renderApiAccessor(model: TypeModel, directoryNeeds: Map<String, Boolean>): String =
        buildString {
            appendLine(API_HEADER)
            appendLine("package ${model.packageName}")
            appendLine()
            appendLine("import moe.lolosia.keel.api.PluginContext")
            appendLine()
            appendLine("/** 声明方插件 `${model.fqn}` 的单例访问器。 */")
            appendLine("val PluginContext.${apiAccessorName(model)}: ${model.simpleName}")
            appendLine("    get() {")
            if (directoryNeeds[model.fqn] == true) {
                appendLine("        val directory = publicApi")
                appendLine(
                    "        return directory.requireInstance(${apiKeyName(model)}) { " +
                        "${model.simpleName}Proxy(it, directory) }"
                )
            } else {
                appendLine(
                    "        return publicApi.requireInstance(${apiKeyName(model)}) { " +
                        "${model.simpleName}Proxy(it) }"
                )
            }
            appendLine("    }")
        }

    private fun writeApiFile(dir: java.io.File, name: String, code: String): Boolean {
        val file = java.io.File(dir, name)
        if (file.isFile && !file.readText().startsWith(API_HEADER)) {
            logger.warn("api 文件已存在且不是生成物，跳过：${file.path}")
            return false
        }
        dir.mkdirs()
        val tmp = java.io.File.createTempFile(name, ".tmp", dir)
        tmp.writeText(code)
        val target = file.toPath()
        try {
            java.nio.file.Files.move(
                tmp.toPath(),
                target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Throwable) {
            java.nio.file.Files.move(
                tmp.toPath(),
                target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return true
    }

    private fun apiConstName(model: TypeModel): String =
        "${screamingSnake(model.simpleName)}_TYPE_ID"

    private fun apiKeyName(model: TypeModel): String = "${model.simpleName}Key"

    private fun apiAccessorName(model: TypeModel): String =
        model.simpleName.replaceFirstChar { it.lowercaseChar() }

    private fun screamingSnake(name: String): String = buildString {
        name.forEachIndexed { index, char ->
            if (char.isUpperCase() && index > 0 && !name[index - 1].isUpperCase()) {
                append('_')
            }
            append(char.uppercaseChar())
        }
    }

    private fun writeGlue(model: TypeModel) {
        if (model.kind != Kind.PLUGIN) {
            writeFile(
                fileName = "${model.simpleName}Proxy",
                packageName = model.packageName,
                code = renderProxy(model),
                dependencies = listOfNotNull(model.declaration.containingFile).toTypedArray(),
            )
        }
        if (model.kind == Kind.CLASS) {
            writeFile(
                fileName = "${model.simpleName}Shadow",
                packageName = model.packageName,
                code = renderShadow(model),
                dependencies = listOfNotNull(model.declaration.containingFile).toTypedArray(),
            )
        }
        if (model.kind != Kind.INTERFACE) {
            writeFile(
                fileName = "${model.simpleName}Dispatcher",
                packageName = model.packageName,
                code = renderDispatcher(model),
                dependencies = listOfNotNull(model.declaration.containingFile).toTypedArray(),
            )
        }
    }

    private fun renderProxy(model: TypeModel): String = buildString {
        appendLine("// 由 keel-plugin-ksp 生成，不要手改。")
        appendLine("package ${model.packageName}")
        appendLine()
        appendLine("import kotlinx.serialization.builtins.serializer")
        appendLine("import moe.lolosia.keel.api.service.ServiceWire")
        appendLine("import moe.lolosia.keel.api.share.PublicApiBridge")
        appendLine("import moe.lolosia.keel.api.share.SharedRef")
        appendLine()
        appendLine("/** `${model.fqn}` 的本地代理。 */")
        val superType = if (model.kind == Kind.INTERFACE) model.fqn else "${model.fqn}()"
        appendLine("internal class ${model.simpleName}Proxy(private val proxyHandle: Long) : $superType {")
        model.methods.forEach { method ->
            appendLine()
            appendLine(
                "    override ${if (method.suspend) "suspend " else ""}fun " +
                    "${method.name}(${signature(method.params)}): ${method.resultFqn} {"
            )
            append(proxyBody(model, method, "        "))
            appendLine("    }")
        }
        appendLine("}")
    }

    private fun proxyBody(model: TypeModel, method: Method, indent: String): String = buildString {
        val call = bridgeCall(model, method)
        when (val result = method.result) {
            Wire.Unit -> appendLine("$indent$call")
            is Wire.Shared -> {
                appendLine("$indent val ref = ServiceWire.decode(SharedRef.serializer(), $call)")
                appendLine("$indent return ${result.proxy}(ref.handle)")
            }
            else -> appendLine("$indent return ServiceWire.decode(${serializer(result)}, $call)")
        }
    }

    private fun bridgeCall(model: TypeModel, method: Method): String {
        val args = mutableListOf("proxyHandle", "\"${escape(model.id)}\"", "\"${method.name}\"")
        method.params.firstOrNull()?.let { param ->
            args += "ServiceWire.encode(${serializer(param.wire)}, ${param.name})"
        }
        return "PublicApiBridge.call(${args.joinToString(", ")})"
    }

    private fun renderShadow(model: TypeModel): String = """
        |// 由 keel-plugin-ksp 生成，不要手改。
        |package ${model.packageName}
        |
        |/** `${model.fqn}` 的声明方基类影子。 */
        |internal class ${model.simpleName}Shadow : ${model.fqn}()
        |
    """.trimMargin()

    private fun renderDispatcher(model: TypeModel): String = when (model.kind) {
        Kind.PLUGIN -> renderOwnerDispatcher(model)
        Kind.CLASS -> renderShadowDispatcher(model)
        Kind.INTERFACE -> error("接口不生成分发器")
    }

    private fun renderOwnerDispatcher(model: TypeModel): String = buildString {
        appendLine("// 由 keel-plugin-ksp 生成，不要手改。")
        appendLine("package ${model.packageName}")
        appendLine()
        appendLine("import kotlinx.serialization.builtins.serializer")
        appendLine("import moe.lolosia.keel.api.service.ServiceWire")
        appendLine("import moe.lolosia.keel.api.share.PublicApiDirectory")
        appendLine("import moe.lolosia.keel.api.share.PublicApiDispatcher")
        appendLine("import moe.lolosia.keel.api.share.SharedRef")
        appendLine()
        appendLine("/** `${model.fqn}` 的所有者侧分发器。 */")
        val directory = if (model.dispatcherNeedsDirectory) ", private val directory: PublicApiDirectory" else ""
        appendLine("internal class ${model.simpleName}Dispatcher(")
        appendLine("    private val impl: ${model.fqn}$directory,")
        appendLine(") : PublicApiDispatcher {")
        appendLine()
        appendLine("    override val typeId: String = \"${escape(model.id)}\"")
        appendLine()
        appendLine("    override suspend fun dispatch(")
        appendLine("        handle: Long,")
        appendLine("        method: String,")
        appendLine("        payload: ByteArray,")
        appendLine("        callerModule: Long,")
        appendLine("        overrides: Set<String>,")
        appendLine("    ): ByteArray = when (method) {")
        model.methods.forEach { method ->
            appendLine("        \"${method.name}\" -> {")
            append(decodeParams(method, "            "))
            append(ownerCall(method, "            "))
            appendLine("        }")
            appendLine()
        }
        appendLine("        else -> throw IllegalArgumentException(\"${model.simpleName} 没有方法 \$method\")")
        appendLine("    }")
        appendLine("}")
    }

    private fun renderShadowDispatcher(model: TypeModel): String = buildString {
        appendLine("// 由 keel-plugin-ksp 生成，不要手改。")
        appendLine("package ${model.packageName}")
        appendLine()
        appendLine("import kotlinx.serialization.builtins.serializer")
        appendLine("import moe.lolosia.keel.api.service.ServiceWire")
        appendLine("import moe.lolosia.keel.api.share.PublicApiBridge")
        appendLine("import moe.lolosia.keel.api.share.PublicApiDirectory")
        appendLine("import moe.lolosia.keel.api.share.PublicApiDispatcher")
        appendLine("import moe.lolosia.keel.api.share.SharedRef")
        appendLine()
        appendLine("/** `${model.fqn}` 的声明方分发器。 */")
        val directory = if (model.dispatcherNeedsDirectory) "private val directory: PublicApiDirectory" else ""
        appendLine("internal class ${model.simpleName}Dispatcher($directory) : PublicApiDispatcher {")
        appendLine()
        appendLine("    override val typeId: String = \"${escape(model.id)}\"")
        appendLine()
        appendLine("    private val shadows = mutableMapOf<Long, ${model.shadowFqn}>()")
        appendLine()
        appendLine("    override suspend fun dispatch(")
        appendLine("        handle: Long,")
        appendLine("        method: String,")
        appendLine("        payload: ByteArray,")
        appendLine("        callerModule: Long,")
        appendLine("        overrides: Set<String>,")
        appendLine("    ): ByteArray {")
        appendLine("        val shadow = shadows.getOrPut(handle) { ${model.shadowFqn}() }")
        appendLine("        return when (method) {")
        model.methods.forEach { method ->
            appendLine("            \"${method.name}\" -> {")
            if (method.open) {
                appendLine("                if (\"${method.name}\" in overrides) {")
                appendLine("                    forward(handle, \"${method.name}\", payload)")
                appendLine("                } else {")
                append(decodeParams(method, "                    "))
                append(localCall(method, "                    "))
                appendLine("                }")
                if (method.result is Wire.Unit) {
                    appendLine("                ByteArray(0)")
                }
            } else {
                append(decodeParams(method, "                "))
                append(localCall(method, "                "))
                if (method.result is Wire.Unit) {
                    appendLine("                ByteArray(0)")
                }
            }
            appendLine("            }")
            appendLine()
            appendLine("            \"base.${method.name}\" -> {")
            append(decodeParams(method, "                "))
            append(localCall(method, "                "))
            if (method.result is Wire.Unit) {
                appendLine("                ByteArray(0)")
            }
            appendLine("            }")
            appendLine()
        }
        appendLine("            else -> throw IllegalArgumentException(\"${model.simpleName} 没有方法 \$method\")")
        appendLine("        }")
        appendLine("    }")
        appendLine()
        appendLine("    private fun forward(handle: Long, method: String, payload: ByteArray): ByteArray =")
        appendLine("        PublicApiBridge.call(handle, typeId, method, payload, owner = true)")
        appendLine("}")
    }

    private fun renderSetup(
        mainFqn: String,
        models: List<TypeModel>,
        handlers: List<HandlerEntry>,
    ): String = buildString {
        val main = models.firstOrNull { it.fqn == mainFqn && it.kind == Kind.PLUGIN }
        val handlerByTarget = handlers.associateBy { it.target }
        val usesPlugin = main != null || handlers.any { it.usesPlugin }

        appendLine("// 由 keel-plugin-ksp 生成，不要手改。")
        appendLine("package ${mainFqn.substringBeforeLast('.')}")
        appendLine()
        appendLine("import moe.lolosia.keel.api.Plugin")
        appendLine("import moe.lolosia.keel.api.PluginContext")
        appendLine("import moe.lolosia.keel.api.share.GeneratedPublicApi")
        appendLine("import moe.lolosia.keel.api.share.PublicApiKey")
        appendLine("import moe.lolosia.keel.api.share.PublicApiType")
        appendLine()
        appendLine("/** 本插件的 `@PublicApi` 注册入口；由 Worker 在 `onLoad` 之后调用 [setup]。 */")
        appendLine("object KeelPluginPublicApi : GeneratedPublicApi {")
        appendLine()
        appendLine("    override fun setup(plugin: Plugin, context: PluginContext) {")
        if (usesPlugin) {
            appendLine("        val pluginInstance = plugin as $mainFqn")
        }
        if (models.isNotEmpty()) {
            appendLine("        context.publicApi.declare(")
            appendLine("            types = listOf(")
            models.forEach { model ->
                appendLine(
                    "                PublicApiType(\"${escape(model.id)}\", hasBase = ${model.hasBase}),"
                )
            }
            appendLine("            ),")
            val entries = models.mapNotNull { model ->
                when {
                    model.manual -> handlerByTarget[model.fqn]?.expression
                    !model.supported -> null
                    model.kind == Kind.PLUGIN -> generatedDispatcher(model, main = true)
                    model.kind == Kind.CLASS -> generatedDispatcher(model, main = false)
                    else -> null
                }
            }
            if (entries.isNotEmpty()) {
                appendLine("            handlers = listOf(")
                entries.forEach { appendLine("                $it,") }
                appendLine("            ),")
            }
            appendLine("        )")
        }
        if (main != null && main.supported) {
            val dispatcher = if (main.manual) {
                handlerByTarget[main.fqn]?.expression
            } else {
                generatedDispatcher(main, main = true)
            }
            if (dispatcher != null) {
                appendLine("        context.publicApi.register(")
                appendLine("            instance = pluginInstance,")
                appendLine("            key = PublicApiKey<$mainFqn>(\"${escape(main.id)}\"),")
                appendLine("            dispatcher = $dispatcher,")
                appendLine("            singleton = true,")
                appendLine("        )")
            }
        }
        appendLine("    }")
        appendLine()
        appendLine("    override fun release(plugin: Plugin) {}")
        appendLine("}")
    }

    private fun generatedDispatcher(model: TypeModel, main: Boolean): String {
        val args = mutableListOf<String>()
        if (main || model.kind == Kind.PLUGIN) args += "pluginInstance"
        if (model.dispatcherNeedsDirectory) args += "context.publicApi"
        return "${model.dispatcherFqn}(${args.joinToString(", ")})"
    }

    // ---------------------------------- 方法体片段 ----------------------------------

    private fun decodeParams(method: Method, indent: String): String = buildString {
        method.params.firstOrNull()?.let { param ->
            when (val wire = param.wire) {
                is Wire.Shared ->
                    appendLine(
                        "${indent}val ${param.name} = " +
                            "${wire.proxy}(ServiceWire.decode(SharedRef.serializer(), payload).handle)"
                    )
                else ->
                    appendLine(
                        "${indent}val ${param.name} = " +
                            "ServiceWire.decode(${serializer(wire)}, payload)"
                    )
            }
        }
    }

    private fun ownerCall(method: Method, indent: String): String = buildString {
        val call = "impl.${method.name}(${method.params.joinToString(", ") { it.name }})"
        when (val result = method.result) {
            Wire.Unit -> {
                appendLine("${indent}$call")
                appendLine("${indent}ByteArray(0)")
            }
            is Wire.Shared -> {
                appendLine("${indent}val value = $call")
                appendLine(
                    "${indent}ServiceWire.encode(SharedRef.serializer(), " +
                        "SharedRef(directory.handleOf(value)))"
                )
            }
            else -> appendLine("${indent}ServiceWire.encode(${serializer(result)}, $call)")
        }
    }

    private fun localCall(method: Method, indent: String): String = buildString {
        val call = "shadow.${method.name}(${method.params.joinToString(", ") { it.name }})"
        when (val result = method.result) {
            Wire.Unit -> appendLine("${indent}$call")
            is Wire.Shared -> {
                appendLine("${indent}val value = $call")
                appendLine(
                    "${indent}ServiceWire.encode(SharedRef.serializer(), " +
                        "SharedRef(directory.handleOf(value)))"
                )
            }
            else -> appendLine("${indent}ServiceWire.encode(${serializer(result)}, $call)")
        }
    }

    private fun signature(params: List<Param>): String =
        params.joinToString(", ") { "${it.name}: ${it.fqn}" }

    private fun serializer(wire: Wire): String = when (wire) {
        Wire.Unit -> error("Unit 没有序列化器")
        Wire.Text -> "String.serializer()"
        is Wire.Primitive -> "${wire.type}.serializer()"
        Wire.Bytes -> "ByteArraySerializer()"
        is Wire.Shared -> "SharedRef.serializer()"
    }

    // ---------------------------------- 注解与文件 ----------------------------------

    private fun idOf(type: KSClassDeclaration): String? {
        val annotation = type.annotations.firstOrNull { it.isNamed(PUBLIC_API) } ?: return null
        val id = annotation.arguments.firstOrNull()?.value as? String
        return id?.takeIf { it.isNotBlank() }
    }

    private fun handlerTarget(handler: KSClassDeclaration): String? {
        val annotation = handler.annotations.firstOrNull { it.isNamed(PUBLIC_API_HANDLER) } ?: return null
        val type = annotation.arguments.firstOrNull()?.value as? KSType ?: return null
        return type.declaration.qualifiedName?.asString()
    }

    private fun handlerInstantiation(
        handler: KSClassDeclaration,
        mainName: String,
        mainType: KSType,
    ): Instantiation? {
        val name = handler.qualifiedName?.asString() ?: return null
        if (handler.classKind == ClassKind.OBJECT) return Instantiation(name, usesPlugin = false)

        val parameters = handler.primaryConstructor?.parameters.orEmpty()
        return when {
            parameters.isEmpty() -> Instantiation("$name()", usesPlugin = false)

            parameters.size == 1 -> {
                val parameterType = parameters.first().type.resolve()
                val parameterName = parameterType.declaration.qualifiedName?.asString()
                when {
                    parameterName == PLUGIN_CONTEXT ->
                        Instantiation("$name(context)", usesPlugin = false)

                    runCatching { parameterType.isAssignableFrom(mainType) }.getOrDefault(false) ->
                        Instantiation("$name(pluginInstance)", usesPlugin = true)

                    else -> null
                }
            }

            else -> null
        }
    }

    private fun KSAnnotation.isNamed(name: String): Boolean =
        annotationType.resolve().declaration.qualifiedName?.asString() == name

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
            else -> name
        }
        return if (isMarkedNullable) "$simple?" else simple
    }

    private fun writeFile(
        fileName: String,
        packageName: String,
        code: String,
        dependencies: Array<KSFile>,
    ) {
        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(false, *dependencies),
            packageName = packageName,
            fileName = fileName,
            extensionName = "kt",
        )
        file.bufferedWriter().use { it.write(code) }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val PUBLIC_API = "moe.lolosia.keel.api.annotation.PublicApi"
        const val PUBLIC_API_HANDLER = "moe.lolosia.keel.api.annotation.PublicApiHandler"
        const val SINGLETON = "moe.lolosia.keel.api.annotation.Singleton"
        const val PLUGIN_CONTEXT = "moe.lolosia.keel.api.PluginContext"
        const val API_HEADER = "// 由 keel-plugin-ksp 生成，不要手改。"
        val ANY_METHODS = setOf("equals", "hashCode", "toString")
    }
}
