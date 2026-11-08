package moe.lolosia.keel.api.auth

import kotlinx.serialization.Serializable
import moe.lolosia.keel.api.web.UserSnapshot

/**
 * 认证插件的应答载荷（`KEEL_SUBJECT_AUTHN`，CBOR）。
 *
 * @property user 解析出的用户；null 表示 token 无效 / 已过期
 */
@Serializable
data class AuthnReply(val user: UserSnapshot? = null)
