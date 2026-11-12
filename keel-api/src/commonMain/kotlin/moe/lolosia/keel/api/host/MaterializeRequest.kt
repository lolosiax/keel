package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable

/**
 * 资源物化请求（`HostSubjects.MATERIALIZE`，CBOR）。
 *
 * @property path `.keel` 内的相对路径：单个文件，或某个目录前缀
 */
@Serializable
data class MaterializeRequest(
    val path: String,
)
