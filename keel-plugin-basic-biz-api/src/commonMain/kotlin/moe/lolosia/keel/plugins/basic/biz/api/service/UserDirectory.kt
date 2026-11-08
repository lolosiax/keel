package moe.lolosia.keel.plugins.basic.biz.api.service

import moe.lolosia.keel.api.annotation.*
import moe.lolosia.keel.plugins.basic.biz.api.dto.*

/**
 * 用户查询的只读面（热路径）。
 *
 * 实现方是 `keel-plugin-basic-biz`；第三方插件只依赖本契约。
 * 出参一律不含密码哈希。
 */
@ModuleService("basic.biz.user")
interface UserDirectory {

    /** 按 id 取用户；不存在返回 `null`。 */
    suspend fun byId(id: String): UserRow?

    /** 按 id 批量取用户；查不到的直接略过。 */
    suspend fun byIds(ids: List<String>): List<UserRow>

    /** 分页查询用户；[keyword] 模糊匹配用户名 / 手机号 / 邮箱 / 组名。 */
    suspend fun page(pageNo: Int = 1, pageSize: Int = 10, keyword: String? = null): UserPage

    /** 按关键字搜索用户；关键字为空时返回前 [limit] 条。 */
    suspend fun search(keyword: String?, limit: Int = 10): List<UserRow>
}
