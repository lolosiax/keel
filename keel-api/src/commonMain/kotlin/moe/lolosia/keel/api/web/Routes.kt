package moe.lolosia.keel.api.web

/**
 * 插件自己的路由表。
 *
 * 当前由插件在 `onLoad` 里手工注册；后续由 `keel-router-ksp` 生成同样的调用。
 * 只在插件 Worker 线程访问，注册完成后由 Worker 在启用时统一发给 Host。
 */
interface Routes {

    /**
     * @param descriptor 暴露给 Host 的路由信息
     * @param handler 在 Worker 线程上执行；[pathParameters] 已由 Host 解析好
     */
    fun register(
        descriptor: RouteDescriptor,
        handler: (HttpRequest, Map<String, String>) -> HttpResponse,
    )
}
