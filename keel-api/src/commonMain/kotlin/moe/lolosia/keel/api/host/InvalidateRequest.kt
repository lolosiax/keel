package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable

/** 缓存失效通知的载荷；字段为空表示清全部。 */
@Serializable
data class InvalidateRequest(
    val userId: String? = null,
    val token: String? = null,
)
