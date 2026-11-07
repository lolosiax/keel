package moe.lolosia.keel.host.plugin

import kotlinx.serialization.*
import moe.lolosia.keel.api.*

/**
 * `.keel` 包根的 `plugin.json`。
 *
 * @property lib 目标名 → 包内动态库相对路径
 * @property resources 资源在包内的前缀，默认 `resources/`
 * @property color 插件日志模块名的自定义颜色（`#RRGGBB` / `#RGB`），不填则用哈希色
 */
@Serializable
data class KeelPackageManifest(
    val id: String,
    val name: String = "",
    val version: String,
    val abiVersion: Int = 1,
    val entry: String,
    val color: String? = null,
    val lib: Map<String, String> = emptyMap(),
    val resources: String = "resources/",
    val requires: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val sha256: String? = null,
) {

    fun libraryFor(target: String): String? = lib[target]

    fun toMetadata(): PluginMetadata = PluginMetadata(
        id = id,
        name = name.ifEmpty { id },
        version = version,
        entry = entry,
        authors = authors,
        color = color,
    )
}
