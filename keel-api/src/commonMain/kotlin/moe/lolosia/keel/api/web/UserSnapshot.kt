package moe.lolosia.keel.api.web

import kotlinx.serialization.Serializable

/**
 * 当前请求的用户快照。
 *
 * 由 Host 在分发前用 `Authenticator` 解析；[rules] / [owner] 也一并解析好，
 * 所以插件的 `ctx.hasPermission` 是**本地同步判定**，不再回 Host。
 *
 * @property owner 超级管理员：直接放行，并忽略 `-` 规则
 * @property rules 已展开的规则原文（`*` / `a.b.*` / `-a.b.c`）
 */
@Serializable
data class UserSnapshot(
    val id: String,
    val username: String? = null,
    val roles: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
    val owner: Boolean = false,
)
