package moe.lolosia.keel.host.plugin

/**
 * 单个插件加载失败的原因。
 *
 * @property keelPath 出问题的 `.keel` 路径（扫描不到目录时为目录本身）
 * @property pluginId 能读出 manifest 时才有值
 */
class PluginLoadFailure(
    val keelPath: String,
    val pluginId: String?,
    val reason: String,
)
