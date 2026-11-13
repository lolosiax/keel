package moe.lolosia.keel.gradle.plugin

import com.github.luben.zstd.Zstd
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 *
 * 容器由 [compression] 决定：`zstd`（默认，单帧 + 索引）或 `deflate`（zip）。
 * `zstd` 的布局见 Host 侧 `BundleArchiveClasspath`，两边的 magic / 版本 / 字段必须一致。
 */
@DisableCachingByDefault(because = "输入都在本工程内，重复解压 .keel 的收益不足以做可复用缓存")
abstract class KeelBundleTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packages: ConfigurableFileCollection

    @get:Input
    abstract val target: Property<String>

    /** `zstd` / `deflate`；只影响 bundle，不改 `.keel` 自身的 zip 压缩。 */
    @get:Input
    abstract val compression: Property<String>

    /** zstd 压缩级别；默认 19。 */
    @get:Input
    abstract val compressionLevel: Property<Int>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun build() {
        val targetName = target.get()
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        val entries = collectEntries(targetName)
        when (compression.get().lowercase()) {
            "zstd" -> output.writeBytes(buildZstd(entries, compressionLevel.get()))
            "deflate" -> buildZip(entries, output)
            else -> throw GradleException(
                "未知的 bundle 压缩方式：${compression.get()}（支持 zstd / deflate）"
            )
        }
    }

    /** 摊平后的条目，按路径排序，保证产物可复现。 */
    private fun collectEntries(targetName: String): Map<String, ByteArray> {
        val seen = mutableMapOf<String, String>()
        val plugins = mutableListOf<Map<String, Any?>>()
        val entries = sortedMapOf<String, ByteArray>()

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
                        entries["plugins/$id/$name"] =
                            source.getInputStream(entry).use { it.readBytes() }
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
        entries["bundle.json"] = (JsonOutput.prettyPrint(JsonOutput.toJson(bundle)) + "\n")
            .toByteArray(Charsets.UTF_8)
        return entries
    }

    /** 旧格式：标准 zip。所有条目都 deflate —— 之前 `lib/` 用 STORED，白白多出 15MB。 */
    private fun buildZip(entries: Map<String, ByteArray>, output: java.io.File) {
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                entry.time = 0L
                entry.method = ZipEntry.DEFLATED
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /**
     * `KELB` 容器：单帧 zstd + 索引。
     *
     * 布局（little-endian）：
     * `magic(4) version(1) format(1) flags(2) indexLength(4) rawSize(4) frameSize(4) index frame`
     */
    private fun buildZstd(entries: Map<String, ByteArray>, level: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        val indexEntries = entries.map { (name, bytes) ->
            raw.write(bytes)
            mapOf("name" to name, "size" to bytes.size)
        }
        val rawBytes = raw.toByteArray()

        val indexBytes = JsonOutput.toJson(mapOf("entries" to indexEntries))
            .toByteArray(Charsets.UTF_8)
        val frame = Zstd.compress(rawBytes, level)

        val header = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC.toByteArray(Charsets.US_ASCII))
            .put(VERSION.toByte())
            .put(FORMAT_ZSTD.toByte())
            .putShort(0)
            .putInt(indexBytes.size)
            .putInt(rawBytes.size)
            .putInt(frame.size)
            .array()

        return ByteArrayOutputStream(header.size + indexBytes.size + frame.size).use { out ->
            out.write(header)
            out.write(indexBytes)
            out.write(frame)
            out.toByteArray()
        }
    }

    private companion object {
        const val HEADER_SIZE = 20
        const val MAGIC = "KELB"
        const val VERSION = 2
        const val FORMAT_ZSTD = 1
    }
}
