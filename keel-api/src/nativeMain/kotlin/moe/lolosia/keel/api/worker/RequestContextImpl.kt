package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.annotation.PublicApiProvider
import moe.lolosia.keel.api.web.*

/**
 * 一次 HTTP 请求的插件侧上下文。
 *
 * 由 [PluginRoutes] 在 Worker 线程上构造，请求结束即丢弃；[local] 是本次请求
 * 控制器与服务共享的临时区，不要跨请求持有。
 *
 * 请求信息以 [WebRequestComponentImpl] 的形式挂进组件表，因此组件查询与
 * `webRequest()` 走同一条路径；[destroy] 会级联销毁所有挂载组件。
 */
@PublicApiProvider(Context::class, WebContext::class)
internal class RequestContextImpl(
    override val plugin: PluginContext,
    request: HttpRequest,
    pathParameters: Map<String, String>,
    user: UserSnapshot?,
) : LocalContext, WebContext {

    override val local: MutableMap<String, Any?> = mutableMapOf()

    private val componentMap = mutableMapOf<String, Component>()

    init {
        val webRequest = WebRequestComponentImpl(request, pathParameters, user)
        webRequest.context = this
        componentMap[WebRequestComponent.TYPE_ID] = webRequest
    }

    override suspend fun components(): List<Component> = componentMap.values.toList()

    override suspend fun component(id: String): Component? = componentMap[id]

    override suspend fun addComponent(component: Component) {
        val id = component.id()
        val owner = component.context
        require(owner == null || owner === this) { "组件 $id 已挂载在其它 Context 上" }
        val previous = componentMap.put(id, component)
        if (previous != null && previous !== component) previous.context = null
        component.context = this
    }

    override suspend fun removeComponent(id: String): Component? {
        val removed = componentMap.remove(id) ?: return null
        removed.context = null
        return removed
    }

    override suspend fun webRequest(): WebRequestComponent? =
        componentMap[WebRequestComponent.TYPE_ID] as? WebRequestComponent

    /**
     * 先解除挂载，再按逆序释放；组件在 destroy 里看到的 `context` 已经是 null。
     * 先清空表，避免组件在 destroy 里再次操作本表。
     */
    override suspend fun destroy() {
        if (componentMap.isEmpty()) return
        val mounted = componentMap.values.toList().asReversed()
        componentMap.clear()
        mounted.forEach { it.context = null }
        var failure: Throwable? = null
        for (component in mounted) {
            try {
                component.destroy()
            } catch (t: Throwable) {
                if (failure == null) failure = t
            }
        }
        failure?.let { throw it }
    }
}
