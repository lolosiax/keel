package moe.lolosia.keel.host.plugin

import kotlinx.serialization.json.*
import moe.lolosia.keel.classpath.*
import moe.lolosia.keel.classpath.full.*
import moe.lolosia.keel.host.classpath.*

/**
 * 读取并安装 `.keel` 包。
 *
 * 负责 manifest 解析与 Classpath 注册；动态库解包、dlopen、生命周期在 [PluginLoader] 里。
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
    fun read(keelPath: String): KeelPackageManifest =
        read(FileZipClasspath.open(keelPath), keelPath)

    /**
     * 从已打开的包视图读取 `plugin.json`；参数是日志里展示的来源标识。
     *
     * @throws IllegalArgumentException 包内没有 manifest 或 JSON 无法解析
     */
    fun read(classpath: Classpath, origin: String = classpath.origin): KeelPackageManifest {
        if (classpath.entries.isEmpty()) {
            throw IllegalArgumentException("$origin 不是有效的 .keel（zip 无法解析或为空）")
        }
        val bytes = classpath.read(MANIFEST_PATH)
            ?: throw IllegalArgumentException("$origin 缺少 $MANIFEST_PATH")
        return try {
            json.decodeFromString(bytes.decodeToString())
        } catch (e: Exception) {
            throw IllegalArgumentException("$origin 的 $MANIFEST_PATH 解析失败", e)
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
