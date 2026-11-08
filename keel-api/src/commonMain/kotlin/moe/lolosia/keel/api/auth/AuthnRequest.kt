package moe.lolosia.keel.api.auth

import kotlinx.serialization.Serializable

/**
 * Host → 认证插件的请求载荷（`KEEL_SUBJECT_AUTHN`，CBOR）。
 *
 * @property token `Authorization: Bearer` 后面的原文，由认证实现自行解析
 */
@Serializable
data class AuthnRequest(val token: String)
