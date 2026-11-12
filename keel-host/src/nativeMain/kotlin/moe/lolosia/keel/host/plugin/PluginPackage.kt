package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.classpath.Classpath

/**
 * 一个可安装的插件单元。
 *
 * 磁盘上的 `.keel` 与单文件 exe 内嵌的 bundle 条目都归一到这个视图，
 * 加载器之后只认 [PluginPackage]，不再关心包从哪里来。
 */
interface PluginPackage {

    /** 日志与卸载时展示的来源标识；内嵌条目形如 `embedded://plugins/<id>`。 */
    val origin: String

    /** 包根 `plugin.json`。 */
    val manifest: KeelPackageManifest

    /** 已应用 `resources/` 前缀的资源视图，直接注册进 Host classpath。 */
    val classpath: Classpath

    /** 是否来自内嵌 bundle；这决定卸载时是否删除磁盘文件。 */
    val bundled: Boolean

    /** 读取包内条目（相对包根，如 `lib/mingwX64/x.dll`）。 */
    fun read(path: String): ByteArray?
}
