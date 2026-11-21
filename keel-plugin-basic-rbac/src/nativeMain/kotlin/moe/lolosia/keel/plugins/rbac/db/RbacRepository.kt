package moe.lolosia.keel.plugins.rbac.db

import moe.lolosia.keel.api.web.ApiException
import moe.lolosia.keel.api.web.Permissions
import moe.lolosia.keel.database.DbValue
import moe.lolosia.keel.plugins.rbac.DefaultGroups
import moe.lolosia.keel.plugins.rbac.api.*
import moe.lolosia.keel.plugins.rbac.api.dto.*
import moe.lolosia.keel.plugins.rbac.displayTime
import moe.lolosia.keel.plugins.rbac.nowDb

/**
 * 组、组规则、用户直授规则的仓库（手写 SQL）。
 *
 * 读写都发生在插件 Worker 线程；数据库连接池由能力包管理，这里不持有连接。
 * 时间列统一按能力包返回的文本处理（MariaDB 的 `timestamp` 驱动会给字符串）。
 */
internal object RbacRepository {

    private const val ROLE_COLUMNS =
        "id, role_name, type, parent_id, created_at, created_by, updated_at, updated_by, deleted"

    // ---------------------------------- 读 ----------------------------------

    /** 全部未删除的组；判定与继承展开都基于这份快照。 */
    fun allRoles(): Map<Long, RoleRef> = select(
        "SELECT id, role_name, type, parent_id FROM sys_role WHERE deleted = 0 ORDER BY id",
    ) { it.toRoleRef() }.associateBy { it.id }

    fun listRoles(): List<RoleRef> = allRoles().values.toList()

    fun roleById(id: Long): RoleRef? = selectOne(
        "SELECT id, role_name, type, parent_id FROM sys_role WHERE id = ? AND deleted = 0",
        listOf(longParam(id)),
    ) { it.toRoleRef() }

    fun roleByType(type: String): RoleRef? = selectOne(
        "SELECT id, role_name, type, parent_id FROM sys_role WHERE type = ? AND deleted = 0 ORDER BY id LIMIT 1",
        listOf(textParam(type)),
    ) { it.toRoleRef() }

    /** 用户的直接组绑定（按绑定先后）。 */
    fun rolesOf(userId: String): List<RoleRef> = select(
        "SELECT r.id, r.role_name, r.type, r.parent_id " +
            "FROM sys_user_roles ur JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0 " +
            "WHERE ur.user_id = ? AND ur.deleted = 0 ORDER BY ur.id",
        listOf(textParam(userId)),
    ) { it.toRoleRef() }

    fun roleOf(userId: String): RoleRef? = rolesOf(userId).firstOrNull()

    /** 绑定的组 + 沿 `parent_id` 递归的祖先组（去重，按首次出现顺序）。 */
    fun groupsOf(userId: String): List<RoleRef> {
        val all = allRoles()
        val chain = LinkedHashMap<Long, RoleRef>()
        rolesOf(userId).forEach { binding ->
            var current = all[binding.id]
            while (current != null && !chain.containsKey(current.id)) {
                chain[current.id] = current
                current = current.parentId?.let(all::get)
            }
        }
        return chain.values.toList()
    }

    fun isOwner(userId: String): Boolean =
        groupsOf(userId).any { it.type?.lowercase() == ROLE_TYPE_OWNER }

    /** 某组**自身**的规则：库里有就用库里的，没有回退内置默认值。 */
    fun rulesOfRole(roleId: Long): List<String> {
        val role = roleById(roleId) ?: return emptyList()
        val stored = storedRoleRules(roleId)
        return stored.ifEmpty { DefaultGroups.rulesOf(role.type) }
    }

    fun rulesOfUser(userId: String): List<String> = select(
        "SELECT permission FROM sys_user_permission WHERE user_id = ? AND deleted = 0 ORDER BY id",
        listOf(textParam(userId)),
    ) { it.text(0) }.filterNotNull().filter { it.isNotBlank() }

    /** 生效规则：组链上各组的规则 + 用户直授，按出现顺序去重。 */
    fun effectiveRules(userId: String): List<String> {
        val grouped = groupsOf(userId).flatMap { role ->
            storedRoleRules(role.id).ifEmpty { DefaultGroups.rulesOf(role.type) }
        }
        return (grouped + rulesOfUser(userId)).distinct()
    }

    // ---------------------------------- 管理 ----------------------------------

