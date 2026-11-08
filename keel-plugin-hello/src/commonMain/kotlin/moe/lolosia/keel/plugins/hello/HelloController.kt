package moe.lolosia.keel.plugins.hello

import kotlinx.serialization.Serializable
import moe.lolosia.keel.api.config.*
import moe.lolosia.keel.api.web.*

/**
 * 注解式路由的最小示例。
 *
 * 本文件只写业务：路由描述、参数绑定、JSON 信封与权限节点都由 `keel-router-ksp`
 * 在构建期生成（产物是同包的 `KeelPluginRoutes`）。
 */
@Controller("/api/hello")
object HelloController {

    /** 返回值是普通 `@Serializable` 类，生成器会包成 `{code,msg,data}`。 */
    @Serializable
    data class Greeting(
        val message: String,
        val user: String? = null,
        val echo: String? = null,
    )

    /** `@Body` 的入参类型。 */
    @Serializable
    data class Echo(val text: String)

    /** `GET /api/hello/greeting?name=...`：带权限声明，供 Host 聚合权限节点。 */
    @GET("/greeting")
    @Permission("hello.greeting")
    fun greeting(ctx: Context, @Query("name") name: String?): Greeting =
        Greeting("hello from keel", ctx.userId, name)

    /** `POST /api/hello/echo`：验证 JSON 请求体绑定。 */
    @POST("/echo")
    fun echo(ctx: Context, @Body body: Echo): Greeting =
        Greeting("echo", ctx.userId, body.text)

    /** `GET /api/hello/plain`：字符串返回值按 `text/plain` 原样输出，不包信封。 */
    @GET("/plain")
    fun plain(): String = "plain ok"

    /** `GET /api/hello/config`：读插件私有配置，验证本地快照与启动期读取。 */
    @GET("/config")
    fun config(ctx: Context): Greeting {
        val config = ctx.config.read<HelloConfig>()
        // 主线配置经 ctx.server.config 向 Host 取，不缓存写副本
        val serverName = ctx.server.config.read<String>("server.name")
        return Greeting(config.greeting, ctx.userId, "repeat=${config.repeat} server=$serverName")
    }

    /** `POST /api/hello/config`：写入插件私有配置，Host 合并后保留 YAML 注释。 */
    @POST("/config")
    fun writeConfig(ctx: Context, @Body body: Echo): Greeting {
        ctx.config.write(HelloConfig(greeting = body.text, repeat = body.text.length))
        val saved = ctx.config.read<HelloConfig>()
        return Greeting(saved.greeting, ctx.userId, "repeat=${saved.repeat}")
    }
}
