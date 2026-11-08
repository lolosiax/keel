package moe.lolosia.keel.gradle.router

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate

/**
 * Web 路由生成器的 KSP 入口。
 *
 * 扫描插件里的 `@Controller` / `@GET` 等注解，生成与 `@KeelPluginMain` 同包的
 * `KeelPluginRoutes`：它实现 `GeneratedRoutes`，把每条路由的 `RouteDescriptor`、
 * 权限节点、参数绑定与返回值适配全部写成普通代码，运行期不需要反射。
 *
 * 与 `keel-plugin-ksp` 的约定：后者生成的 Worker 入口把这个对象交给 `PluginWorker`，
 * 所以两个处理器必须一起挂到 `ksp<target>` 配置上（由 `moe.lolosia.keel.plugin` 负责）。
 */
class KeelRouterProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        KeelRouterProcessor(environment.codeGenerator, environment.logger)
}

private class KeelRouterProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        // 输出包名跟着插件入口走；没有 @KeelPluginMain 的编译单元不生成任何东西
        val mains = resolver.getSymbolsWithAnnotation(PLUGIN_MAIN)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (mains.isEmpty()) return emptyList()
        val deferredMains = mains.filterNot { it.validate() }
        if (deferredMains.isNotEmpty()) return deferredMains

        val controllers = resolver.getSymbolsWithAnnotation(CONTROLLER)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        val deferredControllers = controllers.filterNot { it.validate() }
        if (deferredControllers.isNotEmpty()) return deferredControllers

        val models = buildControllers(controllers)
        val pkg = mains.first().packageName.asString()
        val file = codeGenerator.createNewFile(
            // 汇总多个源文件：必须把所有文件作为依赖，否则未改动文件的控制器会被漏掉
            dependencies = Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()),
            packageName = pkg,
            fileName = "KeelPluginRoutes",
            extensionName = "kt",
        )
        file.bufferedWriter().use { it.write(renderRoutes(pkg, models)) }
        generated = true
        return emptyList()
    }

    private fun buildControllers(controllers: List<KSClassDeclaration>): List<ControllerModel> {
        val routeIds = mutableSetOf<String>()
        return controllers.mapNotNull { controller ->
            val kind = controller.classKind
            if (kind != ClassKind.OBJECT && kind != ClassKind.CLASS) {
                logger.error("@Controller 只能标在 object 或 class 上", controller)
                return@mapNotNull null
            }
            if (kind == ClassKind.CLASS && controller.primaryConstructor?.parameters?.isNotEmpty() == true) {
                logger.error("@Controller 的类必须有无参构造函数", controller)
                return@mapNotNull null
            }
            if (controller.qualifiedName == null) {
                logger.error("@Controller 必须是具名类", controller)
                return@mapNotNull null
            }

            val basePath = annotationArg(controller, CONTROLLER, "path").orEmpty()
            val classPermissions = permissionsOf(controller)
            val routes = controller.declarations
                .filterIsInstance<KSFunctionDeclaration>()
                .mapNotNull { function ->
                    buildRoute(controller, function, basePath, classPermissions, routeIds)
                }
                .toList()
            ControllerModel(controller, routes)
        }
    }

    private fun buildRoute(
        controller: KSClassDeclaration,
        function: KSFunctionDeclaration,
        basePath: String,
        classPermissions: List<String>,
        routeIds: MutableSet<String>,
    ): RouteModel? {
        val http = HTTP_ANNOTATIONS.firstNotNullOfOrNull { (annotation, method) ->
            if (hasAnnotation(function, annotation)) annotation to method else null
        }
        if (http == null) return null

        val qualified = controller.qualifiedName?.asString() ?: run {
            logger.error("@Controller 必须是具名类", controller)
            return null
        }
        val functionName = function.simpleName.asString()
        val routeId = "$qualified#$functionName"
        if (!routeIds.add(routeId)) {
            logger.error("重复的路由 id：$routeId（同名的重载函数请改函数名）", function)
            return null
        }

        val parameters = function.parameters.mapNotNull { parameter ->
            buildParameter(parameter)
        }
        val returnType = function.returnType?.resolve() ?: return null

        val path = joinPath(basePath, annotationArg(function, http.first, "path").orEmpty())
        val permissions = (permissionsOf(function) + classPermissions).distinct()

        return RouteModel(
            method = http.second,
            path = path,
            routeId = routeId,
            permissions = permissions,
            controller = controller,
            function = function,
            parameters = parameters,
            returnType = returnType,
        )
    }

    private fun buildParameter(parameter: KSValueParameter): ParameterModel? {
        val name = parameter.name?.asString() ?: return null
        val type = parameter.type.resolve()

        if (isContext(type)) return ParameterModel(ContextBinding, type)

        val body = hasAnnotation(parameter, BODY)
        val path = hasAnnotation(parameter, PATH)
        val query = hasAnnotation(parameter, QUERY)
        val header = hasAnnotation(parameter, HEADER)
        val annotated = listOf(body, path, query, header).count { it }
        if (annotated == 0) {
            logger.error(
                "参数 $name 未标注：只支持 Context / @Body / @Path / @Query / @Header" +
                    "（跨模块服务注入在 M1-2 接入）",
                parameter,
            )
            return null
        }
        if (annotated > 1) {
            logger.error("参数 $name 只能有一个绑定注解", parameter)
            return null
        }

        if (body) return ParameterModel(BodyBinding, type)

        val source = when {
            path -> Source.PATH
            query -> Source.QUERY
            else -> Source.HEADER
        }
        val annotation = when (source) {
            Source.PATH -> PATH
            Source.QUERY -> QUERY
            Source.HEADER -> HEADER
        }
        val boundName = annotationArg(parameter, annotation, "name")?.takeIf { it.isNotBlank() } ?: name
        return ParameterModel(SimpleBinding(source, boundName), type)
    }

    private fun renderRoutes(pkg: String, controllers: List<ControllerModel>): String = buildString {
        appendLine("// 由 keel-router-ksp 生成，不要手改。")
        appendLine("package $pkg")
        appendLine()
        appendLine("import kotlinx.serialization.decodeFromString")
        appendLine("import kotlinx.serialization.encodeToString")
        appendLine("import kotlinx.serialization.json.Json")
        appendLine("import kotlinx.serialization.serializer")
        appendLine("import moe.lolosia.keel.api.web.*")
        if (controllers.any { it.usesUuid() }) appendLine("import kotlin.uuid.Uuid")
        appendLine()
        appendLine("object KeelPluginRoutes : GeneratedRoutes {")
        appendLine()
        appendLine("    private const val JSON_TYPE = \"application/json; charset=utf-8\"")
        appendLine()
        appendLine("    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }")
        appendLine()

        controllers.forEachIndexed { index, controller ->
            if (controller.declaration.classKind == ClassKind.CLASS) {
                appendLine("    private val controller$index = ${controller.qualified}()")
            }
        }
        if (controllers.any { it.declaration.classKind == ClassKind.CLASS }) appendLine()

        appendLine("    override fun permissions(): List<PermissionNode> = listOf(")
        permissionNodes(controllers).forEach { node ->
            appendLine(
                "        PermissionNode(" +
                    "node = ${node.node.quote()}, title = ${node.title.quote()}, " +
                    "controller = ${node.controller.quote()}, function = ${node.function.quote()}),"
            )
        }
        appendLine("    )")
        appendLine()
        appendLine("    override fun register(routes: Routes) {")

        controllers.forEachIndexed { index, controller ->
            controller.routes.forEach { route ->
                renderRoute(this, index, route)
            }
        }

        appendLine("    }")
        appendLine()
        appendLine(HELPERS)
        appendLine("}")
    }

    private fun renderRoute(out: StringBuilder, controllerIndex: Int, route: RouteModel) {
        val receiver = if (route.controller.classKind == ClassKind.CLASS) {
            "controller$controllerIndex"
        } else {
            route.controller.qualifiedName?.asString() ?: return
        }
        val args = route.parameters.joinToString(", ") { argumentOf(it) }

        out.appendLine("        routes.register(")
        out.appendLine("            RouteDescriptor(")
        out.appendLine("                method = ${route.method.quote()},")
        out.appendLine("                path = ${route.path.quote()},")
        out.appendLine("                routeId = ${route.routeId.quote()},")
        if (route.permissions.isEmpty()) {
            out.appendLine("                permissions = emptyList(),")
        } else {
            out.appendLine("                permissions = listOf(${route.permissions.joinToString(", ") { it.quote() }}),")
        }
        out.appendLine("            ),")
        out.appendLine("        ) { ctx ->")
        out.appendLine("            val result = $receiver.${route.function.simpleName.asString()}($args)")
        out.appendLine("            ${adaptResult(route.returnType)}")
        out.appendLine("        }")
    }

    private fun argumentOf(parameter: ParameterModel): String = when (val binding = parameter.binding) {
        ContextBinding -> "ctx"
        BodyBinding -> "json.decodeFromString(serializer<${parameter.type.render()}>(), ctx.request.body.decodeToString())"
        is SimpleBinding -> convertFrom(binding, parameter.type)
    }

    private fun convertFrom(binding: SimpleBinding, type: KSType): String {
        val raw = when (binding.source) {
            Source.PATH -> "ctx.pathParameters[${binding.name.quote()}]"
            Source.QUERY -> "ctx.query(${binding.name.quote()})"
            Source.HEADER -> "ctx.header(${binding.name.quote()})"
        }
        val name = binding.name.quote()
        val qualified = type.declaration.qualifiedName?.asString()
        val nullable = type.isMarkedNullable

        return when {
            qualified == "kotlin.String" -> if (nullable) raw else "keelParam($name, $raw)"
            qualified == "kotlin.Int" -> if (nullable) "$raw?.toIntOrNull()" else "keelInt($name, $raw)"
            qualified == "kotlin.Long" -> if (nullable) "$raw?.toLongOrNull()" else "keelLong($name, $raw)"
            qualified == "kotlin.Double" -> if (nullable) "$raw?.toDoubleOrNull()" else "keelDouble($name, $raw)"
            qualified == "kotlin.Boolean" -> if (nullable) "$raw?.let { keelBoolean($name, it) }" else "keelBoolean($name, $raw)"
            qualified == "kotlin.uuid.Uuid" ->
                if (nullable) "$raw?.let { Uuid.parse(it) }" else "Uuid.parse(keelParam($name, $raw))"
            type.declaration is KSClassDeclaration &&
                (type.declaration as KSClassDeclaration).classKind == ClassKind.ENUM_CLASS ->
                if (nullable) "$raw?.let { enumValueOf<${type.render()}>(it) }"
                else "enumValueOf<${type.render()}>(keelParam($name, $raw))"
            else -> {
                logger.error(
                    "参数 ${binding.name} 的类型 ${type.render()} 不支持直接绑定，请用 @Body 或改成字符串后自解析",
                    null,
                )
                "keelParam($name, $raw)"
            }
        }
    }

    private fun adaptResult(type: KSType): String {
        val qualified = type.declaration.qualifiedName?.asString()
        return when {
            qualified == "moe.lolosia.keel.api.web.HttpResponse" -> "result"
            qualified == "kotlin.Unit" -> "HttpResponse()"
            qualified == "kotlin.String" -> "HttpResponse(body = ResponseBody.Text(result))"
            qualified == "kotlin.ByteArray" -> "HttpResponse(body = ResponseBody.Bytes(result))"
            // ApiResult 自己就是信封，不再包一层
            qualified == "moe.lolosia.keel.api.web.ApiResult" ->
                "HttpResponse(body = ResponseBody.Text(json.encodeToString(serializer<${type.render()}>(), result), JSON_TYPE))"
            else ->
                "HttpResponse(body = ResponseBody.Text(keelEnvelope(json.encodeToString(serializer<${type.render()}>(), result)), JSON_TYPE))"
        }
    }

    private fun permissionNodes(controllers: List<ControllerModel>): List<NodeModel> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<NodeModel>()
        controllers.forEach { controller ->
            val controllerName = controller.declaration.simpleName.asString()
            controller.routes.forEach { route ->
                val functionName = route.function.simpleName.asString()
                route.permissions.forEach { node ->
                    if (seen.add(node)) {
                        result += NodeModel(node, node, controllerName, functionName)
                    }
                }
            }
        }
        return result
    }

    /** 当前插件用到 `Uuid` 时才生成对应 import。 */
    private fun ControllerModel.usesUuid(): Boolean =
        routes.any { route -> route.parameters.any { it.type.declaration.qualifiedName?.asString() == "kotlin.uuid.Uuid" } }
}

