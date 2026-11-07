package moe.lolosia.keel.host.plugin

/**
 * 一次目录扫描的结果。
 *
 * 失败的条目只记录原因，不影响其它插件加载。
 */
class PluginLoadResult(
    val loaded: List<LoadedPlugin>,
    val failed: List<PluginLoadFailure>,
)
