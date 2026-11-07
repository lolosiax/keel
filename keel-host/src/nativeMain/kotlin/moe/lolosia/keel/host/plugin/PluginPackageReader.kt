package moe.lolosia.keel.host.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import moe.lolosia.keel.api.PluginMetadata
import moe.lolosia.keel.classpath.FileZipClasspath
import moe.lolosia.keel.classpath.full.ClasspathRef
import moe.lolosia.keel.classpath.full.PluginClasspathEntry
import moe.lolosia.keel.host.classpath.HostClasspath

/**
 * `.keel` 包根的 `plugin.json`。
 *
 * @property lib 目标名 → 包内动态库相对路径
 * @property resources 资源在包内的前缀，默认 `resources/`
 */
@Serializable
data class KeelPackageManifest(
    val id: String,
    val name: String = "",
    val version: String,
    val abiVersion: Int = 1,
    val entry: String,
    val lib: Map<String, String> = emptyMap(),
    val resources: String = "resources/",
    val requires: List<String> = emptyList(),
    val sha256: String? = null,
) {

    fun libraryFor(target: String): String? = lib[target]

    fun toMetadata(): PluginMetadata = PluginMetadata(
        id = id,
        name = name.ifEmpty { id },
        version = version,
        entry = entry,
    )
}

/**
 * 读取并安装 `.keel` 包。
 *
 * 目前只负责 manifest 与 Classpath 注册；动态库解包、dlopen、生命周期在插件加载器里。
 */
object PluginPackageReader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 读取包根 `plugin.json`。
     *
     * @throws IllegalArgumentException 包内没有 manifest 或 JSON 无法解析
     */
    fun read(keelPath: String): KeelPackageManifest {
        val classpath = FileZipClasspath.open(keelPath)
        val bytes = classpath.read(MANIFEST_PATH)
            ?: throw IllegalArgumentException("$keelPath 缺少 $MANIFEST_PATH")
        return try {
            json.decodeFromString(bytes.decodeToString())
        } catch (e: Exception) {
            throw IllegalArgumentException("$keelPath 的 $MANIFEST_PATH 解析失败", e)
        }
    }

    /**
     * 读取 manifest 并把 `.keel` 的 `resources/` 注册进 Host。
     *
     * 返回的 [ClasspathRef] 绑定当前 generation；[HostClasspath.unregister] 后失效。
     */
    fun install(host: HostClasspath, keelPath: String): Pair<KeelPackageManifest, ClasspathRef> {
        val manifest = read(keelPath)
        val ref = host.register(
            PluginClasspathEntry(
                pluginId = manifest.id,
                keelPath = keelPath,
                resourcesPrefix = manifest.resources,
                version = manifest.version,
                sha256 = manifest.sha256,
            )
        )
        return manifest to ref
    }

    private const val MANIFEST_PATH = "plugin.json"
}
