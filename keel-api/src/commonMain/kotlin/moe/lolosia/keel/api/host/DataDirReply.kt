package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable

/**
 * 持久化数据目录应答（`HostSubjects.DATA_DIR`，CBOR）。
 *
 * @property path 本插件的数据目录绝对路径，已由 Host 创建且可写；跨重启保持同一位置
 */
@Serializable
data class DataDirReply(
    val path: String,
)
