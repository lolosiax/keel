package moe.lolosia.keel.host.capability

import moe.lolosia.keel.classpath.FileZipClasspath
import moe.lolosia.keel.host.plugin.*

/**
 * `kind: capability` 包的发现与解包。
 *
 * 与 [PluginLoader] 的区别：能力包是纯 C 动态库，没有 K/N 运行时、没有资源视图，
 * 也不需要 `keel_plugin_bootstrap`；这里只做到「库已落到缓存目录」。
 */
internal class CapabilityLoader(private val cacheRoot: String) {

    private val installed = mutableMapOf<String, InstalledCapability>()

    /** 已安装的能力包，按扫描顺序。 */
    fun all(): List<InstalledCapability> = installed.values.toList()

    /**
     * 读取并解包一个能力包。
     *
     * @throws IllegalArgumentException manifest 缺失、kind 不是 capability、
     *   abiVersion 不匹配、当前目标没有对应库、包内缺少该库、id 重复
     */
    fun install(keelPath: String): InstalledCapability {
        val manifest = PluginPackageReader.read(keelPath)
        require(manifest.isCapability) { "${manifest.id} 不是 capability 包" }
        require(manifest.id.isNotBlank()) { "plugin.json 缺少 id" }
        require(manifest.abiVersion == SUPPORTED_ABI_VERSION) {
            "abiVersion=${manifest.abiVersion}，Host 只支持 $SUPPORTED_ABI_VERSION"
        }
        require(manifest.entry.isNotBlank()) { "plugin.json 缺少 entry" }
        require(manifest.id !in installed) { "能力包 id 重复：${manifest.id}" }

        val libPath = manifest.libraryFor(currentTargetName)
            ?: throw IllegalArgumentException(
                "plugin.json 的 lib 不含目标 $currentTargetName" +
                    "（已有：${manifest.lib.keys.joinToString().ifEmpty { "无" }}）"
            )
        val bytes = FileZipClasspath.open(keelPath).read(libPath)
            ?: throw IllegalArgumentException("包内缺少动态库：$libPath")

        val libraryPath = installLibrary(cacheRoot, manifest, libPath, bytes)
        val result = InstalledCapability(manifest, keelPath, libraryPath)
        installed[manifest.id] = result
        return result
    }

    private companion object {
        /** 与 `keel_abi.h` 的 `KEEL_ABI_VERSION` 保持一致。 */
        const val SUPPORTED_ABI_VERSION = 1
    }
}
