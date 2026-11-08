package moe.lolosia.keel.api.storage

import kotlinx.serialization.Serializable

/**
 * 存储应答（`HostSubjects.STORAGE`，CBOR）。
 *
 * [data] 只在读操作时非空；对象不存在时仍为空。
 */
@Serializable
data class StorageReply(val data: ByteArray? = null)
