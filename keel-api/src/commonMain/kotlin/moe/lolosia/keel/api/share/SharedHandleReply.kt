package moe.lolosia.keel.api.share

import kotlinx.serialization.Serializable

/**
 * Host 对登记 / 单例查询的应答：只回一个句柄 id。
 */
@Serializable
data class SharedHandleReply(val handle: Long)
