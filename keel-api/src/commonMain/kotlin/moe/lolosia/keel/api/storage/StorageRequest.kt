package moe.lolosia.keel.api.storage

import kotlinx.serialization.Serializable

/**
 * 存储请求载荷（`HostSubjects.STORAGE`，CBOR）。
 *
 * @property op 读 / 写 / 删
 * @property path 相对插件命名空间的路径
 * @property data 写入时的内容；读 / 删为空
 */
@Serializable
data class StorageRequest(
    val op: StorageOp,
    val path: String,
    val data: ByteArray? = null,
)
