package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.classpath.Classpath
import moe.lolosia.keel.classpath.PrefixedClasspath
import moe.lolosia.keel.classpath.ZipClasspath
import moe.lolosia.keel.classpath.readEmbeddedPluginBundleBytes

/**
 * 可执行文件内嵌的插件 bundle（`KELB` zstd 容器或旧版 zip）。
 *
 * 构建期由 `moe.lolosia.keel.bundle` 摊平产出：
 * ```
 * bundle.json
 * plugins/<id>/plugin.json
 * plugins/<id>/lib/<target>/...
 * plugins/<id>/resources/...
 * ```
 * 运行期只在内存里打开，不落盘；单个条目 manifest 损坏只跳过该条目。
 */
class PluginBundle private constructor(
    /** 打开来源，用于日志；通常是可执行文件路径。 */
    val origin: String,
    private val classpath: Classpath,
    val entries: List<Entry>,
) {

    /**
     * bundle 内的一个插件根。
     *
     * @property root 该插件在 bundle zip 内的目录，`plugins/<id>`
     */
    class Entry(
        val root: String,
        val manifest: KeelPackageManifest,
    ) {
        val id: String get() = manifest.id
    }

    /** 打开一个条目；返回的视图不落盘。 */
    fun open(entry: Entry): PluginPackage =
        BundlePluginPackage(classpath, entry.root, entry.manifest)

    companion object {

        /** 从已打开的内存 zip 建索引；找不到任何 `plugins` 条目的 `plugin.json` 时返回 null。 */
        fun open(classpath: Classpath, origin: String): PluginBundle? {
            val dirs = classpath.entries
                .filter { it.startsWith(PREFIX) && it.endsWith("/$MANIFEST_PATH") }
                .map { it.removeSuffix(MANIFEST_PATH).trimEnd('/') }
                .sorted()
            if (dirs.isEmpty()) return null

            val entries = mutableListOf<Entry>()
            val seen = mutableSetOf<String>()
            dirs.forEach { root ->
                val manifest = try {
                    PluginPackageReader.read(PrefixedClasspath(classpath, root))
                } catch (e: Exception) {
                    Log.warn("$origin!/$root: ${e.message}", tag = "Loader")
                    return@forEach
                }
                if (!seen.add(manifest.id)) {
                    Log.warn("$origin 内插件 id 重复：${manifest.id}（忽略 $root）", tag = "Loader")
                    return@forEach
                }
                entries += Entry(root, manifest)
            }
            if (entries.isEmpty()) return null
            return PluginBundle(origin, classpath, entries)
        }

        /**
         * 从可执行文件读取内嵌 bundle。
         *
         * 优先按 `KELB`（zstd）容器解析，旧产物 / `deflate` 模式仍是内存 zip。
         * 没有内嵌、或 trailer / 资源损坏时返回 null（Linux footer 在打开时已校验 CRC）。
         */
        fun openEmbedded(executablePath: String): PluginBundle? {
            val bytes = readEmbeddedPluginBundleBytes(executablePath) ?: return null
            val classpath = BundleArchiveClasspath.open(bytes) ?: ZipClasspath.open(bytes)
            return open(classpath, executablePath)
        }

        private const val PREFIX = "plugins/"
        private const val MANIFEST_PATH = "plugin.json"
    }
}
