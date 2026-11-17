package moe.lolosia.keel.plugins.basic.biz.api.dto

import kotlinx.serialization.Serializable
import moe.lolosia.keel.api.annotation.PublicApi

/**
 * 用户分页结果。
 *
 * 键名是 `data` / `total`（前端用户列表按这两个键读取），与组分页的 `rows` / `total` 不同名。
 */
@PublicApi
@Serializable
data class UserPage(
    val data: List<UserListRow> = emptyList(),
    val total: Long = 0L,
)
