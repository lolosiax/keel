package moe.lolosia.keel.plugins.basic.biz.api.service

import moe.lolosia.keel.api.annotation.*
import moe.lolosia.keel.plugins.basic.biz.api.dto.*

/**
 * 用户管理面。
 *
 * `operatorId` 写入审计列（`created_by` / `updated_by`），传 `null` 表示没有操作者；
 * 注册链路没有登录态，因此不从请求上下文隐式取。
 */
@ModuleService("basic.biz.user.admin")
interface UserAdmin {

    /** 新建用户并绑定 [UserCreateReq.roleId] 指定的组。 */
    suspend fun create(req: UserCreateReq, operatorId: String? = null): UserRow

    /** 修改用户：`null` 字段保持原值。 */
    suspend fun update(req: UserUpdateReq, operatorId: String? = null)

    /** 批量删除用户（软删用户与其组绑定）。 */
    suspend fun delete(ids: List<String>, operatorId: String? = null)

    /** 改密码：需要校验 [origin]，[target] 按 bcrypt(cost=10) 入库。 */
    suspend fun changePassword(userId: String, origin: String, target: String)

    /** 保存头像：文件内容落插件存储，[fileName] 的扩展名决定落盘文件名。 */
    suspend fun saveAvatar(userId: String, fileName: String?, bytes: ByteArray, operatorId: String? = null)

    /** 改组绑定（组定义与规则归 rbac，这里只写绑定关系）。 */
    suspend fun bindRole(userId: String, roleId: Long, operatorId: String? = null)
}
