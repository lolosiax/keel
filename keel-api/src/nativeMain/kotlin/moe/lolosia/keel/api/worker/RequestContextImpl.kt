package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.web.*

/**
 * 一次 HTTP 请求的插件侧上下文。
 *
 * 由 [PluginRoutes] 在 Worker 线程上构造，请求结束即丢弃；[data] 是本次请求
 * 控制器与服务共享的临时区，不要跨请求持有。
 */
internal class RequestContextImpl(
    override val plugin: PluginContext,
    override val request: HttpRequest,
    override val pathParameters: Map<String, String>,
    override val user: UserSnapshot?,
) : Context {

    override val userId: String? get() = user?.id

    override val data: MutableMap<String, Any?> = mutableMapOf()
}
