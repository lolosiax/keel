package moe.lolosia.keel.api.web

/**
 * 插件自己的路由表。
 *
 * 手写路由在 `onLoad` 里注册；`keel-router-ksp` 生成的控制器路由在
 * `onLoad` 之后由 Worker 统一并入，两者用同一个 [RouteDescriptor.routeId] 去重。
 * 只在插件 Worker 线程访问，注册完成后由 Worker 在启用时统一发给 Host。
 */
interface Routes {

    /**
     * @param descriptor 暴露给 Host 的路由信息
     * @param handler 在 Worker 线程上执行，拿到的 [Context] 已带好请求、路径参数与用户快照
     */
    fun register(
        descriptor: RouteDescriptor,
        handler: suspend (Context) -> HttpResponse,
    )
}
