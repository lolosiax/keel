package moe.lolosia.keel.host.plugin

import kotlinx.serialization.*
import moe.lolosia.keel.api.*

/**
 * 构建期注入的插件构建信息（`keelPackage` 写入 `plugin.json`）。
 */
@Serializable
data class PluginBuildInfo(
    val gitSha: String = "unknown",
    val commitTime: String = "unknown",
)

/**
 * `.keel` 包根的 `plugin.json`。
 *
 * @property lib 目标名 → 包内动态库相对路径
 * @property resources 资源在包内的前缀，默认 `resources/`
 * @property color 插件日志模块名的自定义颜色（`#RRGGBB` / `#RGB`），不填则用哈希色
 * @property buildInfo 构建期注入的 git 提交与提交时间
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
    val buildInfo: PluginBuildInfo? = null,
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
