package moe.lolosia.keel.gradle.plugin

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary

/**
 * 单 exe 产出：把插件工程的 `.keel` 与 `include(...)` 的依赖摊平成 bundle，
 * 再注入预编译 launcher，得到 `build/keel/<target>/<name>[.exe]`。
 *
 * 由 `moe.lolosia.keel.plugin` 自动 apply；`keelPlugin.executable.enabled = false` 时什么都不做。
 * 用户工程始终是插件工程，不编译 Host、不声明 executable。
 */
class KeelBundlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val keelPlugin = project.extensions.findByType(KeelPluginExtension::class.java)
            ?: throw GradleException(
                "moe.lolosia.keel.bundle 需要先应用 moe.lolosia.keel.plugin"
            )

        project.afterEvaluate {
            if (!keelPlugin.enabled) return@afterEvaluate
            val executable = keelPlugin.executable
            if (!executable.enabled) return@afterEvaluate
            // 命令行属性优先于工程里写死的值（CI 用 -Pkeel.buildType=RELEASE 出包）
            (project.findProperty("keel.buildType") as String?)?.let { executable.buildType = it }

            // launcher 按 keel 框架版本解析，exe 的版本资源用工程自己的 version
            val keelVersion = keelPlugin.keelVersion ?: project.version.toString()
            val appVersion = project.version.toString()
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            val targets = kotlin.targets.withType(KotlinNativeTarget::class.java)
                .filter { target ->
                    target.binaries.withType(SharedLibrary::class.java).isNotEmpty() &&
                        (keelPlugin.targets.isEmpty() || target.name in keelPlugin.targets)
                }
            if (targets.isEmpty()) {
                throw GradleException(
                    "keelPlugin.executable.enabled = true，但没有可打包的 native sharedLib 目标" +
                        "（keelPlugin.targets = ${keelPlugin.targets}）"
                )
            }

            // release 出包用 keelPackageRelease（release sharedLib、已 strip），调试仍用 keelPackage
            val ownPackage = project.tasks.named(executable.packageTaskName(), Zip::class.java)
            val projectPackages = executable.projectInputs.map { other ->
                try {
                    other.tasks.named(executable.packageTaskName(), Zip::class.java)
                } catch (e: Exception) {
                    throw GradleException(
                        "keelPlugin.executable.include(project) 需要目标工程应用 moe.lolosia.keel.plugin：" +
                            "${other.path} 没有 ${executable.packageTaskName()} 任务",
                        e,
                    )
                }
            }
            val coordinateConfig = project.configurations.create("keelBundleCoordinates") { config ->
                config.isCanBeConsumed = false
                config.isCanBeResolved = true
                config.isVisible = false
                config.description = "单 exe bundle 额外引用的 .keel 工件"
            }
            executable.coordinates().forEach { coordinate ->
                project.dependencies.add(coordinateConfig.name, coordinate)
            }

            val bundleInputs = project.files(
                ownPackage.flatMap { it.archiveFile },
                executable.inputs,
                coordinateConfig,
            )
            bundleInputs.from(projectPackages.map { it.flatMap { pkg -> pkg.archiveFile } })

            val executableTasks = targets.map { target ->
                val suffix = target.name.replaceFirstChar { it.uppercaseChar() }

                val bundleTask = project.tasks.register(
                    "keelBundle$suffix",
                    KeelBundleTask::class.java,
                ) { task ->
                    task.group = "keel"
                    task.description = "把插件与依赖摊平成 ${target.name} 的 keel-bundle（${executable.compression}）"
                    task.packages.from(bundleInputs)
                    task.target.set(target.name)
                    task.compression.set(executable.compression)
                    task.compressionLevel.set(executable.compressionLevel)
                    task.outputFile.set(
                        project.layout.buildDirectory.file(
                            "generated/keel/bundle/${target.name}/keel-bundle.bin"
                        )
                    )
                }

                val launcherFiles = if (executable.launcher.isNullOrBlank()) {
                    val config = project.configurations.create("keelLauncher$suffix") { c ->
                        c.isCanBeConsumed = false
                        c.isCanBeResolved = true
                        c.isVisible = false
                        c.description = "${target.name} 单 exe 使用的预编译 launcher"
                    }
                    val artifact = if (executable.buildType.equals("DEBUG", ignoreCase = true)) {
                        "keel-launcher-dev-${target.name}"
                    } else {
                        "keel-launcher-${target.name}"
                    }
                    project.dependencies.add(
                        config.name,
                        "moe.lolosia.keel:$artifact:$keelVersion@zip",
                    )
                    project.files(config)
                } else {
                    project.files(project.file(executable.launcher!!))
                }

                val isWindows = target.name.startsWith("mingw")
                project.tasks.register(
                    "keelExecutable$suffix",
                    KeelExecutableTask::class.java,
                ) { task ->
                    task.group = "keel"
                    task.description = "把 ${target.name} 的 bundle 注入 launcher，产出单文件"
                    task.bundle.set(bundleTask.flatMap { it.outputFile })
                    task.launcherArtifacts.from(launcherFiles)
                    task.target.set(target.name)
                    task.appName.set(executable.name)
                    task.console.set(executable.console)
                    // release 出包先在注入前 strip，避免 UpdateResource 留下失效的 COFF 指针
                    task.stripLauncher.set(executable.isRelease())
                    task.version.set(appVersion)
                    task.productName.set(
                        project.provider {
                            executable.windows.productName.ifBlank { executable.name }
                        }
                    )
                    task.fileDescription.set(
                        project.provider {
                            executable.windows.fileDescription.ifBlank {
                                executable.windows.productName.ifBlank { executable.name }
                            }
                        }
                    )
                    task.companyName.set(executable.windows.companyName)
                    task.copyright.set(executable.windows.copyright)
                    executable.windows.icon.takeIf { it.isNotBlank() }?.let { path ->
                        task.icon.set(project.file(path).absolutePath)
                    }
                    task.outputFile.set(
                        project.layout.buildDirectory.file(
                            "keel/${target.name}/${executable.name}" +
                                if (isWindows) ".exe" else ""
                        )
                    )
                }
            }

            val aggregate = project.tasks.register("keelExecutable") { task ->
                task.group = "keel"
                task.description = "产出所有目标的内嵌 bundle 单文件"
                task.dependsOn(executableTasks)
            }
            project.tasks.named("assemble").configure { it.dependsOn(aggregate) }
        }
    }
}
