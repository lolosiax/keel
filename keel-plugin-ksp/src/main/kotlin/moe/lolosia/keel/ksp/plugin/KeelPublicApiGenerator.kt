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

    private enum class Kind { CLASS, INTERFACE, PLUGIN, VALUE }

    private sealed interface Wire {
        object Unit : Wire

        /** 按值传递的 `@Serializable` 类型；[type] 是源码里的 Kotlin 类型文本。 */
        data class Value(val type: String) : Wire

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
        /** 契约来自依赖（klib）而不是本模块源码：只生成提供者侧分发器，不生成镜像 / 代理。 */
        val external: Boolean = false,
    ) {
        val hasBase: Boolean get() = kind == Kind.CLASS
        val isValue: Boolean get() = kind == Kind.VALUE
        val packageName: String get() = fqn.substringBeforeLast('.')
        val annotatedSingleton: Boolean
            get() = kind == Kind.PLUGIN ||
                declaration.annotations.any {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == SINGLETON
                }

        /** 是否有 `@Singleton` 实现类提供本契约；由 [singletonProviders] 回填。 */
        var providedSingleton: Boolean = false

        val singleton: Boolean get() = annotatedSingleton || providedSingleton
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

    private class SingletonProvider(
        val declaration: KSClassDeclaration,
        val implFqn: String,
        val simpleName: String,
        val contracts: List<TypeModel>,
        val useContext: Boolean,
        val usePlugin: Boolean,
    )

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
                    it.classKind == ClassKind.OBJECT ||
                    it.classKind == ClassKind.ENUM_CLASS
            }
            .toList()

        // @KeelPluginMain 语义上等价于 @PublicApi + @Singleton。
        // KSP 不会传播元注解，这里显式把插件本体并入声明集合。
        val declarations = (declared + main)
            .filter {
                it.classKind == ClassKind.CLASS ||
                    it.classKind == ClassKind.INTERFACE ||
                    it.classKind == ClassKind.OBJECT ||
                    it.classKind == ClassKind.ENUM_CLASS
            }
            .distinctBy { it.qualifiedName?.asString() }
            .sortedBy { it.qualifiedName?.asString() }
            .toList()

        val handlerDeclarations = resolver.getSymbolsWithAnnotation(PUBLIC_API_HANDLER)
            .filterIsInstance<KSClassDeclaration>()
            .distinctBy { it.qualifiedName?.asString() }
            .sortedBy { it.qualifiedName?.asString() }
            .toList()

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

        // 句柄类型（共享对象）才参与 declare / 分发；DTO / 枚举走按值传递。
        val sharedNames: Map<String, String> = declarations
            .filter { !isValueDeclaration(it) }
            .mapNotNull { declaration ->
                declaration.qualifiedName?.asString()?.let { it to declaration.simpleName.asString() }
            }
            .toMap()

        val models = declarations.mapNotNull { declaration ->
            val fqn = declaration.qualifiedName?.asString() ?: return@mapNotNull null
            val kind = when {
                fqn == mainFqn -> Kind.PLUGIN
                isValueDeclaration(declaration) -> Kind.VALUE
                declaration.classKind == ClassKind.INTERFACE -> Kind.INTERFACE
                declaration.classKind == ClassKind.CLASS -> Kind.CLASS
                else -> {
                    logger.error("@PublicApi 暂不支持 object 类型：$fqn", declaration)
                    return@mapNotNull null
                }
            }
            val manual = fqn in manualTargets
            val methods = if (manual || kind == Kind.VALUE) emptyList() else
                extractMethods(declaration, kind, sharedNames)
            if (kind == Kind.VALUE &&
                declaration.classKind == ClassKind.CLASS &&
                !isSerializable(declaration)
            ) {
                logger.error(
                    "@PublicApi 值类型必须标 @Serializable，否则无法按值跨插件传递：$fqn",
                    declaration,
                )
            }
            TypeModel(
                declaration = declaration,
                fqn = fqn,
                simpleName = declaration.simpleName.asString(),
                id = idOf(declaration) ?: fqn,
                kind = kind,
                manual = manual,
                supported = manual || kind == Kind.VALUE || methods != null,
                methods = methods ?: emptyList(),
            )
        }

        models.forEach { model ->
            if (model.kind == Kind.PLUGIN || model.manual) return@forEach
            model.methods.forEach { method ->
                (method.params.map { it.wire } + method.result).forEach { wire ->
                    if (wire is Wire.Shared && wire.fqn !in sharedNames) {
                        logger.error("共享类型 ${wire.fqn} 未被声明为 @PublicApi", model.declaration)
                    }
                }
            }
        }

        // 实现类实现的契约可能来自依赖（可替换能力 / 第三方实现），补进模型集合：
        // 只参与 declare 与提供者侧分发器，不生成镜像 / 代理（api 工件里已有）。
        val external = dependencyContracts(resolver, main, models)
        val allModels = models + external
        val allProviders = singletonProviders(resolver, allModels, mainFqn)
        allProviders.flatMap { it.contracts }.forEach { it.providedSingleton = true }

        models.filter { !it.manual && it.supported }.forEach { model ->
            writeGlue(model)
        }

        allProviders.forEach { provider ->
            provider.contracts.forEach { contract ->
                writeInterfaceDispatcher(contract, provider)
            }
        }

        val dependencies = (declarations + handlerDeclarations)
            .mapNotNull { it.containingFile }
            .distinct()
            .toTypedArray()
        writeFile(
            fileName = "KeelPluginPublicApi",
            packageName = main.packageName.asString(),
            code = renderSetup(mainFqn, allModels, handlerEntries, allProviders),
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

    // ---------------------------------- 单例提供者 ----------------------------------

    /**
     * 扫 `@Singleton` 实现类，找出它们提供的 `@PublicApi` 契约。
     *
     * 一个 typeId 只允许一个单例；重复时 Host 拒绝后到者，这里保持声明顺序。
     */
    private fun singletonProviders(
        resolver: Resolver,
        models: List<TypeModel>,
        mainFqn: String,
    ): List<SingletonProvider> =
        resolver.getSymbolsWithAnnotation(SINGLETON)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.CLASS }
            .distinctBy { it.qualifiedName?.asString() }
            .mapNotNull { declaration ->
                val implFqn = declaration.qualifiedName?.asString() ?: return@mapNotNull null
                // 插件本体已经由 @KeelPluginMain 自动登记，不需要再处理
                if (implFqn == mainFqn) return@mapNotNull null
                val implType = declaration.asStarProjectedType()
                val contracts = models.filter { model ->
                    model.kind == Kind.INTERFACE &&
                        runCatching {
                            model.declaration.asStarProjectedType().isAssignableFrom(implType)
                        }.getOrDefault(false)
                }
                if (contracts.isEmpty()) {
                    logger.warn(
                        "@Singleton 类没有实现任何 @PublicApi 接口：$implFqn",
                        declaration,
                    )
                    return@mapNotNull null
                }
                val injection = providerInjection(declaration, mainFqn) ?: return@mapNotNull null
                SingletonProvider(
                    declaration = declaration,
                    implFqn = implFqn,
                    simpleName = declaration.simpleName.asString(),
                    contracts = contracts,
                    useContext = injection.first,
                    usePlugin = injection.second,
                )
            }
            .toList()

    /**
     * 实现类实现的、来自依赖（klib）的 `@PublicApi` 接口。
     *
     * 可替换能力（如 rbac）的契约由框架的 api 工件提供，第三方实现只依赖该工件；
     * 这类契约不在本模块源码里，按注解扫描不到，需要顺着 `@Singleton` 实现类的父类型
     * 反向收集。只生成提供者侧分发器与 declare，镜像 / 代理由 api 工件负责。
     */
    private fun dependencyContracts(
        resolver: Resolver,
        main: KSClassDeclaration,
        local: List<TypeModel>,
    ): List<TypeModel> {
        val known = local.mapTo(mutableSetOf()) { it.fqn }
        val result = mutableListOf<TypeModel>()
        val impls = resolver.getSymbolsWithAnnotation(SINGLETON)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.CLASS }
            .toList() + main
        impls.forEach { impl ->
            collectSupertypes(impl).forEach { declaration ->
                if (declaration.classKind != ClassKind.INTERFACE) return@forEach
                val fqn = declaration.qualifiedName?.asString() ?: return@forEach
                if (!known.add(fqn)) return@forEach
                // 本模块的契约已在 local 里；有源码声明的也不属于「依赖契约」
                if (declaration.containingFile != null) return@forEach
                if (declaration.annotations.none { it.isNamed(PUBLIC_API) }) return@forEach
                val methods = extractMethods(declaration, Kind.INTERFACE, emptyMap()) ?: return@forEach
                result += TypeModel(
                    declaration = declaration,
                    fqn = fqn,
                    simpleName = declaration.simpleName.asString(),
                    id = idOf(declaration) ?: fqn,
                    kind = Kind.INTERFACE,
                    manual = false,
                    supported = true,
                    methods = methods,
                    external = true,
                )
            }
        }
        return result
    }

    /** 递归收集父类型（按 FQN 去重），只取声明本身，不解析成员。 */
    private fun collectSupertypes(declaration: KSClassDeclaration): List<KSClassDeclaration> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<KSClassDeclaration>()
        fun walk(type: KSType) {
            val target = type.declaration as? KSClassDeclaration ?: return
            val fqn = target.qualifiedName?.asString() ?: return
            if (!seen.add(fqn)) return
            result += target
            target.superTypes.forEach { walk(it.resolve()) }
        }
        declaration.superTypes.forEach { walk(it.resolve()) }
        return result
    }

    /** `@Singleton` 实现的构造注入：无参 / `PluginContext` / 插件本体。 */
    private fun providerInjection(
        declaration: KSClassDeclaration,
        mainFqn: String,
    ): Pair<Boolean, Boolean>? {
        val parameters = declaration.primaryConstructor?.parameters.orEmpty()
        if (parameters.isEmpty()) return false to false
        if (parameters.size == 1) {
            val type = parameters.single().type.resolve().declaration.qualifiedName?.asString()
            when (type) {
                PLUGIN_CONTEXT -> return true to false
                mainFqn -> return false to true
            }
        }
        logger.error(
            "@Singleton 类的构造器必须是 () / (PluginContext) / (插件本体)：${declaration.qualifiedName}",
            declaration,
        )
        return null
    }

    private fun writeInterfaceDispatcher(model: TypeModel, provider: SingletonProvider) {
        writeFile(
            fileName = "${model.simpleName}Dispatcher",
            packageName = model.packageName,
            code = renderInterfaceDispatcher(model, provider.implFqn),
            dependencies = listOfNotNull(
                model.declaration.containingFile,
                provider.declaration.containingFile,
            ).distinct().toTypedArray(),
        )
    }

    // ---------------------------------- 模型提取 ----------------------------------

    private fun extractMethods(
        declaration: KSClassDeclaration,
        kind: Kind,
        shared: Map<String, String>,
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
                    Kind.VALUE -> false
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
            val params = function.parameters.mapIndexed { index, parameter ->
                if (parameter.isVararg) {
                    logger.error("@PublicApi 方法不能有 vararg：${function.qualifiedName}", function)
                    return null
                }
                val type = parameter.type.resolve()
                val wire = wireOf(type, shared)
                if (wire == null) {
                    logger.error("不支持的参数类型 ${type.render()}：${function.qualifiedName}", function)
                    return null
                }
                if (wire is Wire.Shared && (kind != Kind.PLUGIN || function.parameters.size > 1)) {
                    logger.error(
                        "共享类型参数只支持单参数插件方法：${function.qualifiedName}",
                        function,
                    )
                    return null
                }
                Param(parameter.name?.asString() ?: "arg$index", type.render(), wire)
            }

            val resultType = function.returnType?.resolve() ?: run {
                logger.error("无法解析返回类型：${function.qualifiedName}", function)
                return null
            }
            val result = wireOf(resultType, shared)
            if (result == null) {
                logger.error("不支持的返回类型 ${resultType.render()}：${function.qualifiedName}", function)
                return null
            }

            methods += Method(
                name = function.simpleName.asString(),
                suspend = Modifier.SUSPEND in function.modifiers,
                open = kind != Kind.PLUGIN && isOverridable(function),
                params = params,
                result = result,
                resultFqn = resultType.render(),
            )
        }
        return methods
    }

    private fun wireOf(type: KSType, shared: Map<String, String>): Wire? {
        val name = type.declaration.qualifiedName?.asString() ?: return null
        if (name == UNIT) return if (type.isMarkedNullable) null else Wire.Unit

        val sharedSimple = shared[name]
        if (sharedSimple != null) {
            // 共享对象按句柄传，不支持可空或泛型包装
            if (type.isMarkedNullable || type.arguments.isNotEmpty()) return null
            return Wire.Shared(name, "${name.substringBeforeLast('.')}.$sharedSimple" + "Proxy")
        }

        return if (isValueType(type)) Wire.Value(type.render()) else null
    }

    /** `@Serializable` 值类型的白名单：基本类型、枚举、DTO、集合（递归）与可空。 */
    private fun isValueType(type: KSType): Boolean {
        val declaration = type.declaration as? KSClassDeclaration ?: return false
        val name = declaration.qualifiedName?.asString() ?: return false

        if (type.arguments.isNotEmpty()) {
            if (name !in COLLECTIONS) return false
            return type.arguments.all { argument ->
                val resolved = argument.type?.resolve() ?: return@all false
                isValueType(resolved)
            }
        }
        return when {
            name in PRIMITIVES -> true
            declaration.classKind == ClassKind.ENUM_CLASS -> true
            name.startsWith("kotlin.Function") -> false
            declaration.classKind == ClassKind.CLASS -> isSerializable(declaration)
            else -> false
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
     * 把 api 侧需要的镜像 / 代理 / Key / 访问器直接写进 `api/src/commonMain/kotlin`。
     *
     * KSP 的 `CodeGenerator` 只能写当前编译输出（在 KMP 里是目标 fragment，common 代码看不到），
     * 所以这里用普通文件写入；落点由 `keel.api.dir` 指定，生成物随仓库提交。
     * 接口与 DTO 的镜像直接搬 impl 的源文件（保留默认值 / `@SerialName` / KDoc），
     * 共享类渲染成无状态桩；代理 / Key / 访问器按需生成。
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
            withFileLock(channel) {
                targets.forEach { model ->
                    val packageDir = java.io.File(root, model.packageName.replace('.', '/'))
                    // DTO：只镜像类型本身，不生成 Key / 代理 / 访问器
                    if (model.isValue) {
                        emitSourceMirror(model, packageDir)
                        return@forEach
                    }
                    if (model.kind == Kind.INTERFACE) {
                        emitSourceMirror(model, packageDir)
                    } else {
                        writeApiFile(
                            packageDir,
                            "${model.simpleName}.kt",
                            renderApiMirror(model, directoryNeeds),
                        )
                    }
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

    /**
     * 把 impl 的声明源文件原样搬进 api。
     *
     * KSP 读不到默认值表达式，只有搬源码才能保住 `page(pageNo: Int = 1, ...)`
     * 这类默认参数与 `@SerialName`；因此要求一个源文件只放一个顶层声明。
     */
    private fun emitSourceMirror(model: TypeModel, packageDir: java.io.File): Boolean {
        val containing = model.declaration.containingFile ?: run {
            logger.error("@PublicApi 镜像只能从源码生成：${model.fqn}", model.declaration)
            return false
        }
        if (containing.declarations.count() != 1) {
            logger.error(
                "@PublicApi 镜像要求源文件只有一个顶层声明：${containing.filePath}",
                model.declaration,
            )
            return false
        }
        val source = java.io.File(containing.filePath)
        val text = runCatching { source.readText() }.getOrNull() ?: run {
            logger.error("读不到 @PublicApi 源文件：${source.path}", model.declaration)
            return false
        }
        val body = text.removePrefix("\uFEFF").trimStart()
        return writeApiFile(
            packageDir,
            "${model.simpleName}.kt",
            API_HEADER + "\n" + body.trimEnd() + "\n",
        )
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

    private fun renderApiMirror(
        model: TypeModel,
        directoryNeeds: Map<String, Boolean>,
    ): String = buildString {
        appendLine(API_HEADER)
        appendLine("package ${model.packageName}")
        appendLine()
        appendLine("import moe.lolosia.keel.api.annotation.PublicApi")
        if (model.singleton) appendLine("import moe.lolosia.keel.api.annotation.Singleton")
        val methods = if (model.kind == Kind.CLASS) {
            model.methods.filter { method ->
                val sharedParam = method.params.any { it.wire is Wire.Shared }
                val sharedResultNeedsDirectory =
                    (method.result as? Wire.Shared)?.let { directoryNeeds[it.fqn] == true } == true
                if (sharedParam || sharedResultNeedsDirectory) {
                    logger.warn(
                        "镜像方法需要 PublicApiDirectory，暂不支持，跳过：${model.fqn}.${method.name}",
                        model.declaration,
                    )
                    false
                } else {
                    true
                }
            }
        } else {
            model.methods
        }
        if (model.kind == Kind.CLASS) {
            val usesValue = methods.any { it.params.isNotEmpty() || it.result !is Wire.Unit }
            val usesShared = methods.any { it.result is Wire.Shared }
            val usesArgs = methods.any { it.params.size > 1 }
            if (usesArgs) appendLine("import kotlinx.serialization.Serializable")
            if (usesValue) appendLine("import kotlinx.serialization.serializer")
            appendLine("import moe.lolosia.keel.api.service.ServiceWire")
            appendLine("import moe.lolosia.keel.api.share.PublicApiBridge")
            appendLine("import moe.lolosia.keel.api.share.SharedProxy")
            if (usesShared) appendLine("import moe.lolosia.keel.api.share.SharedRef")
        }
        appendLine()
        appendLine("/** `${model.fqn}` 在 api 工件里的镜像；对应 impl 的同名类型。 */")
        appendLine("@PublicApi")
        if (model.singleton) appendLine("@Singleton")
        when (model.kind) {
            Kind.CLASS -> {
                methods.filter { it.params.size > 1 }.forEach { method ->
                    appendLine(renderArgsClass(argsClassName(model, method, "Mirror"), method))
                    appendLine()
                }
                appendLine("open class ${model.simpleName} : SharedProxy {")
                appendLine()
                appendLine("    /** Host 分配的句柄；由登记方在首次跨界登记后写入。 */")
                appendLine("    override var sharedHandle: Long = 0L")
                methods.forEach { method ->
                    appendLine()
                    appendLine(
                        "    open fun ${method.name}(${signature(method.params)}): ${method.resultFqn} {"
                    )
                    append(mirrorBody(model, method, "        "))
                    appendLine("    }")
                }
                appendLine("}")
            }
            else -> {
                appendLine("interface ${model.simpleName} {")
                methods.forEachIndexed { index, method ->
                    if (index > 0) appendLine()
                    appendLine(
                        "    ${if (method.suspend) "suspend " else ""}fun " +
                            "${method.name}(${signature(method.params)}): ${method.resultFqn}"
                    )
                }
                appendLine("}")
            }
        }
    }

    /** 镜像类的基类桩：只走 `base.*`，绝不调虚方法。 */
    private fun mirrorBody(model: TypeModel, method: Method, indent: String): String = buildString {
        val call = apiBridgeCall(model, method, "sharedHandle", "Mirror", "base.${method.name}")
        when (val result = method.result) {
            Wire.Unit -> appendLine("$indent$call")
            is Wire.Shared -> {
                appendLine("${indent}val ref = ServiceWire.decode(SharedRef.serializer(), $call)")
                appendLine("${indent}return ${result.proxy}(ref.handle)")
            }
            else -> appendLine("${indent}return ServiceWire.decode(${serializer(result)}, $call)")
        }
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
            val usesArgs = model.methods.any { it.params.size > 1 }
            if (usesArgs) appendLine("import kotlinx.serialization.Serializable")
            if (usesValue) appendLine("import kotlinx.serialization.serializer")
            appendLine("import moe.lolosia.keel.api.service.ServiceWire")
            appendLine("import moe.lolosia.keel.api.share.PublicApiBridge")
            if (needsDirectory) appendLine("import moe.lolosia.keel.api.share.PublicApiDirectory")
            if (usesShared) appendLine("import moe.lolosia.keel.api.share.SharedRef")
            appendLine()

            model.methods.filter { it.params.size > 1 }.forEach { method ->
                appendLine(renderArgsClass(argsClassName(model, method, "Call"), method))
                appendLine()
            }
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
                val call = apiBridgeCall(model, method, handle, "Call")
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

    private fun apiBridgeCall(
        model: TypeModel,
        method: Method,
        handle: String,
        argsSuffix: String,
        lineName: String = method.name,
    ): String {
        val payload = payloadExpression(model, method, argsSuffix)
        return "PublicApiBridge.call(" +
            "$handle, \"${escape(model.id)}\", \"${escape(lineName)}\", $payload)"
    }

    /** 参数载荷：0 参空字节，单参直接编码，多参包一层生成的 Args DTO。 */
    private fun payloadExpression(model: TypeModel, method: Method, argsSuffix: String): String {
        if (method.params.isEmpty()) return "ByteArray(0)"
        if (method.params.size > 1) {
            val argsName = argsClassName(model, method, argsSuffix)
            val init = method.params.joinToString(", ") { "${it.name} = ${it.name}" }
            return "ServiceWire.encode(serializer<$argsName>(), $argsName($init))"
        }
        val param = method.params.single()
        return when (val wire = param.wire) {
            is Wire.Shared ->
                "ServiceWire.encode(SharedRef.serializer(), " +
                    "SharedRef(directory.handleOf(${param.name})))"
            else -> "ServiceWire.encode(${serializer(wire)}, ${param.name})"
        }
    }

    private fun renderApiAccessor(model: TypeModel, directoryNeeds: Map<String, Boolean>): String =
        buildString {
            val needsDirectory = directoryNeeds[model.fqn] == true
            val name = apiAccessorName(model)
            val proxy = if (needsDirectory) {
                "${model.simpleName}Proxy(it, directory)"
            } else {
                "${model.simpleName}Proxy(it)"
            }
            appendLine(API_HEADER)
            appendLine("package ${model.packageName}")
            appendLine()
            appendLine("import moe.lolosia.keel.api.PluginContext")
            appendLine()
            appendLine("/** 声明方插件 `${model.fqn}` 的单例访问器；提供者未启用时抛异常。 */")
            appendLine("val PluginContext.$name: ${model.simpleName}")
            appendLine("    get() {")
            if (needsDirectory) appendLine("        val directory = publicApi")
            appendLine("        return publicApi.requireInstance(${apiKeyName(model)}) { $proxy }")
            appendLine("    }")
            appendLine()
            appendLine("/** 同 [${name}]；提供者未启用时返回 null（可替换能力按缺席降级时用它）。 */")
            appendLine("val PluginContext.${name}OrNull: ${model.simpleName}?")
            appendLine("    get() {")
            if (needsDirectory) appendLine("        val directory = publicApi")
            appendLine("        return publicApi.getInstance(${apiKeyName(model)}) { $proxy }")
            appendLine("    }")
        }

    /**
     * 跨进程 / 跨 target 串行化 api 生成目录。
     *
     * 同一 JVM 里多个 KSP worker 同时锁同一个文件会抛 `OverlappingFileLockException`，
     * 所以用 `tryLock` 轮询而不是阻塞 `lock()`。
     */
    private fun <T> withFileLock(channel: java.nio.channels.FileChannel, block: () -> T): T {
        val deadline = System.currentTimeMillis() + 180_000
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                null
            }
            if (lock != null) {
                return try {
                    block()
                } finally {
                    lock.release()
                }
            }
            check(System.currentTimeMillis() < deadline) { "等待 api 生成目录锁超时：$channel" }
            Thread.sleep(50)
        }
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
        if (model.isValue) return
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
        val usesValue = model.methods.any { it.params.isNotEmpty() || it.result !is Wire.Unit }
        val usesShared = model.methods.any {
            it.params.any { param -> param.wire is Wire.Shared } || it.result is Wire.Shared
        }
        val usesArgs = model.methods.any { it.params.size > 1 }
        if (usesArgs) appendLine("import kotlinx.serialization.Serializable")
        if (usesValue) appendLine("import kotlinx.serialization.serializer")
        appendLine("import moe.lolosia.keel.api.service.ServiceWire")
        appendLine("import moe.lolosia.keel.api.share.PublicApiBridge")
        if (usesShared) appendLine("import moe.lolosia.keel.api.share.SharedRef")
        appendLine()
        model.methods.filter { it.params.size > 1 }.forEach { method ->
            appendLine(renderArgsClass(argsClassName(model, method, "Call"), method))
            appendLine()
        }
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
        val payload = payloadExpression(model, method, "Call")
        return "PublicApiBridge.call(" +
            "proxyHandle, \"${escape(model.id)}\", \"${method.name}\", $payload)"
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
        Kind.INTERFACE, Kind.VALUE -> error("接口 / DTO 不生成分发器")
    }

    private fun renderOwnerDispatcher(model: TypeModel): String = buildString {
        appendLine("// 由 keel-plugin-ksp 生成，不要手改。")
        appendLine("package ${model.packageName}")
        appendLine()
        val usesValue = model.methods.any { it.params.isNotEmpty() || it.result !is Wire.Unit }
        val usesArgs = model.methods.any { it.params.size > 1 }
        if (usesArgs) appendLine("import kotlinx.serialization.Serializable")
        if (usesValue) appendLine("import kotlinx.serialization.serializer")
        appendLine("import moe.lolosia.keel.api.service.ServiceWire")
        appendLine("import moe.lolosia.keel.api.share.PublicApiDirectory")
        appendLine("import moe.lolosia.keel.api.share.PublicApiDispatcher")
        appendLine("import moe.lolosia.keel.api.share.SharedRef")
        appendLine()
        model.methods.filter { it.params.size > 1 }.forEach { method ->
            appendLine(renderArgsClass(argsClassName(model, method, "Dispatch"), method))
            appendLine()
        }
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
            append(decodeParams(model, method, "Dispatch", "            "))
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
        val usesValue = model.methods.any { it.params.isNotEmpty() || it.result !is Wire.Unit }
        val usesArgs = model.methods.any { it.params.size > 1 }
        if (usesArgs) appendLine("import kotlinx.serialization.Serializable")
        if (usesValue) appendLine("import kotlinx.serialization.serializer")
        appendLine("import moe.lolosia.keel.api.service.ServiceWire")
        appendLine("import moe.lolosia.keel.api.share.PublicApiBridge")
        appendLine("import moe.lolosia.keel.api.share.PublicApiDirectory")
        appendLine("import moe.lolosia.keel.api.share.PublicApiDispatcher")
        appendLine("import moe.lolosia.keel.api.share.SharedRef")
        appendLine()
        model.methods.filter { it.params.size > 1 }.forEach { method ->
            appendLine(renderArgsClass(argsClassName(model, method, "Dispatch"), method))
            appendLine()
        }
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
                append(decodeParams(model, method, "Dispatch", "                    "))
                append(localCall(method, "                    "))
                appendLine("                }")
                if (method.result is Wire.Unit) {
                    appendLine("                ByteArray(0)")
                }
            } else {
                append(decodeParams(model, method, "Dispatch", "                "))
                append(localCall(method, "                "))
                if (method.result is Wire.Unit) {
                    appendLine("                ByteArray(0)")
                }
            }
            appendLine("            }")
            appendLine()
            appendLine("            \"base.${method.name}\" -> {")
            append(decodeParams(model, method, "Dispatch", "                "))
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

    /** 接口契约的单例提供者分发器：方法与实现类一一对应，走所有者侧。 */
    private fun renderInterfaceDispatcher(model: TypeModel, implFqn: String): String = buildString {
        appendLine("// 由 keel-plugin-ksp 生成，不要手改。")
        appendLine("package ${model.packageName}")
        appendLine()
        val usesValue = model.methods.any { it.params.isNotEmpty() || it.result !is Wire.Unit }
        val usesShared = model.methods.any { it.result is Wire.Shared }
        val usesArgs = model.methods.any { it.params.size > 1 }
        if (usesArgs) appendLine("import kotlinx.serialization.Serializable")
        if (usesValue) appendLine("import kotlinx.serialization.serializer")
        appendLine("import moe.lolosia.keel.api.service.ServiceWire")
        if (model.dispatcherNeedsDirectory) {
            appendLine("import moe.lolosia.keel.api.share.PublicApiDirectory")
        }
        appendLine("import moe.lolosia.keel.api.share.PublicApiDispatcher")
        if (usesShared) appendLine("import moe.lolosia.keel.api.share.SharedRef")
        appendLine()
        model.methods.filter { it.params.size > 1 }.forEach { method ->
            appendLine(renderArgsClass(argsClassName(model, method, "Dispatch"), method))
            appendLine()
        }
        appendLine("/** `${model.fqn}` 的提供者侧分发器；实现类是 `$implFqn`。 */")
        val directory = if (model.dispatcherNeedsDirectory) ", private val directory: PublicApiDirectory" else ""
        appendLine("internal class ${model.simpleName}Dispatcher(private val impl: $implFqn$directory) : PublicApiDispatcher {")
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
            append(decodeParams(model, method, "Dispatch", "            "))
            append(ownerCall(method, "            "))
            appendLine("        }")
            appendLine()
        }
        appendLine("        else -> throw IllegalArgumentException(\"${model.simpleName} 没有方法 \$method\")")
        appendLine("    }")
        appendLine("}")
    }

    private fun argsClassName(model: TypeModel, method: Method, suffix: String): String =
        "${model.simpleName}$suffix${method.name.replaceFirstChar { it.uppercaseChar() }}Args"

    private fun renderArgsClass(name: String, method: Method): String = buildString {
        appendLine("@Serializable")
        appendLine("internal class $name(")
        appendLine(method.params.joinToString(",\n") { "    val ${it.name}: ${it.fqn}" })
        appendLine(")")
    }

    private fun Method.callArguments(): String =
        if (params.size > 1) params.joinToString(", ") { "args.${it.name}" }
        else params.joinToString(", ") { it.name }

    private fun renderSetup(
        mainFqn: String,
        models: List<TypeModel>,
        handlers: List<HandlerEntry>,
        providers: List<SingletonProvider>,
    ): String = buildString {
        val main = models.firstOrNull { it.fqn == mainFqn && it.kind == Kind.PLUGIN }
        val handlerByTarget = handlers.associateBy { it.target }
        val handleTypes = models.filter { !it.isValue }
        val usesPlugin = main != null || handlers.any { it.usesPlugin } || providers.any { it.usePlugin }

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
        if (handleTypes.isNotEmpty()) {
            appendLine("        context.publicApi.declare(")
            appendLine("            types = listOf(")
            handleTypes.forEach { model ->
                appendLine(
                    "                PublicApiType(\"${escape(model.id)}\", hasBase = ${model.hasBase}),"
                )
            }
            appendLine("            ),")
            val entries = handleTypes.mapNotNull { model ->
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
        providers.forEach { provider ->
            val instance = provider.simpleName.replaceFirstChar { it.lowercaseChar() } + "Instance"
            val injection = when {
                provider.useContext -> "context"
                provider.usePlugin -> "pluginInstance"
                else -> ""
            }
            appendLine("        val $instance = ${provider.implFqn}($injection)")
            provider.contracts.forEach { contract ->
                val args = mutableListOf(instance)
                if (contract.dispatcherNeedsDirectory) args += "context.publicApi"
                appendLine("        context.publicApi.register(")
                appendLine("            instance = $instance,")
                appendLine("            key = PublicApiKey<${contract.fqn}>(\"${escape(contract.id)}\"),")
                appendLine("            dispatcher = ${contract.dispatcherFqn}(${args.joinToString(", ")}),")
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

    private fun decodeParams(
        model: TypeModel,
        method: Method,
        argsSuffix: String,
        indent: String,
    ): String = buildString {
        if (method.params.size > 1) {
            val argsName = argsClassName(model, method, argsSuffix)
            appendLine(
                "${indent}val args = ServiceWire.decode(serializer<$argsName>(), payload)"
            )
            return@buildString
        }
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
        val call = "impl.${method.name}(${method.callArguments()})"
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
        val call = "shadow.${method.name}(${method.callArguments()})"
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
        is Wire.Value -> "serializer<${wire.type}>()"
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

    private fun KSType.render(): String {
        val name = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
        val base = when (name) {
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
        val text = if (arguments.isEmpty()) base else
            base + "<" + arguments.joinToString(", ") { it.type?.resolve()?.render() ?: "*" } + ">"
        return if (isMarkedNullable) "$text?" else text
    }

    /** DTO / 枚举：按值传，需要在 api 里镜像。 */
    private fun isValueDeclaration(declaration: KSClassDeclaration): Boolean =
        declaration.classKind == ClassKind.ENUM_CLASS ||
            (declaration.classKind == ClassKind.CLASS && Modifier.DATA in declaration.modifiers)

    private fun isSerializable(declaration: KSClassDeclaration): Boolean =
        declaration.annotations.any { it.isNamed(SERIALIZABLE) } ||
            declaration.annotations.any { it.isNamed(SERIALIZABLE_WITH) }

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
        const val SERIALIZABLE = "kotlinx.serialization.Serializable"
        const val SERIALIZABLE_WITH = "kotlinx.serialization.SerializableWith"
        const val UNIT = "kotlin.Unit"
        const val API_HEADER = "// 由 keel-plugin-ksp 生成，不要手改。"
        val COLLECTIONS = setOf(
            "kotlin.collections.List",
            "kotlin.collections.Set",
            "kotlin.collections.Map",
        )
        val PRIMITIVES = setOf(
            "kotlin.String",
            "kotlin.Boolean",
            "kotlin.Int",
            "kotlin.Long",
            "kotlin.Short",
            "kotlin.Byte",
            "kotlin.Float",
            "kotlin.Double",
            "kotlin.ByteArray",
            "kotlin.UInt",
            "kotlin.ULong",
            "kotlin.UShort",
            "kotlin.UByte",
        )
        val ANY_METHODS = setOf("equals", "hashCode", "toString")
    }
}
