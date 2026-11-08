package moe.lolosia.keel.api.config

import kotlinx.serialization.Serializable

/**
 * 写入成功后的应答（`HostSubjects.CONFIG_WRITE`，CBOR）。
 *
 * 回整棵合并后的文档（JSON 文本），插件据此替换本地快照，避免两边各算一次合并。
 */
@Serializable
data class ConfigDocReply(val document: String)
