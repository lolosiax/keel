package moe.lolosia.keel.api.service

import kotlinx.serialization.Serializable

/**
 * 跨模块一次服务调用的信封。
 *
 * 只有 [service] / [method] / [args] 三段：参数与返回值都已经是方法自己的字节，
 * Host 不需要理解业务类型，只按 [service] 转发。
 */
@Serializable
data class ServiceCall(
    val service: String,
    val method: String,
    val args: ByteArray,
)
