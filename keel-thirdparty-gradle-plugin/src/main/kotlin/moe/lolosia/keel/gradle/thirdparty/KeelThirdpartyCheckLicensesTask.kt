package moe.lolosia.keel.gradle.thirdparty

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * 按链接方式校验许可：宽松许可可静态，LGPL 只允许动态，GPL / AGPL 拒绝。
 *
 * 只在 manifest 存在时执行；判定表见 `doc/thirdparty-licensing.md` §3。
 */
@DisableCachingByDefault(because = "只做静态校验，读 manifest 不产出可复用文件")
abstract class KeelThirdpartyCheckLicensesTask : DefaultTask() {

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun check() {
        val file = manifestFile.orNull?.asFile
        if (file == null || !file.isFile) {
            logger.lifecycle("keel-thirdparty: 没有 $KeelThirdpartyManifest.DEFAULT_PATH，跳过许可校验")
            return
        }

        val document = KeelThirdpartyManifest.read(file)
        val problems = mutableListOf<String>()

        document.libraries.values.sortedBy { it.name }.forEach { library ->
            val prefix = "libraries.${library.name}"
            val tokens = spdxTokens(library.license)
            if (tokens.isEmpty()) {
                problems += "$prefix.license 无法解析：${library.license}"
                return@forEach
            }

            tokens.forEach { token ->
                when {
                    token.startsWith("AGPL") || token.startsWith("GPL") ->
                        problems += "$prefix.license 含 $token：GPL / AGPL 不允许分发"

                    token.startsWith("LGPL") -> if (!library.link.equals("dynamic", ignoreCase = true)) {
                        problems += "$prefix.license 含 $token：LGPL 只允许动态链接（当前 link=${library.link}）"
                    }

                    token !in PERMISSIVE ->
                        problems += "$prefix.license 含未识别许可 $token，先确认后再加入允许列表"
                }
            }

            if (tokens.any { it == "APACHE-2.0" }) {
                val hasNotice = library.licenseFiles.any { it.contains("NOTICE", ignoreCase = true) }
                if (!hasNotice) {
                    problems += "$prefix.license 是 Apache-2.0，licenseFiles 必须包含 NOTICE"
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "第三方库许可校验失败（$file）：\n" +
                    problems.joinToString("\n") { "  - $it" }
            )
        }
        logger.lifecycle("keel-thirdparty: 许可校验通过")
    }
}

private val PERMISSIVE = setOf(
    "MIT",
    "BSD-2-CLAUSE",
    "BSD-3-CLAUSE",
    "ISC",
    "ZLIB",
    "BSL-1.0",
    "APACHE-2.0",
    "FTL",
    "LIBPNG",
    "LIBPNG-2.0",
    "IJG",
    "UNLICENSE",
    "CC0-1.0",
    "0BSD",
    "PD",
    "PUBLIC-DOMAIN",
    "SQLITE",
)

private fun spdxTokens(expression: String): List<String> =
    expression
        .uppercase()
        .replace("(", " ")
        .replace(")", " ")
        .replace(Regex("\\s+(AND|OR|WITH)\\s+"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "AND" && it != "OR" && it != "WITH" }
