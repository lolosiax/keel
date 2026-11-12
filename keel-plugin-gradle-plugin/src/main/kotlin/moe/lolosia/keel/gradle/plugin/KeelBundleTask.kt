package moe.lolosia.keel.gradle.plugin

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 把若干 `.keel` 摊平成运行时 bundle：
 *
 * ```
 * bundle.json
 * plugins/<id>/plugin.json
 * plugins/<id>/lib/<target>/...
 * plugins/<id>/resources/...
 * ```
 *
 * 只保留当前 target 的 `lib/`；同 id 重复直接失败。
 */
@DisableCachingByDefault(because = "输入都在本工程内，重复解压 .keel 的收益不足以做可复用缓存")
abstract class KeelBundleTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packages: ConfigurableFileCollection

    @get:Input
    abstract val target: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun build() {
        val targetName = target.get()
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        val seen = mutableMapOf<String, String>()
        val plugins = mutableListOf<Map<String, Any?>>()

        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            packages.files.filter { it.isFile }.sortedBy { it.absolutePath }.forEach { file ->
                ZipFile(file).use { source ->
                    val manifestEntry = source.getEntry("plugin.json")
                        ?: throw GradleException("$file 缺少 plugin.json")
                    @Suppress("UNCHECKED_CAST")
                    val manifest = source.getInputStream(manifestEntry)
                        .bufferedReader()
                        .use { JsonSlurper().parseText(it.readText()) as Map<String, Any?> }

                    val id = (manifest["id"] as? String)?.trim().orEmpty()
                    if (id.isBlank()) throw GradleException("$file 的 plugin.json 缺少 id")

                    val previous = seen.putIfAbsent(id, file.absolutePath)
                    if (previous != null) {
                        throw GradleException("插件 id 重复：$id（$previous 与 ${file.absolutePath}）")
                    }

                    val libs = manifest["lib"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                    val lib = libs[targetName] as? String
                        ?: throw GradleException(
                            "$id 的 plugin.json 的 lib 不含目标 $targetName" +
                                "（已有：${libs.keys.joinToString().ifEmpty { "无" }}）"
                        )

                    source.entries().toList()
                        .filter { !it.isDirectory }
                        .sortedBy { it.name }
                        .forEach { entry ->
                            val name = entry.name
                            if (name.startsWith("lib/") && !name.startsWith("lib/$targetName/")) {
                                return@forEach
                            }
                            val bytes = source.getInputStream(entry).use { it.readBytes() }
                            writeEntry(zip, "plugins/$id/$name", bytes, stored = name.startsWith("lib/"))
                        }

                    plugins += mapOf(
                        "id" to id,
                        "version" to manifest["version"],
                        "kind" to manifest["kind"],
                        "lib" to lib,
                    )
                }
            }

            val bundle = mapOf(
                "format" to 1,
                "target" to targetName,
                "plugins" to plugins,
            )
            writeEntry(
                zip,
                "bundle.json",
                (JsonOutput.prettyPrint(JsonOutput.toJson(bundle)) + "\n").toByteArray(),
                stored = false,
            )
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray, stored: Boolean) {
        val entry = ZipEntry(name)
        entry.time = 0L
        if (stored) {
            val crc = CRC32().apply { update(bytes) }
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = crc.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }
}