private class ControllerModel(
    val declaration: KSClassDeclaration,
    val routes: List<RouteModel>,
) {
    val qualified: String get() = declaration.qualifiedName!!.asString()
}

private class RouteModel(
    val method: String,
    val path: String,
    val routeId: String,
    val permissions: List<String>,
    val controller: KSClassDeclaration,
    val function: KSFunctionDeclaration,
    val parameters: List<ParameterModel>,
    val returnType: KSType,
)

private class ParameterModel(
    val binding: Binding,
    val type: KSType,
)

private class NodeModel(
    val node: String,
    val title: String,
    val controller: String,
    val function: String,
)

private sealed interface Binding

private object ContextBinding : Binding

private object BodyBinding : Binding

private class SimpleBinding(val source: Source, val name: String) : Binding

private enum class Source { PATH, QUERY, HEADER }

private fun hasAnnotation(annotated: KSAnnotated, qualifiedName: String): Boolean =
    annotated.annotations.any { it.qualifiedName() == qualifiedName }

private fun KSAnnotation.qualifiedName(): String? =
    annotationType.resolve().declaration.qualifiedName?.asString()

private fun annotationArg(annotated: KSAnnotated, qualifiedName: String, argument: String): String? =
    annotated.annotations
        .firstOrNull { it.qualifiedName() == qualifiedName }
        ?.arguments
        ?.firstOrNull { it.name?.asString() == argument }
        ?.value as? String