    fun pageRoles(pageNo: Int, pageSize: Int, keyword: String?): RolePage {
        val size = pageSize.coerceIn(1, 200)
        val offset = (pageNo.coerceAtLeast(1) - 1) * size
        val value = keyword?.trim().orEmpty()
        val (where, params) = if (value.isEmpty()) {
            "WHERE deleted = 0" to emptyList()
        } else {
            val like = "%$value%"
            "WHERE deleted = 0 AND (role_name LIKE ? OR type LIKE ?)" to
                listOf(textParam(like), textParam(like))
        }

        val rows = select(
            "SELECT $ROLE_COLUMNS FROM sys_role $where ORDER BY id LIMIT ? OFFSET ?",
            params + listOf(longParam(size.toLong()), longParam(offset.toLong())),
        ) { it.toRoleRow() }

        val total = selectOne(
            "SELECT COUNT(*) FROM sys_role $where",
            params,
        ) { it.longOrNull(0) ?: 0L } ?: 0L

        return RolePage(rows = rows, total = total)
    }

    fun createRole(req: RoleCreateReq, operatorId: String?): RoleRow {
        val name = req.roleName?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ApiException(400, "组名不能为空")
        val type = req.type?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ApiException(400, "组类型（type）不能为空")
        val now = nowDb()
        execute(
            "INSERT INTO sys_role (role_name, type, parent_id, created_at, created_by, updated_at, updated_by, deleted) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
            listOf(
                textParam(name),
                textParam(type),
                longParam(req.parentId),
                textParam(now),
                textParam(operatorId),
                textParam(now),
                textParam(operatorId),
            ),
        )
        val id = selectOne("SELECT LAST_INSERT_ID()") { it.longOrNull(0) } ?: 0L
        return roleRow(id) ?: throw ApiException(500, "组写入后无法读回：$id")
    }

    fun updateRole(req: RoleUpdateReq, operatorId: String?) {
        roleById(req.id) ?: throw ApiException(400, "组不存在：${req.id}")
        val sets = mutableListOf<String>()
        val params = mutableListOf<DbValue>()
        req.roleName?.let {
            val name = it.trim().takeIf { text -> text.isNotEmpty() } ?: throw ApiException(400, "组名不能为空")
            sets += "role_name = ?"
            params += textParam(name)
        }
        req.type?.let {
            val type = it.trim().takeIf { text -> text.isNotEmpty() } ?: throw ApiException(400, "组类型不能为空")
            sets += "type = ?"
            params += textParam(type)
        }
        req.parentId?.let {
            if (it == req.id) throw ApiException(400, "组不能以自己为父组")
            sets += "parent_id = ?"
            params += longParam(it)
        }
        sets += "updated_at = ?"
        params += textParam(nowDb())
        sets += "updated_by = ?"
        params += textParam(operatorId)
        execute(
            "UPDATE sys_role SET ${sets.joinToString(", ")} WHERE id = ? AND deleted = 0",
            params + listOf(longParam(req.id)),
        )
    }

    fun deleteRole(id: Long, operatorId: String?) {
        val role = roleById(id) ?: return
        if (role.type?.lowercase() == ROLE_TYPE_OWNER) throw ApiException(400, "拥有者组不许删除")
        val bindings = selectOne(
            "SELECT COUNT(*) FROM sys_user_roles WHERE role_id = ? AND deleted = 0",
            listOf(longParam(id)),
        ) { it.longOrNull(0) ?: 0L } ?: 0L
        if (bindings > 0) throw ApiException(400, "组仍绑定着 $bindings 个用户，不能删除")
        execute(
            "UPDATE sys_role SET deleted = 1, updated_at = ?, updated_by = ? WHERE id = ? AND deleted = 0",
            listOf(textParam(nowDb()), textParam(operatorId), longParam(id)),
        )
    }

    /** 全量替换组规则；非法规则串抛 400，且不落库。 */
    fun saveRoleRules(roleId: Long, rules: List<String>, operatorId: String?) {
        roleById(roleId) ?: throw ApiException(400, "组不存在：$roleId")
        val canonical = canonicalRules(rules)
        database().transaction { tx ->
            val now = nowDb()
            tx.exec(
                "UPDATE sys_role_permission SET deleted = 1, updated_at = ?, updated_by = ? " +
                    "WHERE role_id = ? AND deleted = 0",
                listOf(textParam(now), textParam(operatorId), longParam(roleId)),
            )
            canonical.forEach { rule ->
                tx.exec(
                    "INSERT INTO sys_role_permission (role_id, permission, created_at, created_by, " +
                        "updated_at, updated_by, deleted) VALUES (?, ?, ?, ?, ?, ?, 0)",
                    listOf(
                        longParam(roleId),
                        textParam(rule),
                        textParam(now),
                        textParam(operatorId),
                        textParam(now),
                        textParam(operatorId),
                    ),
                )
            }
        }
    }

