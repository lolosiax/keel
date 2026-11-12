package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable

/**
 * 跨运行时读取 `.keel` 资源的应答（`HostSubjects.CLASSPATH`，CBOR）。
 *
 * 只填与请求操作对应的字段，其余保持默认值。
 *
 * @property bytes [ClasspathOp.READ] 的条目内容；条目不存在时为空
 * @property exists [ClasspathOp.EXISTS] 的结果
 * @property entries [ClasspathOp.LIST] 的条目路径；按 `/` 分隔、已排序
 */
@Serializable
data class ClasspathReply(
    val bytes: ByteArray? = null,
    val exists: Boolean = false,
    val entries: List<String>? = null,
)
