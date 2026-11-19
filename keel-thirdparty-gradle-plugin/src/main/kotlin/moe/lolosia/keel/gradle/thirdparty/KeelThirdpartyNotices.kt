package moe.lolosia.keel.gradle.thirdparty

import groovy.json.JsonOutput
import java.time.Instant

/**
 * 由 manifest 生成许可声明与 SBOM。
 *
 * 生成物随工件发布，不接受手工编辑；仓库根的同名文件只保留「随源码分发」说明。
 */
object KeelThirdpartyNotices {

    fun markdown(document: KeelThirdpartyManifest.Document): String = buildString {
        appendLine("# Third-Party Notices")
        appendLine()
        appendLine("> 由 keel-thirdparty 根据 manifest 生成，不要手改。")
        appendLine()
        appendLine("## 构建期依赖")
        appendLine()
        appendLine("- Kotlin/Native 与 Gradle 工具链的许可见各自发行包。")
        appendLine()

        listOf("static" to "静态链接", "dynamic" to "动态链接").forEach { (link, title) ->
            val libraries = document.libraries.values
                .filter { it.link.equals(link, ignoreCase = true) }
                .sortedBy { it.name }
            if (libraries.isEmpty()) return@forEach

            appendLine("## $title（$link）")
            appendLine()
            libraries.forEach { library ->
                appendLine("### ${library.name} ${library.artifactVersion}")
                appendLine()
                appendLine("- SPDX：${library.license}")
                appendLine("- 上游源码：${library.sourceUrl}")
                appendLine("- SHA-256：${library.sourceSha256}")
                if (library.licenseFiles.isNotEmpty()) {
                    appendLine("- 许可文件：${library.licenseFiles.joinToString(", ")}")
                }
                if (library.patches.isNotEmpty()) {
                    appendLine("- 已应用 patch：${library.patches.joinToString(", ")}")
                }
                appendLine()
            }
        }

        val unknown = document.libraries.values
            .filterNot { it.link.equals("static", true) || it.link.equals("dynamic", true) }
            .sortedBy { it.name }
        if (unknown.isNotEmpty()) {
            appendLine("## 其他")
            appendLine()
            unknown.forEach { library ->
                appendLine("- ${library.name} ${library.artifactVersion}（${library.license}，link=${library.link}）")
            }
            appendLine()
        }
    }

    fun sbom(document: KeelThirdpartyManifest.Document): String {
        val components = document.libraries.values.sortedBy { it.name }.map { library ->
            mapOf(
                "type" to "library",
                "bom-ref" to "pkg:generic/${library.name}@${library.artifactVersion}",
                "name" to library.name,
                "version" to library.artifactVersion,
                "licenses" to listOf(mapOf("license" to mapOf("id" to library.license))),
                "externalReferences" to listOf(
                    mapOf("type" to "distribution", "url" to library.sourceUrl)
                ),
                "hashes" to listOf(
                    mapOf("alg" to "SHA-256", "content" to library.sourceSha256)
                ),
            )
        }
        val bom = linkedMapOf(
            "bomFormat" to "CycloneDX",
            "specVersion" to "1.5",
            "version" to 1,
            "metadata" to mapOf(
                "timestamp" to Instant.EPOCH.toString(),
                "component" to mapOf(
                    "type" to "application",
                    "name" to document.groupId,
                ),
            ),
            "components" to components,
        )
        return JsonOutput.prettyPrint(JsonOutput.toJson(bom)) + "\n"
    }

    fun upstreamSource(library: KeelThirdpartyManifest.Library): String = buildString {
        appendLine("library: ${library.name}")
        appendLine("version: ${library.artifactVersion}")
        appendLine("source: ${library.sourceUrl}")
        appendLine("sha256: ${library.sourceSha256}")
        if (library.patches.isEmpty()) {
            appendLine("patches: none")
        } else {
            appendLine("patches:")
            library.patches.forEach { appendLine("  - $it") }
        }
    }
}
