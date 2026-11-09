package moe.lolosia.keel.plugins.basic.biz.db

import moe.lolosia.keel.api.web.ApiException
import moe.lolosia.keel.database.DbValue
import moe.lolosia.keel.plugins.basic.biz.api.dto.*
import moe.lolosia.keel.plugins.basic.biz.crypto.BCrypt
import moe.lolosia.keel.plugins.basic.biz.newIdentifier
import moe.lolosia.keel.plugins.basic.biz.nowDisplay
import moe.lolosia.keel.plugins.rbac.api.ROLE_ID_USER
import moe.lolosia.keel.plugins.rbac.api.ROLE_TYPE_USER

/**
 * 用户表仓库（手写 SQL，不依赖 ORM / KSP）。
 *
 * 读写都发生在插件 Worker 线程；数据库连接池由能力包管理，这里不持有连接。
 * 时间列统一按能力包返回的文本处理（MariaDB 的 `timestamp` 驱动会给字符串）。
 */
internal object UserRepository {

    private const val USER_COLUMNS =
        "id, user_name, password, real_name, phone, email, avatar, is_use, " +
            "created_at, created_by, updated_at, updated_by, deleted"

    private const val LIST_SELECT = """
        SELECT u.id, u.user_name, u.real_name, u.phone, u.email, u.avatar, u.is_use,
               u.created_at, u.updated_at, r.id AS role_id, r.role_name, r.type
        FROM sys_user u
        LEFT JOIN sys_user_roles ur ON ur.user_id = u.id AND ur.deleted = 0
        LEFT JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0
    """

    fun findById(id: String): UserRecord? = selectOne(
        "SELECT $USER_COLUMNS FROM sys_user WHERE id = ? AND deleted = 0",
        listOf(textParam(id)),
    ) { it.toUserRecord() }

    /** 账号可以是用户名 / 手机号 / 邮箱（与 backend 的 `findOneByAccount` 一致）。 */
    fun findByAccount(account: String): UserRecord? = selectOne(
        "SELECT $USER_COLUMNS FROM sys_user " +
            "WHERE deleted = 0 AND (user_name = ? OR phone = ? OR email = ?) LIMIT 1",
        listOf(textParam(account), textParam(account), textParam(account)),
    ) { it.toUserRecord() }

    fun page(pageNo: Int, pageSize: Int, keyword: String?): UserPage {
        val size = pageSize.coerceIn(1, 200)
        val offset = (pageNo.coerceAtLeast(1) - 1) * size
        val (where, params) = keywordClause(keyword)

        val rows = select(
            "$LIST_SELECT $where ORDER BY u.created_at DESC, u.id LIMIT ? OFFSET ?",
            params + listOf(longParam(size.toLong()), longParam(offset.toLong())),
        ) { it.toListRow() }

        val total = selectOne(
            "SELECT COUNT(*) FROM sys_user u " +
                "LEFT JOIN sys_user_roles ur ON ur.user_id = u.id AND ur.deleted = 0 " +
                "LEFT JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0 $where",
            params,
        ) { it.longOrNull(0) ?: 0L } ?: 0L

        return UserPage(data = rows, total = total)
    }

    fun search(keyword: String?, limit: Int): List<UserRecord> {
        val value = keyword?.trim().orEmpty()
        val where = if (value.isEmpty()) "WHERE deleted = 0" else
            "WHERE deleted = 0 AND (user_name LIKE ? OR real_name LIKE ? OR phone LIKE ? OR email LIKE ?)"
        val params = if (value.isEmpty()) {
            emptyList()
        } else {
            val like = "%$value%"
            listOf(textParam(like), textParam(like), textParam(like), textParam(like))
        }
        return select(
            "SELECT $USER_COLUMNS FROM sys_user $where ORDER BY created_at DESC LIMIT ?",
            params + listOf(longParam(limit.coerceIn(1, 50).toLong())),
        ) { it.toUserRecord() }
    }

