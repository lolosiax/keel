package moe.lolosia.keel.plugins.basic.biz

/**
 * `sys_role` 内置组的固定约定。
 *
 * 表结构与初始数据由 basic-biz 与 rbac 共用（两边都用 `CREATE TABLE IF NOT EXISTS` /
 * 幂等 upsert）；basic-biz 只在注册用户、展示组名时用到这两个约定值，不依赖 rbac 契约工件。
 * 兜底 id 与 backend 的 `sys_role.sql` 保持一致。
 */

/** 注册时给新用户的普通用户组 id。 */
const val ROLE_ID_USER: Long = 4L

/** 普通用户组的 `type` 代号；建号时优先按它查默认组。 */
const val ROLE_TYPE_USER: String = "user"

/** 拥有者组的 `type` 代号：判定时视为拥有 `*`，且不可被 `-` 规则剥夺。 */
const val ROLE_TYPE_OWNER: String = "owner"
