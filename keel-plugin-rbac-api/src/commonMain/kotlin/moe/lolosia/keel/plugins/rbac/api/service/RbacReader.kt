package moe.lolosia.keel.plugins.rbac.api.service

import moe.lolosia.keel.api.annotation.*
import moe.lolosia.keel.plugins.rbac.api.dto.*

/**
 * 判定侧的只读面：给业务插件与 Host 查「某个用户有什么组、什么规则」。
 *
 * 实现方是 `keel-plugin-rbac`；第三方插件只依赖本契约，不依赖实现。
 * 所有方法都是 `suspend`：跨插件调用经事件泵转发（本地内嵌形态是同线程直调）。
 *
 * 规则串语法与判定口径见 `Permissions`：负号优先，未命中拒绝。
 */
@ModuleService("rbac")
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

    /** 某组**自身**的规则串（库里有就用库里的，没有回退内嵌注册表默认值）。 */
    suspend fun rulesOfRole(roleId: Long): List<String>

    /** 某用户的**直授**规则串（不含组带来的规则）。 */
    suspend fun rulesOfUser(userId: String): List<String>

    /** 全部未删除的组，按 id 升序。 */
    suspend fun listRoles(): List<RoleRef>

    /** 用户是否属于拥有者组（判定时视为拥有 `*`）。 */
    suspend fun isOwner(userId: String): Boolean
}
