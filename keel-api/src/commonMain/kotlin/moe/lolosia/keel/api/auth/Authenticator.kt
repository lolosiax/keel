package moe.lolosia.keel.api.auth

import moe.lolosia.keel.api.web.UserSnapshot

/**
 * 认证 SPI，由 Host 持有，**实现来自 keel-plugin-basic-biz**。
 *
 * - `Authorization: Bearer <token>` 的 token 交给实现方解析；
 * - 返回 null 表示 token 无效 / 已过期，Host 对受保护路由返回 401；
 * - 没有注册 Authenticator 时，非白名单的 `/api/` 前缀一律 fail closed。
 *
 * 是 `suspend` 的：实现方在插件运行时里，Host 通过事件请求它，协程不能跨 ABI，
 * 所以只在 Host 侧表现为挂起，事件与配对都在 Host 内部完成。
 */
interface Authenticator {

    suspend fun authenticate(token: String): UserSnapshot?
}
