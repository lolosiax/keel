package moe.lolosia.keel.api.config

import kotlinx.serialization.Serializable

/**
 * 写入配置的请求载荷（`HostSubjects.CONFIG_WRITE`，CBOR）。
 *
 * 写入是**合并**：对象逐键递归合并，标量与序列整体替换；
 * [value] 里没出现的键保持原样。
 *
 * [value] 是 JSON 文本：`JsonElement` 的序列化器只接受 JSON 编码器，
 * 不能直接塞进 CBOR。
 *
 * @property scope 写入哪个文件
 * @property path 目标路径；空串表示整棵文档
 * @property value 新值的 JSON 文本（由插件用 `encodeToJsonElement` 再 `toString` 生成）
 */
@Serializable
data class ConfigWriteRequest(
    val scope: ConfigScope,
    val path: String = "",
    val value: String,
)
