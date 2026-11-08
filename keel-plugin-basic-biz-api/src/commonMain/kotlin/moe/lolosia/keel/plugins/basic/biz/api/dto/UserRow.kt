package moe.lolosia.keel.plugins.basic.biz.api.dto

import kotlinx.serialization.Serializable

/**
 * 用户的安全视图（`sys_user` 去掉 `password`）。
 *
 * 时间字段是展示字符串 `yyyy-MM-dd HH:mm:ss`（固定 +08:00），不是 `Instant`：
 * 本类型同时用于 HTTP 出参，序列化后前端直接渲染。
 *
 * @property isUse 线上取值是 `1` / `0`（前端按数字判启用）
 */
@Serializable
data class UserRow(
    val id: String,
    val userName: String,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val isUse: Int? = null,
    val createdAt: String? = null,
    val createdBy: String? = null,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
