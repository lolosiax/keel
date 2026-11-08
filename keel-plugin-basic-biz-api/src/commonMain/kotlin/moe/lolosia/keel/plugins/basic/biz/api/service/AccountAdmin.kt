package moe.lolosia.keel.plugins.basic.biz.api.service

import moe.lolosia.keel.api.annotation.*
import moe.lolosia.keel.plugins.basic.biz.api.dto.*

/**
 * 账户生命周期：注册、建会话、吊销会话。
 *
 * 登录 / 验证码 / 登出是 HTTP 面，不提供跨插件接口——别的插件不该替用户登录。
 * 本接口留给「邀请注册」「管理后台建号」「踢下线」这类场景。
 */
@ModuleService("basic.biz.account")
interface AccountAdmin {

    /** 注册新账户（默认绑定普通用户组），返回用户视图。 */
    suspend fun register(req: RegisterReq): UserRow

    /** 为已存在的用户新建会话，返回会话 id。 */
    suspend fun createSession(userId: String): SessionToken

    /** 吊销会话（登出 / 改密 / 删用户后调用，Host 的认证缓存随之失效）。 */
    suspend fun revokeSession(token: String)
}
