package moe.lolosia.keel.plugins.basic.biz.api

import moe.lolosia.keel.api.service.*
import moe.lolosia.keel.plugins.basic.biz.api.service.*

/**
 * basic-biz 服务的查找键。
 *
 * 跨模块只认 [ServiceKey.id]；在 `plugin.json` 里声明 `requires: ["basic-biz"]` 后，
 * 用下面的键从 `ctx.modules` 取本地代理。
 *
 * 服务 id 一律以 `basic.biz.` 开头：这组实现只是「基础版」，同一份契约可以有更好的实现，
 * 不占用 `biz` 这个更宽的命名空间。
 */
object BasicBizServices {

    /** 用户查询（热路径，只读）。 */
    val directory: ServiceKey<UserDirectory> = ServiceKey("basic.biz.user")

    /** 用户管理（增删改、改密、头像、绑组）。 */
    val userAdmin: ServiceKey<UserAdmin> = ServiceKey("basic.biz.user.admin")

    /** 账户生命周期：注册、建会话、吊销会话。 */
    val account: ServiceKey<AccountAdmin> = ServiceKey("basic.biz.account")

    /** 运行时设置。 */
    val setting: ServiceKey<SettingApi> = ServiceKey("basic.biz.setting")
}
