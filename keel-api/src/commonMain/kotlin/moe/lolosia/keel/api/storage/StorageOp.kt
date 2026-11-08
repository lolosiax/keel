package moe.lolosia.keel.api.storage

import kotlinx.serialization.Serializable

/** 存储反向请求的操作类型（`HostSubjects.STORAGE`）。 */
@Serializable
enum class StorageOp {
    READ,
    WRITE,
    DELETE,
}
