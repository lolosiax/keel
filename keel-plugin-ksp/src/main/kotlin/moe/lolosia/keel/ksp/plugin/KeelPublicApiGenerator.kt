package moe.lolosia.keel.ksp.plugin

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

/**
 * `@PublicApi` 注册代码生成器。
 *
 * 扫描本模块的 `@PublicApi` 类型与 `@PublicApiHandler` 分发器，生成固定的
 * `KeelPluginPublicApi`：声明类型表、收集分发器，并把 `@PublicApi` 的插件本体
 * 登记为共享单例。生成物由 `PluginWorker` 在 `onLoad` 之后、`onEnable` 之前调用。
 */
internal class KeelPublicApiGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {

    private class TypeEntry(val name: String, val id: String, val hasBase: Boolean)

    private class HandlerEntry(
        val target: String,
        val expression: String,
        val usesPlugin: Boolean,
    )

    private class Instantiation(val expression: String, val usesPlugin: Boolean)

    fun generate(resolver: Resolver, main: KSClassDeclaration) {
        val types = resolver.getSymbolsWithAnnotation(PUBLIC_API)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.CLASS || it.classKind == ClassKind.INTERFACE }
            .distinctBy { it.qualifiedName?.asString() }
            .sortedBy { it.qualifiedName?.asString() }
            .toList()

        val handlerDeclarations = resolver.getSymbolsWithAnnotation(PUBLIC_API_HANDLER)
            .filterIsInstance<KSClassDeclaration>()
            .distinctBy { it.qualifiedName?.asString() }
            .sortedBy { it.qualifiedName?.asString() }
            .toList()

        val mainName = main.qualifiedName?.asString()
        if (mainName == null) {
            logger.error("@KeelPluginMain 只能标在具名类上", main)
            return
        }
        val mainType = main.asStarProjectedType()

        val typeEntries = types.mapNotNull { type ->
            val name = type.qualifiedName?.asString() ?: return@mapNotNull null
            TypeEntry(
                name = name,
                id = idOf(type) ?: name,
                hasBase = type.classKind == ClassKind.CLASS,
            )
        }

        val handlerEntries = handlerDeclarations.mapNotNull { handler ->
            val target = handlerTarget(handler)
            if (target == null) {
                logger.error("@PublicApiHandler 的 type 无法解析", handler)
                return@mapNotNull null
            }
            val instantiation = handlerInstantiation(handler, mainName, mainType)
            if (instantiation == null) {
                logger.error(
                    "@PublicApiHandler 的主构造器不受支持：只允许无参、PluginContext 或插件本体",
                    handler,
                )
                return@mapNotNull null
            }
            HandlerEntry(target, instantiation.expression, instantiation.usesPlugin)
        }

        val mainIsPublic = main.annotations.any { it.isNamed(PUBLIC_API) }
        val singleton = if (mainIsPublic) handlerEntries.firstOrNull { it.target == mainName } else null
        if (mainIsPublic && singleton == null) {
            logger.error(
                "标了 @PublicApi 的 @KeelPluginMain 需要一个 " +
                    "@PublicApiHandler(${main.simpleName.asString()}::class)",
                main,
            )
        }
        val mainId = typeEntries.firstOrNull { it.name == mainName }?.id

        val dependencies = (types + handlerDeclarations)
            .mapNotNull { it.containingFile }
            .distinct()
            .toTypedArray()

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(false, *dependencies),
            packageName = main.packageName.asString(),
            fileName = "KeelPluginPublicApi",
            extensionName = "kt",
        )
        file.bufferedWriter().use { writer ->
            writer.write(render(mainName, mainId, typeEntries, handlerEntries, singleton))
        }
    }

    private fun render(
        mainName: String,
        mainId: String?,
        types: List<TypeEntry>,
        handlers: List<HandlerEntry>,
        singleton: HandlerEntry?,
    ): String = buildString {
        appendLine("// 由 keel-plugin-ksp 生成，不要手改。")
        appendLine("package ${mainName.substringBeforeLast('.')}")
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
        val needsPlugin = singleton != null || handlers.any { it.usesPlugin }
        if (needsPlugin) {
            appendLine("        val pluginInstance = plugin as $mainName")
        }
        if (types.isNotEmpty()) {
            appendLine("        context.publicApi.declare(")
            appendLine("            types = listOf(")
            types.forEach { type ->
                appendLine(
                    "                PublicApiType(\"${escape(type.id)}\", hasBase = ${type.hasBase}),"
                )
            }
            appendLine("            ),")
            if (handlers.isNotEmpty()) {
                appendLine("            handlers = listOf(")
                handlers.forEach { handler ->
                    appendLine("                ${handler.expression},")
                }
                appendLine("            ),")
            }
            appendLine("        )")
        }
        if (singleton != null && mainId != null) {
            appendLine("        context.publicApi.register(")
            appendLine("            instance = pluginInstance,")
            appendLine("            key = PublicApiKey<$mainName>(\"${escape(mainId)}\"),")
            appendLine("            dispatcher = ${singleton.expression},")
            appendLine("            singleton = true,")
            appendLine("        )")
        }
        appendLine("    }")
        appendLine()
        appendLine("    override fun release(plugin: Plugin) {}")
        appendLine("}")
    }

    /** `@PublicApi(id=...)` 优先；留空时用类型全名。 */
    private fun idOf(type: KSClassDeclaration): String? {
        val annotation = type.annotations.firstOrNull { it.isNamed(PUBLIC_API) } ?: return null
        val id = annotation.arguments.firstOrNull()?.value as? String
        return id?.takeIf { it.isNotBlank() }
    }

    /** `@PublicApiHandler(Type::class)` 指向的声明。 */
    private fun handlerTarget(handler: KSClassDeclaration): String? {
        val annotation = handler.annotations.firstOrNull { it.isNamed(PUBLIC_API_HANDLER) } ?: return null
        val type = annotation.arguments.firstOrNull()?.value as? KSType ?: return null
        return type.declaration.qualifiedName?.asString()
    }

    /** 把分发器实例化：无参 / `PluginContext` / 插件本体。 */
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

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val PUBLIC_API = "moe.lolosia.keel.api.annotation.PublicApi"
        const val PUBLIC_API_HANDLER = "moe.lolosia.keel.api.annotation.PublicApiHandler"
        const val PLUGIN_CONTEXT = "moe.lolosia.keel.api.PluginContext"
    }
}
