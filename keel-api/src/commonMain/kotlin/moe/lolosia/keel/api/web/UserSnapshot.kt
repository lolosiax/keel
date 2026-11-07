package moe.lolosia.keel.api.web

import kotlinx.serialization.Serializable

/**
 * 当前用户的最小快照。
 *
 * 更细的权限判定走 [Context.hasPermission]（Host 侧按会话句柄实时解析），
 * 不把完整权限表复制到每个插件。
 */
@Serializable
data class UserSnapshot(
    val id: String,
    val username: String? = null,
    val roles: List<String> = emptyList(),
)
