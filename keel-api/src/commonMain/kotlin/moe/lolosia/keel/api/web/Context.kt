package moe.lolosia.keel.api.web

import moe.lolosia.keel.api.KeelServer
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.config.Config
import moe.lolosia.keel.api.service.ModuleDirectory

/**
 * 插件侧请求上下文，尽量贴近单进程 Ktor 里 `ctx` 的用法。
 *
 * 与 backend 的 `Context` 差异：
 * - 没有 `ctx.call`（`ApplicationCall` 不能跨运行时）；
 * - 请求信息是一次性快照，见 [request]；
 * - 权限判定经 Host 实时查询，结果在本次请求内缓存。
 */
interface Context {

    /**
     * 插件运行时上下文；控制器里可以直接用下面的 `modules` / `config` / `server` 快捷属性。
     */
    val plugin: PluginContext

    val request: HttpRequest

    val pathParameters: Map<String, String>

    val userId: String?

    val user: UserSnapshot?

    /**
     * 请求期共享数据区，控制器与插件内部服务可读写。
     */
    val data: MutableMap<String, Any?>

    val modules: ModuleDirectory get() = plugin.modules

    val server: KeelServer get() = plugin.server

    val config: Config get() = plugin.config

    fun query(name: String): String? = request.query[name]?.firstOrNull()

    fun header(name: String): String? = request.headers[name]

    fun body(): ByteArray = request.body

    /**
     * 与 backend 的 `ctx.hasPermission` 同义；未登录 / 权限不足返回 false。
     *
     * 默认用请求快照本地判定（`owner` 直接放行，其余按 `rules` 规则串），
     * 不产生跨运行时调用；自定义授权模型可以覆盖。
     */
    fun hasPermission(permission: String): Boolean {
        val snapshot = user ?: return false
        return snapshot.owner || Permissions.granted(snapshot.rules, permission)
    }
}
