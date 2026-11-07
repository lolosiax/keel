package moe.lolosia.keel.gradle.logger

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
        target.extensions.create("keelLogger", KeelLoggerExtension::class.java, target.objects)
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation is KotlinNativeCompilation

    override fun getCompilerPluginId(): String = PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(GROUP, ARTIFACT, compilerPluginVersion())

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        return project.provider {
            val extension = project.extensions.findByType(KeelLoggerExtension::class.java)
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
                add(SubpluginOption("workspaceRoot", workspaceRoot))
                roots.forEach { add(SubpluginOption("sourceRoot", it)) }
            }
        }
    }

    private fun compilerPluginVersion(): String =
        KeelLoggerGradlePlugin::class.java.`package`?.implementationVersion
            ?: error("[keel-logger] 编译器插件版本缺失：请从 Gradle 插件 jar 加载本插件")

    private companion object {
        const val PLUGIN_ID = "moe.lolosia.keel.logger"
        const val GROUP = "moe.lolosia.keel.toolchain"
        const val ARTIFACT = "keel-logger-compiler-plugin"
    }
}
