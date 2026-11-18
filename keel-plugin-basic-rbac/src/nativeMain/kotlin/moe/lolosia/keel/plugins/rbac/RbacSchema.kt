package moe.lolosia.keel.plugins.rbac

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.plugins.rbac.db.database

/**
 * rbac 的表结构与内置组初始数据（幂等，启动时尽力执行）。
 *
 * 用户表、会话表与用户↔组绑定由 `keel-plugin-basic-biz` 写；这里只保证组、组规则、
 * 用户直授规则三类数据可用。两边都用 `CREATE TABLE IF NOT EXISTS` / 幂等 upsert，
 * 同时安装不会互相破坏。
 */
internal object RbacSchema {

    /** 数据库不可达时只记日志，不阻断插件启用。 */
    fun apply() {
        val database = runCatching { database() }.getOrElse {
            Log.warn("数据库不可用，跳过建表：${it.message}", tag = "RBAC")
            return
        }
        STATEMENTS.forEachIndexed { index, sql ->
            runCatching { database.exec(sql) }.onFailure {
                Log.warn("第 ${index + 1} 条建表 / 初始数据语句失败：${it.message}", tag = "RBAC")
            }
        }
        Log.info("表结构检查完成（${STATEMENTS.size} 条语句）", tag = "RBAC")
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

        CREATE TABLE IF NOT EXISTS `sys_role_permission` (
          `id` int(11) NOT NULL AUTO_INCREMENT,
          `role_id` int(11) NOT NULL,
          `permission` varchar(255) NOT NULL,
          `created_at` timestamp NULL DEFAULT NULL,
          `created_by` varchar(40) DEFAULT NULL,
          `updated_at` timestamp NULL DEFAULT NULL,
          `updated_by` varchar(40) DEFAULT NULL,
          `deleted` tinyint(1) NOT NULL DEFAULT 0,
          PRIMARY KEY (`id`),
          KEY `idx_sys_role_permission_role` (`role_id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

        CREATE TABLE IF NOT EXISTS `sys_user_permission` (
          `id` int(11) NOT NULL AUTO_INCREMENT,
          `user_id` varchar(40) NOT NULL,
          `permission` varchar(255) NOT NULL,
          `created_at` timestamp NULL DEFAULT NULL,
          `created_by` varchar(40) DEFAULT NULL,
          `updated_at` timestamp NULL DEFAULT NULL,
          `updated_by` varchar(40) DEFAULT NULL,
          `deleted` tinyint(1) NOT NULL DEFAULT 0,
          PRIMARY KEY (`id`),
          KEY `idx_sys_user_permission_user` (`user_id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    """.trimIndent()
}
