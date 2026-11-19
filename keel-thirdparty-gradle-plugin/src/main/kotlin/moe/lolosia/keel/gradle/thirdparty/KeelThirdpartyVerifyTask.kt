package moe.lolosia.keel.gradle.thirdparty

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * 解包后按契约检查每个「库 × 目标」：`def/`、`include/`、`lib/<target>/` 都不能为空。
 *
 * 这是最小链接前的静态确认；真正的链接由各 native 目标的 link 任务完成。
 */
@DisableCachingByDefault(because = "只做静态校验，解包目录是构建中间产物")
abstract class KeelThirdpartyVerifyTask : DefaultTask() {

    /** 键为 `<lib>@<target>`，值为解包目录。 */
    @get:Input
    abstract val extracted: MapProperty<String, String>

    @TaskAction
    fun verify() {
        val entries = extracted.get()
        if (entries.isEmpty()) {
            logger.lifecycle("keel-thirdparty: 没有配置任何库，跳过 verify")
            return
        }

        val problems = mutableListOf<String>()
        entries.toSortedMap().forEach { (key, path) ->
            val root = File(path)
            val library = key.substringBefore('@')
            val target = key.substringAfter('@')

            val defDir = root.resolve("def")
            if (defDir.listFiles { file -> file.extension == "def" }.orEmpty().isEmpty()) {
                problems += "$key：缺少 def/*.def"
            }
            val includeDir = root.resolve("include")
            if (!includeDir.isDirectory || includeDir.listFiles().orEmpty().isEmpty()) {
                problems += "$key：缺少 include/ 头文件"
            }
            val libDir = root.resolve("lib/$target")
            if (!libDir.isDirectory || libDir.listFiles().orEmpty().isEmpty()) {
                problems += "$key：缺少 lib/$target/ 库文件（$library）"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "第三方库工件校验失败：\n" + problems.joinToString("\n") { "  - $it" }
            )
        }
        logger.lifecycle("keel-thirdparty: 工件校验通过（${entries.size} 个库 × 目标）")
    }
}
