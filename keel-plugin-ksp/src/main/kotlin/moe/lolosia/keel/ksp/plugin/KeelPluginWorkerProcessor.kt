package moe.lolosia.keel.ksp.plugin

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

/**
 * 插件 Worker 入口生成器。
 *
 * 扫描 `@KeelPluginMain` 标注的插件类，生成该插件 dylib 唯一的 C 入口
 * `keel_plugin_worker_main`：构造插件实例，把后续事件交给 `PluginWorker` 统一分发。
 *
 * 生成的文件落在目标编译的 KSP 输出目录里，属于 native 代码（含 `@CName`），
 * 所以处理器按 target 各跑一次，而不是生成到 commonMain。
 */
class KeelPluginWorkerProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        KeelPluginWorkerProcessor(environment.codeGenerator, environment.logger)
}

private class KeelPluginWorkerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var generated = false

    private val services = KeelServiceGenerator(codeGenerator, logger)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // 服务代码先在当前模块生成：契约侧 Key / Client，实现侧 Dispatcher / KeelPluginServices
        val deferredServices = services.process(resolver)
        if (deferredServices.isNotEmpty()) return deferredServices

        if (generated) return emptyList()

        val mains = resolver.getSymbolsWithAnnotation(ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (mains.isEmpty()) return emptyList()

        // 还没解析完的符号留到下一轮，避免误报「没有 @KeelPluginMain」
        val deferred = mains.filterNot { it.validate() }
        if (deferred.isNotEmpty()) return deferred

        if (mains.size > 1) {
            mains.drop(1).forEach { logger.error("一个插件只能有一个 @KeelPluginMain", it) }
            return emptyList()
        }

        val main = mains.single()
        val qualified = main.qualifiedName?.asString()
        if (qualified == null) {
            logger.error("@KeelPluginMain 只能标在具名类上", main)
            return emptyList()
        }

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(false, *listOfNotNull(main.containingFile).toTypedArray()),
            packageName = main.packageName.asString(),
            fileName = "KeelPluginWorker",
            extensionName = "kt",
        )
        file.bufferedWriter().use { writer ->
            writer.write(render(main.packageName.asString(), qualified))
        }
        generated = true
        return emptyList()
    }

    private fun render(pkg: String, qualified: String): String = """
        |// 由 keel-plugin-ksp 生成，不要手改。
        |package $pkg
        |
        |import kotlin.experimental.ExperimentalNativeApi
        |import kotlin.native.CName
        |import kotlinx.cinterop.CPointer
        |import kotlinx.cinterop.ExperimentalForeignApi
        |import moe.lolosia.keel.abi.c.KeelPluginApi
        |import moe.lolosia.keel.abi.c.KeelPluginHandlers
        |import moe.lolosia.keel.api.worker.PluginWorker
        |
        |/**
        | * 插件 dylib 的 C 入口（`plugin.json.entry`）。
        | *
        | * 由 `keel_plugin_bootstrap` 的事件泵线程在收到 START 时调用，
        | * 是该线程第一次进入插件 Kotlin 运行时。
        | *
        | * `KeelPluginRoutes` 由 `keel-router-ksp` 生成在同一包里；没有控制器的插件
        | * 也会生成空实现，所以这里可以无条件传入。
        | *
        | * `KeelPluginServices` 由本处理器生成，没有 `@ModuleServiceProvider` 时是空实现。
        | */
        |@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
        |@CName("keel_plugin_worker_main")
        |fun keelPluginWorkerMain(api: CPointer<KeelPluginApi>?, out: CPointer<KeelPluginHandlers>?): UInt =
        |    PluginWorker.enter(api, out, KeelPluginRoutes, KeelPluginServices) { $qualified() }
        |
    """.trimMargin()

    private companion object {
        const val ANNOTATION = "moe.lolosia.keel.api.annotation.KeelPluginMain"
    }
}
