package moe.lolosia.keel.gradle.thirdparty

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** 从 manifest 生成 `THIRD-PARTY-NOTICES.md` 与 `SBOM.json`。 */
@DisableCachingByDefault(because = "声明由 manifest 文本生成，重跑成本低")
abstract class KeelThirdpartyNoticesTask : DefaultTask() {

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = manifestFile.orNull?.asFile
        if (file == null || !file.isFile) {
            logger.lifecycle("keel-thirdparty: 没有 $KeelThirdpartyManifest.DEFAULT_PATH，跳过声明生成")
            return
        }

        val document = KeelThirdpartyManifest.read(file)
        val output = outputDirectory.get().asFile
        output.mkdirs()
        output.resolve(NOTICES_FILE).writeText(KeelThirdpartyNotices.markdown(document))
        output.resolve(SBOM_FILE).writeText(KeelThirdpartyNotices.sbom(document))
        logger.lifecycle(
            "keel-thirdparty: 已生成 $NOTICES_FILE / $SBOM_FILE（${document.libraries.size} 个库）"
        )
    }

    private companion object {
        const val NOTICES_FILE = "THIRD-PARTY-NOTICES.md"
        const val SBOM_FILE = "SBOM.json"
    }
}
