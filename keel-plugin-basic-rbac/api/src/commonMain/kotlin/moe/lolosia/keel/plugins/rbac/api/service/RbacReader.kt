// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.rbac.api.service

import moe.lolosia.keel.api.annotation.PublicApi
import moe.lolosia.keel.plugins.rbac.api.dto.RoleRef

/**
 * 判定侧的只读面：给业务插件与 Host 查「某个用户有什么组、什么生效规则」。
 *
 * 契约由框架发布（`keel-plugin-rbac-api`），**默认实现**是 `keel-plugin-basic-rbac`；
 * 换成第三方实现时只要实现同一份接口并占用同一个 typeId，调用方不需要改。
 *
 * 业务插件做权限判定**不需要**本接口：`ctx.hasPermission` 用请求快照本地判定
 * （Host 已经通过 `Authorizer` 把规则并进 `UserSnapshot`）。这里面向管理界面与
 * 需要精细查询的场景；插件缺席时用生成的 `ctx.rbacReaderOrNull` 降级。
 *
 * 所有方法都是 `suspend`：跨插件调用经事件泵转发，不跨运行时传对象。
 */
@PublicApi
interface RbacReader {

    /** 用户的**第一个**组绑定；没有任何绑定时返回 `null`。 */
    suspend fun roleOf(userId: String): RoleRef?

    /** 用户的全部直接组绑定（不含继承来的祖先组）。 */
    suspend fun rolesOf(userId: String): List<RoleRef>

    /** 按稳定代号查组（如 `user`）；查不到返回 `null`。 */
    suspend fun roleByType(type: String): RoleRef?

    /**
     * 用户**生效**的规则串：直接组绑定的组链（沿 `parentId` 展开） + 用户直授，合并去重。
     *
     * 拥有者组直接回 `*`；这里只做合并，负号优先的判定发生在请求期。
     */
    suspend fun effectiveRules(userId: String): List<String>

    /** 某组**自身**的规则串（库里有就用库里的，没有回退默认注册表）。 */
    suspend fun rulesOfRole(roleId: Long): List<String>

    /** 某用户的**直授**规则串（不含组带来的规则）。 */
    suspend fun rulesOfUser(userId: String): List<String>

    /** 全部未删除的组，按 id 升序。 */
    suspend fun listRoles(): List<RoleRef>

    /** 用户是否属于拥有者组（判定时视为拥有 `*`）。 */
    suspend fun isOwner(userId: String): Boolean
}
