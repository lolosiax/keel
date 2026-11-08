package moe.lolosia.keel.plugins.basic.biz.api.dto

import kotlinx.serialization.Serializable

/**
 * 新建会话后回给调用方的凭证。
 *
 * [token] 就是会话 id（与登录返回的 `Authorization` 同一含义），不是 JWT；
 * 调用方把它按 `Authorization: Bearer <token>` 使用。
 */
@Serializable
data class SessionToken(val token: String)