    fun byIds(ids: List<String>): List<UserRecord> = ids.mapNotNull { findById(it) }

    fun create(req: UserCreateReq, operatorId: String?): UserRecord {
        val userName = req.userName?.takeIf { it.isNotBlank() }
            ?: req.phone?.takeIf { it.isNotBlank() }
            ?: req.email?.takeIf { it.isNotBlank() }
            ?: throw ApiException(400, "用户名、手机号、邮箱至少要给一个")

        if (findByAccount(userName) != null) throw ApiException(400, "账号已存在：$userName")

        val id = newIdentifier()
        val password = req.password?.takeIf { it.isNotEmpty() }?.let { BCrypt.hashpw(it, BCrypt.gensalt()) }
        val now = nowDisplay()

        execute(
            "INSERT INTO sys_user (id, user_name, password, real_name, phone, email, is_use, " +
                "created_at, created_by, updated_at, updated_by, deleted) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
            listOf(
                textParam(id),
                textParam(userName),
                textParam(password),
                textParam(req.realName),
                textParam(req.phone),
                textParam(req.email),
                intParam(req.isUse ?: 1),
                textParam(now),
                textParam(operatorId),
                textParam(now),
                textParam(operatorId),
            ),
        )
        bindRole(id, req.roleId, operatorId)
        return findById(id) ?: throw ApiException(500, "用户写入后无法读回：$id")
    }

    fun update(req: UserUpdateReq, operatorId: String?) {
        val existing = findById(req.id) ?: throw ApiException(400, "用户不存在：${req.id}")

        val incomingPassword = req.password
        val password = if (incomingPassword.isNullOrEmpty()) {
            existing.password
        } else {
            BCrypt.hashpw(incomingPassword, BCrypt.gensalt())
        }

        execute(
            "UPDATE sys_user SET user_name = ?, password = ?, real_name = ?, phone = ?, email = ?, " +
                "is_use = ?, updated_at = ?, updated_by = ? WHERE id = ? AND deleted = 0",
            listOf(
                textParam(req.userName ?: existing.userName),
                textParam(password),
                textParam(req.realName ?: existing.realName),
                textParam(req.phone ?: existing.phone),
                textParam(req.email ?: existing.email),
                intParam(req.isUse ?: existing.isUse ?: 1),
                textParam(nowDisplay()),
                textParam(operatorId),
                textParam(req.id),
            ),
        )

        req.roleId?.let { bindRole(req.id, it, operatorId) }
    }

    /** 软删用户与其组绑定；会话由 [AccountRepository] 一并吊销。 */
    fun softDelete(ids: List<String>, operatorId: String?) {
        val now = nowDisplay()
        ids.forEach { id ->
            execute(
                "UPDATE sys_user SET deleted = 1, updated_at = ?, updated_by = ? WHERE id = ?",
                listOf(textParam(now), textParam(operatorId), textParam(id)),
            )
            execute(
                "UPDATE sys_user_roles SET deleted = 1, updated_at = ?, updated_by = ? WHERE user_id = ?",
                listOf(textParam(now), textParam(operatorId), textParam(id)),
            )
        }
    }

    /** 改密码：库里已有密码时校验 [origin]；首次设置（库里为空）直接写入。 */
    fun changePassword(userId: String, origin: String, target: String) {
        val existing = findById(userId) ?: throw ApiException(400, "用户不存在：$userId")
        val stored = existing.password
        if (!stored.isNullOrEmpty() && !BCrypt.checkpw(origin, stored)) {
            throw ApiException(400, "原密码不正确")
        }
        if (target.isEmpty()) throw ApiException(400, "新密码不能为空")
        execute(
            "UPDATE sys_user SET password = ?, updated_at = ? WHERE id = ? AND deleted = 0",
            listOf(textParam(BCrypt.hashpw(target, BCrypt.gensalt())), textParam(nowDisplay()), textParam(userId)),
        )
    }

