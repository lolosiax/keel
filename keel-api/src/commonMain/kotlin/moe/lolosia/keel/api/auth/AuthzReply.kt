package moe.lolosia.keel.api.auth

import kotlinx.serialization.Serializable

/**
 * 授权插件的应答载荷（`KEEL_SUBJECT_AUTHZ`，CBOR）。
 *
 * @property snapshot 该用户的授权快照；空规则表示没有任何权限
 */
@Serializable
data class AuthzReply(val snapshot: AuthzSnapshot = AuthzSnapshot())
