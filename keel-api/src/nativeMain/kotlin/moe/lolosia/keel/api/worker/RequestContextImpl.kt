package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.annotation.PublicApiProvider
import moe.lolosia.keel.api.share.PublicApiDirectory
import moe.lolosia.keel.api.share.SharedExport
import moe.lolosia.keel.api.share.SharedProxy
import moe.lolosia.keel.api.share.SharedRef
import moe.lolosia.keel.api.web.*

/**
 * 一次 HTTP 请求的插件侧上下文。
 *
 * 由 [PluginRoutes] 在 Worker 线程上构造，请求结束由创建方在 `finally` 里销毁；
 * [local] 是本次请求控制器与服务共享的临时区，不要跨请求持有。
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
) : LocalContext, WebContext, SharedExport {

    override val local: MutableMap<String, Any?> = mutableMapOf()

    private val componentMap = mutableMapOf<String, Component>()

    private var handleRef: SharedRef? = null

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
        if (previous != null && previous !== component) {
            previous.context = null
            releaseShared(previous)
        }
        component.context = this
        if (handleRef != null) shareComponent(id, component)
    }

    override suspend fun removeComponent(id: String): Component? {
        val removed = componentMap.remove(id) ?: return null
        removed.context = null
        releaseShared(removed)
        return removed
    }

    override suspend fun webRequest(): WebRequestComponent? =
        componentMap[WebRequestComponent.TYPE_ID] as? WebRequestComponent

    override suspend fun shareHandle(): SharedRef = sharedRef(plugin.publicApi)

    /**
     * 跨界登记入口：Context 第一次作为共享参数出现时，由 [PublicApiObjects.handleOf]
     * 自动调用；业务代码不需要显式登记。
     *
     * 先登记自身，再补登记已挂载的组件，保证 `components()` 的跨插件视图完整。
     */
    override fun sharedRef(directory: PublicApiDirectory): SharedRef {
        handleRef?.let { return it }
        val ref = RequestContextImplProvider.register(directory, this)
        handleRef = ref
        componentMap.forEach { (id, component) -> shareComponent(id, component) }
        return ref
    }

    /**
     * 组件跨界有两种形态：代理本来就有句柄，本地实现按类型登记。
     *
     * 没有 `@PublicApiProvider` 的自定义组件不导出；远端通过 [components] 看不到它，
     * 本插件内的调用不受影响。
     */
    private fun shareComponent(id: String, component: Component) {
        when (component) {
            is SharedProxy -> Unit
            is WebRequestComponentImpl -> WebRequestComponentImplProvider.register(plugin.publicApi, component)
            else -> Log.warn(
                "组件 $id 未提供 @PublicApiProvider，不会跨插件共享",
                tag = "Context",
            )
        }
    }

    private fun releaseShared(component: Component) {
        if (handleRef == null) return
        plugin.publicApi.release(component)
    }

    /**
     * 先解除挂载，再按逆序释放；组件在 destroy 里看到的 `context` 已经是 null。
     * 先清空表，避免组件在 destroy 里再次操作本表；组件销毁或释放抛异常时
     * 仍然释放共享句柄。
     */
    override suspend fun destroy() {
        val mounted = componentMap.values.toList().asReversed()
        componentMap.clear()
        mounted.forEach { it.context = null }
        var failure: Throwable? = null
        try {
            for (component in mounted) {
                try {
                    component.destroy()
                } catch (t: Throwable) {
                    if (failure == null) failure = t
                } finally {
                    releaseShared(component)
                }
            }
        } finally {
            handleRef?.let {
                plugin.publicApi.release(this)
                handleRef = null
            }
        }
        failure?.let { throw it }
    }
}
