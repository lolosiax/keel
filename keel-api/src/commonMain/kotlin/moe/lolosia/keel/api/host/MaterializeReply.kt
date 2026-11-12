package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable

/**
 * 资源物化应答（`HostSubjects.MATERIALIZE`，CBOR）。
 *
 * @property path 物化后的绝对路径（文件或目录），使用当前平台的本地分隔符
 */
@Serializable
data class MaterializeReply(
    val path: String,
)
