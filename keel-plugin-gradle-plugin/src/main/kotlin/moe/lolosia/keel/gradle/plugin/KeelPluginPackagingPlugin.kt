package moe.lolosia.keel.gradle.plugin

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import moe.lolosia.keel.gradle.classpath.KeelStripTask
import moe.lolosia.keel.gradle.logger.KeelLoggerGradlePlugin
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import java.util.*

/**
 * `.keel` 插件打包：
 *
 * ```
 * hello-0.1.0.keel
 *   plugin.json
 *   lib/<target>/<sharedLib>
 *   resources/...
 * ```
 *
 * 与普通 exe/dll/so 的 `classpath.zip` 嵌入是两条独立链路：
 * 插件构建不再把资源链接进动态库，而是作为包的一部分。
 */
class KeelPluginPackagingPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // ABI 静态库由这里统一注入：插件工程只写 moe.lolosia.keel.plugin，不手动加 linkerOpts
        project.pluginManager.apply("moe.lolosia.keel.abi")
        // Worker 入口（keel_plugin_worker_main）由 KSP 生成，插件工程不手写这段胶水代码
        project.pluginManager.apply("com.google.devtools.ksp")
        // 日志调用改写：插件工程不手动加 id("moe.lolosia.keel.logger")
        project.plugins.apply(KeelLoggerGradlePlugin::class.java)

        val extension = project.extensions.create("keelPlugin", KeelPluginExtension::class.java, project)
        val manifest = project.file(extension.manifest)

        // 构建期把 git sha / 提交时间合并进 plugin.json；不改仓库里的源文件
        val processedManifest = project.layout.buildDirectory.file("generated/keel/plugin.json")
        val processManifest = project.tasks.register("keelProcessManifest") { task ->
            task.group = "keel"
            task.description = "把构建信息（git sha / 提交时间）合并进 plugin.json"
            val source = manifest
            val root = project.projectDir
            val providers = project.providers
            task.inputs.file(source)
            task.outputs.file(processedManifest)
            task.doLast {
                require(source.isFile) { "keelPlugin.manifest 不存在：$source" }
                fun git(vararg args: String): String = runCatching {
                    providers.exec {
                        it.workingDir = root
                        it.commandLine("git", *args)
                    }.standardOutput.asText.get().trim()
                }.getOrDefault("")

                val sha = git("rev-parse", "--short", "HEAD").ifBlank { "unknown" }
                val commitTime = git("log", "-1", "--format=%cI").ifBlank { "unknown" }

                @Suppress("UNCHECKED_CAST")
                val json = JsonSlurper().parse(source) as Map<String, Any?>
                val merged = json + (
                    "buildInfo" to mapOf(
                        "gitSha" to sha,
                        "commitTime" to commitTime,
                    )
                )
                val out = processedManifest.get().asFile
                out.parentFile.mkdirs()
                out.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(merged)) + "\n")
            }
        }

        /**
         * 注册一个打包任务。
         *
         * `keelPackage` 供 `keelRun` / 本地调试（默认 DEBUG）；
         * `keelPackageRelease` 供单 exe bundle 与发布（release sharedLib + strip）。
         */
        fun registerPackage(name: String, description: String): TaskProvider<Zip> {
            // 两个任务共用目录：release 包加后缀，避免同一次构建里互相覆盖
            val suffix = if (name.endsWith("Release")) "-release" else ""
            return project.tasks.register(name, Zip::class.java) { task ->
                task.group = "keel"
                task.description = description
                task.archiveFileName.set(
                    project.provider {
                        val base = extension.packageName ?: "${project.name}-${project.version}.keel"
                        if (suffix.isEmpty()) base else base.removeSuffix(".keel") + "$suffix.keel"
                    }
                )
                task.destinationDirectory.set(project.layout.buildDirectory.dir("keel"))
                task.isPreserveFileTimestamps = false
                task.isReproducibleFileOrder = true
                task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE

                task.dependsOn(processManifest)
                task.from(processedManifest, Action<CopySpec> { it.into("") })

                task.from(project.file("src/commonMain/resources"), Action<CopySpec> {
                    it.into("resources")
                    it.exclude("plugin.json")
                })
                task.from(project.file("src/nativeMain/resources"), Action<CopySpec> {
                    it.into("resources")
                })
                task.from(extension.resources.map { project.file(it) }, Action<CopySpec> {
                    it.into("resources")
                })
            }
        }

        val packageTask = registerPackage("keelPackage", "把插件打成 .keel（调试构建，供 keelRun）")
        val releasePackageTask = registerPackage(
            "keelPackageRelease",
            "把插件打成 .keel（release 构建 + strip，供单 exe / 发布）",
        )

        // 单 exe 产出（默认关闭）：与 .keel 打包互不影响，显式开启后额外注入 launcher
        project.pluginManager.apply("moe.lolosia.keel.bundle")

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            // KSP 在自己的 afterEvaluate 里创建 `ksp<target>` 配置并固定处理器 classpath，
            // 处理器依赖必须赶在那一刻挂上，所以这里用 configureEach 而不是 afterEvaluate
            project.configurations.configureEach { configuration ->
                if (!extension.enabled) return@configureEach
                if (nativeTargetOf(project, configuration.name) == null) return@configureEach
                val version = extension.keelVersion ?: project.version.toString()
                project.dependencies.add(
                    configuration.name,
                    "moe.lolosia.keel.toolchain:keel-plugin-ksp:$version",
                )
                // 注解路由：生成 KeelPluginRoutes（keel-plugin-ksp 的 Worker 入口会引用它）
                project.dependencies.add(
                    configuration.name,
                    "moe.lolosia.keel.toolchain:keel-router-ksp:$version",
                )
            }

            project.afterEvaluate {
                if (extension.enabled && extension.autoAddClasspathFull) {
                    val version = extension.keelVersion ?: project.version.toString()
                    val configuration = project.configurations.findByName("commonMainImplementation")
                    if (configuration != null) {
                        project.dependencies.add(
                            configuration.name,
                            "moe.lolosia.keel:keel-classpath-full:$version",
                        )
                    } else {
                        project.logger.warn(
                            "未找到 commonMainImplementation，跳过自动添加 keel-classpath-full"
                        )
                    }
                }
            }

            project.afterEvaluate {
                if (!extension.enabled) return@afterEvaluate
                // 命令行属性优先于工程里写死的值
                (project.findProperty("keel.buildType") as String?)?.let { extension.buildType = it }

                val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
                val selected = extension.targets.ifEmpty { listOf(hostTargetName()) }

                kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { target ->
                    if (target.name !in selected) return@configureEach

                    target.binaries.withType(SharedLibrary::class.java).configureEach { binary ->
                        val isRelease = binary.buildType.name.equals("RELEASE", ignoreCase = true)
                        val packageFor = when {
                            isRelease -> releasePackageTask
                            binary.buildType.name.equals(extension.buildType, ignoreCase = true) -> packageTask
                            else -> return@configureEach
                        }
                        if (isRelease) {
                            // release 产物仍带 COFF / ELF 符号表，打包前显式 strip
                            val suffix = target.name.replaceFirstChar { it.uppercaseChar() } +
                                binary.name.replaceFirstChar { it.uppercaseChar() }
                            val stripTask = project.tasks.register(
                                "keelStrip$suffix",
                                KeelStripTask::class.java,
                            ) { strip ->
                                strip.group = "keel"
                                strip.description = "去掉 ${target.name} release sharedLib 的符号表"
                                strip.inputFile.set(
                                    project.layout.file(binary.linkTaskProvider.flatMap { it.outputFile })
                                )
                                strip.target.set(target.name)
                                strip.outputDirectory.set(
                                    project.layout.buildDirectory.dir(
                                        "generated/keel/strip/${target.name}/${binary.name}"
                                    )
                                )
                            }
                            packageFor.configure { task ->
                                task.dependsOn(stripTask)
                                task.from(
                                    stripTask.flatMap { it.outputDirectory },
                                    Action<CopySpec> { it.into("lib/${target.name}") },
                                )
                            }
                        } else {
                            packageFor.configure { task ->
                                task.dependsOn(binary.linkTaskProvider)
                                task.from(
                                    binary.linkTaskProvider.flatMap { it.outputFile },
                                    Action<CopySpec> { it.into("lib/${target.name}") },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 运行期工件：插件包之外的 .keel（能力包 / 依赖插件）与 Host
        val runConfiguration = project.configurations.create("keelRunPlugins") { configuration ->
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = true
            configuration.isVisible = false
            configuration.description = "keelRun 额外加载的 .keel 工件"
        }
        val hostConfiguration = project.configurations.create("keelHost") { configuration ->
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = true
            configuration.isVisible = false
            configuration.description = "keelRun 使用的 Host 可执行包"
        }

        project.afterEvaluate {
            if (!extension.enabled) return@afterEvaluate

            val version = extension.keelVersion ?: project.version.toString()
            val target = hostTargetName()

            // plugin.json 的 requires 自动映射成同名能力包，版本与本插件一致
            val explicit = extension.runPlugins.toMutableList()
            readRequires(manifest).forEach { id ->
                val artifact = CAPABILITY_ARTIFACTS[id] ?: return@forEach
                if (explicit.none { it.contains(":$artifact:") }) {
                    explicit += "moe.lolosia.keel:$artifact:$version@keel"
                }
            }
            explicit.forEach { coordinate ->
                project.dependencies.add(runConfiguration.name, coordinate)
            }

            val hostProperty = (project.findProperty("keel.run.host") as String?)
                ?: extension.runHostExecutable
            if (hostProperty == null) {
                project.dependencies.add(
                    hostConfiguration.name,
                    "moe.lolosia.keel:keel-host-dev-$target:$version@zip",
                )
            }

            val port = (project.findProperty("keel.run.port") as String?)?.toIntOrNull()
                ?: extension.runPort
            val serveMillis = (project.findProperty("keel.run.serveMs") as String?)?.toLongOrNull()
                ?: extension.runServeMillis

            // 运行目录只组装一次；Host 与插件分开准备，避免改插件时重写正在占用的可执行文件
            val runWorkDir = project.file(extension.runWorkDir)
            val prepareHost = project.tasks.register("keelPrepareHost", KeelPrepareHostTask::class.java) { task ->
                task.group = "keel"
                task.description = "解出运行用的 Host（不启动）"
                task.hostArchive.from(hostConfiguration)
                hostProperty?.let { path ->
                    task.hostExecutable.set(project.layout.projectDirectory.file(path))
                }
                task.hostDirectory.set(runWorkDir.resolve("host"))
            }

            val prepareRun = project.tasks.register("keelPrepareRun", KeelPrepareRunTask::class.java) { task ->
                task.group = "keel"
                task.description = "构建并组装 build/keel-run（不启动 Host）"
                task.dependsOn(packageTask, prepareHost)
                task.pluginPackages.from(packageTask.flatMap { it.archiveFile })
                task.extraArtifacts.from(runConfiguration)
                task.runDirectory.set(runWorkDir)
                task.pluginsDirectory.set(runWorkDir.resolve("plugins"))
                task.configTemplates.from(
                    project.fileTree("dev/config/plugins") { it.include("*.yaml") }
                )
                if (project.file("dev/data").isDirectory) {
                    task.dataTemplate.set(project.layout.projectDirectory.dir("dev/data"))
                }
            }

            project.tasks.register("keelRun", KeelRunTask::class.java) { task ->
                task.group = "keel"
                task.description = "构建插件并启动 Host（集成测试，类似 runServer）"
                task.dependsOn(prepareRun)
                task.runDirectory.set(runWorkDir)
                task.port.set(port)
                serveMillis?.let { task.serveMillis.set(it) }
                task.environment.set(extension.runEnvironment)
                task.arguments.set(extension.runArguments)
            }
        }
    }

    /**
     * `kspMingwX64` / `kspLinuxArm64` 这类配置对应的 native 目标；不是则返回 null。
     *
     * KSP 的命名规则是 `ksp` + 目标名（首字母大写）。
     */
    private fun nativeTargetOf(project: Project, configurationName: String): KotlinNativeTarget? {
        if (!configurationName.startsWith("ksp")) return null
        val targetName = configurationName.removePrefix("ksp").replaceFirstChar { it.lowercaseChar() }
        if (targetName.isEmpty()) return null
        val kotlin = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return null
        return kotlin.targets.findByName(targetName) as? KotlinNativeTarget
    }

    /** 读取 `plugin.json` 的 `requires`；文件缺失 / 格式不对时按空列表处理。 */
    private fun readRequires(manifest: java.io.File): List<String> {
        if (!manifest.isFile) return emptyList()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val json = JsonSlurper().parse(manifest) as Map<String, Any?>
            (json["requires"] as? List<*>).orEmpty().filterIsInstance<String>()
        }.getOrDefault(emptyList())
    }

    private companion object {
        /** `requires` 里的能力 id → 运行期要加的 `.keel` 工件。 */
        val CAPABILITY_ARTIFACTS = mapOf(
            "database" to "keel-database-mariadb",
            "basic-biz" to "keel-plugin-basic-biz",
            "rbac" to "keel-plugin-rbac",
        )
    }
}