private fun permissionsOf(annotated: KSAnnotated): List<String> {
    val annotation = annotated.annotations.firstOrNull { it.qualifiedName() == PERMISSION } ?: return emptyList()
    val value = annotation.arguments.firstOrNull { it.name?.asString() == "value" }?.value
    return when (value) {
        is List<*> -> value.mapNotNull { it as? String }
        is String -> listOf(value)
        else -> emptyList()
    }
}

private fun isContext(type: KSType): Boolean {
    val qualified = type.declaration.qualifiedName?.asString()
    // DbContext 是含数据库能力插件用的请求上下文，与 Context 同义
    return qualified == CONTEXT || qualified == DB_CONTEXT
}

private fun joinPath(base: String, sub: String): String {
    val parts = (base.trim('/') + "/" + sub.trim('/'))
        .split('/')
        .filter { it.isNotEmpty() }
    return "/" + parts.joinToString("/")
}

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

private const val PLUGIN_MAIN = "moe.lolosia.keel.api.annotation.KeelPluginMain"
private const val CONTROLLER = "moe.lolosia.keel.api.web.Controller"
private const val PERMISSION = "moe.lolosia.keel.api.web.Permission"
private const val PATH = "moe.lolosia.keel.api.web.Path"
private const val QUERY = "moe.lolosia.keel.api.web.Query"
private const val HEADER = "moe.lolosia.keel.api.web.Header"
private const val BODY = "moe.lolosia.keel.api.web.Body"
private const val CONTEXT = "moe.lolosia.keel.api.web.Context"
private const val DB_CONTEXT = "moe.lolosia.keel.database.DbContext"

