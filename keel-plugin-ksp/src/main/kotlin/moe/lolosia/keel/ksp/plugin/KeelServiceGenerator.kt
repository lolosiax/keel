package moe.lolosia.keel.ksp.plugin

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

/**
 * `@ModuleService` 的服务代码生成器。
 *
 * 契约侧生成 Key / Client / `PluginContext` 访问器；实现侧生成分发器与
 * `KeelPluginServices`。参数与返回值都用 CBOR 包装，运行期不碰反射。
 */
internal class KeelServiceGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {

    private var generated = false

    fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val interfaces = resolver.getSymbolsWithAnnotation(MODULE_SERVICE)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        val providers = resolver.getSymbolsWithAnnotation(MODULE_SERVICE_PROVIDER)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        val mains = resolver.getSymbolsWithAnnotation(PLUGIN_MAIN)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val deferred = (interfaces + providers + mains).filterNot { it.validate() }
        if (deferred.isNotEmpty()) return deferred

        val services = buildServices(interfaces)
        val implementations = buildProviders(providers, services)

        services.forEach { generateClient(it, resolver) }
        implementations.forEach { generateDispatcher(it, resolver) }

        val main = mains.firstOrNull()
        if (main != null) {
            generateBindings(main.packageName.asString(), implementations, resolver)
        }
        generated = true
        return emptyList()
    }

    private fun buildServices(interfaces: List<KSClassDeclaration>): List<ServiceModel> {
        val ids = mutableSetOf<String>()
        return interfaces.mapNotNull { declaration ->
            if (declaration.classKind != ClassKind.INTERFACE) {
                logger.error("@ModuleService 只能标在 interface 上", declaration)
                return@mapNotNull null
            }
            val id = annotationString(declaration, MODULE_SERVICE, "id")
            if (id.isNullOrBlank()) {
                logger.error("@ModuleService 必须声明非空的 id", declaration)
                return@mapNotNull null
            }
            if (!ids.add(id)) {
                logger.error("重复的服务 id：$id", declaration)
                return@mapNotNull null
            }
            val qualified = declaration.qualifiedName?.asString() ?: run {
                logger.error("@ModuleService 接口必须是具名类型", declaration)
                return@mapNotNull null
            }

            val names = mutableSetOf<String>()
            val methods = declaration.declarations
                .filterIsInstance<KSFunctionDeclaration>()
                .filter { it.isAbstract }
                .mapNotNull { function ->
                    buildMethod(declaration, function, names)
                }
                .toList()

            ServiceModel(declaration, qualified, id, methods)
        }
    }

    private fun buildMethod(
        owner: KSClassDeclaration,
        function: KSFunctionDeclaration,
        names: MutableSet<String>,
    ): MethodModel? {
        val name = function.simpleName.asString()
        if (!names.add(name)) {
            logger.error(
                "服务 ${owner.simpleName.asString()} 的方法 $name 有重载；跨模块按方法名分发，不允许重载",
                function,
            )
            return null
        }
        if (function.typeParameters.isNotEmpty()) {
            logger.error("服务方法 $name 不支持泛型", function)
            return null
        }

        val parameters = function.parameters.mapNotNull { parameter ->
            buildParameter(function, parameter)
        }
        val returnType = function.returnType?.resolve() ?: run {
            logger.error("服务方法 $name 缺少返回类型", function)
            return null
        }
        return MethodModel(
            name = name,
            parameters = parameters,
            returnType = returnType,
            isSuspend = function.modifiers.contains(Modifier.SUSPEND),
        )
    }

    private fun buildParameter(
        function: KSFunctionDeclaration,
        parameter: KSValueParameter,
    ): ParameterModel? {
        val name = parameter.name?.asString() ?: run {
            logger.error("服务方法 ${function.simpleName.asString()} 的参数必须有名字", parameter)
            return null
        }
        if (parameter.isVararg) {
            logger.error("服务方法 ${function.simpleName.asString()} 的参数 $name 不支持 vararg", parameter)
            return null
        }
        val type = parameter.type.resolve()
        if (type.isFunctionType()) {
            logger.error(
                "服务方法 ${function.simpleName.asString()} 的参数 $name 是函数类型；" +
                    "回调参数尚未接入（P0）",
                parameter,
            )
            return null
        }
        return ParameterModel(name, type)
    }

    private fun buildProviders(
        providers: List<KSClassDeclaration>,
        services: List<ServiceModel>,
    ): List<ImplementationModel> {
        val byInterface = services.associateBy { it.qualified }
        val dispatcherNames = mutableSetOf<String>()

        return providers.mapNotNull { declaration ->
            if (declaration.classKind != ClassKind.CLASS) {
                logger.error("@ModuleServiceProvider 只能标在 class 上", declaration)
                return@mapNotNull null
            }
            val qualified = declaration.qualifiedName?.asString() ?: run {
                logger.error("@ModuleServiceProvider 必须是具名类", declaration)
                return@mapNotNull null
            }

            val requestedId = annotationString(declaration, MODULE_SERVICE_PROVIDER, "serviceId").orEmpty()
            val implemented = declaration.getAllSuperTypes()
                .mapNotNull { it.declaration as? KSClassDeclaration }
                .mapNotNull { supertype ->
                    val qualifiedName = supertype.qualifiedName?.asString() ?: return@mapNotNull null
                    // 契约与实现在同一个模块时用已扫描的模型；跨模块时从 klib 的注解重建
                    byInterface[qualifiedName] ?: buildImportedService(supertype)
                }
                .distinctBy { it.qualified }
                .toList()

            val targets = if (requestedId.isBlank()) {
                implemented
            } else {
                implemented.filter { it.id == requestedId }
            }
            if (targets.isEmpty()) {
                logger.error(
                    if (requestedId.isBlank()) {
                        "$qualified 没有实现任何 @ModuleService 接口"
                    } else {
                        "$qualified 没有实现服务 $requestedId"
                    },
                    declaration,
                )
                return@mapNotNull null
            }

            val useContext = useContextConstructor(declaration) ?: return@mapNotNull null
            val unique = targets.filter { service ->
                val key = "${service.packageName}.${service.simpleName}Dispatcher"
                if (dispatcherNames.add(key)) {
                    true
                } else {
                    logger.error("服务 ${service.id} 的提供者重复：$qualified", declaration)
                    false
                }
            }
            if (unique.isEmpty()) return@mapNotNull null
            ImplementationModel(declaration, qualified, unique, useContext)
        }
    }

    /**
     * 从依赖的 `-api` 工件里重建服务模型。
     *
     * `getSymbolsWithAnnotation` 只返回当前模块的源码，实现方看不到 api 模块的接口；
     * 这里按实现的父类型直接读取 klib 上的注解与方法。
     */
    private fun buildImportedService(declaration: KSClassDeclaration): ServiceModel? {
        if (declaration.classKind != ClassKind.INTERFACE) return null
        val id = annotationString(declaration, MODULE_SERVICE, "id") ?: return null
        val qualified = declaration.qualifiedName?.asString() ?: return null

        val names = mutableSetOf<String>()
        val methods = declaration.declarations
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.isAbstract }
            .mapNotNull { buildMethod(declaration, it, names) }
            .toList()
        return ServiceModel(declaration, qualified, id, methods)
    }

    /** @return true 表示构造器要注入 `PluginContext`；null 表示构造器不合法。 */
    private fun useContextConstructor(declaration: KSClassDeclaration): Boolean? {
        val parameters = declaration.primaryConstructor?.parameters.orEmpty()
        if (parameters.isEmpty()) return false
        if (parameters.size == 1) {
            val type = parameters.single().type.resolve().declaration.qualifiedName?.asString()
            if (type == PLUGIN_CONTEXT) return true
        }
        logger.error(
            "${declaration.simpleName.asString()} 的构造器必须是 () 或 (PluginContext)",
            declaration,
        )
        return null
    }

    private fun generateClient(service: ServiceModel, resolver: Resolver) {
        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()),
            packageName = service.packageName,
            fileName = "${service.simpleName}Keel",
            extensionName = "kt",
        )
        file.bufferedWriter().use { it.write(renderClient(service)) }
    }

    private fun renderClient(service: ServiceModel): String = buildString {
        val simple = service.simpleName
        val accessor = simple.replaceFirstChar { it.lowercaseChar() }

        appendLine("// 由 keel-plugin-ksp 生成，不要手改。")
        appendLine("package ${service.packageName}")
        appendLine()
        appendLine("import kotlinx.serialization.Serializable")
        appendLine("import kotlinx.serialization.serializer")
        appendLine("import moe.lolosia.keel.api.PluginContext")
        appendLine("import moe.lolosia.keel.api.service.*")
        appendLine()
        appendLine("val ${simple}Key: ServiceKey<$simple> = ServiceKey(${service.id.quote()})")
        appendLine()
        appendLine("val PluginContext.$accessor: $simple")
        appendLine("    get() = modules.get(${simple}Key)")
        appendLine("        ?: ${simple}Client(modules.caller(${simple}Key))")
        appendLine()

        service.methods.forEach { method ->
            if (method.parameters.isNotEmpty()) {
                appendLine("@Serializable")
                appendLine(
                    "internal class ${simple}Call${method.capitalized}Args(" +
                        method.parameters.joinToString(", ") { "val ${it.name}: ${it.type.render()}" } +
                        ")"
                )
                appendLine()
            }
        }

        appendLine("class ${simple}Client(private val caller: ServiceCaller) : $simple {")
        appendLine()
        service.methods.forEach { method ->
            renderClientMethod(this, service, method)
        }
        appendLine("}")
    }

    private fun renderClientMethod(out: StringBuilder, service: ServiceModel, method: MethodModel) {
        val call = if (method.isSuspend) "call" else "callBlocking"
        val payload = payloadExpression(service, method, "Call")
        val prefix = if (method.isSuspend) "suspend " else ""

        if (method.returnType.isUnit()) {
            out.appendLine("    override ${prefix}fun ${method.name}(${method.signature()}) {")
            out.appendLine("        caller.$call(${method.name.quote()}, $payload)")
            out.appendLine("    }")
            out.appendLine()
            return
        }

        out.appendLine(
            "    override ${prefix}fun ${method.name}(${method.signature()}): " +
                "${method.returnType.render()} ="
        )
        out.appendLine("        ServiceWire.decode(")
        out.appendLine("            serializer<${method.returnType.render()}>(),")
        out.appendLine("            caller.$call(${method.name.quote()}, $payload),")
        out.appendLine("        )")
        out.appendLine()
    }

    private fun generateDispatcher(implementation: ImplementationModel, resolver: Resolver) {
        implementation.services.forEach { service ->
            val file = codeGenerator.createNewFile(
                dependencies = Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()),
                packageName = service.packageName,
                fileName = "${service.simpleName}Dispatcher",
                extensionName = "kt",
            )
            file.bufferedWriter().use { it.write(renderDispatcher(service)) }
        }
    }

    private fun renderDispatcher(service: ServiceModel): String = buildString {
        val simple = service.simpleName

        appendLine("// 由 keel-plugin-ksp 生成，不要手改。")
        appendLine("package ${service.packageName}")
        appendLine()
        appendLine("import kotlinx.serialization.Serializable")
        appendLine("import kotlinx.serialization.serializer")
        appendLine("import moe.lolosia.keel.api.service.*")
        appendLine()

        service.methods.forEach { method ->
            if (method.parameters.isNotEmpty()) {
                appendLine("@Serializable")
                appendLine(
                    "internal class ${simple}Dispatch${method.capitalized}Args(" +
                        method.parameters.joinToString(", ") { "val ${it.name}: ${it.type.render()}" } +
                        ")"
                )
                appendLine()
            }
        }

        appendLine("class ${simple}Dispatcher(private val impl: $simple) : ServiceDispatcher {")
        appendLine()
        appendLine("    override val serviceId: String get() = ${service.id.quote()}")
        appendLine()
        appendLine("    override suspend fun dispatch(")
        appendLine("        method: String,")
        appendLine("        payload: ByteArray,")
        appendLine("        callerModule: Long,")
        appendLine("    ): ByteArray = when (method) {")

        service.methods.forEach { method ->
            val prefix = "        ${method.name.quote()} -> {"
            appendLine(prefix)
            if (method.parameters.isNotEmpty()) {
                appendLine(
                    "            val args = ServiceWire.decode(" +
                        "serializer<${simple}Dispatch${method.capitalized}Args>(), payload)"
                )
            }
            val call = "impl.${method.name}(" +
                method.parameters.joinToString(", ") { "args.${it.name}" } + ")"
            if (method.returnType.isUnit()) {
                appendLine("            $call")
                appendLine("            ByteArray(0)")
            } else {
                appendLine("            ServiceWire.encode(serializer<${method.returnType.render()}>(), $call)")
            }
            appendLine("        }")
        }

        appendLine("        else -> throw IllegalArgumentException(\"unknown service method: \$method\")")
        appendLine("    }")
        appendLine("}")
    }

    private fun generateBindings(
        packageName: String,
        implementations: List<ImplementationModel>,
        resolver: Resolver,
    ) {
        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()),
            packageName = packageName,
            fileName = "KeelPluginServices",
            extensionName = "kt",
        )
        file.bufferedWriter().use { it.write(renderBindings(packageName, implementations)) }
    }

    private fun renderBindings(
        packageName: String,
        implementations: List<ImplementationModel>,
    ): String = buildString {
        appendLine("// 由 keel-plugin-ksp 生成，不要手改。")
        appendLine("package $packageName")
        appendLine()
        appendLine("import moe.lolosia.keel.api.PluginContext")
        appendLine("import moe.lolosia.keel.api.service.GeneratedServices")
        appendLine("import moe.lolosia.keel.api.service.ServiceBinder")
        appendLine("import moe.lolosia.keel.api.service.ServiceKey")
        appendLine()
        appendLine("object KeelPluginServices : GeneratedServices {")
        appendLine()
        appendLine("    override fun register(binder: ServiceBinder, context: PluginContext) {")

        implementations.forEachIndexed { index, implementation ->
            val constructor = if (implementation.useContext) "context" else ""
            appendLine("        val impl$index = ${implementation.qualified}($constructor)")
            implementation.services.forEach { service ->
                appendLine("        binder.bind(")
                appendLine("            ServiceKey<${service.qualified}>(${service.id.quote()}),")
                appendLine("            impl$index,")
                appendLine(
                    "            ${service.packageName}.${service.simpleName}Dispatcher(impl$index),"
                )
                appendLine("        )")
            }
        }

        appendLine("    }")
        appendLine("}")
    }

    private fun payloadExpression(
        service: ServiceModel,
        method: MethodModel,
        suffix: String,
    ): String {
        if (method.parameters.isEmpty()) return "ByteArray(0)"
        val args = method.parameters.joinToString(", ") { it.name }
        return "ServiceWire.encode(serializer<${service.simpleName}$suffix${method.capitalized}Args>(), " +
            "${service.simpleName}$suffix${method.capitalized}Args($args))"
    }
}

