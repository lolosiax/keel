package moe.lolosia.keel.api.auth

import kotlinx.serialization.Serializable

/**
 * Host → 授权插件的请求载荷（`KEEL_SUBJECT_AUTHZ`，CBOR）。
 *
 * @property userId 已认证用户的 id
 */
@Serializable
data class AuthzRequest(val userId: String)
