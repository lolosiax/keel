package moe.lolosia.keel.gradle.abi

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import org.jetbrains.kotlin.konan.target.Family
import java.io.File

/**
 * 把 `keel-abi` 的静态库接进每个 native 二进制。
 *
 * 每个二进制（Host 可执行文件、插件 dylib）链接期各自并进一份 `libkeel_abi.a`，
 * 于是队列与句柄表天然按二进制隔离，符合 ABI「每个二进制自带 hidden 实现」的约束。
 *
 * 包来源顺序：`keelAbi.zipFile` → 同仓库 `../keel-abi/build/keel/keel-abi-<version>.zip`。
 * 后者在 composite 里会顺带依赖 `keel-abi` 的打包任务，本地不用额外执行。
 */
class KeelAbiPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("keelAbi", KeelAbiExtension::class.java)

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            project.afterEvaluate {
                if (!extension.enabled) return@afterEvaluate

                val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
                val targets = kotlin.targets.withType(KotlinNativeTarget::class.java)
                    .filter { extension.targets.isEmpty() || it.name in extension.targets }
                targets.forEach { configure(project, it, extension) }
            }
        }
    }

    private fun configure(project: Project, target: KotlinNativeTarget, extension: KeelAbiExtension) {
        val zip = resolveZip(project, extension)
        if (zip == null) {
            project.logger.warn(
                "keel-abi: 未找到 keel-abi 静态库包，${target.name} 不会链接 ABI 实现"
            )
            return
        }

        val extractDir = project.layout.buildDirectory
            .dir("generated/keel-abi/${target.name}")
            .get().asFile

        val extract = project.tasks.register("keelAbiExtract${target.suffix()}", Sync::class.java) { task ->
            task.group = "keel"
            task.description = "解出 ${target.name} 的 keel-abi 头文件与静态库"
            task.from(project.zipTree(zip)) { spec ->
                spec.include("include/**")
                spec.include("lib/${target.name}/**")
            }
            task.into(extractDir)
        }

        includedAbiBuild(project)?.let { build ->
            extract.configure { it.dependsOn(build.task(":keelAbiPackage")) }
        }

        val libDir = File(extractDir, "lib/${target.name}").absolutePath
        val exportSymbols = DEFAULT_EXPORTS + extension.exports
        // PE 导出表需要显式列表；Kotlin 的 @CName 符号不会自动进导出表
        val defFile = if (target.konanTarget.family == Family.MINGW) {
            File(extractDir, "keel-plugin.def")
        } else {
            null
        }
        val defTask = defFile?.let { file ->
            project.tasks.register("keelAbiDef${target.suffix()}", DefaultTask::class.java) { task ->
                task.group = "keel"
                task.description = "生成 ${target.name} 插件的符号导出列表"
                task.outputs.file(file)
                task.doLast {
                    file.parentFile.mkdirs()
                    file.writeText(
                        buildString {
                            appendLine("LIBRARY keel_plugin")
                            appendLine("EXPORTS")
                            exportSymbols.forEach { appendLine("    $it") }
                        }
                    )
                }
            }
        }

        target.binaries.all { binary ->
            binary.linkerOpts("-L$libDir", "-lkeel_abi")
            if (binary is SharedLibrary) {
                // 插件的对外入口在静态库里，Kotlin 侧从不引用它：不 -u 的话归档成员不会被拉入，
                // 生成的 dylib 里就没有 keel_plugin_bootstrap / keel_plugin_shutdown
                binary.linkerOpts("-u", "keel_plugin_bootstrap")
                if (defFile != null && defTask != null) {
                    binary.linkerOpts(defFile.absolutePath)
                    binary.linkTaskProvider.configure { it.dependsOn(defTask) }
                }
            }
            binary.linkTaskProvider.configure { task ->
                task.dependsOn(extract)
                // linkerOpts 只是路径：静态库内容变了也得让链接任务重跑
                task.inputs.dir(libDir)
            }
        }
    }

    private fun resolveZip(project: Project, extension: KeelAbiExtension): File? {
        extension.zipFile?.let { return project.file(it) }

        val version = extension.version ?: project.version.toString()
        val default = project.file("../keel-abi/build/keel/keel-abi-$version.zip")
        if (default.isFile) return default

        // 还没打包过：只要 composite 里有 keel-abi，就让解压任务依赖它的打包任务
        return if (includedAbiBuild(project) != null) default else null
    }

    private fun includedAbiBuild(project: Project) =
        project.gradle.includedBuilds.firstOrNull { it.name == "keel-abi" }

    private fun KotlinNativeTarget.suffix(): String =
        name.replaceFirstChar { it.uppercaseChar() }

    private companion object {
        /**
         * 插件 dylib 固定对外导出的符号。
         *
         * Kotlin 侧的 Worker 入口按 `keel_abi.h` 的约定命名，所以能直接列在导出表里；
         * 用别的名字的插件通过 `keelAbi { export("...") }` 追加。
         */
        val DEFAULT_EXPORTS = listOf(
            "keel_plugin_bootstrap",
            "keel_plugin_shutdown",
            "keel_plugin_worker_main",
        )
    }
}