private class ServiceModel(
    val declaration: KSClassDeclaration,
    val qualified: String,
    val id: String,
    val methods: List<MethodModel>,
) {
    val packageName: String get() = declaration.packageName.asString()
    val simpleName: String get() = declaration.simpleName.asString()
}

private class MethodModel(
    val name: String,
    val parameters: List<ParameterModel>,
    val returnType: KSType,
    val isSuspend: Boolean,
) {
    val capitalized: String get() = name.replaceFirstChar { it.uppercaseChar() }

    fun signature(): String = parameters.joinToString(", ") { "${it.name}: ${it.type.render()}" }
}

private class ParameterModel(val name: String, val type: KSType)

private class ImplementationModel(
    val declaration: KSClassDeclaration,
    val qualified: String,
    val services: List<ServiceModel>,
    val useContext: Boolean,
)

private fun KSType.isUnit(): Boolean = declaration.qualifiedName?.asString() == "kotlin.Unit"

private fun KSType.isFunctionType(): Boolean =
    declaration.qualifiedName?.asString()?.startsWith("kotlin.Function") == true

private fun KSType.render(): String {
    val declaration = declaration
    val base = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
    val args = arguments
    val text = if (args.isEmpty()) base else
        base + "<" + args.joinToString(", ") { it.type?.resolve()?.render() ?: "*" } + ">"
    return if (isMarkedNullable) "$text?" else text
}

private fun String.quote(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun annotationString(
    annotated: KSAnnotated,
    qualifiedName: String,
    argument: String,
): String? = annotated.annotations
    .firstOrNull { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }
    ?.arguments
    ?.firstOrNull { it.name?.asString() == argument }
    ?.value as? String

private const val MODULE_SERVICE = "moe.lolosia.keel.api.annotation.ModuleService"
private const val MODULE_SERVICE_PROVIDER = "moe.lolosia.keel.api.annotation.ModuleServiceProvider"
private const val PLUGIN_MAIN = "moe.lolosia.keel.api.annotation.KeelPluginMain"
private const val PLUGIN_CONTEXT = "moe.lolosia.keel.api.PluginContext"
