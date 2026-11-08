package moe.lolosia.keel.gradle.plugin

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import moe.lolosia.keel.gradle.logger.KeelLoggerGradlePlugin
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Zip
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

        val extension = project.extensions.create("keelPlugin", KeelPluginExtension::class.java)
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

        val packageTask = project.tasks.register("keelPackage", Zip::class.java) { task ->
            task.group = "keel"
            task.description = "把插件打成 .keel（plugin.json + lib/<target> + resources/）"
            task.archiveFileName.set(
                project.provider {
                    extension.packageName ?: "${project.name}-${project.version}.keel"
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

                val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
                val selected = extension.targets.ifEmpty { listOf(defaultTargetName()) }

                kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { target ->
                    if (target.name !in selected) return@configureEach

                    target.binaries.withType(SharedLibrary::class.java).configureEach { binary ->
                        if (!binary.buildType.name.equals(extension.buildType, ignoreCase = true)) {
                            return@configureEach
                        }
                        packageTask.configure { task ->
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

    private fun defaultTargetName(): String {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        return when {
            os.contains("win") -> "mingwX64"
            arch.contains("aarch64") || arch.contains("arm64") -> "linuxArm64"
            else -> "linuxX64"
        }
    }
}
