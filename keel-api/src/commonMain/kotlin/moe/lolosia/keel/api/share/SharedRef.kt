package moe.lolosia.keel.api.share

import kotlinx.serialization.Serializable

/**
 * 共享对象在调用载荷里的引用。
 *
 * Kotlin 对象不跨运行时；跨界时只传 Host 分配的句柄 id。
 */
@Serializable
data class SharedRef(val handle: Long)
