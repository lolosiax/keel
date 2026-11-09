package moe.lolosia.keel.plugins.basic.biz.db

import moe.lolosia.keel.database.Row
import moe.lolosia.keel.plugins.basic.biz.api.dto.UserRow
import moe.lolosia.keel.plugins.basic.biz.epochMsToDisplay

/**
 * `sys_user` 的完整一行（含密码哈希）。
 *
 * 只在插件内部使用；对外的 [UserRow] 由 [toRow] 去掉 `password`。
 */
internal class UserRecord(
    val id: String,
    val userName: String,
    val password: String?,
    val realName: String?,
    val phone: String?,
    val email: String?,
    val avatar: String?,
    val isUse: Int?,
    val createdAt: String?,
    val createdBy: String?,
    val updatedAt: String?,
    val updatedBy: String?,
    val deleted: Boolean,
)

/** `SELECT id, user_name, password, real_name, phone, email, avatar, is_use, created_at, created_by, updated_at, updated_by, deleted` 的列序。 */
internal fun Row.toUserRecord(): UserRecord = UserRecord(
    id = text(0) ?: "",
    userName = text(1).orEmpty(),
    password = text(2),
    realName = text(3),
    phone = text(4),
    email = text(5),
    avatar = text(6),
    isUse = flagOrNull(7) ?: intOrNull(7),
    createdAt = text(8),
    createdBy = text(9),
    updatedAt = text(10),
    updatedBy = text(11),
    deleted = boolOrNull(12) ?: false,
)

/** epoch 毫秒 → 展示时间；库里已经是字符串时原样返回。 */
internal fun displayTime(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val epoch = raw.toLongOrNull() ?: return raw
    return epochMsToDisplay(epoch)
}

internal fun UserRecord.toRow(): UserRow = UserRow(
    id = id,
    userName = userName,
    realName = realName,
    phone = phone,
    email = email,
    avatar = avatar,
    isUse = isUse,
    createdAt = displayTime(createdAt),
    createdBy = createdBy,
    updatedAt = displayTime(updatedAt),
    updatedBy = updatedBy,
    deleted = deleted,
)
