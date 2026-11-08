package moe.lolosia.keel.api.auth

import kotlinx.serialization.Serializable

/**
 * 一个用户的授权快照，由 `Authorizer` 的实现（keel-plugin-rbac）返回。
 *
 * @property rules 已展开的规则原文；判定口径见 [moe.lolosia.keel.api.web.Permissions]
 * @property owner 超级管理员：直接放行，并忽略 `-` 规则
 */
@Serializable
data class AuthzSnapshot(
    val roles: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
    val owner: Boolean = false,
)
