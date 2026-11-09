package moe.lolosia.keel.plugins.basic.biz

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.Plugin
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.annotation.KeelPluginMain
import moe.lolosia.keel.api.auth.Authenticator
import moe.lolosia.keel.api.web.*
import moe.lolosia.keel.plugins.basic.biz.db.UserRepository

/**
 * basic-biz 的生命周期入口。
 *
 * 注解路由由 `keel-router-ksp` 生成（`AuthController` / `UserController` / `SettingController` /
 * `MenuController`），服务由 `@ModuleServiceProvider` 注册；这里只做两件普通路由做不到的事：
 *
 * 1. 启动时幂等检查表结构（数据库不可达只告警，不阻断启用）；
 * 2. 注册头像的取件路由（要按文件扩展名回 `image/png` / `image/jpeg`，注解返回值只支持固定类型）。
 *
 * `plugin.json` 声明了 `provides: ["authn"]`，Host 会用 [authenticator] 解析 Bearer token。
 */
@KeelPluginMain
class BasicBizPlugin : Plugin {

    override fun onLoad(context: PluginContext) {
        Schema.apply()
        context.routes.register(
            RouteDescriptor("GET", "/api/user/avatar", "basic-biz#avatar"),
        ) { ctx -> avatar(ctx, ctx.query("id") ?: ctx.userId) }
        context.routes.register(
            RouteDescriptor("GET", "/api/user/avatar/get/{id}", "basic-biz#avatarById"),
        ) { ctx -> avatar(ctx, ctx.pathParameters["id"]) }
        Log.info("loaded basic-biz", tag = "BasicBiz")
    }

    override fun onEnable() {
        Log.info("basic-biz enabled", tag = "BasicBiz")
    }

    override fun authenticator(): Authenticator = BasicBizAuthenticator

    /** 头像取件：文件内容在插件存储里，扩展名决定 MIME。 */
    private suspend fun avatar(ctx: Context, userId: String?): HttpResponse {
        val id = userId?.takeIf { it.isNotBlank() }
            ?: return HttpResponse(status = 401, body = ResponseBody.Text("需要登录"))
        val name = UserRepository.avatarName(id)
            ?.takeIf { it.isNotBlank() }
            ?: return HttpResponse(status = 404)
        val bytes = ctx.plugin.server.storage.read("image/avatar/$name")
            ?: return HttpResponse(status = 404)
        val contentType = if (name.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
        return HttpResponse(body = ResponseBody.Bytes(bytes, contentType))
    }
}
