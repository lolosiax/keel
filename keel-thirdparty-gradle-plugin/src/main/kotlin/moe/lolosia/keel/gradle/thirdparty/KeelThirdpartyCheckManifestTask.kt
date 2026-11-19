package moe.lolosia.keel.gradle.thirdparty

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * 校验 manifest 的结构完整性：字段齐全、`.def` / 许可文件 / patch 真实存在。
 *
 * 只做静态检查，不访问网络；上游源码与编译产物由发布任务处理。
 */
@DisableCachingByDefault(because = "只做静态校验，读 manifest 不产出可复用文件")
abstract class KeelThirdpartyCheckManifestTask : DefaultTask() {

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    /** 只在 `thirdparty/` 之外查找许可文件时使用。 */
    @get:Internal
    abstract val projectDir: DirectoryProperty

    @TaskAction
    fun check() {
        val file = manifestFile.orNull?.asFile
        if (file == null || !file.isFile) {
            logger.lifecycle("keel-thirdparty: 没有 $KeelThirdpartyManifest.DEFAULT_PATH，跳过 manifest 校验")
            return
        }

        val document = KeelThirdpartyManifest.read(file)
        val problems = mutableListOf<String>()

        if (document.groupId.isBlank()) problems += "groupId 不能为空"
        if (document.libraries.isEmpty()) problems += "libraries 不能为空"

        val sourceRoot = file.parentFile
        document.libraries.forEach { (name, library) ->
            val prefix = "libraries.$name"
            if (library.version.isBlank()) problems += "$prefix.version 不能为空"
            if (library.revision < 1) problems += "$prefix.revision 必须 >= 1"
            if (library.sourceUrl.isBlank()) problems += "$prefix.source.url 不能为空"
            if (!library.sourceUrl.startsWith("http://") &&
                !library.sourceUrl.startsWith("https://") &&
                !library.sourceUrl.startsWith("file:")
            ) {
                problems += "$prefix.source.url 只接受 http(s):// 或 file://：${library.sourceUrl}"
            }
            if (!library.sourceSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
                problems += "$prefix.source.sha256 必须是 64 位十六进制"
            }
            if (library.def.isBlank()) {
                problems += "$prefix.def 不能为空"
            } else if (!File(sourceRoot, "$name/${library.def}").isFile) {
                problems += "$prefix.def 指向的文件不存在：${sourceRoot.resolve("$name/${library.def}")}"
            }
            if (library.libs.isEmpty()) problems += "$prefix.libs 至少要声明一个目标"
            library.libs.forEach { (target, files) ->
                if (files.isEmpty()) problems += "$prefix.libs.$target 不能为空"
            }
            if (library.license.isBlank()) problems += "$prefix.license 不能为空"
            if (library.licenseFiles.isEmpty()) {
                problems += "$prefix.licenseFiles 不能为空"
            } else {
                library.licenseFiles.forEach { path ->
                    if (!resolveLicense(sourceRoot, projectDir.get().asFile, path).isFile) {
                        problems += "$prefix.licenseFiles 指向的文件不存在：$path"
                    }
                }
            }
            library.patches.forEach { patch ->
                if (!File(sourceRoot, "$name/$patch").isFile) {
                    problems += "$prefix.patches 指向的文件不存在：$patch"
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "第三方库 manifest 校验失败（$file）：\n" +
                    problems.joinToString("\n") { "  - $it" }
            )
        }
        logger.lifecycle(
            "keel-thirdparty: manifest 校验通过，共 ${document.libraries.size} 个库"
        )
    }
}

private fun resolveLicense(sourceRoot: File, projectDir: File, path: String): File {
    val inSource = File(sourceRoot, path)
    if (inSource.isFile) return inSource
    return File(projectDir, path)
}
