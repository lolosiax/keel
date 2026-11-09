package moe.lolosia.keel.plugins.basic.biz

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.plugins.basic.biz.db.database

/**
 * basic-biz 的表结构（幂等，启动时尽力执行）。
 *
 * 暂时内嵌成字符串：`ctx.classpath` 的插件侧实现尚未接通，读 `.keel` 里的 `resources/sql/`
 * 还拿不到；等 Classpath 落下后把 [SCHEMA] 挪回资源文件即可，语句本身不用改。
 *
 * 组定义在归属上属于 rbac；rbac 尚未落地时这里只保证 `view_user_role` 可查，
 * 两边都用 `CREATE TABLE IF NOT EXISTS` / `ON DUPLICATE KEY UPDATE`，不会互相破坏。
 */
internal object Schema {

    /** 数据库不可达时只记日志，不阻断插件启用（与设计里的懒建池语义一致）。 */
    fun apply() {
        val database = runCatching { database() }.getOrElse {
            Log.warn("数据库不可用，跳过建表：${it.message}", tag = "Schema")
            return
        }
        STATEMENTS.forEachIndexed { index, sql ->
            runCatching { database.exec(sql) }.onFailure {
                Log.warn("第 ${index + 1} 条建表 / 迁移语句失败：${it.message}", tag = "Schema")
            }
        }
        Log.info("表结构检查完成（${STATEMENTS.size} 条语句）", tag = "Schema")
    }

    private val STATEMENTS: List<String> by lazy {
        SCHEMA
            .lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private val SCHEMA = """
        CREATE TABLE IF NOT EXISTS `sys_user` (
          `id` varchar(40) NOT NULL,
          `user_name` varchar(50) NOT NULL,
          `password` varchar(255) DEFAULT NULL,
          `real_name` varchar(50) DEFAULT NULL,
          `phone` varchar(20) DEFAULT NULL,
          `email` varchar(255) DEFAULT NULL,
          `avatar` varchar(255) DEFAULT NULL,
          `is_use` tinyint(1) DEFAULT 1,
          `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
          `created_by` varchar(40) DEFAULT NULL,
          `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
          `updated_by` varchar(40) DEFAULT NULL,
          `deleted` tinyint(1) NOT NULL DEFAULT 0,
          PRIMARY KEY (`id`),
          KEY `idx_sys_user_name` (`user_name`),
          KEY `idx_sys_user_phone` (`phone`),
          KEY `idx_sys_user_email` (`email`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

        CREATE TABLE IF NOT EXISTS `sys_role` (
          `id` int(11) NOT NULL AUTO_INCREMENT,
          `role_name` varchar(50) DEFAULT NULL,
          `type` varchar(50) DEFAULT NULL,
          `parent_id` int(11) DEFAULT NULL,
          `created_at` timestamp NULL DEFAULT NULL,
          `created_by` varchar(40) DEFAULT NULL,
          `updated_at` timestamp NULL DEFAULT NULL,
          `updated_by` varchar(40) DEFAULT NULL,
          `deleted` tinyint(1) NOT NULL DEFAULT 0,
          PRIMARY KEY (`id`),
          KEY `idx_sys_role_type` (`type`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

        INSERT INTO `sys_role` (`id`, `role_name`, `type`, `parent_id`, `created_at`, `deleted`)
        VALUES (1, '拥有者', 'owner', NULL, NOW(), 0),
               (2, '管理员', 'admin', 4, NOW(), 0),
               (4, '普通用户', 'user', 5, NOW(), 0),
               (5, '访客', 'guest', NULL, NOW(), 0)
        ON DUPLICATE KEY UPDATE `role_name` = VALUES(`role_name`), `type` = VALUES(`type`),
                                `parent_id` = VALUES(`parent_id`), `deleted` = 0;

        CREATE TABLE IF NOT EXISTS `sys_user_roles` (
          `id` int(11) NOT NULL AUTO_INCREMENT,
          `user_id` varchar(40) NOT NULL,
          `role_id` int(11) NOT NULL,
          `created_at` timestamp NULL DEFAULT NULL,
          `created_by` varchar(40) DEFAULT NULL,
          `updated_at` timestamp NULL DEFAULT NULL,
          `updated_by` varchar(40) DEFAULT NULL,
          `deleted` tinyint(1) NOT NULL DEFAULT 0,
          PRIMARY KEY (`id`),
          KEY `idx_sys_user_roles_user` (`user_id`),
          KEY `idx_sys_user_roles_role` (`role_id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

        CREATE TABLE IF NOT EXISTS `sys_sessions` (
          `id` varchar(64) NOT NULL,
          `user_id` varchar(40) DEFAULT NULL,
          `data` mediumtext DEFAULT NULL,
          `created_at` timestamp NULL DEFAULT NULL,
          `updated_at` timestamp NULL DEFAULT NULL,
          `deleted` tinyint(1) NOT NULL DEFAULT 0,
          PRIMARY KEY (`id`),
          KEY `idx_sys_sessions_user` (`user_id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

        ALTER TABLE `sys_sessions` ADD COLUMN IF NOT EXISTS `user_id` varchar(40) DEFAULT NULL;

        CREATE OR REPLACE VIEW `view_user_role` AS
        SELECT sys_user_roles.id         AS id,
               sys_user_roles.user_id    AS user_id,
               sys_user.user_name        AS user_name,
               sys_user.real_name        AS real_name,
               sys_user.phone            AS phone,
               sys_user.email            AS email,
               sys_user.avatar           AS avatar,
               sys_role.id               AS role_id,
               sys_role.role_name        AS role_name,
               sys_role.type             AS type,
               sys_user_roles.created_at AS created_at,
               sys_user_roles.updated_at AS updated_at,
               sys_user_roles.created_by AS created_by,
               sys_user_roles.updated_by AS updated_by,
               sys_user_roles.deleted    AS deleted
        FROM sys_user_roles
                 JOIN sys_role ON sys_user_roles.role_id = sys_role.id AND sys_role.deleted != 1
                 JOIN sys_user ON sys_user_roles.user_id = sys_user.id AND sys_user.deleted != 1;
    """.trimIndent()
}
