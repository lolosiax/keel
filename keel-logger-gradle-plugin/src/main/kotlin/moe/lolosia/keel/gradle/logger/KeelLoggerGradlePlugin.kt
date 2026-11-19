package moe.lolosia.keel.gradle.logger

import moe.lolosia.keel.gradle.common.KeelExtension
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation

/**
 * 把 `keel-logger-compiler-plugin` 挂到所有 KMP native 编译。
 *
 * 由 KGP 负责 `-Xplugin`、`-P`、插件 jar 的输入与任务依赖；本插件只提供
 * 编译器插件坐标与源码根信息。
 */
class KeelLoggerGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        val extension = KeelExtension.of(target).register(KeelLoggerExtension(target.objects))
        // 兼容旧写法：keelLogger { } 与 keel { logger { } } 是同一个对象
        target.extensions.add("keelLogger", extension)
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation is KotlinNativeCompilation

    override fun getCompilerPluginId(): String = PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(GROUP, ARTIFACT, compilerPluginVersion())

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        return project.provider {
            val extension = project.extensions.findByType(KeelExtension::class.java)
                ?.find(KeelLoggerExtension::class.java)
            if (extension != null && !extension.enabled.get()) {
                return@provider listOf(SubpluginOption("enabled", "false"))
            }

            val workspaceRoot = extension?.workspaceRoot?.orNull
                ?: project.gradle.startParameter.currentDir.absolutePath
            val roots = kotlinCompilation.allKotlinSourceSets
                .flatMap { it.kotlin.srcDirs }
                .map { it.absolutePath }
                .distinct()

            buildList {
                add(SubpluginOption("enabled", "true"))
                add(SubpluginOption("exceptionTrace", (extension?.exceptionTrace?.get() ?: true).toString()))
                // 未单独配置时跟随 debug：正常模块两者一起开，keel-api 只开前者
                val debugValues = extension?.debugValues?.orNull ?: (extension?.debug?.get() ?: true)
                add(SubpluginOption("debugValues", debugValues.toString()))
                add(SubpluginOption("workspaceRoot", workspaceRoot))
                roots.forEach { add(SubpluginOption("sourceRoot", it)) }

                // 调试位置表：文件名带上工程与目标，避免同一运行目录里 Host 与插件互相覆盖
                if (extension?.debug?.get() != false) {
                    val directory = extension?.debugOutput?.orNull?.asFile
                        ?: project.layout.buildDirectory.dir("keel-debug").get().asFile
                    val name = "${project.name}-${kotlinCompilation.target.name}-${kotlinCompilation.name}.keel-debug"
                    add(SubpluginOption("debug", "true"))
                    add(SubpluginOption("debugOutput", directory.resolve(name).absolutePath))
                    add(SubpluginOption("debugModule", extension?.debugModule?.getOrNull() ?: project.name))
                    add(SubpluginOption("debugBuildId", project.version.toString()))
                } else {
                    add(SubpluginOption("debug", "false"))
                }
            }
        }
    }

    private fun compilerPluginVersion(): String {
        KeelLoggerGradlePlugin::class.java.`package`?.implementationVersion?.let { return it }
        // composite 下插件从 classes 目录加载时 manifest 不可用，回退到构建期写入的资源
        val fromResource = KeelLoggerGradlePlugin::class.java
            .getResourceAsStream("/META-INF/keel-logger-version.txt")
            ?.bufferedReader()
            ?.use { it.readText().trim() }
        if (!fromResource.isNullOrBlank()) return fromResource
        error("[keel-logger] 编译器插件版本缺失：请从 Gradle 插件 jar 加载本插件")
    }

    private companion object {
        const val PLUGIN_ID = "moe.lolosia.keel.logger"
        const val GROUP = "moe.lolosia.keel.toolchain"
        const val ARTIFACT = "keel-logger-compiler-plugin"
    }
}
