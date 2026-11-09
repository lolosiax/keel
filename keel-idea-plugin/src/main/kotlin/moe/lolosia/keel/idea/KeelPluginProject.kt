package moe.lolosia.keel.idea

import java.nio.file.Path

/**
 * 一个可运行的 Keel 插件工程。
 *
 * @property directory 插件工程根目录，含 `settings.gradle.kts` 与
 *   `src/commonMain/resources/plugin.json`
 * @property pluginId `plugin.json` 的 `id`；缺失时回落到目录名
 * @property displayName `plugin.json` 的 `name`；缺失时回落到 [pluginId]
 */
data class KeelPluginProject(
    val directory: Path,
    val pluginId: String,
    val displayName: String,
) {
    override fun toString(): String = "$displayName ($pluginId)"
}
