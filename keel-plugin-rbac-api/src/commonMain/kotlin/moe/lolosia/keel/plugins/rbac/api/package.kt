package moe.lolosia.keel.plugins.rbac.api

/** 查不到组绑定时使用的哨兵 id。 */
const val ROLE_ID_UNKNOWN: Long = 0L

/** 拥有者组的固定 id（`sys_role` 初始数据，见 `sql/sys_role.sql`）。 */
const val ROLE_ID_OWNER: Long = 1L

/** 管理员组的固定 id。 */
const val ROLE_ID_ADMIN: Long = 2L

/** 注册时给新用户的普通用户组 id。 */
const val ROLE_ID_USER: Long = 4L

/** 访客组 id；用户没有任何组绑定时按它处理。 */
const val ROLE_ID_GUEST: Long = 5L

/** 无绑定时的兜底组 id，与 [ROLE_ID_GUEST] 同值。 */
const val ROLE_ID_DEFAULT: Long = ROLE_ID_GUEST

/** 拥有者组的 `type` 代号（不是展示名）：判定时视为拥有 `*`，且不可被 `-` 规则剥夺。 */
const val ROLE_TYPE_OWNER: String = "owner"

/** 管理员组的 `type` 代号。 */
const val ROLE_TYPE_ADMIN: String = "admin"

/** 普通用户组的 `type` 代号；注册 / 建号时优先按它查默认组。 */
const val ROLE_TYPE_USER: String = "user"
