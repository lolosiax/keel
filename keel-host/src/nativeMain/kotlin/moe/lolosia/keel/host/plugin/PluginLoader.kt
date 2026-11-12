package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.classpath.full.PluginClasspathEntry
import moe.lolosia.keel.host.classpath.HostClasspath

/**
 * `.keel` 目录扫描与安装。
 *
 * 分两步，便于内嵌 bundle 与磁盘包共用同一套校验：
 * 1. [scanDirectory] 只读 `plugin.json`，收集可安装单元与失败原因，不落任何动态库；
 * 2. [load] 校验 id / abiVersion / 目标库，把动态库落到 `<cacheRoot>/<id>/<version>/`
 *    （K/N 运行时不可卸载，所以按版本目录保留，绝不覆盖正在使用的库），
 *    再把 `resources/` 注册进 Host 的 classpath 注册表。
 *
 * 动态库解包与 `dlopen` 失败都由调用方隔离成 [PluginLoadFailure]，
 * 单个坏插件不会中断整轮加载。
 */
class PluginLoader(
    private val host: HostClasspath,
    private val cacheRoot: String,
) {

    /**
     * 扫描目录下所有 `.keel`，只解析 manifest。
     *
     * 顺序按文件名排序；目录不存在时返回一条失败记录，而不是抛异常。
     * capability 包也一并返回，由调用方按「能力先于业务插件」的顺序安装。
     */
    internal fun scanDirectory(pluginsDir: String): PluginScan {
        val dir = pluginsDir.trimEnd('/', '\\')
        val names = listNames(dir)
            ?: return PluginScan(
                packages = emptyList(),
                failed = listOf(PluginLoadFailure(dir, null, "插件目录不存在或不可读：$dir")),
            )

        val packages = mutableListOf<PluginPackage>()
        val failed = mutableListOf<PluginLoadFailure>()
        names.filter { it.endsWith(KEEL_SUFFIX) }.sorted().forEach { name ->
            val keelPath = "$dir/$name"
            try {
                packages += FilePluginPackage(keelPath, PluginPackageReader.read(keelPath))
            } catch (e: Exception) {
                failed += PluginLoadFailure(keelPath, pluginIdOrNull(keelPath), e.message ?: "未知错误")
            }
        }
        return PluginScan(packages, failed)
    }

    /**
     * 读取单个 `.keel`、解出动态库并注册资源。
     *
     * @throws IllegalArgumentException manifest 缺失、字段非法、abiVersion 不匹配、
     *   当前目标没有对应库、包内缺少该库、插件 id 与已加载的重复
     */
    fun load(keelPath: String): LoadedPlugin =
        load(FilePluginPackage(keelPath, PluginPackageReader.read(keelPath)))

    /**
     * 安装一个已解析的插件单元（磁盘包或内嵌条目）。
     *
     * @throws IllegalArgumentException 是 capability 包、字段非法、abiVersion 不匹配、
     *   当前目标没有对应库、包内缺少该库、插件 id 与已加载的重复
     */
    fun load(entry: PluginPackage): LoadedPlugin {
        val manifest = entry.manifest
        require(!manifest.isCapability) {
            "${manifest.id} 是 capability 包，不能用 native 插件的方式加载"
        }
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
        val bytes = entry.read(libPath)
            ?: throw IllegalArgumentException("包内缺少动态库：$libPath")

        val libraryPath = installLibrary(cacheRoot, manifest, libPath, bytes)
        val ref = host.register(
            PluginClasspathEntry(
                pluginId = manifest.id,
                keelPath = entry.origin,
                resourcesPrefix = manifest.resources,
                version = manifest.version,
                sha256 = manifest.sha256,
                classpath = entry.classpath,
            )
        )
        return LoadedPlugin(
            manifest = manifest,
            keelPath = entry.origin,
            libraryPath = libraryPath,
            classpathRef = ref,
            bundled = entry.bundled,
        )
    }

    /** 卸载：资源引用立即失效；动态库留在缓存目录，等进程重启回收。 */
    fun unload(pluginId: String) {
        host.unregister(pluginId)
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
