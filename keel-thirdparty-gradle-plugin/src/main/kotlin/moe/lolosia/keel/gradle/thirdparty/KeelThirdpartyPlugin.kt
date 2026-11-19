package moe.lolosia.keel.gradle.thirdparty

import moe.lolosia.keel.gradle.common.KeelExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File

/**
 * 第三方原生库的消费与发布接线。
 *
 * 消费：按目标解析 zip → 解压 → 用包内 `.def` 建 cinterop → 追加 `-L` / 链接参数，
 * 并把库文件声明成 cinterop 与链接任务的输入。
 *
 * 发布 / 门禁：`keelThirdpartyCheckManifest` / `keelThirdpartyCheckLicenses` /
 * `keelThirdpartyNotices` / `keelThirdpartyVerify` / `keelThirdpartyPublish`，
 * 依赖 `thirdparty/manifest.json`，见 `doc/thirdparty-licensing.md`。
 *
 * 与 keel 运行期无关：只 apply 本插件的普通 KMP 工程也能用。
 */
class KeelThirdpartyPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = KeelExtension.of(project).register(KeelThirdpartyExtension(project))
        // 顶层别名也保留，方便 Groovy / 旧脚本
        project.extensions.add("keelThirdparty", extension)

        project.afterEvaluate {
            registerChecks(project, extension)
            if (!extension.enabled) return@afterEvaluate

            val extracted = mutableMapOf<String, String>()
            val extractTasks = mutableListOf<TaskProvider<*>>()
            if (project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
                val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
                kotlin.targets.withType(KotlinNativeTarget::class.java).forEach { target ->
                    configureTarget(project, target, extension, extracted, extractTasks)
                }
            }
            registerVerify(project, extracted, extractTasks)
        }
    }

    private fun configureTarget(
        project: Project,
        target: KotlinNativeTarget,
        extension: KeelThirdpartyExtension,
        extracted: MutableMap<String, String>,
        extractTasks: MutableList<TaskProvider<*>>,
    ) {
        val compilation = target.compilations.getByName("main")
        val libraries = extension.libraries
            .filter { library -> library.targets.isEmpty() || target.name in library.targets }
        if (libraries.isEmpty()) return

        libraries.forEach { library ->
            val extractDir = project.layout.buildDirectory
                .dir("thirdparty/${library.name}/${target.name}")
                .get().asFile
            extracted["${library.name}@${target.name}"] = extractDir.absolutePath

            val extract = registerExtract(project, library, target, extension, extractDir)
            extractTasks += extract
            val includeDir = File(extractDir, "include")
            val libDir = File(extractDir, "lib/${target.name}")
            val packageDef = extract.map { File(extractDir, "def/${library.defFile}") }

            val definition = library.headerFilter?.let { filter ->
                val defTask = project.tasks.register(
                    "keelThirdpartyDef${taskSuffix(library.name, target.name)}",
                    KeelThirdpartyDefTask::class.java,
                ) { task ->
                    task.group = GROUP
                    task.description = "为 ${library.name}@${target.name} 追加 headerFilter"
                    task.sourceDef.set(project.layout.file(packageDef))
                    task.headerFilter.set(filter)
                    task.outputDef.set(
                        project.layout.buildDirectory.file(
                            "thirdparty/${library.name}/def/${target.name}/${library.defFile}"
                        )
                    )
                }
                defTask.flatMap { it.outputDef }
            } ?: project.layout.file(packageDef)

            compilation.cinterops.create(library.name) { settings ->
                settings.definitionFile.set(definition)
                settings.includeDirs(includeDir)
                settings.packageName = library.packageName
            }

            target.binaries.all { binary ->
                binary.linkerOpts("-L${libDir.absolutePath}")
                library.linkerOpts.forEach { binary.linkerOpts(it) }
                // 本地目录包在配置期就能列出库文件；Maven 包由 .def 的 linkerOpts 提供 -l
                if (library.dir != null) {
                    localLinkInputs(libDir).forEach { binary.linkerOpts(it) }
                }
                binary.linkTaskProvider.configure { link ->
                    link.dependsOn(extract)
                    // 静态库内容变了也要重链
                    link.inputs.dir(libDir)
                }
            }
        }
    }

    private fun registerExtract(
        project: Project,
        library: KeelThirdpartyLibrary,
        target: KotlinNativeTarget,
        extension: KeelThirdpartyExtension,
        extractDir: File,
    ): TaskProvider<Sync> = project.tasks.register(
        "keelThirdpartyExtract${taskSuffix(library.name, target.name)}",
        Sync::class.java,
    ) { task ->
        task.group = GROUP
        task.description = "解出 ${library.name} 的 ${target.name} 工件"
        task.into(extractDir)

        val localDir = library.dir
        if (localDir != null) {
            task.from(project.file(localDir))
        } else {
            val version = library.version ?: extension.resolvedVersion()
            val coordinate = "${extension.group}:${library.name}-${target.name}:$version@zip"
            // 配置名保留原始库名 / 目标名，避免不同库归一化后撞名
            val configurationName = "keelThirdparty-${library.name}-${target.name}"
            val configuration = project.configurations.findByName(configurationName)
                ?: project.configurations.create(configurationName) {
                it.isCanBeConsumed = false
                it.isCanBeResolved = true
                it.isVisible = false
                it.description = "${library.name} 的 ${target.name} 原生库工件"
            }
            if (configuration.dependencies.isEmpty()) {
                project.dependencies.add(configuration.name, coordinate)
            }
            task.from(project.zipTree(configuration))
        }
    }

    private fun registerChecks(project: Project, extension: KeelThirdpartyExtension) {
        val manifest = project.file(extension.manifest)
        val manifestProvider = project.layout.file(project.provider { manifest })

        val checkManifest = project.tasks.register(
            "keelThirdpartyCheckManifest",
            KeelThirdpartyCheckManifestTask::class.java,
        ) { task ->
            task.group = GROUP
            task.description = "校验 thirdparty/manifest.json 的字段与文件完整性"
            task.projectDir.set(project.layout.projectDirectory)
            if (manifest.isFile) task.manifestFile.set(manifestProvider)
        }

        val checkLicenses = project.tasks.register(
            "keelThirdpartyCheckLicenses",
            KeelThirdpartyCheckLicensesTask::class.java,
        ) { task ->
            task.group = GROUP
            task.description = "按链接方式校验第三方库许可"
            if (manifest.isFile) task.manifestFile.set(manifestProvider)
        }

        val notices = project.tasks.register(
            "keelThirdpartyNotices",
            KeelThirdpartyNoticesTask::class.java,
        ) { task ->
            task.group = GROUP
            task.description = "由 manifest 生成 THIRD-PARTY-NOTICES.md 与 SBOM.json"
            if (manifest.isFile) task.manifestFile.set(manifestProvider)
            task.outputDirectory.set(project.file(extension.noticesDirectory))
        }

        project.tasks.register("keelThirdpartyPublish", KeelThirdpartyPublishTask::class.java) { task ->
            task.group = GROUP
            task.description = "把编译好的原生库按 manifest 打包成 Maven 工件"
            if (manifest.isFile) task.manifestFile.set(manifestProvider)
            task.publishFrom.set(project.file(extension.publishFrom))
            task.projectDir.set(project.layout.projectDirectory)
            task.outputDirectory.set(project.layout.buildDirectory.dir("thirdparty/repo"))
        }

        project.plugins.withId("base") {
            project.tasks.named("check").configure {
                it.dependsOn(checkManifest, checkLicenses)
            }
        }
    }

    private fun registerVerify(
        project: Project,
        extracted: Map<String, String>,
        extractTasks: List<TaskProvider<*>>,
    ) {
        project.tasks.register("keelThirdpartyVerify", KeelThirdpartyVerifyTask::class.java) { task ->
            task.group = GROUP
            task.description = "确认每个库 × 目标的 def / include / lib 都不为空"
            task.extracted.set(extracted)
            task.dependsOn(extractTasks)
        }
    }

    private fun localLinkInputs(libDir: File): List<String> =
        libDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.lowercase() in LIBRARY_EXTENSIONS }
            .sortedBy { it.name }
            .map { it.absolutePath }

    private fun taskSuffix(library: String, target: String): String =
        library.split('-', '_', '.').joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } } +
            target.replaceFirstChar { it.uppercaseChar() }

    private companion object {
        const val GROUP = "keel"

        val LIBRARY_EXTENSIONS = setOf("a", "lib", "so", "dylib", "dll")
    }
}
