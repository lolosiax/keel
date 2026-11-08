package moe.lolosia.keel.plugins.basic.biz.api.dto

import kotlinx.serialization.Serializable

/**
 * 新建用户的入参。
 *
 * 手机号与邮箱至少给一个；[userName] 缺省时取手机号或邮箱。
 * [isUse] 是接口上的 `1` / `0`，缺省按启用处理。
 *
 * @property roleId 必填：新用户直接绑定的组 id
 */
@Serializable
data class UserCreateReq(
    val userName: String? = null,
    val password: String? = null,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val isUse: Int? = null,
    val roleId: Long,
)