private val HTTP_ANNOTATIONS = listOf(
    "moe.lolosia.keel.api.web.GET" to "GET",
    "moe.lolosia.keel.api.web.POST" to "POST",
    "moe.lolosia.keel.api.web.PUT" to "PUT",
    "moe.lolosia.keel.api.web.DELETE" to "DELETE",
    "moe.lolosia.keel.api.web.PATCH" to "PATCH",
)

private val HELPERS = """
    |    private fun keelEnvelope(data: String): String =
    |        "{\"code\":200,\"msg\":\"success\",\"data\":${'$'}data}"
    |
    |    private fun keelParam(name: String, raw: String?): String =
    |        raw ?: throw ApiException(400, "缺少参数：${'$'}name")
    |
    |    private fun keelInt(name: String, raw: String?): Int =
    |        keelParam(name, raw).toIntOrNull() ?: throw ApiException(400, "参数不是整数：${'$'}name")
    |
    |    private fun keelLong(name: String, raw: String?): Long =
    |        keelParam(name, raw).toLongOrNull() ?: throw ApiException(400, "参数不是整数：${'$'}name")
    |
    |    private fun keelDouble(name: String, raw: String?): Double =
    |        keelParam(name, raw).toDoubleOrNull() ?: throw ApiException(400, "参数不是小数：${'$'}name")
    |
    |    private fun keelBoolean(name: String, raw: String?): Boolean = when (keelParam(name, raw).lowercase()) {
    |        "true", "1" -> true
    |        "false", "0" -> false
    |        else -> throw ApiException(400, "参数不是布尔值：${'$'}name")
    |    }
    |
""".trimMargin()