    fun bindRole(userId: String, roleId: Long, operatorId: String?) {
        val now = nowDisplay()
        execute(
            "UPDATE sys_user_roles SET deleted = 1, updated_at = ?, updated_by = ? " +
                "WHERE user_id = ? AND deleted = 0",
            listOf(textParam(now), textParam(operatorId), textParam(userId)),
        )
        execute(
            "INSERT INTO sys_user_roles (user_id, role_id, created_at, created_by, updated_at, updated_by, deleted) " +
                "VALUES (?, ?, ?, ?, ?, ?, 0)",
            listOf(
                textParam(userId),
                longParam(roleId),
                textParam(now),
                textParam(operatorId),
                textParam(now),
                textParam(operatorId),
            ),
        )
    }

    /** 当前绑定；没有绑定时按普通用户组兜底。 */
    fun roleOf(userId: String): RoleBinding? = selectOne(
        "SELECT ur.id, ur.user_id, r.id, r.role_name, r.type " +
            "FROM sys_user_roles ur JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0 " +
            "WHERE ur.user_id = ? AND ur.deleted = 0 ORDER BY ur.id DESC LIMIT 1",
        listOf(textParam(userId)),
    ) { row ->
        RoleBinding(
            bindingId = row.longOrNull(0) ?: 0L,
            userId = row.text(1).orEmpty(),
            roleId = row.longOrNull(2) ?: ROLE_ID_USER,
            roleName = row.text(3),
            type = row.text(4),
        )
    }

    fun roleTypes(userId: String): List<String> = select(
        "SELECT r.type FROM sys_user_roles ur JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0 " +
            "WHERE ur.user_id = ? AND ur.deleted = 0",
        listOf(textParam(userId)),
    ) { it.text(0) }.filterNotNull().filter { it.isNotBlank() }

    /** 注册默认组：优先按 `type = user` 查，查不到回落固定 id。 */
    fun defaultUserRoleId(): Long = selectOne(
        "SELECT id FROM sys_role WHERE type = ? AND deleted = 0 LIMIT 1",
        listOf(textParam(ROLE_TYPE_USER)),
    ) { it.longOrNull(0) } ?: ROLE_ID_USER

    /** 头像文件名（`sys_user.avatar`）；没有设置返回 null。 */
    fun avatarName(userId: String): String? = selectOne(
        "SELECT avatar FROM sys_user WHERE id = ? AND deleted = 0",
        listOf(textParam(userId)),
    ) { it.text(0) }

    fun updateAvatar(userId: String, fileName: String, operatorId: String?) {
        execute(
            "UPDATE sys_user SET avatar = ?, updated_at = ?, updated_by = ? WHERE id = ? AND deleted = 0",
            listOf(textParam(fileName), textParam(nowDisplay()), textParam(operatorId), textParam(userId)),
        )
    }

    private fun keywordClause(keyword: String?): Pair<String, List<DbValue>> {
        val value = keyword?.trim().orEmpty()
        if (value.isEmpty()) return "" to emptyList()
        val like = "%$value%"
        return "WHERE u.deleted = 0 AND (u.user_name LIKE ? OR u.real_name LIKE ? OR " +
            "u.phone LIKE ? OR u.email LIKE ? OR r.role_name LIKE ?)" to
            listOf(textParam(like), textParam(like), textParam(like), textParam(like), textParam(like))
    }

    private fun moe.lolosia.keel.database.Row.toListRow(): UserListRow = UserListRow(
        id = text(0) ?: "",
        userId = text(0) ?: "",
        userName = text(1),
        realName = text(2),
        phone = text(3),
        email = text(4),
        avatar = text(5),
        isUse = flagOrNull(6) ?: intOrNull(6),
        createdAt = displayTime(text(7)),
        updatedAt = displayTime(text(8)),
        roleId = longOrNull(9),
        roleName = text(10),
        type = text(11),
        roleType = text(11),
    )
}
