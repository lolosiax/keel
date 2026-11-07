package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.classpath.FileZipClasspath
import moe.lolosia.keel.classpath.full.PluginClasspathEntry
import moe.lolosia.keel.host.classpath.HostClasspath

/**
 * `.keel` 目录扫描与安装。
 *
 * 只做到「可以 `dlopen`」为止，不碰 C ABI：
 * 1. 读 `plugin.json`，校验 id / abiVersion / 当前目标是否有库 / entry；
 * 2. 从包里取出动态库，落到 `<cacheRoot>/<id>/<version>/`（K/N 运行时不可卸载，
 *    所以按版本目录保留，绝不覆盖正在使用的库）；
 * 3. 把 `resources/` 注册进 Host 的 classpath 注册表，得到带 generation 的引用。
 *
 * 动态库解包与 `dlopen` 失败都在 [loadAll] 里被隔离成 [PluginLoadFailure]，
 * 单个坏插件不会中断整轮加载。
 */
class PluginLoader(
    private val host: HostClasspath,
    private val cacheRoot: String,
) {

    /**
     * 扫描目录下所有 `.keel` 并逐个安装。
     *
     * 顺序按文件名排序；同 id 的包只接受第一个，后面的记失败。
     * 目录不存在时返回一条失败记录，而不是抛异常。
     */
    fun loadAll(pluginsDir: String): PluginLoadResult {
        val dir = pluginsDir.trimEnd('/', '\\')
        val names = listNames(dir)
            ?: return PluginLoadResult(
                loaded = emptyList(),
                failed = listOf(PluginLoadFailure(dir, null, "插件目录不存在或不可读：$dir")),
            )

        val loaded = mutableListOf<LoadedPlugin>()
        val failed = mutableListOf<PluginLoadFailure>()
        names.filter { it.endsWith(KEEL_SUFFIX) }.sorted().forEach { name ->
            val keelPath = "$dir/$name"
            try {
                loaded += load(keelPath)
            } catch (e: Exception) {
                failed += PluginLoadFailure(keelPath, pluginIdOrNull(keelPath), e.message ?: "未知错误")
            }
        }
        return PluginLoadResult(loaded, failed)
    }

    /**
     * 读取单个 `.keel`、解出动态库并注册资源。
     *
     * @throws IllegalArgumentException manifest 缺失、字段非法、abiVersion 不匹配、
     *   当前目标没有对应库、包内缺少该库、插件 id 与已加载的重复
     */
    fun load(keelPath: String): LoadedPlugin {
        val manifest = PluginPackageReader.read(keelPath)
        require(manifest.id.isNotBlank()) { "plugin.json 缺少 id" }
        require(manifest.abiVersion == SUPPORTED_ABI_VERSION) {
            "abiVersion=${manifest.abiVersion}，Host 只支持 $SUPPORTED_ABI_VERSION"
        }
        require(manifest.entry.isNotBlank()) { "plugin.json 缺少 entry" }
        require(!host.isLoaded(manifest.id)) { "插件 id 重复：${manifest.id}" }

        val libPath = manifest.libraryFor(currentTargetName)
            ?: throw IllegalArgumentException(
                "plugin.json 的 lib 不含目标 $currentTargetName" +
                    "（已有：${manifest.lib.keys.joinToString().ifEmpty { "无" }}）"
            )
        val bytes = FileZipClasspath.open(keelPath).read(libPath)
            ?: throw IllegalArgumentException("包内缺少动态库：$libPath")

        val libraryPath = installLibrary(manifest, libPath, bytes)
        val ref = host.register(
            PluginClasspathEntry(
                pluginId = manifest.id,
                keelPath = keelPath,
                resourcesPrefix = manifest.resources,
                version = manifest.version,
                sha256 = manifest.sha256,
            )
        )
        return LoadedPlugin(manifest, keelPath, libraryPath, ref)
    }

    /** 卸载：资源引用立即失效；动态库留在缓存目录，等进程重启回收。 */
    fun unload(pluginId: String) {
        host.unregister(pluginId)
    }

    private fun installLibrary(
        manifest: KeelPackageManifest,
        libPath: String,
        bytes: ByteArray,
    ): String {
        val dir = "$cacheRoot/${manifest.id}/${manifest.version}"
        val dest = "$dir/${libPath.substringAfterLast('/')}"
        // 同名同大小视为已解包：库可能仍被当前进程映射，重写会破坏正在使用的文件
        if (PluginFs.sizeOrNull(dest) != bytes.size.toLong()) {
            mkdirs(dir)
            PluginFs.writeBytes(dest, bytes)
        }
        return dest
    }

    private fun pluginIdOrNull(keelPath: String): String? =
        try {
            PluginPackageReader.read(keelPath).id
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val KEEL_SUFFIX = ".keel"

        /** 与 `keel_abi.h` 的 `KEEL_ABI_VERSION` 保持一致。 */
        const val SUPPORTED_ABI_VERSION = 1
    }
}
