package moe.lolosia.keel.api.config

import kotlinx.serialization.Serializable

/**
 * 读取配置的应答（`HostSubjects.CONFIG_READ`，CBOR）。
 *
 * [value] 是 JSON 文本而不是 `JsonElement`：`JsonElement` 的序列化器只接受 JSON 编码器，
 * 不能直接塞进 CBOR。插件侧用 `Json.parseToJsonElement` 还原。
 *
 * @property found 路径是否存在；false 时 [value] 为空
 * @property value 该路径的 JSON 文本（YAML 已按标量 / 映射 / 序列展开）
 */
@Serializable
data class ConfigReadReply(
    val found: Boolean,
    val value: String? = null,
)
