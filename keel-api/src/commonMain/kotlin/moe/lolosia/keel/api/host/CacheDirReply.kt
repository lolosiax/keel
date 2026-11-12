package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable

/**
 * 缓存目录应答（`HostSubjects.CACHE_DIR`，CBOR）。
 *
 * @property path 本次加载独占的目录绝对路径，已由 Host 创建且可写
 */
@Serializable
data class CacheDirReply(
    val path: String,
)
