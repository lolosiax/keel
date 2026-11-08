package moe.lolosia.keel.plugins.basic.biz.api.dto

import kotlinx.serialization.Serializable

/**
 * 注册入参。
 *
 * [code] 是邮箱验证码；当前注册流程只校验用户名 / 密码重复性，保留字段供后续启用的邮箱验证。
 */
@Serializable
data class RegisterReq(
    val userName: String,
    val password: String,
    val email: String? = null,
    val phone: String? = null,
    val code: String? = null,
)
