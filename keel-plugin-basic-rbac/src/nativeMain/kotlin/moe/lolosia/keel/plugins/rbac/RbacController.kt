package moe.lolosia.keel.plugins.rbac

import kotlinx.serialization.Serializable
import moe.lolosia.keel.api.web.*
import moe.lolosia.keel.plugins.rbac.api.dto.*

/**
 * 组与权限的管理接口（形状照 backend 的 `RoleController` / `PermissionController`）。
 *
 * 管理动作要求 `rbac.role.*` / `rbac.user.*` 节点；内置的拥有者 / 管理员组默认拥有 `+*`，
 * 普通用户与访客无权访问。读接口单独给 `rbac.role.read`，便于只读面板。
 */
@Controller("/api/rbac")
object RbacController {

    /** 管理动作直接走默认实现；替换实现时这个控制器随插件一起被换掉。 */
    private val rbac = RbacService()

    /** 全量替换规则时的入参。 */
    @Serializable
    data class RulesReq(val rules: List<String> = emptyList())

    /** 绑定组时的入参。 */
    @Serializable
    data class BindReq(val roleId: Long)

    @GET("/roles")
    @Permission("rbac.role.read")
    suspend fun roles(
        @Query("pageNo") pageNo: Int?,
        @Query("pageSize") pageSize: Int?,
        @Query("keyword") keyword: String?,
    ): RolePage = rbac.pageRoles(pageNo ?: 1, pageSize ?: 10, keyword)

    @POST("/role")
    @Permission("rbac.role.write")
    suspend fun createRole(ctx: Context, @Body body: RoleCreateReq): RoleRow =
        rbac.createRole(body, ctx.userId)

    @PUT("/role")
    @Permission("rbac.role.write")
    suspend fun updateRole(ctx: Context, @Body body: RoleUpdateReq) {
        rbac.updateRole(body, ctx.userId)
    }

    @DELETE("/role/{id}")
    @Permission("rbac.role.write")
    suspend fun deleteRole(ctx: Context, @Path("id") id: Long) {
        rbac.deleteRole(id, ctx.userId)
    }

    /** 某组自身规则（管理界面回填）；库为空时返回默认值。 */
    @GET("/role/{id}/rules")
    @Permission("rbac.role.read")
    suspend fun roleRules(@Path("id") id: Long): List<String> = rbac.rulesOfRole(id)

    @POST("/role/{id}/rules")
    @Permission("rbac.role.write")
    suspend fun saveRoleRules(ctx: Context, @Path("id") id: Long, @Body body: RulesReq) {
        rbac.saveRoleRules(id, body.rules, ctx.userId)
    }

    @GET("/user/{id}/rules")
    @Permission("rbac.user.read")
    suspend fun userRules(@Path("id") id: String): List<String> = rbac.rulesOfUser(id)

    @POST("/user/{id}/rules")
    @Permission("rbac.user.write")
    suspend fun saveUserRules(ctx: Context, @Path("id") id: String, @Body body: RulesReq) {
        rbac.saveUserRules(id, body.rules, ctx.userId)
    }

    @POST("/user/{id}/role")
    @Permission("rbac.user.write")
    suspend fun bindRole(ctx: Context, @Path("id") id: String, @Body body: BindReq) {
        rbac.bindRole(id, body.roleId, ctx.userId)
    }

    @DELETE("/user/{id}/role")
    @Permission("rbac.user.write")
    suspend fun unbindRole(ctx: Context, @Path("id") id: String) {
        rbac.unbindRole(id, ctx.userId)
    }
}
