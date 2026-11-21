package moe.lolosia.keel.ksp.plugin

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

/** api 工件的落点；由插件构建通过 `ksp { arg(...) }` 传入。 */
private const val API_DIR_OPTION = "keel.api.dir"

/** 消费方子类胶水的落点；默认不生成。 */
private const val GLUE_DIR_OPTION = "keel.glue.dir"

/** core 模式下提供者分发器 / 登记入口的落点；默认不生成。 */
private const val PROVIDER_DIR_OPTION = "keel.provider.dir"

/** 生成模式：`MIRROR`（默认，impl 生成 api）或 `CONTRACT_FIRST`（契约手写在 api 模块）。 */
private const val MODE_OPTION = "keel.api.mode"

/** 为 `true` 时在 Worker 入口调用 `OrmMetaBootstrap.install()`（由 `moe.lolosia.keel.database` 传入）。 */
private const val ORM_ENABLED_OPTION = "keel.orm.enabled"

private const val MODE_MIRROR = "MIRROR"

private const val MODE_CONTRACT_FIRST = "CONTRACT_FIRST"

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

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val mode = environment.options[MODE_OPTION]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val contractFirst = when (mode) {
            null, MODE_MIRROR -> false
            MODE_CONTRACT_FIRST -> true
            else -> {
                environment.logger.warn("未知的 keel.api.mode=$mode，按 MIRROR 处理")
                false
            }
        }
        return KeelPluginWorkerProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            apiDir = environment.options[API_DIR_OPTION]?.takeIf { it.isNotBlank() },
            glueDir = environment.options[GLUE_DIR_OPTION]?.takeIf { it.isNotBlank() },
            providerDir = environment.options[PROVIDER_DIR_OPTION]?.takeIf { it.isNotBlank() },
            contractFirst = contractFirst,
            ormEnabled = environment.options[ORM_ENABLED_OPTION]?.trim() == "true",
        )
    }
}

private class KeelPluginWorkerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val apiDir: String?,
    private val glueDir: String?,
    private val providerDir: String?,
    private val contractFirst: Boolean,
    private val ormEnabled: Boolean,
) : SymbolProcessor {

    private var generated = false

    /** core 模式（无 `@KeelPluginMain`）只生成一次；KSP 每轮都会再进 process。 */
    private var coreGenerated = false

    private val publicApi = KeelPublicApiGenerator(codeGenerator, logger)

    private val subclass = KeelSubclassGenerator(logger, glueDir)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val mains = resolver.getSymbolsWithAnnotation(ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (mains.isEmpty()) {
            // 没有插件入口：CONTRACT_FIRST 下这是 api / core 模块，只生成 proxy 与 Key
            if (!coreGenerated) {
                coreGenerated = true
                if (contractFirst) publicApi.generateContracts(resolver, apiDir, providerDir)
            }
            return emptyList()
        }

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

        // @PublicApi 的注册表与 Worker 入口成对生成：入口固定引用 KeelPluginPublicApi
        publicApi.generate(resolver, main, apiDir, contractFirst)

        // 消费方子类（如 Dog : Animal）的 overrides / dispatcher，写进提交制的源码目录
        subclass.generate(resolver)

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

    private fun render(pkg: String, qualified: String): String {
        // ORM 的方言与实体登记由 keel-orm-ksp 生成的引导器完成；没有实体时它也是空实现
        val bootstrap = if (ormEnabled) {
            "        moe.lolosia.keel.orm.generated.OrmMetaBootstrap.install()\n"
        } else {
            ""
        }
        return """
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
        | * `KeelPluginPublicApi` 由本处理器生成，没有 `@PublicApi` 声明时是空实现。
        | */
        |@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
        |@CName("keel_plugin_worker_main")
        |fun keelPluginWorkerMain(api: CPointer<KeelPluginApi>?, out: CPointer<KeelPluginHandlers>?): UInt {
        |$bootstrap        return PluginWorker.enter(api, out, KeelPluginRoutes, KeelPluginPublicApi) { $qualified() }
        |}
        |
        """.trimMargin()
    }

    private companion object {
        const val ANNOTATION = "moe.lolosia.keel.api.annotation.KeelPluginMain"
    }
}
