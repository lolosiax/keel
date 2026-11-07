package moe.lolosia.keel.gradle.plugin

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import java.util.Locale

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

        val extension = project.extensions.create("keelPlugin", KeelPluginExtension::class.java)

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

            val manifest = project.file(extension.manifest)
            task.from(manifest, Action<CopySpec> { it.into("") })

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

            task.doFirst {
                require(manifest.isFile) { "keelPlugin.manifest 不存在：${manifest}" }
            }
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
