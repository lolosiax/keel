package moe.lolosia.keel.gradle.thirdparty

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * 生成派生的 `.def`：在包内声明后追加 `headerFilter`。
 *
 * 只用于消费方覆盖 headerFilter；包内文件不被修改，改完参数只影响本工程。
 */
@DisableCachingByDefault(because = "派生 def 只做文本拼装，跨机器复用价值低")
abstract class KeelThirdpartyDefTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDef: RegularFileProperty

    @get:Input
    abstract val headerFilter: Property<String>

    @get:OutputFile
    abstract val outputDef: RegularFileProperty

    @TaskAction
    fun generate() {
        val filter = headerFilter.get().trim()
        val text = sourceDef.get().asFile.readText()
        val output = outputDef.get().asFile
        output.parentFile.mkdirs()
        output.writeText(text.trimEnd() + "\nheaderFilter = $filter\n")
    }
}
