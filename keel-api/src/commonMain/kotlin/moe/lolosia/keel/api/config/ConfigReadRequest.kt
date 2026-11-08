package moe.lolosia.keel.api.config

import kotlinx.serialization.Serializable

/**
 * 读取配置的请求载荷（`HostSubjects.CONFIG_READ`，CBOR）。
 *
 * @property scope 读取哪个文件
 * @property path 点分路径；空串表示整棵文档
 */
@Serializable
data class ConfigReadRequest(
    val scope: ConfigScope,
    val path: String = "",
)
