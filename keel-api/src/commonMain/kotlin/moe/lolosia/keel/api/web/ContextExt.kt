package moe.lolosia.keel.api.web

import moe.lolosia.keel.api.KeelServer
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.config.Config

/**
 * `Context` 上的语法糖：核心契约只留组件，具体能力由这里按业务追加。
 *
 * - 本地字段（`plugin` / `server` / `config`）非 suspend，底层要求上下文
 *   与调用方在同一个运行时；跨插件句柄访问会立即失败。
 * - Web 能力（`request` / `user` / `query` …）是 suspend，走
 *   [WebRequestComponent] 契约，本地与句柄两种实现都成立。
 */

/** 插件运行时上下文；只有同运行时的 [LocalContext] 才有。 */
val Context.plugin: PluginContext
    get() = (this as? LocalContext)?.plugin ?: notLocal()

val Context.server: KeelServer get() = plugin.server

val Context.config: Config get() = plugin.config

/** 当前请求组件；非 Web 场景返回 null。 */
suspend fun Context.webRequestOrNull(): WebRequestComponent? =
    (this as? WebContext)?.webRequest()
        ?: component(WebRequestComponent.TYPE_ID) as? WebRequestComponent

/** 当前请求组件；不是 Web 上下文时抛异常。 */
suspend fun Context.requireWebRequest(): WebRequestComponent =
    webRequestOrNull() ?: error("ctx 不是 Web 上下文")

suspend fun Context.request(): HttpRequest = requireWebRequest().request()

suspend fun Context.pathParameters(): Map<String, String> = requireWebRequest().pathParameters()

suspend fun Context.user(): UserSnapshot? = requireWebRequest().user()

suspend fun Context.userId(): String? = user()?.id

suspend fun Context.query(name: String): String? = request().query[name]?.firstOrNull()

suspend fun Context.header(name: String): String? = request().headers[name]

suspend fun Context.body(): ByteArray = request().body

/**
 * 与 backend 的 `ctx.hasPermission` 同义；未登录 / 权限不足返回 false。
 *
 * 判定用请求快照本地完成（`owner` 直接放行，其余按 `rules` 规则串），
 * 不产生跨运行时调用；自定义授权模型可以覆盖本扩展的调用点。
 */
suspend fun Context.hasPermission(permission: String): Boolean {
    val snapshot = user() ?: return false
    return snapshot.owner || Permissions.granted(snapshot.rules, permission)
}

private fun notLocal(): Nothing =
    error("ctx 不是本地上下文：跨插件句柄只能使用 Context 的公共组件方法")
