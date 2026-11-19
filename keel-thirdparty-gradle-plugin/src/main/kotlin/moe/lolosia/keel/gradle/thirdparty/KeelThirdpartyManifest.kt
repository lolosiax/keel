package moe.lolosia.keel.gradle.thirdparty

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File

/**
 * `thirdparty/manifest.json` 的读取与模型。
 *
 * 仓库只保存 manifest、`.def`、patch 与许可文本；上游源码由 CI 按
 * `source.url` + `source.sha256` 下载，不在仓库里 vendor。
 */
object KeelThirdpartyManifest {

    const val DEFAULT_PATH = "thirdparty/manifest.json"

    data class Document(
        val groupId: String,
        val libraries: Map<String, Library>,
    )

    data class Library(
        val name: String,
        val version: String,
        val revision: Int,
        val sourceUrl: String,
        val sourceSha256: String,
        val headers: List<String>,
        val def: String,
        val libs: Map<String, List<String>>,
        val link: String,
        val license: String,
        val licenseFiles: List<String>,
        val patches: List<String>,
    ) {
        /** Maven 工件版本：`<upstream>-keel.<revision>`。 */
        val artifactVersion: String get() = "$version-keel.$revision"
    }

    fun read(file: File): Document {
        if (!file.isFile) throw GradleException("找不到第三方库 manifest：$file")
        val root = runCatching { JsonSlurper().parse(file) }
            .getOrElse { throw GradleException("第三方库 manifest 解析失败：$file（${it.message}）", it) }
        val json = root as? Map<*, *>
            ?: throw GradleException("第三方库 manifest 顶层必须是对象：$file")

        val groupId = json["groupId"] as? String ?: ""
        val librariesNode = json["libraries"] as? Map<*, *> ?: emptyMap<Any, Any>()

        val libraries = librariesNode.entries.associate { (key, value) ->
            val name = key as? String
                ?: throw GradleException("第三方库 manifest 的库名必须是字符串：$key")
            val node = value as? Map<*, *>
                ?: throw GradleException("第三方库 `$name` 的配置必须是对象")
            name to parseLibrary(name, node)
        }
        return Document(groupId.trim(), libraries)
    }

    private fun parseLibrary(name: String, node: Map<*, *>): Library {
        val source = node["source"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val libs = (node["libs"] as? Map<*, *>).orEmpty().entries.associate { (target, files) ->
            val targetName = target as? String
                ?: throw GradleException("第三方库 `$name` 的目标名必须是字符串：$target")
            targetName to (files as? List<*>).orEmpty()
                .map { it as? String ?: throw GradleException("第三方库 `$name` 的库文件名必须是字符串：$it") }
        }
        return Library(
            name = name,
            version = (node["version"] as? String)?.trim().orEmpty(),
            revision = (node["revision"] as? Number)?.toInt() ?: 0,
            sourceUrl = (source["url"] as? String)?.trim().orEmpty(),
            sourceSha256 = (source["sha256"] as? String)?.trim().orEmpty(),
            headers = (node["headers"] as? List<*>).orEmpty().filterIsInstance<String>(),
            def = (node["def"] as? String)?.trim().orEmpty(),
            libs = libs,
            link = (node["link"] as? String)?.trim().orEmpty().ifBlank { "static" },
            license = (node["license"] as? String)?.trim().orEmpty(),
            licenseFiles = (node["licenseFiles"] as? List<*>).orEmpty().filterIsInstance<String>(),
            patches = (node["patches"] as? List<*>).orEmpty().filterIsInstance<String>(),
        )
    }
}
