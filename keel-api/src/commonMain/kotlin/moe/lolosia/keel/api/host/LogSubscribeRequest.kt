package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable

/**
 * 订阅请求；[startId] 语义与 `Log.history` 一致：
 * `-1` 只要新日志、`0` 从最老一条重放、`> 0` 只取 id 更大的。
 */
@Serializable
data class LogSubscribeRequest(val startId: Long = 0)