    /** 全量替换用户直授规则；非法规则串抛 400，且不落库。 */
    fun saveUserRules(userId: String, rules: List<String>, operatorId: String?) {
        if (userId.isBlank()) throw ApiException(400, "userId 不能为空")
        val canonical = canonicalRules(rules)
        database().transaction { tx ->
            val now = nowDb()
            tx.exec(
                "UPDATE sys_user_permission SET deleted = 1, updated_at = ?, updated_by = ? " +
                    "WHERE user_id = ? AND deleted = 0",
                listOf(textParam(now), textParam(operatorId), textParam(userId)),
            )
            canonical.forEach { rule ->
                tx.exec(
                    "INSERT INTO sys_user_permission (user_id, permission, created_at, created_by, " +
                        "updated_at, updated_by, deleted) VALUES (?, ?, ?, ?, ?, ?, 0)",
                    listOf(
                        textParam(userId),
                        textParam(rule),
                        textParam(now),
                        textParam(operatorId),
                        textParam(now),
                        textParam(operatorId),
                    ),
                )
            }
        }
    }

    /** 绑定组：同一组重复绑定按幂等处理，否则先撤回旧绑定再插入。 */
    fun bindRole(userId: String, roleId: Long, operatorId: String?) {
        if (userId.isBlank()) throw ApiException(400, "userId 不能为空")
        roleById(roleId) ?: throw ApiException(400, "组不存在：$roleId")
        val current = selectOne(
            "SELECT id FROM sys_user_roles WHERE user_id = ? AND role_id = ? AND deleted = 0 LIMIT 1",
            listOf(textParam(userId), longParam(roleId)),
        ) { it.longOrNull(0) }
        if (current != null) return

        val now = nowDb()
        database().transaction { tx ->
            tx.exec(
                "UPDATE sys_user_roles SET deleted = 1, updated_at = ?, updated_by = ? " +
                    "WHERE user_id = ? AND deleted = 0",
                listOf(textParam(now), textParam(operatorId), textParam(userId)),
            )
            tx.exec(
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
    }

    fun unbindRole(userId: String, operatorId: String?) {
        execute(
            "UPDATE sys_user_roles SET deleted = 1, updated_at = ?, updated_by = ? " +
                "WHERE user_id = ? AND deleted = 0",
            listOf(textParam(nowDb()), textParam(operatorId), textParam(userId)),
        )
    }

    // ---------------------------------- 内部 ----------------------------------

    fun roleRow(id: Long): RoleRow? = selectOne(
        "SELECT $ROLE_COLUMNS FROM sys_role WHERE id = ? AND deleted = 0",
        listOf(longParam(id)),
    ) { it.toRoleRow() }

    private fun storedRoleRules(roleId: Long): List<String> = select(
        "SELECT permission FROM sys_role_permission WHERE role_id = ? AND deleted = 0 ORDER BY id",
        listOf(longParam(roleId)),
    ) { it.text(0) }.filterNotNull().filter { it.isNotBlank() }

    /** 校验并规范化：统一小写、补全符号；任何一条非法即整批失败。 */
    private fun canonicalRules(rules: List<String>): List<String> =
        rules.map { raw ->
            Permissions.canonical(raw) ?: throw ApiException(400, "非法权限规则：$raw")
        }.distinct()

    private fun moe.lolosia.keel.database.Row.toRoleRef(): RoleRef = RoleRef(
        id = longOrNull(0) ?: 0L,
        name = text(1),
        type = text(2),
        parentId = longOrNull(3),
    )

    private fun moe.lolosia.keel.database.Row.toRoleRow(): RoleRow = RoleRow(
        id = longOrNull(0) ?: 0L,
        roleName = text(1),
        type = text(2),
        parentId = longOrNull(3),
        createdAt = displayTime(4),
        createdBy = text(5),
        updatedAt = displayTime(6),
        updatedBy = text(7),
        deleted = boolOrNull(8) ?: false,
    )
}
