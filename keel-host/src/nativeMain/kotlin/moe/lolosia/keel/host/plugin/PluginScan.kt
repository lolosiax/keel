package moe.lolosia.keel.host.plugin

/**
 * 一次插件目录扫描的结果。
 *
 * 只读到 manifest 为止，不解包动态库；[packages] 交给调用方决定安装顺序。
 */
internal class PluginScan(
    val packages: List<PluginPackage>,
    val failed: List<PluginLoadFailure>,
)
